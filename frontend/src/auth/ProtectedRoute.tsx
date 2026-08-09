import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { Permission } from '../api/types';
import { useAuth } from './useAuth';

interface Props {
  children: ReactNode;
  /** Optional: also require these permissions, not just a signed-in user. */
  requires?: Permission[];
}

/**
 * Wraps a route so only a signed-in user (optionally holding specific permissions) can reach it.
 *
 * This is navigation, not security. It stops someone landing on a page that would only show them
 * errors; it is not what prevents them acting. Every request the page makes is authorised again by
 * the server, which is the check that actually counts.
 */
export function ProtectedRoute({ children, requires }: Props) {
  const { user, initialising, can } = useAuth();
  const location = useLocation();

  // Until the initial /auth/me check finishes we genuinely do not know whether there is a session.
  // Rendering the redirect now would bounce a signed-in user to /login on every page refresh.
  if (initialising) {
    return (
      <div className="flex min-h-screen items-center justify-center text-slate-500">
        Loading…
      </div>
    );
  }

  if (!user) {
    // `state` remembers where they were headed so login can send them back there afterwards,
    // and `replace` keeps the protected URL out of the history stack.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (requires && !can(...requires)) {
    return (
      <div className="mx-auto max-w-lg px-6 py-24 text-center">
        <h1 className="text-xl font-semibold text-slate-900">Not available for your account</h1>
        <p className="mt-2 text-slate-600">
          You are signed in as <span className="font-medium">{user.role}</span>, which does not have
          access to this page.
        </p>
      </div>
    );
  }

  return <>{children}</>;
}
