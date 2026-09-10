package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.observability.IngestionObservations;
import com.personal.chatbot.observability.Measured;
import com.personal.chatbot.observability.Outcome;
import com.personal.chatbot.service.lifecycle.ActiveWork;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The single-writer work queue in front of the index (docs/system-plan.md §5, INV-11): one worker
 * thread, at most one document in flight, and one pending request per document. Everything the
 * worker will do lives in this object's state — the thread only ever takes work from here, so the
 * queue and the work actually scheduled cannot drift apart.
 *
 * <p>Requests are coalesced rather than dropped. A request for the document currently in flight is
 * remembered as a re-run and queued the moment that run ends, which is what makes a replacement or a
 * re-index during ingestion take effect instead of getting lost. Each claimed run carries a token;
 * {@link IngestionClaim#isCurrent()} turns false as soon as a deletion, a newer request or a rebuild
 * supersedes it, so a run that lost the document cannot publish its result.
 *
 * <p>A full rebuild is a command in the same queue (docs/concurrency-plan.md C03): it takes
 * precedence over ordinary work, waits for the run in flight to finish, and re-queues the documents
 * the rebuild callback reports — including those uploaded while it was waiting.
 *
 * <p>Lock order: the queue monitor may be held while taking the registry lock, never the reverse.
 *
 * <p>The queue owns its worker thread and nothing else stops it: {@link ActiveWork} is the whole
 * lifecycle (docs/concurrency-plan.md C08), driven by the shutdown sequence.
 */
public class IngestionQueue implements ActiveWork {

    private static final Logger log = LoggerFactory.getLogger(IngestionQueue.class);

    /** Queue depth (waiting requests plus a rebuild that is waiting or running) and the document in flight. */
    public record Status(int pending, @Nullable String activeDocumentId) {
    }

    /**
     * The physical index rebuild, executed as a queue command between documents.
     *
     * @return ids of the documents to queue once the index is empty again
     */
    @FunctionalInterface
    public interface Rebuild {
        List<String> perform();
    }

    /**
     * One accepted request. {@code request} distinguishes repeated requests for the same version
     * (a re-index), {@code generation} ties the request to the index it was made against, and
     * {@code queued} measures how long it took to be claimed — or what happened instead. Requests are
     * never equal to one another: {@code request} is unique, so the identity this record already had
     * is unchanged.
     */
    private record Job(String documentId, long request, long generation, Measured queued) {
    }

    private record Token(IngestionQueue queue, Job job) implements IngestionClaim {

        @Override
        public String documentId() {
            return job.documentId();
        }

        @Override
        public boolean isCurrent() {
            return queue.isCurrent(job);
        }

        @Override
        public <T> Optional<T> ifCurrent(Supplier<T> publish) {
            return queue.ifCurrent(job, publish);
        }
    }

    private final Consumer<IngestionClaim> processor;
    private final Rebuild rebuild;
    private final IngestionObservations observations;
    private final Thread worker;
    private final Object monitor = new Object();

    /** The single source of truth for waiting work; one entry per document, in arrival order. */
    private final Map<String, Job> pending = new LinkedHashMap<>();

    private @Nullable Job active;
    private boolean activeIsCurrent;
    /** A request that arrived for the document in flight; queued as soon as that run ends. */
    private @Nullable Job rerun;
    private boolean rebuildRequested;
    private boolean rebuildRunning;
    private boolean stopping;
    private long requests;
    private long generation;

    /**
     * @param processor invoked on the worker thread once a document has been claimed
     * @param rebuild   invoked on the worker thread when a full rebuild has been requested
     */
    public IngestionQueue(Consumer<IngestionClaim> processor, Rebuild rebuild, IngestionObservations observations) {
        this.processor = processor;
        this.rebuild = rebuild;
        this.observations = observations;
        this.worker = Thread.ofPlatform().name("ingestion-worker").daemon(true).unstarted(this::work);
        this.worker.start();
    }

    /**
     * Requests (re-)ingestion of a document. A request for the document in flight is kept as a
     * re-run; a request for one already waiting replaces it, so repeats cannot pile up.
     *
     * @return false if the queue is shutting down and the request was not accepted
     */
    public boolean enqueue(String documentId) {
        return enqueue(documentId, () -> Boolean.TRUE).isPresent();
    }

    /**
     * Prepares metadata and accepts work under the same monitor as shutdown and result publication.
     * The callback must not publish events or wait for the worker; announce its result afterwards.
     */
    public <T> Optional<T> enqueue(String documentId, Supplier<T> prepare) {
        T prepared;
        Job replaced;
        synchronized (monitor) {
            if (stopping) {
                log.warn("Ingestion queue is stopping; request for {} rejected", documentId);
                return Optional.empty();
            }
            prepared = Objects.requireNonNull(prepare.get());
            Job job = new Job(documentId, ++requests, generation, observations.startQueueWait());
            if (active != null && active.documentId().equals(documentId)) {
                replaced = rerun;
                rerun = job;
            } else {
                replaced = pending.put(documentId, job);
            }
            monitor.notifyAll();
        }
        // The request this one collapsed into never waited for a worker of its own.
        settle(replaced, Outcome.SUPERSEDED);
        return Optional.of(prepared);
    }

    /**
     * Withdraws a deleted document: its waiting request is dropped and a run in flight loses the
     * right to publish anything about it.
     */
    public void invalidate(String documentId) {
        Job dropped;
        Job droppedRerun = null;
        synchronized (monitor) {
            dropped = pending.remove(documentId);
            if (rerun != null && rerun.documentId().equals(documentId)) {
                droppedRerun = rerun;
                rerun = null;
            }
            if (active != null && active.documentId().equals(documentId)) {
                activeIsCurrent = false;
            }
        }
        settle(dropped, Outcome.CANCELLED);
        settle(droppedRerun, Outcome.CANCELLED);
    }

    /**
     * Schedules a full index rebuild ahead of ordinary work. Everything queued against the old index
     * is invalidated — the rebuild callback reports what has to be ingested afterwards. Repeated
     * requests collapse into the one rebuild that is still waiting.
     *
     * @return false if the queue is shutting down and the rebuild was not accepted
     */
    public boolean requestRebuild() {
        return requestRebuild(() -> Boolean.TRUE).isPresent();
    }

    /** Like {@link #enqueue(String, Supplier)}, but invalidates the old generation before releasing the monitor. */
    public <T> Optional<T> requestRebuild(Supplier<T> prepare) {
        T prepared;
        List<Job> invalidated;
        synchronized (monitor) {
            if (stopping) {
                log.warn("Ingestion queue is stopping; index rebuild rejected");
                return Optional.empty();
            }
            prepared = Objects.requireNonNull(prepare.get());
            generation++;
            invalidated = waiting();
            pending.clear();
            rerun = null;
            activeIsCurrent = false;
            rebuildRequested = true;
            monitor.notifyAll();
        }
        // The rebuild reports what has to be ingested afterwards, so these requests are replaced
        // rather than lost; each of the documents is queued again, with a wait of its own.
        invalidated.forEach(job -> settle(job, Outcome.SUPERSEDED));
        return Optional.of(prepared);
    }

    public Status status() {
        synchronized (monitor) {
            int waiting = pending.size() + (rerun != null ? 1 : 0) + (rebuildRequested || rebuildRunning ? 1 : 0);
            return new Status(waiting, active != null ? active.documentId() : null);
        }
    }

    @Override
    public String name() {
        return "ingestion";
    }

    /**
     * Stops taking requests and wakes the worker. A run in flight is left to finish: its result is
     * still wanted, and interrupting it mid-write is what {@link #interruptActive()} is for.
     */
    @Override
    public void stopAccepting() {
        List<Job> abandoned;
        synchronized (monitor) {
            if (stopping) {
                return;
            }
            stopping = true;
            abandoned = waiting();
            pending.clear();
            rerun = null;
            monitor.notifyAll();
        }
        abandoned.forEach(job -> settle(job, Outcome.CANCELLED));
    }

    /** The worker ends once the run in flight is over, so its thread dying is what "quiet" means. */
    @Override
    public boolean awaitQuiet(Duration timeout) {
        try {
            return worker.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !worker.isAlive();
        }
    }

    /**
     * Interrupts the run in flight. What it leaves behind is a document still in its ingestion stage:
     * the pipeline does not record a failure for an interrupted run, so startup reconciliation sees
     * an interrupted ingestion and re-queues it.
     */
    @Override
    public void interruptActive() {
        worker.interrupt();
    }

    private void work() {
        while (true) {
            Job claimed;
            synchronized (monitor) {
                while (!stopping && !rebuildRequested && pending.isEmpty()) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        // Nothing here will ever be claimed now; leaving the waits open would leave
                        // their measurements unfinished rather than reporting what became of them.
                        waiting().forEach(job -> settle(job, Outcome.CANCELLED));
                        pending.clear();
                        rerun = null;
                        return;
                    }
                }
                if (stopping) {
                    return;
                }
                if (rebuildRequested) {
                    rebuildRequested = false;
                    rebuildRunning = true;
                    claimed = null;
                } else {
                    claimed = take();
                    active = claimed;
                    activeIsCurrent = true;
                }
            }
            if (claimed == null) {
                runRebuild();
            } else {
                runJob(claimed);
            }
        }
    }

    /** Must be called under the monitor. */
    private Job take() {
        Iterator<Job> waiting = pending.values().iterator();
        Job next = waiting.next();
        waiting.remove();
        return next;
    }

    private void runJob(Job job) {
        // The wait is over the moment the worker takes the job on; what follows is the run itself.
        settle(job, Outcome.SUCCESS);
        try {
            processor.accept(new Token(this, job));
        } catch (RuntimeException e) {
            log.error("Ingestion of {} ended unexpectedly", job.documentId(), e);
        } finally {
            synchronized (monitor) {
                active = null;
                activeIsCurrent = false;
                if (rerun != null) {
                    pending.put(rerun.documentId(), rerun);
                    rerun = null;
                }
                monitor.notifyAll();
            }
        }
    }

    private void runRebuild() {
        try {
            List<String> documents = rebuild.perform();
            documents.forEach(this::enqueue);
            log.info("Index rebuilt; {} documents queued", documents.size());
        } catch (RuntimeException e) {
            // The index is left in whatever state the rebuild reached; documents stay un-indexed and
            // visibly so, which is better than queueing work against an index that may not be there.
            log.error("Index rebuild failed; no documents were queued", e);
        } finally {
            synchronized (monitor) {
                rebuildRunning = false;
                monitor.notifyAll();
            }
        }
    }

    private boolean isCurrent(Job job) {
        synchronized (monitor) {
            return holdsClaim(job);
        }
    }

    private <T> Optional<T> ifCurrent(Job job, Supplier<T> publish) {
        synchronized (monitor) {
            return holdsClaim(job) ? Optional.of(Objects.requireNonNull(publish.get())) : Optional.empty();
        }
    }

    /** Must be called under the monitor. */
    private boolean holdsClaim(Job job) {
        return activeIsCurrent && job.equals(active) && job.generation() == generation;
    }

    /** Every request that is still waiting for a worker. Must be called under the monitor. */
    private List<Job> waiting() {
        List<Job> jobs = new ArrayList<>(pending.values());
        if (rerun != null) {
            jobs.add(rerun);
        }
        return jobs;
    }

    /**
     * Ends a request's wait with what became of it. Called outside the monitor wherever the caller can
     * arrange it: publishing a measurement is not work the single writer should be holding a lock for.
     */
    private static void settle(@Nullable Job job, Outcome outcome) {
        if (job == null) {
            return;
        }
        job.queued().finished(outcome);
        job.queued().close();
    }
}
