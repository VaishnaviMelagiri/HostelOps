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

/** Mirrors `com.hostelops.user.Role`. */
export type Role = 'STUDENT' | 'ADMIN' | 'GUEST';

/**
 * Mirrors `com.hostelops.security.Permission`.
 *
 * Spelled out as a union rather than `string` so a typo in a permission check is a compile error.
 * `usePermission('REQUEST_CRATE')` would otherwise silently return false forever, hiding a button
 * from users who should see it — a bug with no error message anywhere.
 */
export type Permission =
  | 'ROOM_READ'
  | 'REQUEST_CREATE'
  | 'REQUEST_CANCEL_OWN'
  | 'ALLOCATION_READ_OWN'
  | 'REQUEST_QUEUE_READ'
  | 'REQUEST_APPROVE'
  | 'REQUEST_REJECT'
  | 'BED_BLOCK'
  | 'BED_UNBLOCK'
  | 'OCCUPANCY_READ';

/** Mirrors `com.hostelops.auth.dto.UserDto`. Returned by both /auth/login and /auth/me. */
export interface User {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  /** Present for STUDENT only. */
  studentCode?: string;
  /** Present for STUDENT only. */
  course?: string;
  /** Resolved server-side from the role. A UI hint — the server re-checks every request. */
  permissions: Permission[];
}

/** Mirrors `com.hostelops.auth.dto.LoginResponse`. */
export interface LoginResponse {
  accessToken: string;
  /** ISO-8601 instant. */
  expiresAt: string;
  user: User;
}
