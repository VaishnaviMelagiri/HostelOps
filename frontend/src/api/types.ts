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

// ─────────────────────────── Floor map (Phase 3) ───────────────────────────

/** Mirrors `com.hostelops.room.dto.BedStatus` — the four states one bed can be in. */
export type BedStatus = 'AVAILABLE' | 'PENDING' | 'ALLOCATED' | 'BLOCKED';

/** Mirrors `RoomStatus` — a whole-room roll-up, used only to tint a cell at a glance. */
export type RoomStatus = 'AVAILABLE' | 'PARTIAL' | 'FULL' | 'BLOCKED';

/**
 * Mirrors `BedStateDto`.
 *
 * Note what is absent: no student id, no name. A bed can be seen to be taken; who took it is never
 * sent to the browser. There is no field here to leak.
 */
export interface BedState {
  bedId: number;
  bedLabel: string;
  status: BedStatus;
}

/** Grid position — column and row, both 1-based. Not pixels; the renderer decides the sizing. */
export interface Cell {
  col: number;
  row: number;
}

export interface Grid {
  columns: number;
  rows: number;
}

/** Mirrors `RoomCellDto`. */
export interface RoomCell {
  roomId: number;
  roomNumber: number;
  roomType: 'Single' | 'Double';
  bathroomType: 'Attached' | 'Common';
  capacity: number;
  cell: Cell;
  roomStatus: RoomStatus;
  beds: BedState[];
}

/** Mirrors `FloorRoomsDto`. */
export interface FloorRooms {
  wing: string;
  floor: string;
  floorLevel: number;
  grid: Grid;
  rooms: RoomCell[];
}

/** Mirrors `WingSummaryDto`. */
export interface WingSummary {
  wing: string;
  block: string;
  roomType: 'Single' | 'Double';
  bathroomType: 'Attached' | 'Common';
  /** Ordered bottom to top, e.g. ["BAS","GF","FF","SF","TF"]. */
  floors: string[];
  roomsPerFloor: number;
  totalRooms: number;
  totalBeds: number;
}

// ─────────────────────────── Requests (Phase 4) ───────────────────────────

/** Mirrors `com.hostelops.claim.ClaimStatus`. */
export type ClaimStatus =
  | 'PENDING'
  | 'ALLOCATED'
  | 'BLOCKED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'UNBLOCKED';

/**
 * Mirrors `ClaimDto` — the student's own claim, returned only to that student.
 *
 * Carries no identity at all, not even their own id: they already know who they are, and a field
 * that never needs to exist can never leak.
 */
export interface Claim {
  requestId: number;
  bedId: number;
  roomNumber: number;
  bedLabel: string;
  wing: string;
  floor: string;
  roomType: 'Single' | 'Double';
  bathroomType: 'Attached' | 'Common';
  status: ClaimStatus;
  createdAt: string;
  expiresAt: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
}

/** Mirrors `MyAllocationDto`. */
export interface MyAllocation {
  state: 'NONE' | 'PENDING' | 'ALLOCATED';
  claim: Claim | null;
}

/** Mirrors `CancelResultDto`. */
export interface CancelResult {
  requestId: number;
  status: ClaimStatus;
  /** True when it was already cancelled — a success, not a failure. */
  alreadyHandled: boolean;
}
