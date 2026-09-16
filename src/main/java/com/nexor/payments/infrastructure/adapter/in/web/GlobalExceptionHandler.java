package com.nexor.payments.infrastructure.adapter.in.web;

import com.nexor.payments.domain.exception.FraudRejectionException;
import com.nexor.payments.domain.exception.InsufficientFundsException;
import com.nexor.payments.domain.exception.InvalidStateTransitionException;
import com.nexor.payments.domain.exception.LedgerImbalanceException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception-to-HTTP-response mapping for the Nexor RTP Core API.
 *
 * <p><b>Design principles:</b>
 * <ul>
 *   <li>Never expose internal stack traces or implementation details to callers</li>
 *   <li>Always include a machine-readable {@code error} code + human-readable {@code message}</li>
 *   <li>Log all server errors (5xx) with full context; client errors (4xx) at WARN only</li>
 *   <li>Financial errors (insufficient funds, ledger imbalance) use 422 Unprocessable Entity</li>
 *   <li>Concurrent conflicts (optimistic lock) use 409 Conflict + Retry-After hint</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // Financial domain errors — 422 Unprocessable Entity
    // -------------------------------------------------------------------------

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(
            InsufficientFundsException ex, HttpServletRequest req) {
        log.warn("[PAYMENT-REJECTED] Insufficient funds | path={}", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error(
                "INSUFFICIENT_FUNDS",
                ex.getMessage()
        ));
    }

    @ExceptionHandler(LedgerImbalanceException.class)
    public ResponseEntity<Map<String, Object>> handleLedgerImbalance(
            LedgerImbalanceException ex, HttpServletRequest req) {
        // CRITICAL: this should never happen in production — indicates a bug in journal entry creation
        log.error("[CRITICAL-LEDGER-IMBALANCE] Fatal accounting invariant violated | path={} | detail={}",
                req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                "CRITICAL_LEDGER_IMBALANCE",
                "Fatal accounting invariant violated. Incident logged. Reference your X-Correlation-Id."
        ));
    }

    @ExceptionHandler(FraudRejectionException.class)
    public ResponseEntity<Map<String, Object>> handleFraudRejection(
            FraudRejectionException ex, HttpServletRequest req) {
        log.warn("[FRAUD-REJECTED] rule={} | path={}", ex.getRuleName(), req.getRequestURI());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "AML_FRAUD_REJECTED");
        body.put("rule", ex.getRuleName());
        body.put("reasonCode", ex.getReasonCode());
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStateTransition(
            InvalidStateTransitionException ex, HttpServletRequest req) {
        log.warn("[INVALID-STATE-TRANSITION] {} | path={}", ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "INVALID_STATE_TRANSITION",
                ex.getMessage()
        ));
    }

    // -------------------------------------------------------------------------
    // Concurrent conflict — 409 Conflict (optimistic locking lost update)
    // -------------------------------------------------------------------------

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockConflict(
            OptimisticLockingFailureException ex, HttpServletRequest req) {
        // This means two concurrent requests tried to modify the same entity simultaneously.
        // The caller should retry with exponential backoff. The idempotency layer ensures
        // a retry with the same idempotency key will return the already-committed result.
        log.warn("[OPTIMISTIC-LOCK-CONFLICT] Concurrent modification detected | path={}", req.getRequestURI());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CONCURRENT_MODIFICATION_CONFLICT");
        body.put("message", "A concurrent modification conflict occurred. Retry with the same Idempotency-Key.");
        body.put("retryable", true);
        body.put("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // -------------------------------------------------------------------------
    // Client errors — 400/401/403/404
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("[BAD-REQUEST] {} | path={}", ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex, HttpServletRequest req) {
        log.warn("[CONFLICT] {} | path={}", ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("CONCURRENT_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(
                "MISSING_REQUIRED_HEADER",
                "Required header '" + ex.getHeaderName() + "' must be provided."
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        String allErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[VALIDATION-FAILED] {} | path={}", allErrors, req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("VALIDATION_FAILED", allErrors));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        // Do NOT expose the reason — just a generic forbidden
        log.warn("[ACCESS-DENIED] Insufficient permissions | path={}", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(
                "FORBIDDEN",
                "You do not have permission to perform this action."
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("[MALFORMED-REQUEST-BODY] {} | path={}", ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(
                "MALFORMED_REQUEST_BODY",
                "Request body is missing, malformed, or contains unreadable JSON format."
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.warn("[METHOD-NOT-SUPPORTED] Method {} not allowed | path={}", ex.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error(
                "METHOD_NOT_ALLOWED",
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint."
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("[TYPE-MISMATCH] Param {} invalid | path={}", ex.getName(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(
                "INVALID_PARAMETER_TYPE",
                "Parameter '" + ex.getName() + "' has invalid type or format."
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        log.warn("[MISSING-PARAM] Param {} missing | path={}", ex.getParameterName(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(
                "MISSING_REQUIRED_PARAMETER",
                "Required query parameter '" + ex.getParameterName() + "' must be provided."
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("[DATA-INTEGRITY-VIOLATION] Uniqueness or constraint violation | path={}", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "DATA_INTEGRITY_CONFLICT",
                "Database constraint violation or duplicate key conflict."
        ));
    }

    // -------------------------------------------------------------------------
    // Fallback — 500 Internal Server Error
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, HttpServletRequest req) {
        // IMPORTANT: Log the full stack trace here — this should NEVER be returned to the client
        log.error("[UNHANDLED-EXCEPTION] Unexpected error | path={} | class={}",
                req.getRequestURI(), ex.getClass().getName(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                "INTERNAL_ERROR",
                "An unexpected error occurred. Reference your X-Correlation-Id for support."
        ));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("timestamp", Instant.now());
        return body;
    }
}
