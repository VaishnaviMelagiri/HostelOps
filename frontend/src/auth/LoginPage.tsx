import { useCallback, useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '../api/client';
import { DemoAccountButtons } from './DemoAccountButtons';
import { useAuth } from './useAuth';

/**
 * The only way into the application, for every role.
 */
export function LoginPage() {
  const { user, initialising, signIn } = useAuth();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const attempt = useCallback(
    async (emailToUse: string, passwordToUse: string) => {
      setError(null);
      setSubmitting(true);
      try {
        await signIn(emailToUse, passwordToUse);
        // No navigate() here: once `user` is set, the redirect below runs on the next render.
      } catch (e) {
        if (e instanceof ApiError && e.code === 'NETWORK_ERROR') {
          setError('Cannot reach the server. Is the backend running on port 8080?');
        } else if (e instanceof ApiError) {
          // Shows the server's message as-is. The backend deliberately returns the same text for an
          // unknown email and a wrong password, so this cannot leak which accounts exist.
          setError(e.message);
        } else {
          setError('Something went wrong. Please try again.');
        }
      } finally {
        setSubmitting(false);
      }
    },
    [signIn],
  );

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    void attempt(email, password);
  };

  const onDemoPick = (demoEmail: string, demoPassword: string) => {
    // Fill the form as well as submitting, so it is visible that these are ordinary credentials
    // going through the ordinary login — not a hidden back door.
    setEmail(demoEmail);
    setPassword(demoPassword);
    void attempt(demoEmail, demoPassword);
  };

  if (initialising) {
    return (
      <div className="flex min-h-screen items-center justify-center text-slate-500">Loading…</div>
    );
  }

  if (user) {
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from && from !== '/login' ? from : '/'} replace />;
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-6 py-12">
      <div className="w-full max-w-md">
        <header className="mb-8 text-center">
          <h1 className="text-3xl font-semibold tracking-tight text-slate-900">HostelOps</h1>
          <p className="mt-2 text-sm text-slate-600">Room allocation &amp; approval</p>
        </header>

        <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <label htmlFor="email" className="mb-1 block text-sm font-medium text-slate-700">
                Email
              </label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900
                           focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="you@hostelops.demo"
              />
            </div>

            <div>
              <label htmlFor="password" className="mb-1 block text-sm font-medium text-slate-700">
                Password
              </label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900
                           focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>

            {error && (
              // role="alert" so screen readers announce the failure instead of it appearing silently.
              <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-lg bg-blue-600 px-4 py-2.5 font-medium text-white
                         hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          <DemoAccountButtons onPick={onDemoPick} disabled={submitting} />
        </div>

        <p className="mt-6 text-center text-xs text-slate-400">
          All three roles sign in through this one form — there is no separate admin login.
        </p>
      </div>
    </main>
  );
}
