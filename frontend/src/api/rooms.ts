import { apiFetch } from './client';
import type { FloorRooms, WingSummary } from './types';

/** The six wings, for the picker. */
export function listWings(): Promise<WingSummary[]> {
  return apiFetch<WingSummary[]>('/api/wings');
}

/**
 * One floor of one wing.
 *
 * Returns a 404 for a floor a wing does not have — e.g. wing A has no basement. That is a real
 * error rather than an empty map, which would look like a rendering bug.
 */
export function getFloor(wing: string, floor: string): Promise<FloorRooms> {
  return apiFetch<FloorRooms>(
    `/api/wings/${encodeURIComponent(wing)}/floors/${encodeURIComponent(floor)}/rooms`,
  );
}
