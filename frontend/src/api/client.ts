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
 * The single place every HTTP call goes through.
 *
 * Centralising it now means the Phase 2 `Authorization: Bearer` header is added in exactly one
 * place, not sprinkled across a dozen call sites.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;

  try {
    response = await fetch(`${BASE_URL}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...init.headers,
      },
    });
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
