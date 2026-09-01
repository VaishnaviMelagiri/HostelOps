import { apiFetch } from './client';
import type { CancelResult, Claim, MyAllocation } from './types';

/**
 * Requests a bed.
 *
 * The `Idempotency-Key` is generated fresh for each click and sent with the request. If the same
 * key arrives twice — a double-click, or the browser retrying a request whose response was lost —
 * the server returns the original request instead of creating a second one. One click, one row,
 * even when the network makes that hard.
 *
 * `crypto.randomUUID()` is built into every modern browser; no library needed.
 */
export function requestBed(bedId: number): Promise<Claim> {
  return apiFetch<Claim>('/api/requests', {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ bedId }),
  });
}

/** Cancels the student's own pending request, returning the bed to AVAILABLE. */
export function cancelRequest(requestId: number): Promise<CancelResult> {
  return apiFetch<CancelResult>(`/api/requests/${requestId}`, { method: 'DELETE' });
}

/** What the signed-in student currently holds: nothing, a pending request, or an allocation. */
export function myAllocation(): Promise<MyAllocation> {
  return apiFetch<MyAllocation>('/api/me/allocation');
}
