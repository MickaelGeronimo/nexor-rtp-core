package com.nexor.payments.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticates requests via the {@code X-API-Key} header.
 *
 * <p><b>Design:</b> Each API key is bound to a single role. The filter
 * populates Spring Security's {@link SecurityContextHolder} so that
 * downstream {@code @PreAuthorize} or {@code .hasRole()} checks work
 * transparently without any additional coupling to the domain layer.
 *
 * <p><b>Failure behavior:</b> Requests with a missing or unrecognized API key
 * receive HTTP 401. Requests with a recognized key but insufficient role
 * receive HTTP 403 from Spring Security's authorization layer.
 *
 * <p><b>Production note:</b> In production, API keys should be hashed with
 * Argon2 before storage and looked up via constant-time comparison to
 * prevent timing attacks. This reference implementation uses configuration keys loaded
 * from environment variables or application secrets.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    // Map: API key value → Spring Security role string (e.g., "ROLE_ADMIN")
    private final Map<String, String> keyToRole;

    public ApiKeyAuthenticationFilter(String paymentSubmitterKey, String auditorKey, String adminKey) {
        Objects.requireNonNull(paymentSubmitterKey, "paymentSubmitterKey must not be null");
        Objects.requireNonNull(auditorKey, "auditorKey must not be null");
        Objects.requireNonNull(adminKey, "adminKey must not be null");
        this.keyToRole = Map.of(
                paymentSubmitterKey, "ROLE_PAYMENT_SUBMITTER",
                auditorKey, "ROLE_AUDITOR",
                adminKey, "ROLE_ADMIN"
        );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Skip authentication if already authenticated (e.g., by another filter)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow actuator health endpoint without authentication
        String path = request.getRequestURI();
        if (path.equals("/actuator/health") || path.equals("/actuator/info") || path.equals("/actuator/prometheus")) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"UNAUTHORIZED","message":"Missing X-API-Key header"}
                    """);
            return;
        }

        String role = keyToRole.get(apiKey.trim());
        if (role == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"UNAUTHORIZED","message":"Invalid API key"}
                    """);
            return;
        }

        // Populate security context with authenticated principal + granted authority
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "api-client",
                null,
                List.of(new SimpleGrantedAuthority(role))
        );
        authentication.setDetails(request.getRemoteAddr());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
