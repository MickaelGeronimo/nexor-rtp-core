package com.nexor.payments.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexor.payments.infrastructure.adapter.in.web.dto.PaymentRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API integration tests for PaymentController.
 *
 * <p>Uses MockMvc to exercise the full Spring filter chain, including:
 * - CorrelationIdFilter (MDC population)
 * - RateLimitingFilter (100 req/min per key — well under limit in tests)
 * - ApiKeyAuthenticationFilter (X-API-Key → RBAC role)
 * - Spring Security authorization rules
 * - PaymentController → PaymentSagaOrchestrator
 *
 * <p>API keys are configured in src/test/resources/application.properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Payment Controller REST API Integration Tests")
class PaymentControllerIntegrationTest extends com.nexor.payments.testsupport.AbstractContainerizedTest {

    // Test API key values match src/test/resources/application.properties
    private static final String PAY_KEY   = "test-pay-key";
    private static final String AUDIT_KEY = "test-audit-key";
    private static final String ADMIN_KEY = "test-admin-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/payments - Valid PAYMENT_SUBMITTER key → 201 Created")
    void shouldProcessPaymentViaRestApi() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto(
                "NEXOR:0001:1001-9",
                "NEXOR:0001:2002-8",
                "250.00",
                "BRL",
                "REST API Transfer test",
                null
        );

        String idempotencyKey = "REST-IDEMP-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", PAY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.endToEndId").exists())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.rail").value("BOOK_TRANSFER"));

        // Verify ledger journal is accessible by AUDITOR role
        mockMvc.perform(get("/api/v1/ledger/journal")
                        .header("X-API-Key", AUDIT_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /api/v1/payments - Missing Idempotency-Key header → 400 Bad Request")
    void shouldRequireIdempotencyKeyHeader() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto(
                "NEXOR:0001:1001-9",
                "NEXOR:0001:2002-8",
                "50.00",
                "BRL",
                "No header test",
                null
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-API-Key", PAY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_REQUIRED_HEADER"));
    }

    @Test
    @DisplayName("POST /api/v1/payments - Missing X-API-Key → 401 Unauthorized")
    void shouldReturn401WhenApiKeyMissing() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto(
                "NEXOR:0001:1001-9",
                "NEXOR:0001:2002-8",
                "50.00",
                "BRL",
                "No auth test",
                null
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "some-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/payments - AUDITOR key used for POST → 403 Forbidden")
    void shouldReturn403WhenAuditorTriesToSubmitPayment() throws Exception {
        PaymentRequestDto request = new PaymentRequestDto(
                "NEXOR:0001:1001-9",
                "NEXOR:0001:2002-8",
                "50.00",
                "BRL",
                "Auditor submit test",
                null
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "auditor-attempt-" + UUID.randomUUID())
                        .header("X-API-Key", AUDIT_KEY)  // AUDITOR cannot submit payments
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/ledger/journal - PAYMENT_SUBMITTER cannot access ledger → 403 Forbidden")
    void shouldReturn403WhenPaymentSubmitterAccessesLedger() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/journal")
                        .header("X-API-Key", PAY_KEY))  // PAYMENT_SUBMITTER cannot read ledger
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/ledger/journal - ADMIN key can access ledger → 200 OK")
    void shouldAllowAdminToAccessLedger() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/journal")
                        .header("X-API-Key", ADMIN_KEY))
                .andExpect(status().isOk());
    }
}
