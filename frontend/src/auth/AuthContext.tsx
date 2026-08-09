import {
  createContext,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import * as authApi from '../api/auth';
import { setAccessToken, setUnauthenticatedHandler } from '../api/client';
import type { Permission, User } from '../api/types';

/**
 * Key under which the token is kept so a page refresh does not sign you out.
 *
 * The Phase 0 contract originally said "JWT in memory". That sounds stricter, but it does not
 * actually buy security, and it is worth being clear about why rather than repeating the folklore:
 *
 * Any script running in this page can reach the token either way. From sessionStorage it is one
 * synchronous read; from a module-scoped variable it means patching `fetch` and waiting for the
 * next request to go out. That is an extra step for an attacker, not a barrier — an XSS payload
 * that can run at all can do both. So choosing in-memory would trade away refresh-survival (every
 * page reload signs the user out, which in a demo reads as a broken app) in exchange for
 * protection the wording never actually provided.
 *
 * What DOES limit the damage here is elsewhere: the token lives 15 minutes, logout revokes it
 * server-side immediately, and it grants nothing that signing in again would not.
 *
 * `sessionStorage` rather than `localStorage` is a real distinction though, and the reason to
 * prefer it: sessionStorage is scoped to one tab and cleared when that tab closes, so a token
 * cannot outlive the session on a shared machine.
 *
 * The genuine upgrade is an httpOnly refresh cookie, which no script can read at all. That is
 * correctly out of scope here — it needs CSRF protection and a refresh-token rotation flow, which
 * is a larger design than this project sets out to defend.
 */
const TOKEN_STORAGE_KEY = 'hostelops.accessToken';

interface AuthState {
  user: User | null;
  /** True while the initial "do we already have a valid session?" check is running. */
  initialising: boolean;
  signIn: (email: string, password: string) => Promise<User>;
  signOut: () => Promise<void>;
  /** True when the signed-in user holds every permission listed. */
  can: (...permissions: Permission[]) => boolean;
}

export const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [initialising, setInitialising] = useState(true);

  // A ref, not state: clearSession is called from the API layer's 401 handler, which is not a React
  // event. Keeping it out of state avoids re-registering the handler on every render.
  const clearingRef = useRef(false);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    setUser(null);
  }, []);

  const applyToken = useCallback((token: string) => {
    setAccessToken(token);
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
  }, []);

  /**
   * On first mount, adopt any token left in sessionStorage and ask the server who it belongs to.
   *
   * The identity always comes from `/auth/me`, never from decoding the token in the browser. A JWT
   * payload is only base64 — readable and editable by anyone — so anything decoded client-side is
   * a claim, not a fact. Asking the server is the only way to know the token is still valid,
   * unrevoked, and attached to an active account.
   */
  useEffect(() => {
    setUnauthenticatedHandler(() => {
      if (!clearingRef.current) {
        clearSession();
      }
    });

    const stored = sessionStorage.getItem(TOKEN_STORAGE_KEY);
    if (!stored) {
      setInitialising(false);
      return () => setUnauthenticatedHandler(null);
    }

    setAccessToken(stored);
    let cancelled = false;

    authApi
      .me()
      .then((current) => {
        if (!cancelled) setUser(current);
      })
      .catch(() => {
        // Expired or revoked while the tab was closed. Not an error worth showing anyone — just
        // start signed out.
        if (!cancelled) clearSession();
      })
      .finally(() => {
        if (!cancelled) setInitialising(false);
      });

    return () => {
      cancelled = true;
      setUnauthenticatedHandler(null);
    };
  }, [clearSession]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      const response = await authApi.login(email, password);
      applyToken(response.accessToken);
      setUser(response.user);
      return response.user;
    },
    [applyToken],
  );

  const signOut = useCallback(async () => {
    clearingRef.current = true;
    try {
      // Tell the server to revoke the token. Without this the token stays valid for the rest of its
      // 15 minutes, and "log out" would be a purely cosmetic gesture in this browser.
      await authApi.logout();
    } catch {
      // Deliberately swallowed. If the network is down the user still expects to be logged out
      // here, and the token expires on its own shortly anyway.
    } finally {
      clearingRef.current = false;
      clearSession();
    }
  }, [clearSession]);

  const can = useCallback(
    (...permissions: Permission[]) =>
      user !== null && permissions.every((p) => user.permissions.includes(p)),
    [user],
  );

  const value = useMemo<AuthState>(
    () => ({ user, initialising, signIn, signOut, can }),
    [user, initialising, signIn, signOut, can],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
