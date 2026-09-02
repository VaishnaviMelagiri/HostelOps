import { apiFetch } from './client';
import type { BedActionResult, PendingQueue, ResolveResult } from './types';

/** The pending queue, oldest first. Requires REQUEST_QUEUE_READ. */
export function pendingQueue(page = 0, size = 25): Promise<PendingQueue> {
  return apiFetch<PendingQueue>(`/api/admin/requests?page=${page}&size=${size}`);
}

/**
 * Approves a request.
 *
 * Safe to retry. If another admin approved it a moment ago, this returns 200 with
 * `alreadyHandled: true` rather than an error — the outcome the caller wanted has happened either
 * way, so there is nothing to report as a failure.
 */
export function approveRequest(requestId: number): Promise<ResolveResult> {
  return apiFetch<ResolveResult>(`/api/admin/requests/${requestId}/approve`, { method: 'POST' });
}

/** Rejects a request. The reason is optional; the server substitutes a default. */
export function rejectRequest(requestId: number, reason?: string): Promise<ResolveResult> {
  return apiFetch<ResolveResult>(`/api/admin/requests/${requestId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? null }),
  });
}

/**
 * Takes an available bed out of circulation.
 *
 * Fails with 409 BED_ALLOCATED_CANNOT_BLOCK if a student holds it — evicting an occupant is a
 * separate workflow and out of scope. If the bed has a pending request, that request is
 * auto-rejected and its id comes back in `autoRejectedRequestId`.
 */
export function blockBed(bedId: number, reason?: string): Promise<BedActionResult> {
  return apiFetch<BedActionResult>(`/api/admin/beds/${bedId}/block`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? null }),
  });
}

/** Returns a blocked bed to circulation. */
export function unblockBed(bedId: number, reason?: string): Promise<BedActionResult> {
  return apiFetch<BedActionResult>(`/api/admin/beds/${bedId}/unblock`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? null }),
  });
}
