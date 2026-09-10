package com.personal.chatbot.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class CorrelationConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) { return SafeLog.correlation(event).toString(); }
}
