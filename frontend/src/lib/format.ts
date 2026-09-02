/** Human-readable duration, e.g. "3m", "5h 12m", "2d 4h". */
export function formatAge(seconds: number): string {
  if (seconds < 60) return `${Math.max(0, Math.floor(seconds))}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

/**
 * How long until an instant, or "expired" once it has passed.
 *
 * Used on the admin queue so a request about to be swept away by the Phase 6 expiry job is visible
 * as such, rather than vanishing without warning while an admin reads the list.
 */
export function formatTimeUntil(iso: string | null): string {
  if (!iso) return '—';
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  return seconds <= 0 ? 'expired' : formatAge(seconds);
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}
