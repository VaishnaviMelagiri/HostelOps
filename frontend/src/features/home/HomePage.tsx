import { Link } from 'react-router-dom';
import type { Role } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { usePermission } from '../../auth/usePermission';
import { AdminDashboard } from '../admin/AdminDashboard';
import { StudentDashboard } from '../student/StudentDashboard';

const ROLE_BADGE: Record<Role, string> = {
  STUDENT: 'bg-blue-100 text-blue-800',
  ADMIN: 'bg-amber-100 text-amber-800',
  GUEST: 'bg-slate-200 text-slate-700',
};

/**
 * The signed-in home, which shows whichever dashboard suits the account.
 *
 * The choice is made by asking what the account may DO, not what it is called. A future warden
 * role that can read the queue would land on the admin dashboard with no change here — the same
 * reason every authorization check in the backend names a permission rather than a role.
 */
export function HomePage() {
  const { user, signOut } = useAuth();
  const isStudent = usePermission('ALLOCATION_READ_OWN');
  const isAdmin = usePermission('REQUEST_QUEUE_READ');

  if (!user) return null;

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">HostelOps</h1>
            <p className="text-xs text-slate-500">
              {isAdmin ? 'Admin dashboard' : isStudent ? 'Your room' : 'Browse the building'}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <span className={`rounded-full px-3 py-1 text-xs font-semibold ${ROLE_BADGE[user.role]}`}>
              {user.role}
            </span>
            <Link
              to="/map"
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
            >
              Floor map
            </Link>
            <button
              type="button"
              onClick={() => void signOut()}
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-4xl px-6 py-8">
        {isAdmin ? <AdminDashboard /> : isStudent ? <StudentDashboard /> : <GuestHome />}
      </div>
    </main>
  );
}

/** A guest can look, and nothing else. Saying so plainly beats showing buttons that only 403. */
function GuestHome() {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-8 text-center shadow-sm">
      <h2 className="text-lg font-semibold">Read-only access</h2>
      <p className="mx-auto mt-2 max-w-md text-sm text-slate-500">
        You can explore all 6 wings, 404 rooms and 576 beds, and see which are free, requested,
        allocated or out of service. Nothing you do changes anything.
      </p>
      <Link
        to="/map"
        className="mt-5 inline-block rounded-lg bg-slate-900 px-5 py-2.5 font-medium text-white hover:bg-slate-700"
      >
        Open the floor map
      </Link>
    </section>
  );
}
