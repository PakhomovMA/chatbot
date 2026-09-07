package com.personal.chatbot.controller;

import com.personal.chatbot.models.retrieval.RetrievalResult;
import com.personal.chatbot.service.retrieval.RetrievalTraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Recent retrieval traces (docs/system-plan.md D14). */
@RestController
@RequestMapping("/api/diagnostics/retrieval")
public class DiagnosticsController {

    private final RetrievalTraceStore traces;

    public DiagnosticsController(RetrievalTraceStore traces) {
        this.traces = traces;
    }

    @GetMapping
    public List<RetrievalResult> recent(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return traces.recent(Math.clamp(limit, 1, 200));
    }

    @GetMapping("/{traceId}")
    public ResponseEntity<RetrievalResult> byId(@PathVariable String traceId) {
        return ResponseEntity.of(traces.find(traceId));
    }
}
