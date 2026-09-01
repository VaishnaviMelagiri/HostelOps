package com.hostelops.claim.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/requests}: which bed the student wants. */
public record CreateRequestDto(@NotNull(message = "bedId is required") Long bedId) {
}
