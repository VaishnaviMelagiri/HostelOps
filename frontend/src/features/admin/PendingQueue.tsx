import { useState } from 'react';
import type { PendingQueue as PendingQueueData } from '../../api/types';
import { formatAge, formatDateTime, formatTimeUntil, urgency } from '../../lib/format';

interface Props {
  queue: PendingQueueData | null;
  loading: boolean;
  busyId: number | null;
  onApprove: (requestId: number) => void;
  onReject: (requestId: number, reason?: string) => void;
}

/**
 * The pending queue, oldest first — which is both fair and practical: the longest-waiting request
 * is also the closest to being expired by the sweep, so working down the list handles the most
 * urgent first.
 *
 * This is the only place in the application where one person's identity is shown to another. An
 * admin cannot approve a blank request, so the disclosure is necessary; confining it to a single
 * component and a single endpoint means there is one place to review rather than several.
 */
export function PendingQueue({ queue, loading, busyId, onApprove, onReject }: Props) {
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  if (loading && !queue) {
    return <p className="py-12 text-center text-slate-400">Loading…</p>;
  }

  if (!queue || queue.rows.length === 0) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-12 text-center">
        <p className="font-medium">Nothing waiting</p>
        <p className="mt-1 text-sm text-slate-500">
          Requests appear here the moment a student makes one — no refresh needed.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {queue.rows.map((row) => (
        <article
          key={row.requestId}
          className={`rounded-xl border bg-white p-5 shadow-sm ${
            urgency(row.expiresAt) === 'expired'
              ? 'border-red-300'
              : urgency(row.expiresAt) === 'soon'
                ? 'border-amber-300'
                : 'border-slate-200'
          }`}
        >
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h3 className="font-semibold">{row.student.fullName}</h3>
              <p className="text-sm text-slate-500">
                {row.student.studentCode} · {row.student.course}
              </p>
              <p className="mt-2 text-sm">
                Wants{' '}
                <span className="font-medium">
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
                  urgency(row.expiresAt) === 'expired'
                    ? 'font-semibold text-red-600'
                    : urgency(row.expiresAt) === 'soon'
                      ? 'font-semibold text-amber-700'
                      : ''
                }
              >
                {urgency(row.expiresAt) === 'expired'
                  ? 'past its deadline — the next sweep will free this bed'
                  : `expires in ${formatTimeUntil(row.expiresAt)}`}
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
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
              />
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={busyId !== null}
                  onClick={() => {
                    onReject(row.requestId, reason.trim() || undefined);
                    setRejectingId(null);
                    setReason('');
                  }}
                  className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
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
                onClick={() => onApprove(row.requestId)}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
              >
                {busyId === row.requestId ? 'Working…' : 'Approve'}
              </button>
              <button
                type="button"
                disabled={busyId !== null}
                onClick={() => setRejectingId(row.requestId)}
                className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium hover:bg-slate-100 disabled:opacity-60"
              >
                Reject
              </button>
            </div>
          )}
        </article>
      ))}
    </div>
  );
}
