import { BASE_URL } from './client';
import type { Health } from './types';

/**
 * Fetches `GET /api/health`.
 *
 * This one endpoint deliberately does NOT go through `apiFetch`. Everywhere else, a non-2xx means
 * "the request failed, throw". Here a 503 is the *expected, meaningful answer* when the backend is
 * up but Postgres is not — and its body is a real `Health` payload telling us why, which `apiFetch`
 * would discard while raising a generic error. So we read the body on both 200 and 503, and only
 * treat an unreachable server as a thrown failure.
 */
export async function getHealth(signal?: AbortSignal): Promise<Health> {
  let response: Response;

  try {
    response = await fetch(`${BASE_URL}/api/health`, { signal });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw cause; // component unmounted mid-request; not a real failure
    }
    // The backend process itself is not answering.
    return {
      status: 'DOWN',
      database: 'DOWN',
      detail: `Cannot reach the backend at ${BASE_URL}. Is it running?`,
    };
  }

  if (response.status === 200 || response.status === 503) {
    return (await response.json()) as Health;
  }

  return {
    status: 'DOWN',
    database: 'DOWN',
    detail: `Unexpected response from /api/health: HTTP ${response.status}`,
  };
}
