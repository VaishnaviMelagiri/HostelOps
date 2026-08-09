package com.hostelops.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/login}.
 *
 * <p>The annotations are Jakarta Bean Validation. Combined with {@code @Valid} on the controller
 * parameter, Spring rejects a malformed body before the method runs, and
 * {@code GlobalExceptionHandler} turns that into a 400 VALIDATION_FAILED naming the bad fields.
 *
 * <p>Deliberately only {@code @NotBlank} on the email, not {@code @Email}: rejecting a badly
 * formatted address here would tell an anonymous caller something about what this system considers
 * a valid account, and an unknown address must be indistinguishable from a wrong password anyway.
 * Nor is there a length rule on the password - password policy belongs at registration, and
 * enforcing it at login would let someone infer a stored password's shape.
 */
public record LoginRequest(
        @NotBlank(message = "Email is required") String email,
        @NotBlank(message = "Password is required") String password) {
}
