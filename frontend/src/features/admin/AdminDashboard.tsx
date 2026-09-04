import { useCallback, useEffect, useState } from 'react';
import {
  approveRequest,
  blockedBeds as fetchBlockedBeds,
  occupancy as fetchOccupancy,
  pendingQueue as fetchQueue,
  rejectRequest,
  unblockBed,
} from '../../api/admin';
import { ApiError } from '../../api/client';
import type { BlockedBed, Occupancy, PendingQueue as PendingQueueData } from '../../api/types';
import { usePermission } from '../../auth/usePermission';
import { useAdminQueueSignal } from '../../realtime/useAdminQueueSignal';
import { useConnectionState } from '../../realtime/useConnectionState';
import { BlockPanel } from './BlockPanel';
import { OccupancyOverview } from './OccupancyOverview';
import { PendingQueue } from './PendingQueue';

/**
 * The admin's home: the queue, the numbers, and the beds out of service.
 *
 * Live via the admin queue signal — a broadcast that says only "the queue moved" and carries no
 * student, request or bed. The page reacts by refetching over authenticated REST, so identities
 * only ever travel on a permission-checked request, never on a topic.
 */
export function AdminDashboard() {
  const canSeeQueue = usePermission('REQUEST_QUEUE_READ');
  const canSeeOccupancy = usePermission('OCCUPANCY_READ');
  const canUnblock = usePermission('BED_UNBLOCK');
  const live = useConnectionState();

  const [queue, setQueue] = useState<PendingQueueData | null>(null);
  const [occupancy, setOccupancy] = useState<Occupancy | null>(null);
  const [blocked, setBlocked] = useState<BlockedBed[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      // Each panel is gated on its own permission, so a role holding only some of them still gets
      // a working page rather than a 403 that blanks the lot.
      const [q, o, b] = await Promise.all([
        canSeeQueue ? fetchQueue() : Promise.resolve(null),
        canSeeOccupancy ? fetchOccupancy() : Promise.resolve(null),
        canUnblock ? fetchBlockedBeds() : Promise.resolve([]),
      ]);
      setQueue(q);
      setOccupancy(o);
      setBlocked(b);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Could not load the dashboard.');
    } finally {
      setLoading(false);
    }
  }, [canSeeQueue, canSeeOccupancy, canUnblock]);

  useEffect(() => {
    void load();
  }, [load]);

  // The signal channel. Refetch everything: a request appearing changes the queue AND the pending
  // count in the occupancy table, so refreshing only the queue would leave the numbers stale.
  const onQueueChanged = useCallback(() => {
    void load();
  }, [load]);
  useAdminQueueSignal(canSeeQueue, onQueueChanged);

  const act = async (id: number, action: () => Promise<string>) => {
    setBusyId(id);
    setError(null);
    try {
      setNotice(await action());
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : 'That action did not go through.');
    } finally {
      setBusyId(null);
      await load();
    }
  };

  const onApprove = (requestId: number) =>
    void act(requestId, async () => {
      const r = await approveRequest(requestId);
      // alreadyHandled is not a failure - another admin got there first and the outcome is the one
      // this admin wanted. Saying so plainly beats a red banner.
      return r.alreadyHandled
        ? `Request ${requestId} had already been approved by someone else.`
        : `Approved request ${requestId}.`;
    });

  const onReject = (requestId: number, reason?: string) =>
    void act(requestId, async () => {
      const r = await rejectRequest(requestId, reason);
      return r.alreadyHandled
        ? `Request ${requestId} had already been rejected.`
        : `Rejected request ${requestId}.`;
    });

  const onUnblock = (bedId: number) =>
    void act(bedId, async () => {
      const r = await unblockBed(bedId);
      return `Bed ${r.roomNumber}-${r.bedLabel} is back in service.`;
    });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <p className="flex items-center gap-1.5 text-xs text-slate-500">
          <span
            className={`inline-block h-1.5 w-1.5 rounded-full ${
              live ? 'bg-status-available' : 'bg-slate-300'
            }`}
            aria-hidden
          />
          {live ? 'live — new requests appear on their own' : 'reconnecting…'}
        </p>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
        >
          Refresh
        </button>
      </div>

      {notice && (
        <p className="rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-900">{notice}</p>
      )}
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800">
          {error}
        </p>
      )}

      {canSeeQueue && (
        <section>
          <h2 className="mb-3 font-medium">
            Pending requests{' '}
            <span className="text-sm font-normal text-slate-500">
              {queue ? `· ${queue.total} waiting` : ''}
            </span>
          </h2>
          <PendingQueue
            queue={queue}
            loading={loading}
            busyId={busyId}
            onApprove={onApprove}
            onReject={onReject}
          />
        </section>
      )}

      {canSeeOccupancy && occupancy && <OccupancyOverview occupancy={occupancy} />}

      {canUnblock && <BlockPanel blocked={blocked} busyBedId={busyId} onUnblock={onUnblock} />}
    </div>
  );
}
