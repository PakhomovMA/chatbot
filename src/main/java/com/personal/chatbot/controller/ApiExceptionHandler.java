package com.personal.chatbot.controller;

import com.personal.chatbot.exceptions.ConversationNotFoundException;
import com.personal.chatbot.exceptions.DocumentNotFoundException;
import com.personal.chatbot.exceptions.IndexUnavailableException;
import com.personal.chatbot.exceptions.InvalidUploadException;
import com.personal.chatbot.exceptions.ServiceStoppingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 9457 problem details. Framework exceptions (multipart size limit,
 * bad parameters, unsupported media type) are handled by the superclass.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail documentNotFound(DocumentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Document not found");
        problem.setProperty("documentId", e.documentId());
        return problem;
    }

    @ExceptionHandler(InvalidUploadException.class)
    ProblemDetail invalidUpload(InvalidUploadException e) {
        HttpStatus status = switch (e.reason()) {
            case EMPTY, BAD_FILENAME -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle("Upload rejected");
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    ProblemDetail conversationNotFound(ConversationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Conversation not found");
        return problem;
    }

    @ExceptionHandler(IndexUnavailableException.class)
    ProblemDetail indexUnavailable(IndexUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Index unavailable");
        problem.setProperty("indexState", e.state().name());
        return problem;
    }

    @ExceptionHandler(ServiceStoppingException.class)
    ProblemDetail serviceStopping(ServiceStoppingException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Shutting down");
        problem.setProperty("component", e.what());
        return problem;
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpected(RuntimeException e) {
        log.error("Unhandled error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        problem.setTitle("Internal error");
        return problem;
    }
}
