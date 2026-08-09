package com.hostelops.common;

import java.util.Map;

/**
 * A rule of this application was broken - as opposed to a bug or an infrastructure failure.
 *
 * <p>Services throw this; {@link GlobalExceptionHandler} turns it into the right HTTP status and
 * error envelope. That means a service method never imports anything from the web layer and never
 * returns an HTTP status - it states what went wrong in domain terms and lets one place decide how
 * that looks over HTTP.
 *
 * <p>It extends {@code RuntimeException} (unchecked) deliberately: these propagate from deep inside
 * a service to the handler, and forcing every intermediate method to declare {@code throws} would
 * add noise without adding safety.
 */
public class DomainException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public DomainException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
