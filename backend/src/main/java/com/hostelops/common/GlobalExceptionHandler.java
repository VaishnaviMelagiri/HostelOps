package com.hostelops.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns exceptions into the one error envelope, in one place.
 *
 * <p>{@code @RestControllerAdvice} is a cross-cutting companion to every {@code @RestController}:
 * when a controller (or anything it called) throws, Spring looks here for a matching
 * {@code @ExceptionHandler} method. The upshot is that controllers contain no try/catch at all,
 * and no endpoint can accidentally invent its own error shape.
 *
 * <p>Note what is deliberately absent: a handler for Spring Security's authentication and
 * authorization exceptions. Those are thrown by servlet filters that run <em>before</em> the
 * dispatcher servlet, so this class never sees them - which is why SecurityConfig registers its
 * own entry point and access-denied handler that produce the same envelope. A single security
 * failure taking a different code path from every other error is a genuinely easy thing to get
 * wrong, and it shows up as a stray HTML error page in an otherwise JSON API.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Every deliberate, rule-based failure in the application. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        // Expected outcomes, not incidents - logged at DEBUG so real problems stay visible.
        log.debug("Domain rule rejected the request: {} - {}", ex.errorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.errorCode().status())
                .body(ApiError.of(ex.errorCode(), ex.getMessage(), ex.details()));
    }

    /** Bean-validation failures from {@code @Valid @RequestBody} DTOs. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED,
                        ErrorCode.VALIDATION_FAILED.defaultMessage(),
                        Map.of("fields", fieldErrors)));
    }

    /**
     * A {@code @PreAuthorize} check refused the call.
     *
     * <p>This handler is essential, and its absence is a trap worth understanding. Security
     * failures come from two different places:
     * <ul>
     *   <li><strong>URL rules</strong> ({@code anyRequest().authenticated()}) are enforced by a
     *       servlet filter, before the dispatcher servlet. Those never reach this class, which is
     *       why SecurityConfig registers its own entry point and access-denied handler.</li>
     *   <li><strong>Method rules</strong> ({@code @PreAuthorize}) are enforced by an AOP proxy
     *       around the controller method, which is well inside the dispatcher servlet. Those throw
     *       here.</li>
     * </ul>
     *
     * <p>Without this method the catch-all below would swallow every method-level denial and answer
     * <strong>500 INTERNAL_ERROR instead of 403 FORBIDDEN</strong> - telling a guest who clicked
     * something they may not do that the server is broken, and burying a routine authorization
     * decision in the error logs as if it were an incident. Caught by SecurityRulesTest.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        log.debug("Permission denied: {}", ex.getMessage());
        return ResponseEntity
                .status(ErrorCode.FORBIDDEN.status())
                .body(ApiError.of(ErrorCode.FORBIDDEN));
    }

    /**
     * An authentication problem surfaced from inside the dispatcher rather than from a filter.
     * Same reasoning as above - without it, this would be reported as a 500.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            org.springframework.security.core.AuthenticationException ex) {
        log.debug("Authentication failed: {}", ex.getMessage());
        return ResponseEntity
                .status(ErrorCode.UNAUTHENTICATED.status())
                .body(ApiError.of(ErrorCode.UNAUTHENTICATED));
    }

    /** Anything unanticipated: a real bug. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Full stack trace to our logs; a generic message to the client. An exception message can
        // leak table names, SQL, or file paths, and none of that belongs in an HTTP response.
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR));
    }
}
