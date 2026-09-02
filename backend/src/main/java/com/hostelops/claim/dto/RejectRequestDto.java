package com.hostelops.claim.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body of a reject: why.
 *
 * <p>Optional rather than mandatory - an admin working through a queue of forty should not be
 * blocked by a required text field - but the student always receives whatever is given, and a
 * default is substituted when it is omitted, so a rejection is never silent.
 */
public record RejectRequestDto(@Size(max = 500, message = "Reason is too long") String reason) {

    public static final String DEFAULT_REASON = "Not approved by the hostel office";

    public String reasonOrDefault() {
        return (reason == null || reason.isBlank()) ? DEFAULT_REASON : reason.trim();
    }
}
