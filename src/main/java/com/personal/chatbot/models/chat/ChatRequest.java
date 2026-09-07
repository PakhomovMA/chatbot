package com.personal.chatbot.models.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/** Chat API request (docs/system-plan.md §8). */
public record ChatRequest(
        @Nullable @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 4000) String message,
        @Nullable @Valid Options options
) {

    public record Options(
            @Nullable @Min(1) @Max(20) Integer topK,
            @Nullable Set<String> documentIds,
            @Nullable Boolean includeDiagnostics
    ) {
        public static final Options DEFAULT = new Options(null, null, null);

        public boolean diagnostics() {
            return Boolean.TRUE.equals(includeDiagnostics);
        }
    }

    public Options optionsOrDefault() {
        return options != null ? options : Options.DEFAULT;
    }
}
