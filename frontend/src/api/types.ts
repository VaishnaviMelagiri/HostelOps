/**
 * Hand-written mirrors of the backend DTOs.
 *
 * There is no code generation here on purpose: the API contract from Phase 0 is small and stable,
 * and writing these by hand keeps the contract visible in the frontend repo. If a field ever
 * disagrees with the backend, TypeScript fails the build rather than the bug reaching a user.
 */

/** Mirrors `com.hostelops.health.HealthDto`. */
export interface Health {
  status: 'UP' | 'DOWN';
  database: 'UP' | 'DOWN';
  detail: string;
}

/**
 * Mirrors the single error envelope every endpoint uses (Phase 0, section 4).
 * `code` is the stable value to switch on; `message` is human text that may be reworded.
 */
export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, unknown>;
}
