package com.nexor.payments.infrastructure.web;

import com.nexor.payments.infrastructure.adapter.in.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Global Exception Handler RFC & Status Code Mapping Tests")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");

    @Test
    @DisplayName("Malformed JSON Body → HTTP 400 Bad Request")
    void shouldHandleMalformedJsonBody() {
        var ex = new HttpMessageNotReadableException("Invalid JSON syntax");
        var response = handler.handleMessageNotReadable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("MALFORMED_REQUEST_BODY");
        assertThat(response.getBody().get("message")).toString().contains("malformed");
    }

    @Test
    @DisplayName("Unsupported HTTP Method → HTTP 405 Method Not Allowed")
    void shouldHandleUnsupportedHttpMethod() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");
        var response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().get("message")).toString().contains("DELETE");
    }

    @Test
    @DisplayName("Parameter Type Mismatch → HTTP 400 Bad Request")
    void shouldHandleParameterTypeMismatch() {
        var ex = new MethodArgumentTypeMismatchException("abc", Long.class, "limit", null, null);
        var response = handler.handleTypeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("INVALID_PARAMETER_TYPE");
    }

    @Test
    @DisplayName("Missing Required Parameter → HTTP 400 Bad Request")
    void shouldHandleMissingParameter() {
        var ex = new MissingServletRequestParameterException("idempotencyKey", "String");
        var response = handler.handleMissingParam(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("MISSING_REQUIRED_PARAMETER");
    }

    @Test
    @DisplayName("Database Integrity Violation → HTTP 409 Conflict")
    void shouldHandleDataIntegrityViolation() {
        var ex = new DataIntegrityViolationException("Duplicate unique key");
        var response = handler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("DATA_INTEGRITY_CONFLICT");
    }

    @Test
    @DisplayName("Optimistic Locking Conflict → HTTP 409 Conflict with retryable=true")
    void shouldHandleOptimisticLockingConflict() {
        var ex = new OptimisticLockingFailureException("Row updated by another transaction");
        var response = handler.handleOptimisticLockConflict(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("CONCURRENT_MODIFICATION_CONFLICT");
        assertThat(response.getBody().get("retryable")).isEqualTo(true);
    }
}
