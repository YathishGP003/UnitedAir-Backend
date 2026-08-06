package com.unitedair.ai.shared;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Turns exceptions into a single consistent error shape.
 *
 * <p>Every response carries the trace ID, because the first question anyone asks about a
 * failure is "which request was that?" and the audit trail is keyed by trace.
 *
 * <p>Messages returned to clients are deliberately generic for unexpected failures: the
 * stack trace goes to the log, not to the browser. An assistant that handles PNRs and
 * passport numbers should not leak internal detail into an error body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiExceptions.ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiExceptions.ApiException ex) {
        if (ex.status().is5xxServerError()) {
            log.error("API error {}: {}", ex.status(), ex.getMessage(), ex);
        } else {
            log.debug("API error {}: {}", ex.status(), ex.getMessage());
        }
        return build(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail.isBlank() ? "Validation failed" : detail);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the configured size limit.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        // FR-031: KB ingestion is Admin-only. This is the path a Passenger or Staff user
        // hits if they call an admin endpoint directly rather than through the UI.
        return build(HttpStatus.FORBIDDEN, "Your role is not permitted to perform this operation.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the traceId when reporting this.");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("traceId", TraceContext.traceId());
        return ResponseEntity.status(status).body(body);
    }
}
