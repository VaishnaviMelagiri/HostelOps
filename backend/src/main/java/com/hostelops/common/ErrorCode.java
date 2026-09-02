package com.hostelops.common;

import org.springframework.http.HttpStatus;

/**
 * The API's stable error vocabulary, from the Phase 0 contract.
 *
 * <p>The frontend switches on {@code code}, never on the message text. That separation is what lets
 * us reword any user-facing message freely - in a different tone, a different language, a different
 * component - without breaking a single client branch.
 *
 * <p>Each constant carries its own HTTP status, so a handler never has to remember which status
 * pairs with which code, and the two can never drift apart.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST,
            "The request was not valid."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED,
            "You are not signed in, or your session has expired."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "Email or password is incorrect."),

    FORBIDDEN(HttpStatus.FORBIDDEN,
            "Your account does not have permission to do that."),

    NOT_FOUND(HttpStatus.NOT_FOUND,
            "Not found."),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED,
            "That action is not available on this endpoint."),

    // ---- Reserved for Phases 4-5. Listed now so the vocabulary is fixed up front rather than
    // ---- being invented endpoint by endpoint.
    BED_NOT_AVAILABLE(HttpStatus.CONFLICT,
            "That bed was just taken by another student."),

    STUDENT_ALREADY_HAS_CLAIM(HttpStatus.CONFLICT,
            "You already have an active request or allocation."),

    REQUEST_ALREADY_RESOLVED(HttpStatus.CONFLICT,
            "That request has already been handled."),

    BED_ALLOCATED_CANNOT_BLOCK(HttpStatus.CONFLICT,
            "That bed is allocated. Removing an occupant is a separate eviction workflow."),

    BED_NOT_BLOCKED(HttpStatus.CONFLICT,
            "That bed is not blocked."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "Something went wrong on our end.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
