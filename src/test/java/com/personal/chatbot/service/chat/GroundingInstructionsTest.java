package com.personal.chatbot.service.chat;

import com.embabel.common.textio.template.JinjaProperties;
import com.embabel.common.textio.template.JinjavaTemplateRenderer;
import com.embabel.common.textio.template.NoSuchTemplateException;
import com.embabel.common.textio.template.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GroundingInstructionsTest {

    private final GroundingInstructions instructions = new GroundingInstructions(7, 3, 3, 4);

    /**
     * Catches template path drift, which the mocked-LLM tests cannot see: the strict renderer
     * resolves names against {@code classpath:/prompts/} and fails on an unknown token.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            GroundingInstructions.SHARED_RULES_TEMPLATE,
            GroundingInstructions.GROUNDED_ANSWER_TEMPLATE,
            GroundingInstructions.STREAMING_ANSWER_TEMPLATE,
            GroundingInstructions.AGENTIC_RESEARCH_TEMPLATE,
            GroundingInstructions.REWRITE_TEMPLATE,
            GroundingInstructions.HYDE_TEMPLATE,
            GroundingInstructions.CONVERSATION_REWRITE_TEMPLATE,
            GroundingInstructions.DECOMPOSE_TEMPLATE,
            GroundingInstructions.COMPARE_SOURCES_TEMPLATE})
    void everyTemplateResolvesFromTheDefaultPromptsLocation(String template) {
        TemplateRenderer renderer = new JinjavaTemplateRenderer(new JinjaProperties("classpath:/prompts/", ".jinja", true));
        assertThatCode(() -> renderer.load(template)).doesNotThrowAnyException();
    }

    @Test
    void everyBranchGetsThePersonaAndTheSharedRulesFromOneInclude() {
        assertThat(instructions.groundedAnswer().contribution())
                .contains("You are a knowledge assistant for a team's internal documentation")
                .contains("Do not guess")
                .contains("Write the answer in the language named at the end of the user message")
                .contains("the numbered evidence passages");
        assertThat(instructions.streamingAnswer().contribution())
                .contains("You are a knowledge assistant for a team's internal documentation")
                .contains("Do not guess");
        assertThat(instructions.agenticResearch().contribution())
                .contains("You are a knowledge assistant for a team's internal documentation")
                .contains("Do not guess")
                .contains("passages you retrieve with the knowledge-base tools");
    }

    @Test
    void branchSpecificRulesStayInTheirOwnBranch() {
        assertThat(instructions.groundedAnswer().contribution())
                .contains("evidenceSufficient")
                .doesNotContain("INSUFFICIENT:")
                .doesNotContain("knowledge_base_vectorSearch");
        assertThat(instructions.streamingAnswer().contribution())
                .contains("INSUFFICIENT: <what is missing>")
                .contains("no preamble, no JSON")
                .doesNotContain("evidenceSufficient");
    }

    @Test
    void agenticInstructionsNameTheToolsAndKeepTheChunkMarkerLiteral() {
        String agentic = instructions.agenticResearch().contribution();
        assertThat(agentic)
                .contains("knowledge_base_vectorSearch")
                .contains("knowledge_base_textSearch")
                .contains("knowledge_base_broadenChunk")
                .contains("knowledge_base_zoomOut")
                // {% raw %} keeps the marker the model must emit out of Jinja's hands
                .contains("{{chunk:<id>}}")
                .contains("You get 7 searches in total");
    }

    /** Phase 9d: both branches say what they are for and, above all, what they must not do. */
    @Test
    void theDecompositionAndComparisonBranchesAreToldNotToAnswer() {
        assertThat(instructions.questionDecomposition().contribution())
                .contains("at most 3", "Never answer the question")
                .doesNotContain("evidenceSufficient");
        assertThat(instructions.sourceComparison().contribution())
                .contains("at most 4 aspects", "Use only numbers that appear in the evidence list",
                        "do not answer the question");
    }

    @Test
    void anUnknownTemplateFailsAtConstructionRatherThanAtTheFirstQuestion() {
        TemplateRenderer renderer = new JinjavaTemplateRenderer(new JinjaProperties("classpath:/nowhere/", ".jinja", true));
        assertThatCode(() -> new GroundingInstructions(renderer, 4, 3, 3, 4))
                .isInstanceOf(NoSuchTemplateException.class);
    }
}
