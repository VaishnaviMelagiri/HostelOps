import type { BedStatus, ClaimStatus, RoomStatus } from '../api/types';

/** Mirrors `ChangeCause`. */
export type ChangeCause =
  | 'REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'BLOCKED'
  | 'UNBLOCKED';

/**
 * Mirrors `BedStatusChangedPayload` — the public per-floor message.
 *
 * Note there is no student field of any kind. This message is broadcast to every browser watching
 * the floor, so the absence is the privacy rule: there is no field capable of carrying an identity.
 */
export interface BedStatusChanged {
  event: 'BED_STATUS_CHANGED';
  bedId: number;
  roomId: number;
  roomNumber: number;
  wing: string;
  floor: string;
  bedLabel: string;
  status: BedStatus;
  roomStatus: RoomStatus;
  cause: ChangeCause;
  at: string;
}

/** Mirrors `RequestResolvedPayload` — the private per-student message. */
export interface RequestResolved {
  event: 'REQUEST_RESOLVED';
  requestId: number;
  bedId: number;
  roomNumber: number;
  bedLabel: string;
  wing: string;
  floor: string;
  status: ClaimStatus;
  reason: string | null;
  at: string;
}

export const floorTopic = (wing: string, floor: string) => `/topic/floors/${wing}-${floor}`;

/**
 * The private destination.
 *
 * Every client subscribes to this same literal string. Spring rewrites it per session behind the
 * scenes, so a client only ever receives its own messages — there is no destination another student
 * could guess to eavesdrop. Verified with two clients subscribed to exactly this path.
 */
export const MY_REQUESTS_QUEUE = '/user/queue/requests';
