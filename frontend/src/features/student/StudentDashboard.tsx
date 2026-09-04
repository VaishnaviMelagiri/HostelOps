import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { cancelRequest, myAllocation as fetchAllocation } from '../../api/requests';
import type { MyAllocation } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { formatDateTime, formatTimeUntil, urgency } from '../../lib/format';
import type { RequestResolved } from '../../realtime/events';
import { useMyRequests } from '../../realtime/useMyRequests';

/**
 * The student's home: what they hold, or what they are waiting for, or a nudge to go and look.
 *
 * Subscribed to their own private queue, so an admin's decision lands here without a refresh.
 */
export function StudentDashboard() {
  const { user } = useAuth();
  const [allocation, setAllocation] = useState<MyAllocation | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setAllocation(await fetchAllocation());
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not load your allocation.');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Private channel. The message says what happened; the server stays the authority on what the
  // student now holds, so this refetches rather than reconstructing state from the payload.
  const onResolved = useCallback(
    (event: RequestResolved) => {
      const bed = `${event.roomNumber}-${event.bedLabel}`;
      setNotice(
        event.status === 'ALLOCATED'
          ? `Approved — bed ${bed} is yours.`
          : `Your request for bed ${bed} was ${event.status.toLowerCase()}. ${event.reason ?? ''}`.trim(),
      );
      void load();
    },
    [load],
  );
  useMyRequests(true, onResolved);

  const onCancel = async () => {
    if (!allocation?.claim) return;
    setBusy(true);
    try {
      await cancelRequest(allocation.claim.requestId);
      setNotice('Request cancelled. The bed is available again.');
      await load();
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not cancel that request.');
      await load();
    } finally {
      setBusy(false);
    }
  };

  if (!allocation) {
    return <p className="py-12 text-center text-slate-400">Loading…</p>;
  }

  return (
    <div className="space-y-6">
      {notice && (
        <p className="rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-900">{notice}</p>
      )}
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800">
          {error}
        </p>
      )}

      {allocation.state === 'NONE' && (
        <section className="rounded-xl border border-slate-200 bg-white p-8 text-center shadow-sm">
          <h2 className="text-lg font-semibold">You do not have a bed yet</h2>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-500">
            Browse the building and request any free bed. You can hold one request at a time, and
            you can cancel it whenever you like.
          </p>
          <Link
            to="/map"
            className="mt-5 inline-block rounded-lg bg-slate-900 px-5 py-2.5 font-medium text-white hover:bg-slate-700"
          >
            Find a bed
          </Link>
        </section>
      )}

      {allocation.state === 'PENDING' && allocation.claim && (
        <section className="rounded-xl border border-amber-200 bg-amber-50 p-6 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-amber-800">
            Awaiting approval
          </p>
          <h2 className="mt-1 text-2xl font-semibold">
            Room {allocation.claim.roomNumber}, bed {allocation.claim.bedLabel}
          </h2>
          <p className="mt-1 text-sm text-amber-900">
            Wing {allocation.claim.wing} · {allocation.claim.floor} ·{' '}
            {allocation.claim.roomType} · {allocation.claim.bathroomType} bathroom
          </p>
          <dl className="mt-4 space-y-1 text-sm text-amber-900">
            <div>
              <dt className="inline font-medium">Requested: </dt>
              <dd className="inline">{formatDateTime(allocation.claim.createdAt)}</dd>
            </div>
            <div>
              <dt className="inline font-medium">Expires: </dt>
              <dd
                className={`inline ${
                  urgency(allocation.claim.expiresAt) !== 'normal' ? 'font-semibold' : ''
                }`}
              >
                in {formatTimeUntil(allocation.claim.expiresAt)}
              </dd>
            </div>
          </dl>
          <p className="mt-3 text-xs text-amber-800">
            If nobody decides before then, the request lapses on its own and the bed goes back into
            circulation — you will be told, and you can request again.
          </p>
          <button
            type="button"
            onClick={() => void onCancel()}
            disabled={busy}
            className="mt-4 rounded-lg border border-amber-300 bg-white px-4 py-2 text-sm font-medium hover:bg-amber-100 disabled:opacity-60"
          >
            {busy ? 'Cancelling…' : 'Cancel this request'}
          </button>
        </section>
      )}

      {allocation.state === 'ALLOCATED' && allocation.claim && (
        <>
          <section className="rounded-xl border border-blue-200 bg-blue-50 p-6 shadow-sm">
            <p className="text-xs font-semibold uppercase tracking-wide text-blue-800">Your bed</p>
            <h2 className="mt-1 text-2xl font-semibold">
              Room {allocation.claim.roomNumber}, bed {allocation.claim.bedLabel}
            </h2>
            <p className="mt-1 text-sm text-blue-900">
              Wing {allocation.claim.wing} · {allocation.claim.floor} ·{' '}
              {allocation.claim.roomType} · {allocation.claim.bathroomType} bathroom
            </p>
            {allocation.claim.decidedAt && (
              <p className="mt-3 text-xs text-blue-800">
                Confirmed {formatDateTime(allocation.claim.decidedAt)}
              </p>
            )}
          </section>

          <RoommateCard allocation={allocation} />
        </>
      )}

      <p className="text-center text-sm text-slate-400">
        Signed in as {user?.fullName} · {user?.studentCode}
      </p>
    </div>
  );
}

/**
 * What may truthfully be said about the other bed.
 *
 * A name appears in exactly one branch. Every other case says something about the BED — free,
 * awaiting approval, out of service — and nothing about a person. "Awaiting approval" is
 * deliberately informative without being identifying: knowing someone has asked for the bed is
 * already visible on the map; knowing who is not.
 */
function RoommateCard({ allocation }: { allocation: MyAllocation }) {
  const state = allocation.roommateState;

  if (!state || state === 'NONE') {
    return (
      <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <h3 className="font-medium">Single room</h3>
        <p className="mt-1 text-sm text-slate-500">This room has one bed. It is all yours.</p>
      </section>
    );
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h3 className="mb-1 font-medium">The other bed in this room</h3>

      {state === 'ALLOCATED' && allocation.roommate ? (
        <>
          <p className="text-sm text-slate-500">Confirmed — you are sharing with:</p>
          <p className="mt-3 text-lg font-semibold">{allocation.roommate.fullName}</p>
          <p className="text-sm text-slate-600">{allocation.roommate.course}</p>
          <p className="mt-4 text-xs text-slate-400">
            Name and course only. Nothing else is shared, in either direction.
          </p>
        </>
      ) : (
        <>
          <p className="text-sm text-slate-500">
            {state === 'EMPTY' && 'Free — nobody has requested it yet.'}
            {state === 'PENDING' && 'Someone has requested it and is awaiting approval.'}
            {state === 'BLOCKED' && 'Out of service for maintenance.'}
          </p>
          {state === 'PENDING' && (
            <p className="mt-3 text-xs text-slate-400">
              You will see who once an admin confirms them — not before. The same applies to what
              they can see about you.
            </p>
          )}
        </>
      )}
    </section>
  );
}
