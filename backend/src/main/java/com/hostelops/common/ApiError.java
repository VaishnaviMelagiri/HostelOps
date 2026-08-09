package com.hostelops.common;

import java.util.Map;

/**
 * The single error envelope every endpoint returns. Phase 0, section 4:
 *
 * <pre>{ "code": "BED_NOT_AVAILABLE",
 *   "message": "That bed was just taken by another student.",
 *   "details": { "bedId": 214 } }</pre>
 *
 * <p>{@code details} is null for most errors and omitted from the JSON entirely thanks to
 * {@code default-property-inclusion: non_null} in application.yml.
 *
 * @param code    stable enum name the frontend branches on
 * @param message human-readable text, safe to show a user and safe to reword
 * @param details optional structured context (which bed, which field)
 */
public record ApiError(String code, String message, Map<String, Object> details) {

    public static ApiError of(ErrorCode code) {
        return new ApiError(code.name(), code.defaultMessage(), null);
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message, null);
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiError(code.name(), message, details);
    }
}
