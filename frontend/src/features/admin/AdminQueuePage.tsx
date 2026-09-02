import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { approveRequest, pendingQueue, rejectRequest } from '../../api/admin';
import { ApiError } from '../../api/client';
import type { PendingQueue } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { formatAge, formatDateTime, formatTimeUntil } from '../../lib/format';

/**
 * The admin pending-requests queue.
 *
 * Oldest first, which is both fair and practical: the longest-waiting request is also the closest
 * to being expired by the Phase 6 sweep, so working down the list handles the most urgent first.
 */
export function AdminQueuePage() {
  const { user } = useAuth();
  const [queue, setQueue] = useState<PendingQueue | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setQueue(await pendingQueue());
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not load the queue.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const onApprove = async (requestId: number) => {
    setBusyId(requestId);
    setError(null);
    try {
      const result = await approveRequest(requestId);
      // alreadyHandled is not an error - another admin got there first, and the outcome is the one
      // this admin wanted. Saying so plainly is more useful than a red banner.
      setNotice(
        result.alreadyHandled
          ? `Request ${requestId} had already been approved by someone else.`
          : `Approved request ${requestId}.`,
      );
      await load();
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not approve that request.');
      await load(); // the list is stale either way
    } finally {
      setBusyId(null);
    }
  };

  const onReject = async (requestId: number) => {
    setBusyId(requestId);
    setError(null);
    try {
      const result = await rejectRequest(requestId, reason.trim() || undefined);
      setNotice(
        result.alreadyHandled
          ? `Request ${requestId} had already been rejected.`
          : `Rejected request ${requestId}.`,
      );
      setRejectingId(null);
      setReason('');
      await load();
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not reject that request.');
      await load();
    } finally {
      setBusyId(null);
    }
  };

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">Pending requests</h1>
            <p className="text-xs text-slate-500">
              {queue ? `${queue.total} waiting` : 'loading…'} · oldest first
            </p>
          </div>
          <div className="flex items-center gap-3">
            {user && <span className="text-sm text-slate-500">{user.fullName}</span>}
            <button
              type="button"
              onClick={() => void load()}
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
            >
              Refresh
            </button>
            <Link
              to="/"
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
            >
              Home
            </Link>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-4xl space-y-4 px-6 py-8">
        {notice && (
          <p className="rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-900">{notice}</p>
        )}
        {error && (
          <p role="alert" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800">
            {error}
          </p>
        )}

        {loading && <p className="py-12 text-center text-slate-400">Loading…</p>}

        {!loading && queue && queue.rows.length === 0 && (
          <div className="rounded-xl border border-slate-200 bg-white p-12 text-center">
            <p className="font-medium">Nothing waiting</p>
            <p className="mt-1 text-sm text-slate-500">
              Requests appear here as soon as a student makes one.
            </p>
          </div>
        )}

        {!loading &&
          queue?.rows.map((row) => (
            <article
              key={row.requestId}
              className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
            >
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h2 className="font-semibold">{row.student.fullName}</h2>
                  <p className="text-sm text-slate-500">
                    {row.student.studentCode} · {row.student.course}
                  </p>
                  <p className="mt-2 text-sm">
                    Wants <span className="font-medium">
                      room {row.roomNumber}, bed {row.bedLabel}
                    </span>{' '}
                    <span className="text-slate-500">
                      (wing {row.wing}, {row.floor})
                    </span>
                  </p>
                </div>
                <div className="text-right text-xs text-slate-500">
                  <p>requested {formatDateTime(row.createdAt)}</p>
                  <p>waiting {formatAge(row.pendingAgeSeconds)}</p>
                  <p
                    className={
                      formatTimeUntil(row.expiresAt) === 'expired'
                        ? 'font-semibold text-red-600'
                        : ''
                    }
                  >
                    expires in {formatTimeUntil(row.expiresAt)}
                  </p>
                </div>
              </div>

              {rejectingId === row.requestId ? (
                <div className="mt-4 space-y-2">
                  <label htmlFor={`reason-${row.requestId}`} className="block text-sm font-medium">
                    Reason (optional — the student sees this)
                  </label>
                  <input
                    id={`reason-${row.requestId}`}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder="Wing reserved for first-years"
                    className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm
                               focus:border-blue-500 focus:outline-none"
                  />
                  <div className="flex gap-2">
                    <button
                      type="button"
                      disabled={busyId !== null}
                      onClick={() => void onReject(row.requestId)}
                      className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white
                                 hover:bg-red-700 disabled:opacity-60"
                    >
                      Confirm reject
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setRejectingId(null);
                        setReason('');
                      }}
                      className="rounded-lg border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100"
                    >
                      Back
                    </button>
                  </div>
                </div>
              ) : (
                <div className="mt-4 flex gap-2">
                  <button
                    type="button"
                    disabled={busyId !== null}
                    onClick={() => void onApprove(row.requestId)}
                    className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white
                               hover:bg-blue-700 disabled:opacity-60"
                  >
                    {busyId === row.requestId ? 'Working…' : 'Approve'}
                  </button>
                  <button
                    type="button"
                    disabled={busyId !== null}
                    onClick={() => setRejectingId(row.requestId)}
                    className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium
                               hover:bg-slate-100 disabled:opacity-60"
                  >
                    Reject
                  </button>
                </div>
              )}
            </article>
          ))}
      </div>
    </main>
  );
}
