import { useEffect, useState } from 'react';
import { getHealth } from '../../api/health';
import type { Health, Role } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { usePermission } from '../../auth/usePermission';

const ROLE_BADGE: Record<Role, string> = {
  STUDENT: 'bg-blue-100 text-blue-800',
  ADMIN: 'bg-amber-100 text-amber-800',
  GUEST: 'bg-slate-200 text-slate-700',
};

/**
 * The signed-in landing page for Phase 2.
 *
 * It shows who you are and exactly what the server says you may do. That is the whole visible
 * outcome of this phase: sign in as each of the three demo accounts and watch the permission list
 * and the available actions change, with no code branching on the role name anywhere.
 */
export function HomePage() {
  const { user, signOut } = useAuth();
  const [health, setHealth] = useState<Health | null>(null);
  const [signingOut, setSigningOut] = useState(false);

  // Every permission below is asked for by name. Nothing in this file mentions a role — swap the
  // demo account and the UI follows from the server's answer alone.
  const canRequestBed = usePermission('REQUEST_CREATE');
  const canApprove = usePermission('REQUEST_APPROVE');
  const canBlockBeds = usePermission('BED_BLOCK');
  const canSeeQueue = usePermission('REQUEST_QUEUE_READ');

  useEffect(() => {
    const controller = new AbortController();
    void getHealth(controller.signal).then(setHealth).catch(() => undefined);
    return () => controller.abort();
  }, []);

  if (!user) return null; // ProtectedRoute guarantees this cannot happen; satisfies TypeScript.

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">HostelOps</h1>
            <p className="text-xs text-slate-500">Room allocation &amp; approval</p>
          </div>
          <button
            type="button"
            disabled={signingOut}
            onClick={() => {
              setSigningOut(true);
              void signOut().finally(() => setSigningOut(false));
            }}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium
                       hover:bg-slate-100 disabled:opacity-50"
          >
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      </header>

      <div className="mx-auto max-w-3xl space-y-6 px-6 py-10">
        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 className="text-xl font-semibold">{user.fullName}</h2>
              <p className="text-sm text-slate-500">{user.email}</p>
              {user.studentCode && (
                <p className="mt-1 text-sm text-slate-500">
                  {user.studentCode} · {user.course}
                </p>
              )}
            </div>
            <span
              className={`rounded-full px-3 py-1 text-xs font-semibold ${ROLE_BADGE[user.role]}`}
            >
              {user.role}
            </span>
          </div>
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h3 className="mb-1 font-medium">What this account can do</h3>
          <p className="mb-4 text-sm text-slate-500">
            Resolved by the server from your role. The buttons below are shown or hidden by these
            same values — but the server re-checks every request regardless.
          </p>

          <ul className="mb-6 flex flex-wrap gap-2">
            {user.permissions.map((permission) => (
              <li
                key={permission}
                className="rounded-md bg-slate-100 px-2 py-1 font-mono text-xs text-slate-700"
              >
                {permission}
              </li>
            ))}
          </ul>

          <div className="space-y-2">
            <ActionRow label="Browse the floor map" available note="Phase 3" />
            <ActionRow label="Request a bed" available={canRequestBed} note="Phase 4" />
            <ActionRow label="Review the pending queue" available={canSeeQueue} note="Phase 5" />
            <ActionRow label="Approve or reject requests" available={canApprove} note="Phase 5" />
            <ActionRow label="Block a bed for maintenance" available={canBlockBeds} note="Phase 5" />
          </div>
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h3 className="mb-3 font-medium">System</h3>
          <ul className="space-y-2 text-sm">
            <li className="flex items-center gap-2">
              <Dot up={health?.status === 'UP'} />
              <span>Backend</span>
            </li>
            <li className="flex items-center gap-2">
              <Dot up={health?.database === 'UP'} />
              <span>Database</span>
              <span className="text-slate-400">
                {health?.database === 'UP' ? '576 beds seeded' : (health?.detail ?? 'checking…')}
              </span>
            </li>
          </ul>
        </section>
      </div>
    </main>
  );
}

function ActionRow({
  label,
  available,
  note,
}: {
  label: string;
  available: boolean;
  note: string;
}) {
  return (
    <div
      className={`flex items-center justify-between rounded-lg border px-4 py-2.5 ${
        available ? 'border-slate-200 bg-white' : 'border-slate-100 bg-slate-50'
      }`}
    >
      <span className={available ? 'text-slate-900' : 'text-slate-400 line-through'}>{label}</span>
      <span className="text-xs text-slate-400">
        {available ? `unlocked · ${note}` : 'not permitted'}
      </span>
    </div>
  );
}

function Dot({ up }: { up: boolean }) {
  return (
    <span
      className={`h-2.5 w-2.5 rounded-full ${up ? 'bg-status-available' : 'bg-status-blocked'}`}
      aria-hidden
    />
  );
}
