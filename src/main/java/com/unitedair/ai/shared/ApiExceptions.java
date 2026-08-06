package com.unitedair.ai.shared;

import org.springframework.http.HttpStatus;

/**
 * The small set of application exceptions that map onto a specific HTTP status.
 *
 * <p>Grouped in one file because each is a two-line carrier with no behaviour; splitting
 * them across six files would add navigation cost without adding clarity.
 */
public final class ApiExceptions {

    private ApiExceptions() { }

    /** Base type carrying the status the handler should emit. */
    public static class ApiException extends RuntimeException {
        private final HttpStatus status;

        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public ApiException(HttpStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    public static class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    public static class Unauthorized extends ApiException {
        public Unauthorized(String message) {
            super(HttpStatus.UNAUTHORIZED, message);
        }
    }

    public static class Forbidden extends ApiException {
        public Forbidden(String message) {
            super(HttpStatus.FORBIDDEN, message);
        }
    }

    public static class Conflict extends ApiException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    /** Raised when an upstream model or tool endpoint is unavailable or rate limited. */
    public static class UpstreamUnavailable extends ApiException {
        public UpstreamUnavailable(String message, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
        }
    }
}
