package com.personal.chatbot.agents;

import com.embabel.agent.api.tool.Tool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SearchBudgetTest {

    private final AtomicInteger vectorCalls = new AtomicInteger();
    private final AtomicInteger expansions = new AtomicInteger();

    private final Tool vectorSearch = Tool.Companion.create("knowledge_base_vectorSearch", "search",
            input -> Tool.Result.Companion.text("passages for " + input + " #" + vectorCalls.incrementAndGet()));
    private final Tool broaden = Tool.Companion.create("knowledge_base_broadenChunk", "expand",
            input -> Tool.Result.Companion.text("wider chunk #" + expansions.incrementAndGet()));

    private List<Tool> limited(int maxSearches) {
        return new SearchBudget(maxSearches, "m", new SimpleMeterRegistry()).limit(List.of(vectorSearch, broaden));
    }

    private static String call(Tool tool, String input) {
        return ((Tool.Result.Text) tool.call(input)).getContent();
    }

    @Test
    void searchesPastTheBudgetAreAnsweredWithANoteInsteadOfBeingRun() {
        List<Tool> tools = limited(2);

        assertThat(call(tools.getFirst(), "{\"query\":\"first\"}")).contains("passages for");
        assertThat(call(tools.getFirst(), "{\"query\":\"second\"}")).contains("passages for");
        String refused = call(tools.getFirst(), "{\"query\":\"third\"}");

        assertThat(refused).contains("Search budget spent").doesNotContain("passages for");
        assertThat(vectorCalls).hasValue(2);
    }

    @Test
    void aRepeatedQueryStillReturnsItsPassagesAndSaysThatItRepeats() {
        List<Tool> tools = limited(4);
        String query = "{\"query\":\"GLM-5.3\"}";

        assertThat(call(tools.getFirst(), query)).doesNotContain("repeats");
        String again = call(tools.getFirst(), query);

        assertThat(again).contains("repeats a search you already ran").contains("passages for");
        assertThat(vectorCalls).as("the passages the answer will be verified against are still shown").hasValue(2);
    }

    @Test
    void readingMoreOfWhatTheModelWasAlreadyShownCostsNoBudget() {
        List<Tool> tools = limited(1);

        assertThat(call(tools.getFirst(), "{\"query\":\"only search\"}")).contains("passages for");
        assertThat(call(tools.getLast(), "{\"chunkId\":\"a:1:2\"}")).isEqualTo("wider chunk #1");
        assertThat(call(tools.getLast(), "{\"chunkId\":\"a:1:3\"}")).isEqualTo("wider chunk #2");
    }

    @Test
    void toolsThatAreNotSearchesAreHandedOverUntouched() {
        List<Tool> tools = limited(1);

        assertThat(tools.getLast()).isSameAs(broaden);
        assertThat(tools.getFirst()).isNotSameAs(vectorSearch);
        assertThat(tools.getFirst().getDefinition().getName()).isEqualTo(vectorSearch.getDefinition().getName());
    }
}
