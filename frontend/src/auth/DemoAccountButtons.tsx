import type { Role } from '../api/types';

interface DemoAccount {
  role: Role;
  label: string;
  email: string;
  password: string;
  blurb: string;
}

/**
 * The seeded demo accounts, matching SeedRunner on the backend.
 *
 * These credentials are compiled into the JavaScript bundle, and that is fine: they are demo
 * accounts on a demo database whose entire purpose is that anyone can use them. What would not be
 * fine is a shortcut — there is none here. Each button calls the same `signIn(email, password)`
 * the form calls, hits the same endpoint, and gets a token the same way. Nothing about the
 * authentication path is special-cased for a demo.
 */
const DEMO_ACCOUNTS: DemoAccount[] = [
  {
    role: 'STUDENT',
    label: 'Student',
    email: 'student@hostelops.demo',
    password: 'Student@123',
    blurb: 'Browse the map, request a bed, cancel your own request',
  },
  {
    role: 'ADMIN',
    label: 'Admin',
    email: 'admin@hostelops.demo',
    password: 'Admin@123',
    blurb: 'Approve or reject requests, block beds for maintenance',
  },
  {
    role: 'GUEST',
    label: 'Guest',
    email: 'guest@hostelops.demo',
    password: 'Guest@123',
    blurb: 'Read-only. Explore the building without changing anything',
  },
];

const ROLE_STYLES: Record<Role, string> = {
  STUDENT: 'border-blue-200 hover:border-blue-400 hover:bg-blue-50',
  ADMIN: 'border-amber-200 hover:border-amber-400 hover:bg-amber-50',
  GUEST: 'border-slate-200 hover:border-slate-400 hover:bg-slate-50',
};

interface Props {
  onPick: (email: string, password: string) => void;
  disabled?: boolean;
}

export function DemoAccountButtons({ onPick, disabled = false }: Props) {
  // A build-time flag so the buttons can be switched off without changing how login works.
  if (import.meta.env.VITE_SHOW_DEMO_LOGINS === 'false') {
    return null;
  }

  return (
    <div className="mt-8">
      <div className="mb-3 flex items-center gap-3">
        <span className="h-px flex-1 bg-slate-200" />
        <span className="text-xs font-medium uppercase tracking-wide text-slate-400">
          or try a demo account
        </span>
        <span className="h-px flex-1 bg-slate-200" />
      </div>

      <div className="space-y-2">
        {DEMO_ACCOUNTS.map((account) => (
          <button
            key={account.role}
            type="button"
            disabled={disabled}
            onClick={() => onPick(account.email, account.password)}
            className={`w-full rounded-lg border px-4 py-3 text-left transition
                        disabled:cursor-not-allowed disabled:opacity-50 ${ROLE_STYLES[account.role]}`}
          >
            <span className="block text-sm font-semibold text-slate-900">
              Sign in as {account.label}
            </span>
            <span className="block text-xs text-slate-500">{account.blurb}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
