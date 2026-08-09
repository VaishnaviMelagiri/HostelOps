package com.hostelops.auth.dto;

import java.time.Instant;

/**
 * Body of a successful {@code POST /api/auth/login}.
 *
 * @param accessToken the JWT to send as {@code Authorization: Bearer <token>}
 * @param expiresAt   when it stops being valid, so the client can refresh or warn before it does
 *                    rather than discovering it through a surprise 401 mid-action
 * @param user        who just signed in, including their resolved permissions
 */
public record LoginResponse(String accessToken, Instant expiresAt, UserDto user) {
}
