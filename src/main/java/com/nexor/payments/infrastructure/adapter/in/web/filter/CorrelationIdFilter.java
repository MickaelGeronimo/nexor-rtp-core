package com.nexor.payments.infrastructure.adapter.in.web.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Distributed Tracing & Correlation Filter.
 *
 * <p>Extracts or generates standard {@code X-Correlation-Id} and {@code W3C Traceparent}
 * headers, propagating them to SLF4J MDC for unified log correlation across services.
 *
 * <p><b>MDC keys populated:</b>
 * <ul>
 *   <li>{@code correlationId} — request-scoped trace ID (echoed in response header)</li>
 *   <li>{@code clientIp} — originating IP (X-Forwarded-For aware, for structured logs)</li>
 * </ul>
 *
 * <p><b>Why highest precedence?</b> Must run before Spring Security filters so that
 * correlation IDs appear in security-related log lines (failed auth, rate limit, etc.).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    public static final String MDC_CLIENT_IP_KEY = "clientIp";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            // Extract or generate correlation ID
            String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            // Resolve originating client IP (X-Forwarded-For aware)
            String clientIp = resolveClientIp(httpRequest);

            MDC.put(MDC_CORRELATION_ID_KEY, correlationId);
            MDC.put(MDC_CLIENT_IP_KEY, clientIp);

            // Echo the correlation ID back to the caller for client-side tracing
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

            try {
                chain.doFilter(request, response);
            } finally {
                // Always clear MDC to prevent thread pool leakage (critical with virtual threads)
                MDC.remove(MDC_CORRELATION_ID_KEY);
                MDC.remove(MDC_CLIENT_IP_KEY);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take only the first IP in the chain (leftmost = originating client)
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
