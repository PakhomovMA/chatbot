package com.personal.chatbot.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class SafeMessageConverter extends ClassicConverter {
    @Override public String convert(ILoggingEvent event) { return SafeLog.message(event); }
}
