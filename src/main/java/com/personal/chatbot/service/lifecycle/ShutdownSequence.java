package com.personal.chatbot.service.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The order in which the application stops (docs/concurrency-plan.md C08): stop accepting, let what
 * is running finish, interrupt what did not, and report what is still busy.
 *
 * <p>Everyone is asked to stop before anyone is waited for, so all of them are stopping while the
 * first one is waited for, and the waits of one round share a single deadline instead of adding up.
 *
 * <p>The sequence deliberately closes nothing itself. Closing the Lucene store and the embedding
 * backend stays with the beans that own them, which Spring destroys right after this runs; each of
 * those closes refuses to touch its resource while a call is still using it, so an incomplete stop
 * leaves the resource open — the process is going away anyway — instead of crashing the JVM.
 */
public final class ShutdownSequence {

    private static final Logger log = LoggerFactory.getLogger(ShutdownSequence.class);

    /** @param stillRunning names of the work that survived even the interrupt */
    public record Result(List<String> stillRunning) {

        public boolean complete() {
            return stillRunning.isEmpty();
        }
    }

    private final List<ActiveWork> work;
    private final Duration grace;
    private final Duration afterInterrupt;

    /**
     * @param work           participants, in the order they should be asked to stop
     * @param grace          total time given to work that stops on its own
     * @param afterInterrupt total time given to work that had to be interrupted
     */
    public ShutdownSequence(List<ActiveWork> work, Duration grace, Duration afterInterrupt) {
        this.work = List.copyOf(work);
        this.grace = grace;
        this.afterInterrupt = afterInterrupt;
    }

    /** Runs the sequence to its end; never throws. */
    public Result stop() {
        long started = System.nanoTime();
        for (ActiveWork participant : work) {
            run(participant, "stop", participant::stopAccepting);
        }
        List<ActiveWork> busy = awaitAll(work, grace);
        if (!busy.isEmpty()) {
            log.warn("Still running after {}: {}; interrupting", grace, names(busy));
            for (ActiveWork participant : busy) {
                run(participant, "interrupt", participant::interruptActive);
            }
            busy = awaitAll(busy, afterInterrupt);
        }
        Result result = new Result(names(busy));
        if (result.complete()) {
            log.info("All active work stopped in {} ms", (System.nanoTime() - started) / 1_000_000);
        } else {
            log.error("Shutdown incomplete: {} did not stop. The index and the embedding backend are left open"
                    + " rather than closed under an active call; documents in flight stay in their current stage"
                    + " and startup reconciliation picks them up.", result.stillRunning());
        }
        return result;
    }

    /** @return the participants that were still busy when {@code timeout} ran out */
    private List<ActiveWork> awaitAll(List<ActiveWork> participants, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ActiveWork> busy = new ArrayList<>();
        for (ActiveWork participant : participants) {
            Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (!quiet(participant, left)) {
                busy.add(participant);
            }
        }
        return busy;
    }

    private boolean quiet(ActiveWork participant, Duration timeout) {
        try {
            return participant.awaitQuiet(timeout);
        } catch (RuntimeException e) {
            log.warn("Waiting for {} failed: {}", participant.name(), e.toString());
            return false;
        }
    }

    private void run(ActiveWork participant, String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Could not {} {}: {}", step, participant.name(), e.toString());
        }
    }

    private static List<String> names(List<ActiveWork> participants) {
        return participants.stream().map(ActiveWork::name).toList();
    }
}
