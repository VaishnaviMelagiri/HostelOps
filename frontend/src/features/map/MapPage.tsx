import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { blockBed, unblockBed } from '../../api/admin';
import { getFloor, listWings } from '../../api/rooms';
import { cancelRequest, myAllocation as fetchMyAllocation, requestBed } from '../../api/requests';
import type { FloorRooms, MyAllocation, RoomCell, WingSummary } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { usePermission } from '../../auth/usePermission';
import { FloorMap } from './FloorMap';
import { Legend } from './Legend';
import { RoomPopover } from './RoomPopover';
import { WingFloorPicker } from './WingFloorPicker';

const NO_ALLOCATION: MyAllocation = { state: 'NONE', claim: null };

/** Browse the building, and — from Phase 4 — request a bed. */
export function MapPage() {
  const { user } = useAuth();
  const canRequest = usePermission('REQUEST_CREATE');
  const canReadOwn = usePermission('ALLOCATION_READ_OWN');
  const canBlock = usePermission('BED_BLOCK');

  const [wings, setWings] = useState<WingSummary[]>([]);
  const [wing, setWing] = useState('A');
  const [floor, setFloor] = useState('GF');
  const [floorData, setFloorData] = useState<FloorRooms | null>(null);
  const [selected, setSelected] = useState<RoomCell | null>(null);
  const [allocation, setAllocation] = useState<MyAllocation>(NO_ALLOCATION);
  const [busyBedId, setBusyBedId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [banner, setBanner] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadFloor = useCallback(async (w: string, f: string) => {
    const data = await getFloor(w, f);
    setFloorData(data);
    return data;
  }, []);

  const loadAllocation = useCallback(async () => {
    // ADMIN and GUEST have no allocation to read and would get a 403 - so do not ask.
    if (!canReadOwn) return NO_ALLOCATION;
    const mine = await fetchMyAllocation();
    setAllocation(mine);
    return mine;
  }, [canReadOwn]);

  useEffect(() => {
    let cancelled = false;
    listWings()
      .then((data) => {
        if (cancelled || data.length === 0) return;
        setWings(data);
        setWing(data[0].wing);
        setFloor(data[0].floors[0]);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : 'Could not load wings.');
      });
    void loadAllocation();
    return () => {
      cancelled = true;
    };
  }, [loadAllocation]);

  useEffect(() => {
    if (!wing || !floor) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    loadFloor(wing, floor)
      .then(() => {
        if (!cancelled) setSelected(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setFloorData(null);
        setError(e instanceof ApiError ? e.message : 'Could not load this floor.');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [wing, floor, loadFloor]);

  const onWingChange = useCallback(
    (nextWing: string) => {
      const target = wings.find((w) => w.wing === nextWing);
      setWing(nextWing);
      if (target && !target.floors.includes(floor)) setFloor(target.floors[0]);
    },
    [wings, floor],
  );

  /**
   * After any successful action, refetch BOTH the floor and the allocation from the server rather
   * than patching local state. The bed's new status is derived server-side, and another student may
   * have changed something else on this floor in the meantime — guessing locally would show a map
   * that quietly disagrees with the database. Phase 7 replaces the refetch with a live WebSocket
   * push; the correctness argument stays the same.
   */
  const refresh = useCallback(async () => {
    const [updated] = await Promise.all([loadFloor(wing, floor), loadAllocation()]);
    setSelected((current) =>
      current ? (updated.rooms.find((r) => r.roomId === current.roomId) ?? null) : null,
    );
  }, [wing, floor, loadFloor, loadAllocation]);

  const onRequest = useCallback(
    async (bedId: number) => {
      setBusyBedId(bedId);
      setActionError(null);
      try {
        const claim = await requestBed(bedId);
        setBanner(
          `Requested bed ${claim.roomNumber}-${claim.bedLabel}. Waiting for an admin to approve it.`,
        );
        await refresh();
      } catch (e: unknown) {
        // The server's message already explains each case precisely — "that bed was just taken",
        // "you already have an active request" — so show it rather than inventing our own wording.
        setActionError(e instanceof ApiError ? e.message : 'Could not request that bed.');
        if (e instanceof ApiError && e.status === 409) {
          // Someone else won the race, so what is on screen is already stale.
          await refresh();
        }
      } finally {
        setBusyBedId(null);
      }
    },
    [refresh],
  );

  const onCancel = useCallback(
    async (requestId: number) => {
      setBusyBedId(-1);
      setActionError(null);
      try {
        await cancelRequest(requestId);
        setBanner('Request cancelled. The bed is available again.');
        await refresh();
      } catch (e: unknown) {
        setActionError(e instanceof ApiError ? e.message : 'Could not cancel that request.');
        await refresh();
      } finally {
        setBusyBedId(null);
      }
    },
    [refresh],
  );

  const onBlock = useCallback(
    async (bedId: number) => {
      setBusyBedId(bedId);
      setActionError(null);
      try {
        const result = await blockBed(bedId);
        setBanner(
          result.autoRejectedRequestId
            ? `Bed ${result.roomNumber}-${result.bedLabel} blocked. The pending request on it was ` +
              `rejected automatically and the student was told why.`
            : `Bed ${result.roomNumber}-${result.bedLabel} is out of service.`,
        );
        await refresh();
      } catch (e: unknown) {
        setActionError(e instanceof ApiError ? e.message : 'Could not block that bed.');
        await refresh();
      } finally {
        setBusyBedId(null);
      }
    },
    [refresh],
  );

  const onUnblock = useCallback(
    async (bedId: number) => {
      setBusyBedId(bedId);
      setActionError(null);
      try {
        const result = await unblockBed(bedId);
        setBanner(`Bed ${result.roomNumber}-${result.bedLabel} is available again.`);
        await refresh();
      } catch (e: unknown) {
        setActionError(e instanceof ApiError ? e.message : 'Could not unblock that bed.');
        await refresh();
      } finally {
        setBusyBedId(null);
      }
    },
    [refresh],
  );

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">Floor map</h1>
            <p className="text-xs text-slate-500">404 rooms · 576 beds · 6 wings</p>
          </div>
          <div className="flex items-center gap-3">
            {user && (
              <span className="text-sm text-slate-500">
                {user.fullName} · {user.role}
              </span>
            )}
            <Link
              to="/"
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
            >
              Home
            </Link>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-5xl space-y-6 px-6 py-8">
        {allocation.state !== 'NONE' && allocation.claim && (
          <section
            className={`rounded-xl border p-4 ${
              allocation.state === 'ALLOCATED'
                ? 'border-blue-200 bg-blue-50'
                : 'border-amber-200 bg-amber-50'
            }`}
          >
            <p className="text-sm">
              <span className="font-semibold">
                {allocation.state === 'ALLOCATED' ? 'Your bed: ' : 'Awaiting approval: '}
              </span>
              Room {allocation.claim.roomNumber}, bed {allocation.claim.bedLabel} — wing{' '}
              {allocation.claim.wing}, {allocation.claim.floor}
            </p>
            {allocation.state === 'PENDING' && (
              <button
                type="button"
                onClick={() => void onCancel(allocation.claim!.requestId)}
                disabled={busyBedId !== null}
                className="mt-2 rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-sm
                           font-medium hover:bg-amber-100 disabled:opacity-60"
              >
                Cancel request
              </button>
            )}
          </section>
        )}

        {banner && (
          <p className="rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-900">{banner}</p>
        )}

        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <WingFloorPicker
            wings={wings}
            selectedWing={wing}
            selectedFloor={floor}
            onWingChange={onWingChange}
            onFloorChange={setFloor}
          />
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <div className="mb-5 flex flex-wrap items-center justify-between gap-4">
            <h2 className="font-medium">
              Wing {wing} · {floor}
            </h2>
            <Legend />
          </div>

          {loading && <p className="py-12 text-center text-slate-400">Loading floor…</p>}

          {error && !loading && (
            <p role="alert" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-800">
              {error}
            </p>
          )}

          {floorData && !loading && !error && (
            <>
              <FloorMap
                floor={floorData}
                selectedRoomId={selected?.roomId ?? null}
                onSelectRoom={(room) => {
                  setActionError(null);
                  setSelected(room);
                }}
              />
              <p className="mt-4 text-xs text-slate-400">
                Click a room to see its beds{canRequest ? ' and request one' : ''}.{' '}
                {floorData.rooms.length} rooms on this floor.
              </p>
            </>
          )}
        </section>
      </div>

      {selected && (
        <RoomPopover
          room={selected}
          allocation={allocation}
          canRequest={canRequest}
          canBlock={canBlock}
          busyBedId={busyBedId}
          error={actionError}
          onRequest={(bedId) => void onRequest(bedId)}
          onCancel={(requestId) => void onCancel(requestId)}
          onBlock={(bedId) => void onBlock(bedId)}
          onUnblock={(bedId) => void onUnblock(bedId)}
          onClose={() => {
            setSelected(null);
            setActionError(null);
          }}
        />
      )}
    </main>
  );
}
