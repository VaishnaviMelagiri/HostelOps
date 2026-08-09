import { apiFetch } from './client';
import type { LoginResponse, User } from './types';

/**
 * One login call for all three roles. There is no `role` parameter and no separate admin endpoint:
 * the server decides what the caller may do from their stored role, and the client never asserts it.
 */
export function login(email: string, password: string): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

/**
 * Revokes the current token server-side.
 *
 * The caller must clear local state regardless of whether this succeeds — see AuthContext. If the
 * network is down, the user still expects "log out" to log them out of this browser.
 */
export function logout(): Promise<void> {
  return apiFetch<void>('/api/auth/logout', { method: 'POST' });
}

/**
 * Who the current token belongs to.
 *
 * Used on page load: the browser may have a token but knows nothing trustworthy about it. The
 * token's payload could be decoded locally, but nothing in it is worth trusting without the
 * signature check only the server can do — so we ask.
 */
export function me(): Promise<User> {
  return apiFetch<User>('/api/auth/me');
}
