package com.unitedair.ai.tools;

/** Internal carrier used by the invocation logger to preserve typed failure data. */
final class OperationalFailureException extends RuntimeException {

    private final OperationalFailure failure;

    OperationalFailureException(OperationalFailure failure) {
        super(failure.userMessage());
        this.failure = failure;
    }

    OperationalFailure failure() {
        return failure;
    }
}
