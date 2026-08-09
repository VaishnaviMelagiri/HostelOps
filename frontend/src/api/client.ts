import type { ApiErrorBody } from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/**
 * A failed API call, carrying the backend's stable error `code` alongside the HTTP status.
 *
 * Having a real Error subclass means a caller can `catch (e) { if (e instanceof ApiError) ... }`
 * and branch on `e.code` — e.g. showing "that bed was just taken" for BED_NOT_AVAILABLE from
 * Phase 4 onward — instead of parsing message strings.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * The current access token, held in a module variable rather than in React state.
 *
 * `apiFetch` is a plain function called from anywhere, including outside React's render tree, so it
 * cannot read a hook. AuthContext owns the token's lifecycle and calls `setAccessToken` whenever it
 * changes; this module just holds the latest value for outgoing requests.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * Called when any request comes back 401, so the app can drop a token the server no longer accepts
 * — expired, or revoked by logging out in another tab. AuthContext registers the handler.
 */
let onUnauthenticated: (() => void) | null = null;

export function setUnauthenticatedHandler(handler: (() => void) | null): void {
  onUnauthenticated = handler;
}

/**
 * The single place every HTTP call goes through.
 *
 * Centralising it means the `Authorization: Bearer` header is attached in exactly one place rather
 * than at a dozen call sites — and, just as importantly, that an expired session is detected in one
 * place too.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined),
  };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  try {
    response = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  } catch (cause) {
    // fetch() rejects only when the request never got an HTTP response at all: the backend is not
    // running, DNS failed, or the browser blocked it. A 500 is a *resolved* promise, not a
    // rejection — so this branch specifically means "could not reach the server".
    throw new ApiError(0, 'NETWORK_ERROR', `Could not reach the API at ${BASE_URL}`, {
      cause: String(cause),
    });
  }

  // 204 No Content has no body to parse (Phase 2's logout returns one).
  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const body: unknown = text ? JSON.parse(text) : undefined;

  if (!response.ok) {
    const error = body as ApiErrorBody | undefined;

    // A 401 on any call means the token we hold is no longer accepted: expired, or revoked because
    // the user logged out in another tab. Clear it once, here, rather than leaving every component
    // to notice independently and half of them to forget.
    // The login endpoint is exempt — a 401 there means "wrong password", not "session expired",
    // and running the sign-out path mid-login would clear state that was never established.
    if (response.status === 401 && !path.startsWith('/api/auth/login')) {
      onUnauthenticated?.();
    }

    throw new ApiError(
      response.status,
      error?.code ?? 'UNKNOWN',
      error?.message ?? `Request failed with status ${response.status}`,
      error?.details,
    );
  }

  return body as T;
}

export { BASE_URL };
