import { useCallback, useEffect, useState } from 'react';
import { getHealth } from './api/health';
import { BASE_URL } from './api/client';
import type { Health } from './api/types';

/**
 * Phase 1 has no features. This screen exists to make the wiring visible: it proves that the
 * browser can reach Spring Boot, and that Spring Boot can reach Postgres — the two connections
 * every later phase depends on.
 */
export default function App() {
  const [health, setHealth] = useState<Health | null>(null);
  const [checking, setChecking] = useState(true);

  const check = useCallback(async (signal?: AbortSignal) => {
    setChecking(true);
    try {
      setHealth(await getHealth(signal));
    } catch {
      // Only an aborted request reaches here; getHealth turns real failures into a DOWN result.
      return;
    } finally {
      setChecking(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void check(controller.signal);
    // Cancel the in-flight request if the component unmounts, so React never sets state on a
    // component that is gone. In development you will see this run twice — that is React 18's
    // StrictMode intentionally double-invoking effects to surface exactly this kind of bug.
    return () => controller.abort();
  }, [check]);

  const dbUp = health?.database === 'UP';
  const apiReachable = health !== null && !health.detail.startsWith('Cannot reach the backend');

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-2xl px-6 py-16">
        <header className="mb-10">
          <h1 className="text-3xl font-semibold tracking-tight">HostelOps</h1>
          <p className="mt-2 text-slate-600">
            Room allocation &amp; approval system — Phase 1, bootstrap. No features yet: this page
            only proves the pieces can talk to each other.
          </p>
        </header>

        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <h2 className="text-lg font-medium">Connectivity</h2>
            <button
              type="button"
              onClick={() => void check()}
              disabled={checking}
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium
                         hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {checking ? 'Checking…' : 'Re-check'}
            </button>
          </div>

          <ul className="space-y-3">
            <StatusRow label="Frontend (Vite + React + Tailwind)" up={true} note="you are looking at it" />
            <StatusRow
              label="Backend (Spring Boot)"
              up={apiReachable}
              note={apiReachable ? BASE_URL : `not reachable at ${BASE_URL}`}
              pending={checking && health === null}
            />
            <StatusRow
              label="Database (PostgreSQL)"
              up={dbUp}
              note={dbUp ? 'SELECT 1 answered' : (health?.detail ?? 'unknown')}
              pending={checking && health === null}
            />
          </ul>

          {health && !dbUp && (
            <p className="mt-5 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900">
              Start Postgres with <code className="font-mono">docker compose up -d</code> from the
              project root, wait for <code className="font-mono">docker compose ps</code> to show
              <span className="font-mono"> healthy</span>, then restart the backend.
            </p>
          )}
        </section>

        <p className="mt-8 text-sm text-slate-500">
          Next: Phase 2 — authentication and roles (Student, Admin, Guest).
        </p>
      </div>
    </main>
  );
}

function StatusRow({
  label,
  up,
  note,
  pending = false,
}: {
  label: string;
  up: boolean;
  note: string;
  pending?: boolean;
}) {
  const colour = pending ? 'bg-slate-300' : up ? 'bg-status-available' : 'bg-status-blocked';
  return (
    <li className="flex items-start gap-3">
      <span className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${colour}`} aria-hidden />
      <span className="flex-1">
        <span className="font-medium">{label}</span>
        <span className="block text-sm text-slate-500">{pending ? 'checking…' : note}</span>
      </span>
    </li>
  );
}
