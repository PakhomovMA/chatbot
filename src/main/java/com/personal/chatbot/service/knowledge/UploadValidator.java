package com.personal.chatbot.service.knowledge;

import com.personal.chatbot.config.ChatbotProperties;
import com.personal.chatbot.exceptions.InvalidUploadException;
import com.personal.chatbot.exceptions.InvalidUploadException.Reason;
import com.personal.chatbot.models.knowledge.StagedBlob;
import com.personal.chatbot.utils.Filenames;

import java.util.Set;

/**
 * Upload hardening (docs/system-plan.md D9, D13): file name, extension and size. The declared size
 * is only a fast rejection — the staged bytes are what counts, so an oversized upload is caught even
 * when the client lies about {@code Content-Length}.
 */
public class UploadValidator {

    private final ChatbotProperties.Knowledge settings;

    public UploadValidator(ChatbotProperties.Knowledge settings) {
        this.settings = settings;
    }

    /** @return the sanitised file name */
    public String validateName(String rawFilename, long declaredSize) {
        String filename = Filenames.sanitize(rawFilename == null ? "" : rawFilename);
        if (filename.isEmpty()) {
            throw new InvalidUploadException(Reason.BAD_FILENAME, "Upload has no usable file name");
        }
        String extension = Filenames.extension(filename);
        Set<String> allowed = settings.allowedExtensions();
        if (!allowed.contains(extension)) {
            throw new InvalidUploadException(Reason.UNSUPPORTED_TYPE,
                    "Unsupported file type '" + extension + "'; allowed: " + String.join(", ", allowed));
        }
        if (declaredSize > settings.maxUploadSize().toBytes()) {
            throw new InvalidUploadException(Reason.TOO_LARGE, "File exceeds " + settings.maxUploadSize());
        }
        return filename;
    }

    /** Checks what actually landed in staging; throws once the caller has discarded nothing yet. */
    public void validateStaged(StagedBlob staged) {
        if (staged.sizeBytes() == 0) {
            throw new InvalidUploadException(Reason.EMPTY, "Uploaded file is empty");
        }
        if (staged.sizeBytes() > settings.maxUploadSize().toBytes()) {
            throw new InvalidUploadException(Reason.TOO_LARGE, "File exceeds " + settings.maxUploadSize());
        }
    }
}
