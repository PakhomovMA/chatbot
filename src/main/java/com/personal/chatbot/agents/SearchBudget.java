package com.personal.chatbot.agents;

import com.embabel.agent.api.tool.DelegatingTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.ToolCallContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The limit on how many searches one agentic answer may run, enforced where the searches happen
 * rather than only asked for in the prompt (docs/eval-log.md, 2026-09-10).
 *
 * <p>{@code chatbot.chat.agentic-max-searches} used to live in the instructions alone, and a local
 * model that reads it as a suggestion — running the same query twice, or searching on past a full
 * result set — costs a search and a model turn each time, on a request that is already the slowest
 * thing the application does. Here the number is a budget: once it is spent, the search tools answer
 * with a note instead of searching, which the model can act on. Nothing is thrown, because a tool
 * failure would cost the whole tool loop; the model is told to answer from what it has, which is what
 * it should have done anyway.
 *
 * <p>Only the two search tools are counted. Broadening a chunk, zooming out, listing sections or
 * reading one are bounded by what the model has already been shown, and refusing them would take away
 * the cheapest way to complete a passage it is already reading.
 *
 * <p>A query the model has already run still runs — its passages are what the answer will be verified
 * against, and a chunk it saw once has to stay in the evidence — but it costs budget and the result
 * says so, because a repeat is otherwise invisible to the model and it will repeat again.
 *
 * <p>Embabel may run the tool calls of one request on more than one thread, so the ledger is guarded
 * (docs/concurrency-plan.md C09).
 */
public final class SearchBudget {

    private static final Logger log = LoggerFactory.getLogger(SearchBudget.class);

    /** The {@code ToolishRag} search tools, by the suffix Embabel gives them under the reference name. */
    private static final Set<String> SEARCH_TOOLS = Set.of("vectorsearch", "textsearch");

    /** What the budget has to say about one call, and whether the search may still run. */
    private record Verdict(boolean allowed, String note) {

        static final Verdict FRESH = new Verdict(true, "");
    }

    private final int maxSearches;
    private final String messageId;
    private final MeterRegistry meterRegistry;
    private final Set<String> queries = new LinkedHashSet<>();
    private final Object lock = new Object();
    private int spent;

    public SearchBudget(int maxSearches, String messageId, MeterRegistry meterRegistry) {
        this.maxSearches = maxSearches;
        this.messageId = messageId;
        this.meterRegistry = meterRegistry;
    }

    /** The same tools, with the search ones bounded by this budget. */
    public List<Tool> limit(List<Tool> tools) {
        return tools.stream().map(tool -> isSearch(tool) ? new BudgetedTool(tool) : tool).toList();
    }

    /** Searches actually run; a refused call does not count. */
    public int spent() {
        synchronized (lock) {
            return spent;
        }
    }

    private static boolean isSearch(Tool tool) {
        String name = tool.getDefinition().getName().toLowerCase(Locale.ROOT);
        return SEARCH_TOOLS.stream().anyMatch(name::endsWith);
    }

    private Verdict admit(String input) {
        synchronized (lock) {
            if (spent >= maxSearches) {
                return new Verdict(false, ("Search budget spent: %d searches have been run. Do not search again — "
                        + "answer from the passages you have, or say which part of the question they do not cover.")
                        .formatted(maxSearches));
            }
            spent++;
            return queries.add(input.strip()) ? Verdict.FRESH
                    : new Verdict(true, "This repeats a search you already ran. Search for something else, or answer "
                    + "from the passages you have.");
        }
    }

    /** One search tool that may run only while the budget lasts. */
    private final class BudgetedTool implements DelegatingTool {

        private final Tool delegate;

        private BudgetedTool(Tool delegate) {
            this.delegate = delegate;
        }

        @NotNull
        @Override
        public Tool getDelegate() {
            return delegate;
        }

        @NotNull
        @Override
        public Definition getDefinition() {
            return delegate.getDefinition();
        }

        @NotNull
        @Override
        public Metadata getMetadata() {
            return delegate.getMetadata();
        }

        @NotNull
        @Override
        public Result call(@NotNull String input) {
            return call(input, ToolCallContext.EMPTY);
        }

        @NotNull
        @Override
        public Result call(@NotNull String input, @NotNull ToolCallContext context) {
            Verdict verdict = admit(input);
            if (!verdict.allowed()) {
                meterRegistry.counter("chatbot.chat.agentic.search", "outcome", "refused").increment();
                log.info("Agentic search for [{}] refused after {} searches: {}", messageId, maxSearches, input);
                return Result.Companion.text(verdict.note());
            }
            meterRegistry.counter("chatbot.chat.agentic.search", "outcome",
                    verdict.note().isEmpty() ? "ran" : "repeated").increment();
            Result result = delegate.call(input, context);
            if (verdict.note().isEmpty()) {
                return result;
            }
            log.info("Agentic search for [{}] repeats an earlier query ({} of {} spent): {}", messageId, spent(),
                    maxSearches, input);
            return prefixed(verdict.note(), result);
        }

        private Result prefixed(String note, Result result) {
            return switch (result) {
                case Result.Text text -> Result.Companion.text(note + "\n" + text.getContent());
                case Result.WithArtifact artifact ->
                        Result.Companion.withArtifact(note + "\n" + artifact.getContent(), artifact.getArtifact());
                case Result.Error error -> error;
                default -> throw new IllegalStateException("Unexpected value: " + result);
            };
        }
    }
}
