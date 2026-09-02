package com.hostelops.claim.dto;

import jakarta.validation.constraints.Size;

/** Optional body of a block: why the bed is out of circulation. */
public record BlockBedDto(@Size(max = 500, message = "Reason is too long") String reason) {

    public static final String DEFAULT_REASON = "Out of service for maintenance";

    public String reasonOrDefault() {
        return (reason == null || reason.isBlank()) ? DEFAULT_REASON : reason.trim();
    }
}
