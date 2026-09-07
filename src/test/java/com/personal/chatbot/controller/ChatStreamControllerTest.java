package com.personal.chatbot.controller;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.support.LlmInteraction;
import com.jayway.jsonpath.JsonPath;
import com.personal.chatbot.models.agent.AgenticDraft;
import com.personal.chatbot.models.agent.ChatCancellation;
import com.personal.chatbot.models.agent.GroundedAnswerDraft;
import com.personal.chatbot.models.agent.RewrittenQueries;
import com.personal.chatbot.models.chat.ChatRequest;
import com.personal.chatbot.models.chat.ChatStreamEvent;
import com.personal.chatbot.service.chat.ChatService;
import com.personal.chatbot.support.AbstractChatbotIntegrationTest;
import com.personal.chatbot.support.TestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 7 gate: SSE event order and a final payload identical in shape to the synchronous contract. */
@AutoConfigureMockMvc
class ChatStreamControllerTest extends AbstractChatbotIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDir(DynamicPropertyRegistry registry) {
        registry.add("chatbot.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatService chatService;

    private static String documentId;

    record SseEvent(String name, String data) {
    }

    /**
     * The fake embedder scores everything below the sufficiency floor, so the default expandSearch
     * strategy fires on every question here (Phase 9a). The rewrite is stubbed as "nothing better to
     * try", which leaves the evidence and the event contract as they were, plus the extra stage.
     */
    @BeforeEach
    void noRewriteToOffer() {
        whenCreateObject(p -> p.startsWith("Question:"), RewrittenQueries.class)
                .thenReturn(new RewrittenQueries(List.of()));
    }

    @BeforeEach
    void indexRunbookOnce() throws Exception {
        if (documentId != null) {
            return;
        }
        MvcResult upload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "runbook.md", "text/markdown", TestDocuments.markdown())))
                .andExpect(status().isAccepted()).andReturn();
        documentId = JsonPath.read(upload.getResponse().getContentAsString(), "$.documentId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mockMvc.perform(get("/api/documents/{id}/status", documentId)).andExpect(jsonPath("$.status").value("READY")));
    }

    private List<SseEvent> streamChat(String body) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        // The emitter writes straight into the mock response; wait for the terminal event rather than for
        // async completion, which MockMvc may observe before the last write has landed.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(started.getResponse().getContentAsString()).containsAnyOf("event:final", "event:error"));
        assertThat(started.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return parse(started.getResponse().getContentAsString());
    }

    static List<SseEvent> parse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).strip());
            } else if (line.isBlank() && name != null) {
                events.add(new SseEvent(name, data.toString()));
                name = null;
                data.setLength(0);
            }
        }
        if (name != null) { // split() drops trailing blank lines, so the last block has no terminator
            events.add(new SseEvent(name, data.toString()));
        }
        return events;
    }

    @Test
    void streamsDeltasFromTheModelThenAVerifiedFinalResponse() throws Exception {
        supportsStreaming(true);
        whenGenerateStream(p -> p.contains("Question: How do I restart the payment service?"))
                .thenReturn(Flux.just("Run `systemctl restart payments` ", "on the host [1]. ", "Unrelated claim [9]."));

        List<SseEvent> events = streamChat("{\"message\":\"How do I restart the payment service?\"}");

        assertThat(events).extracting(SseEvent::name)
                .containsSubsequence("status", "status", "delta", "delta", "delta", "status", "final");
        assertThat(events).filteredOn(e -> e.name().equals("status")).extracting(SseEvent::data)
                .containsExactly("{\"stage\":\"retrieving\"}", "{\"stage\":\"expanding\"}",
                        "{\"stage\":\"generating\"}", "{\"stage\":\"verifying\"}");
        String streamed = events.stream().filter(e -> e.name().equals("delta"))
                .map(e -> (String) JsonPath.read(e.data(), "$.text")).reduce("", String::concat);
        assertThat(streamed).isEqualTo("Run `systemctl restart payments` on the host [1]. Unrelated claim [9].");
        SseEvent last = events.getLast();
        assertThat(last.name()).isEqualTo("final");
        assertThat((String) JsonPath.read(last.data(), "$.response.answer"))
                .isEqualTo("Run `systemctl restart payments` on the host [1]. Unrelated claim.");
        assertThat((String) JsonPath.read(last.data(), "$.response.grounding")).isIn("GROUNDED", "PARTIAL");
        assertThat((List<?>) JsonPath.read(last.data(), "$.response.citations")).hasSize(1);
        assertThat((String) JsonPath.read(last.data(), "$.response.citations[0].documentId")).isEqualTo(documentId);
        assertThat((String) JsonPath.read(last.data(), "$.response.conversationId")).isNotBlank();
    }

    @Test
    void insufficientMarkerFromTheStreamIsHonoured() throws Exception {
        supportsStreaming(true);
        whenGenerateStream(p -> p.contains("Question: Which port does it listen on?"))
                .thenReturn(Flux.just("The runbook covers restarts [1] but not the port.\n", "INSUFFICIENT: the listening port"));

        List<SseEvent> events = streamChat("{\"message\":\"Which port does it listen on?\"}");
        SseEvent last = events.getLast();
        assertThat(last.name()).isEqualTo("final");
        assertThat((String) JsonPath.read(last.data(), "$.response.grounding")).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat((String) JsonPath.read(last.data(), "$.response.notes")).isEqualTo("the listening port");
        assertThat((String) JsonPath.read(last.data(), "$.response.answer")).doesNotContain("INSUFFICIENT");
    }

    @Test
    void fallsBackToStructuredOutputWhenTheModelCannotStream() throws Exception {
        supportsStreaming(false);
        whenCreateObject(p -> p.contains("Question: How do I roll back?"), GroundedAnswerDraft.class)
                .thenReturn(new GroundedAnswerDraft("Deploy the previous image tag [1].", List.of(1), true, null));

        List<SseEvent> events = streamChat("{\"message\":\"How do I roll back?\"}");
        assertThat(events).extracting(SseEvent::name)
                .containsExactly("status", "status", "status", "delta", "status", "final");
        assertThat((String) JsonPath.read(events.get(3).data(), "$.text")).isEqualTo("Deploy the previous image tag [1].");
        assertThat((String) JsonPath.read(events.getLast().data(), "$.response.grounding")).isIn("GROUNDED", "PARTIAL");
    }

    @Test
    void noEvidenceStreamsTheFallbackAnswerWithoutTheModel() throws Exception {
        List<SseEvent> events = streamChat("{\"message\":\"anything\",\"options\":{\"documentIds\":[\"no-such-document\"]}}");
        assertThat(events).extracting(SseEvent::name).containsExactly("status", "status", "delta", "status", "final");
        assertThat((String) JsonPath.read(events.getLast().data(), "$.response.grounding")).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void validationErrorsAreNotStreamed() throws Exception {
        mockMvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    /** Agentic mode (Phase 9c follow-up): every tool call is narrated as a status detail; the answer arrives whole. */
    @Test
    void agenticStreamNarratesEachSearchAndDeliversTheAnswerInOnePiece() throws Exception {
        whenCreateObject(p -> p.contains("Question: How do I restart payments in agentic mode?"),
                AgenticDraft.class, ChatStreamControllerTest::instructsAgenticResearch)
                .thenAnswer(invocation -> {
                    LlmInteraction interaction = invocation.getArgument(1);
                    Tool vectorSearch = interaction.getTools().stream()
                            .filter(t -> t.getDefinition().getName().equals("knowledge_base_vectorSearch")).findFirst().orElseThrow();
                    String output = vectorSearch.call("{\"query\":\"restart payments\",\"topK\":3}").toString();
                    Matcher matcher = Pattern.compile("chunkId: (\\S+)").matcher(output);
                    assertThat(matcher.find()).as("tool output lists chunk ids: %s", output).isTrue();
                    return new AgenticDraft("Run `systemctl restart payments` {{chunk:" + matcher.group(1) + "}}.", List.of(matcher.group(1)), true, null);
                });

        List<SseEvent> events = streamChat("{\"message\":\"How do I restart payments in agentic mode?\",\"options\":{\"mode\":\"AGENTIC\"}}");

        assertThat(events).extracting(SseEvent::name).containsSubsequence("status", "status", "delta", "status", "final");
        List<String> statuses = events.stream().filter(e -> e.name().equals("status")).map(SseEvent::data).toList();
        assertThat(statuses).hasSize(3);
        assertThat(statuses.getFirst()).isEqualTo("{\"stage\":\"researching\"}");
        assertThat(statuses.get(1)).matches("\\{\"stage\":\"researching\",\"detail\":\"search 1: \\\\\"restart payments\\\\\" \\(\\d+ passages\\)\"}");
        assertThat(statuses.getLast()).isEqualTo("{\"stage\":\"verifying\"}");
        assertThat(events).filteredOn(e -> e.name().equals("delta")).hasSize(1)
                .first().satisfies(delta -> assertThat((String) JsonPath.read(delta.data(), "$.text")).isEqualTo("Run `systemctl restart payments` [1]."));
        SseEvent last = events.getLast();
        assertThat((String) JsonPath.read(last.data(), "$.response.answer")).isEqualTo("Run `systemctl restart payments` [1].");
        assertThat((String) JsonPath.read(last.data(), "$.response.grounding")).isEqualTo("GROUNDED");
    }

    /** A client that went away cancels the streamed generation and gets neither a final nor an error event. */
    @Test
    void abandonedStreamStopsTheModelAndEmitsNoTerminalEvent() {
        supportsStreaming(true);
        whenGenerateStream(p -> p.contains("Question: How do I restart the payment service?"))
                .thenReturn(Flux.just("Run `systemctl restart payments` ", "on the host [1]."));
        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();
        ChatCancellation cancellation = new ChatCancellation();
        cancellation.cancel("client went away");

        chatService.stream(new ChatRequest(null, "How do I restart the payment service?", null), events::add, cancellation);

        assertThat(events).as("a request nobody is waiting for does no work at all").isEmpty();
    }

    /**
     * The failure this whole task is about: a model that stops sending tokens without ending the
     * stream. Cancellation used to be a test on the next fragment, so there was no next fragment to
     * test and the request held its thread until the stream timeout.
     */
    @Test
    void aSilentModelIsDroppedAsSoonAsTheClientLeaves() throws Exception {
        supportsStreaming(true);
        whenGenerateStream(p -> p.contains("Question: What happens when the model goes quiet?"))
                .thenReturn(Flux.never());
        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();
        ChatCancellation cancellation = new ChatCancellation();

        Thread request = Thread.ofPlatform().start(() -> chatService.stream(
                new ChatRequest(null, "What happens when the model goes quiet?", null), events::add, cancellation));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(events)
                .anyMatch(e -> e instanceof ChatStreamEvent.Status status && status.stage().equals("generating")));

        cancellation.cancel("client went away");

        request.join(Duration.ofSeconds(10));
        assertThat(request.isAlive()).as("the request must let go of its thread, not wait for a token").isFalse();
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Final || e instanceof ChatStreamEvent.Error);
    }

    /**
     * An answer that arrives after the client left is not a result: it is not reported, and above all
     * it does not enter the history that the next question would be answered from.
     */
    @Test
    void anAnswerThatFinishesAfterTheClientLeftIsNotRecorded() throws Exception {
        supportsStreaming(false);
        String conversationId = "abandoned-" + System.nanoTime();
        ChatCancellation cancellation = new ChatCancellation();
        whenCreateObject(p -> p.contains("Question: Who reads an answer nobody waited for?"), GroundedAnswerDraft.class)
                .thenAnswer(_ -> {
                    cancellation.cancel("client went away"); // the client leaves while the model is answering
                    return new GroundedAnswerDraft("Nobody [1].", List.of(1), true, null);
                });
        List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();

        chatService.stream(new ChatRequest(conversationId, "Who reads an answer nobody waited for?", null),
                events::add, cancellation);

        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Final || e instanceof ChatStreamEvent.Error);
        mockMvc.perform(get("/api/conversations/{id}", conversationId)).andExpect(status().isNotFound());
    }

    /**
     * The agentic branch is now recognised by its prompt contributor rather than by the user prompt:
     * standing instructions travel in the system message, which the mocked {@code LlmOperations}
     * never sees in the message list.
     */
    private static boolean instructsAgenticResearch(LlmInteraction interaction) {
        return interaction.getPromptContributors().stream()
                .anyMatch(c -> c.contribution().contains("knowledge_base_vectorSearch"));
    }
}
