package com.personal.chatbot.models.knowledge;

import java.nio.file.Path;

/** Uploaded bytes parked in the staging area with their digest, before de-duplication decides their fate. */
public record StagedBlob(Path tempFile, String contentHash, long sizeBytes) {
}
