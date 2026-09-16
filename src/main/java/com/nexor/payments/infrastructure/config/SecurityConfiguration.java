package com.nexor.payments.infrastructure.config;

import com.nexor.payments.infrastructure.security.ApiKeyAuthenticationFilter;
import com.nexor.payments.infrastructure.security.RateLimitingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Security configuration for Nexor RTP Core.
 *
 * <p><b>Authentication model:</b> API Key via {@code X-API-Key} header.
 * Each key carries a role: PAYMENT_SUBMITTER (can POST /payments),
 * AUDITOR (can GET ledger/journal endpoints), ADMIN (all endpoints).
 *
 * <p><b>Why API Key instead of OAuth2/JWT?</b>
 * In enterprise deployments, you typically integrate with your
 * institution's IAM system (Keycloak, Okta, AWS Cognito) for OIDC + JWT. The API Key
 * layer here demonstrates the structural pattern (filter → authentication → authorization)
 * without requiring an external IdP dependency that would complicate standalone execution.
 * See ADR-009 for the full trade-off discussion.
 *
 * <p><b>Rate limiting:</b> Sliding-window counter per API key, 100 req/min default.
 * Implemented in {@link RateLimitingFilter} using ConcurrentHashMap + atomic counters.
 * See ADR-007 for why we chose in-process rate limiting over a distributed solution.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Value("${nexor.security.api-keys.payment-submitter:nexor-pay-key-dev}")
    private String paymentSubmitterKey;

    @Value("${nexor.security.api-keys.auditor:nexor-audit-key-dev}")
    private String auditorKey;

    @Value("${nexor.security.api-keys.admin:nexor-admin-key-dev}")
    private String adminKey;

    @Value("${nexor.security.rate-limit.requests-per-minute:100}")
    private int requestsPerMinute;

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter() {
        return new ApiKeyAuthenticationFilter(paymentSubmitterKey, auditorKey, adminKey);
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter(requestsPerMinute);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — no session, no CSRF token needed
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            // Register our filters before the standard UsernamePasswordAuthenticationFilter
            .addFilterBefore(rateLimitingFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)

            // Consistent JSON error responses for unauthenticated and forbidden requests
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Authentication required: missing or invalid API key.\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"error\":\"FORBIDDEN\",\"message\":\"Access denied: insufficient role or permission.\"}");
                })
            )

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Health & metrics endpoints: always public (monitored by load balancers)
                .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()

                // Payment submission requires PAYMENT_SUBMITTER or ADMIN role
                .requestMatchers(HttpMethod.POST, "/api/v1/payments").hasAnyRole("PAYMENT_SUBMITTER", "ADMIN")

                // Ledger audit endpoints require AUDITOR or ADMIN role
                .requestMatchers(HttpMethod.GET, "/api/v1/ledger/**").hasAnyRole("AUDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/payments/**").hasAnyRole("AUDITOR", "ADMIN")

                // Metrics endpoint requires ADMIN
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // Everything else: deny by default
                .anyRequest().denyAll()
            )

            // Security response headers (defense-in-depth)
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})  // X-Content-Type-Options: nosniff
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                )
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicy(pp -> pp.policy("geolocation=(), microphone=(), camera=()"))
            );

        return http.build();
    }
}
