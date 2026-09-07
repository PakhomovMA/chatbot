package com.personal.chatbot.utils;

import org.jspecify.annotations.Nullable;

/** Media type of an upload: what the client declared, or what the extension implies. */
public final class MediaTypes {

    public static final String UNKNOWN = "application/octet-stream";

    private MediaTypes() {
    }

    /** A declared type wins unless it is missing, blank or the generic {@link #UNKNOWN}. */
    public static String resolve(@Nullable String declared, String filename) {
        if (declared != null && !declared.isBlank() && !declared.equalsIgnoreCase(UNKNOWN)) {
            return declared.strip();
        }
        return forExtension(Filenames.extension(filename));
    }

    public static String forExtension(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            case "txt" -> "text/plain";
            case "html", "htm" -> "text/html";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> UNKNOWN;
        };
    }
}
