import type { BedStatus, RoomStatus } from '../api/types';

/**
 * One place that decides what each status looks like and is called.
 *
 * Defined once so the map, the legend and any future badge cannot drift apart — the classic version
 * of that bug is a legend saying amber means "pending" while the map draws pending in orange.
 *
 * Note the full class names. Tailwind scans source files for literal class strings, so a name built
 * by concatenation (`'bg-' + colour`) is never found and the style silently goes missing.
 */
export const BED_STATUS_STYLE: Record<BedStatus, { fill: string; label: string; help: string }> = {
  AVAILABLE: {
    fill: '#16a34a',
    label: 'Available',
    help: 'Free — nobody has requested it',
  },
  PENDING: {
    fill: '#d97706',
    label: 'Pending',
    help: 'A student has requested it; an admin has not decided yet',
  },
  ALLOCATED: {
    fill: '#2563eb',
    label: 'Allocated',
    help: 'Approved and occupied',
  },
  BLOCKED: {
    fill: '#dc2626',
    label: 'Blocked',
    help: 'Out of circulation for maintenance',
  },
};

/** Subtle tint for the room card itself. The beds inside carry the real colour. */
export const ROOM_STATUS_TINT: Record<RoomStatus, string> = {
  AVAILABLE: '#ffffff',
  PARTIAL: '#fffbeb',
  FULL: '#eff6ff',
  BLOCKED: '#fef2f2',
};

export const ROOM_STATUS_LABEL: Record<RoomStatus, string> = {
  AVAILABLE: 'All beds free',
  PARTIAL: 'Some beds taken',
  FULL: 'All beds taken',
  BLOCKED: 'Out of service',
};
