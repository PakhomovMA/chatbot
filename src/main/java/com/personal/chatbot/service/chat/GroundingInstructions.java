package com.personal.chatbot.service.chat;

import com.embabel.common.ai.prompt.PromptContributor;
import com.embabel.common.textio.template.JinjaProperties;
import com.embabel.common.textio.template.JinjavaTemplateRenderer;
import com.embabel.common.textio.template.TemplateRenderer;

import java.util.Map;

/**
 * The standing instructions of the assistant: persona and grounding rules for each answering branch,
 * plus the rules for the query-widening calls of {@code expandSearch}. They are the same for every
 * question, so they travel as Embabel {@link PromptContributor}s
 * and land in the system message, while the per-question material (history, evidence, question)
 * stays in the user message built by {@link GroundedAnswerPrompt} (docs/system-plan.md D13).
 *
 * <p>The shared half of the rules lives once in {@code prompts/_grounding_rules.jinja} and is pulled
 * into each branch template with {@code {% include %}}. Templates are rendered here, at construction
 * time, and only with trusted scalars: text that came from an uploaded document or from the user
 * never enters a template model, so no Jinja expression can arrive with it.
 */
public final class GroundingInstructions {

    static final String SHARED_RULES_TEMPLATE = "_grounding_rules";
    static final String GROUNDED_ANSWER_TEMPLATE = "grounded-answer";
    static final String STREAMING_ANSWER_TEMPLATE = "grounded-answer-stream";
    static final String AGENTIC_RESEARCH_TEMPLATE = "agentic-research";
    static final String REWRITE_TEMPLATE = "expand-search-rewrite";
    static final String HYDE_TEMPLATE = "expand-search-hyde";
    static final String CONVERSATION_REWRITE_TEMPLATE = "conversation-query-rewrite";

    private static final String ROLE = "grounding_instructions";

    private final PromptContributor groundedAnswer;
    private final PromptContributor streamingAnswer;
    private final PromptContributor agenticResearch;
    private final PromptContributor queryRewrite;
    private final PromptContributor hypotheticalPassage;
    private final PromptContributor conversationRewrite;

    public GroundingInstructions(int agenticMaxSearches, int expansionQueries) {
        this(strictRenderer(), agenticMaxSearches, expansionQueries);
    }

    GroundingInstructions(TemplateRenderer renderer, int agenticMaxSearches, int expansionQueries) {
        this.groundedAnswer = render(renderer, GROUNDED_ANSWER_TEMPLATE, Map.of());
        this.streamingAnswer = render(renderer, STREAMING_ANSWER_TEMPLATE, Map.of());
        this.agenticResearch = render(renderer, AGENTIC_RESEARCH_TEMPLATE, Map.of("maxSearches", agenticMaxSearches));
        this.queryRewrite = render(renderer, REWRITE_TEMPLATE, Map.of("queries", expansionQueries));
        this.hypotheticalPassage = render(renderer, HYDE_TEMPLATE, Map.of());
        this.conversationRewrite = render(renderer, CONVERSATION_REWRITE_TEMPLATE, Map.of());
    }

    /** Instructions for the deterministic branch asking for a structured draft. */
    public PromptContributor groundedAnswer() {
        return groundedAnswer;
    }

    /** Instructions for the deterministic branch streaming free text. */
    public PromptContributor streamingAnswer() {
        return streamingAnswer;
    }

    /** Instructions for the agentic branch, where the model retrieves through the knowledge-base tools. */
    public PromptContributor agenticResearch() {
        return agenticResearch;
    }

    /** Instructions for the REWRITE expansion strategy: other ways to ask a question that found nothing. */
    public PromptContributor queryRewrite() {
        return queryRewrite;
    }

    public PromptContributor conversationRewrite() {
        return conversationRewrite;
    }

    /** Instructions for the HYDE expansion strategy: the passage that would answer the question. */
    public PromptContributor hypotheticalPassage() {
        return hypotheticalPassage;
    }

    /**
     * Fails on an unknown token, unlike the platform renderer: a placeholder that no longer has a
     * value is a broken prompt, and it is worth losing at startup rather than silently reaching the
     * model. The platform's own bean keeps its lenient defaults for Embabel's built-in templates.
     */
    private static TemplateRenderer strictRenderer() {
        return new JinjavaTemplateRenderer(new JinjaProperties("classpath:/prompts/", ".jinja", true));
    }

    private static PromptContributor render(TemplateRenderer renderer, String template, Map<String, Object> model) {
        return PromptContributor.fixed(renderer.renderLoadedTemplate(template, model).strip(), ROLE);
    }
}
