package com.personal.chatbot.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;

/** Select with logging.structured.format.console; shares the console content policy. */
public final class SafeStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override public String format(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("timestamp", event.getInstant().toString());
        fields.put("level", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("message", SafeLog.message(event));
        fields.putAll(SafeLog.correlation(event));
        return json.writeValueAsString(fields) + "\n";
    }
}
