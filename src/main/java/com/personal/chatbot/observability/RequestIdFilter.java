package com.personal.chatbot.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts or generates an {@code X-Request-Id}, echoes it in the response and keeps it in the MDC for
 * the request. Async (SSE) responses keep the header because it is set before the handler runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(RequestContext.REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId);
        try (RequestContext.Scope _ = RequestContext.with(RequestContext.REQUEST_ID, requestId)) {
            chain.doFilter(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
