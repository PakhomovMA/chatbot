package com.personal.chatbot.observability;

import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.resources.Resource;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Last boundary before every exporter, including logging. No span is dropped: ancestry survives.
 */
public final class TelemetrySanitizer {
    public static final int MAX_TEXT = 2048;
    public static final int MAX_ATTRIBUTES = 48;
    private static final Set<String> METADATA = Set.of(
            "session.id", "chatbot.request.id", "chatbot.message.id", "chatbot.document.id",
            "chatbot.retrieval.id", "chatbot.cache.layer", "chatbot.cache.derivation.result",
            "deployment.environment.name", "service.version", "service.name",
            "gen_ai.operation.name", "gen_ai.system", "gen_ai.provider.name", "gen_ai.request.model",
            "gen_ai.response.model", "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens",
            "gen_ai.usage.total_tokens", "gen_ai.request.temperature", "gen_ai.request.max_tokens",
            "embabel.event.type", "embabel.agent.name", "embabel.action.name", "embabel.tool.name",
            "operation", "mode", "answer.mode", "grounding", "outcome", "stage", "strategy",
            "provider", "model", "error.type", "exception.type", "http.request.method", "http.response.status_code", "http.route");
    private static final Set<String> CONTENT = Set.of("gen_ai.prompt", "gen_ai.completion", "embabel.input", "embabel.output",
            "input.value", "output.value", "tool.arguments", "tool.result");
    private static final Pattern SECRETS = Pattern.compile(
            "(?i)(?:bearer|basic)\\s+[A-Za-z0-9+/=._-]+"
                    + "|[\"']?(?:password|passwd|secret|api[_-]?key|access[_-]?token|authorization)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|[^\\s,;}]+)"
                    + "|(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]+"
                    + "|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
                    + "|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"
                    + "|https?://[^\\s/@]+:[^\\s/@]+@");
    private final ContentPolicy policy;
    private final List<String> redactions;
    // Resource attributes are process constants; sanitizing them once avoids repeating the work
    // for every exported span.
    private volatile Resource safeResource;

    public TelemetrySanitizer(ContentPolicy policy, List<String> redactions) {
        this.policy = policy;
        this.redactions = redactions.stream().filter(s -> !s.isBlank()).toList();
    }

    public String text(String value) {
        String sanitized = value;
        for (String secret : redactions) sanitized = sanitized.replace(secret, "[REDACTED]");
        sanitized = SECRETS.matcher(sanitized).replaceAll("[REDACTED]");
        return sanitized.substring(0, Math.min(MAX_TEXT, sanitized.length()));
    }

    private Attributes attributes(Attributes source, boolean content) {
        var result = Attributes.builder();
        int[] count = {0};
        int[] remaining = {8192};
        source.forEach((key, value) -> {
            boolean metadata = METADATA.contains(key.getKey());
            if ((!metadata && !(content && policy == ContentPolicy.REDACTED_CONTENT && CONTENT.contains(key.getKey())))
                    || count[0] >= MAX_ATTRIBUTES || remaining[0] <= 0) return;
            if (key.getType() == AttributeType.STRING) {
                String safe = text((String) value);
                int max = Math.min(metadata ? 128 : MAX_TEXT, remaining[0]);
                safe = safe.substring(0, Math.min(max, safe.length()));
                result.put(key.getKey(), safe);
                remaining[0] -= safe.length();
            } else if (value instanceof Long number) result.put(key.getKey(), number);
            else if (value instanceof Double number) result.put(key.getKey(), number);
            else if (value instanceof Boolean flag) result.put(key.getKey(), flag);
            else return;
            count[0]++;
        });
        return result.build();
    }

    public SpanData span(SpanData source) {
        Attributes safe = attributes(source.getAttributes(), true);
        // Events can include arbitrary exception messages/stack traces. Preserve exception type only.
        List<EventData> events = source.getEvents().stream().limit(16)
                .map(event -> EventData.create(event.getEpochNanos(),
                        event.getName().equals("exception") ? "exception" : "event",
                        attributes(event.getAttributes(), false))).toList();
        List<LinkData> links = source.getLinks().stream().limit(16)
                .map(link -> LinkData.create(link.getSpanContext(), attributes(link.getAttributes(), false))).toList();
        Resource resource = safeResource(source.getResource());
        String name = safeName(source.getName());
        return new DelegatingSpanData(source) {
            @Override
            public InstrumentationScopeInfo getInstrumentationScopeInfo() {
                return InstrumentationScopeInfo.create("chatbot.telemetry");
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public Attributes getAttributes() {
                return safe;
            }

            @Override
            public Resource getResource() {
                return resource;
            }

            @Override
            public List<EventData> getEvents() {
                return events;
            }

            @Override
            public List<LinkData> getLinks() {
                return links;
            }

            @Override
            public StatusData getStatus() {
                return StatusData.create(source.getStatus().getStatusCode(), "");
            }

            @Override
            public int getTotalAttributeCount() {
                return safe.size();
            }

            @Override
            public int getTotalRecordedEvents() {
                return events.size();
            }

            @Override
            public int getTotalRecordedLinks() {
                return links.size();
            }
        };
    }

    private Resource safeResource(Resource source) {
        Resource cached = safeResource;
        if (cached != null) return cached;
        Resource created = Resource.create(attributes(source.getAttributes(), false));
        safeResource = created;
        return created;
    }

    // Precompiled: String.matches would compile this on every span.
    private static final Pattern SAFE_NAME = Pattern.compile(
            "chatbot\\.[a-z.]+|(?:agent|action|chat|tool|embedding|embeddings|llm|llm.invocation|planning|goal|http) [A-Za-z0-9_.:/ -]{1,100}|(?:tool-loop|tool-loop-completed|COMPLETED|knowledge_base_(?:vectorSearch|textSearch|broadenChunk|zoomOut|listSections|readSection))|(?:GET|POST|PUT|DELETE|PATCH) /[A-Za-z0-9/{}._-]*");

    private String safeName(String name) {
        // Framework names contain configured action/model identifiers, never prompts. Unknown dynamic
        // names remain in the tree under a neutral name rather than becoming an unreviewed content path.
        if (SAFE_NAME.matcher(name).matches()) {
            return text(name);
        }
        return "operation";
    }
}
