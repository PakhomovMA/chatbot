package com.personal.chatbot.controller;

import com.personal.chatbot.models.knowledge.Document;
import com.personal.chatbot.models.knowledge.DocumentStatus;
import com.personal.chatbot.models.knowledge.dto.DocumentPage;
import com.personal.chatbot.models.knowledge.dto.DocumentStatusView;
import com.personal.chatbot.models.knowledge.dto.UploadResponse;
import com.personal.chatbot.service.knowledge.DocumentService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Knowledge-base document API (docs/system-plan.md §8). Only DTOs cross this boundary (INV-10). */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** 202 for a new document (indexing is asynchronous), 200 when the content was already known. */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
                                                 @RequestParam(value = "title", required = false) @Nullable String title) {
        UploadResponse response = documentService.upload(toUpload(file, title));
        return ResponseEntity.status(response.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PutMapping(value = "/{id}/content", consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> replaceContent(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        UploadResponse response = documentService.replaceContent(id, toUpload(file, null));
        return ResponseEntity.status(response.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    public DocumentPage list(@RequestParam(value = "status", required = false) @Nullable DocumentStatus status,
                             @RequestParam(value = "q", required = false) @Nullable String text,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "size", defaultValue = "20") int size) {
        return documentService.list(new DocumentService.Query(status, text, page, size));
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable String id) {
        return documentService.get(id);
    }

    @GetMapping("/{id}/status")
    public DocumentStatusView status(@PathVariable String id) {
        return DocumentStatusView.of(documentService.get(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        documentService.delete(id);
    }

    private static DocumentService.Upload toUpload(MultipartFile file, @Nullable String title) {
        try {
            return new DocumentService.Upload(file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    file.getInputStream(), title);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read upload", e);
        }
    }
}
