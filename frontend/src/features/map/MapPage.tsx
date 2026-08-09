import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { getFloor, listWings } from '../../api/rooms';
import type { FloorRooms, RoomCell, WingSummary } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { BED_STATUS_STYLE, ROOM_STATUS_LABEL } from '../../lib/status';
import { FloorMap } from './FloorMap';
import { Legend } from './Legend';
import { WingFloorPicker } from './WingFloorPicker';

/**
 * Browse the building. Read-only in Phase 3 — clicking a room shows its details, nothing more.
 * The "Request this bed" button arrives in Phase 4.
 */
export function MapPage() {
  const { user } = useAuth();

  const [wings, setWings] = useState<WingSummary[]>([]);
  const [wing, setWing] = useState<string>('A');
  const [floor, setFloor] = useState<string>('GF');
  const [floorData, setFloorData] = useState<FloorRooms | null>(null);
  const [selected, setSelected] = useState<RoomCell | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Load the wing list once.
  useEffect(() => {
    let cancelled = false;
    listWings()
      .then((data) => {
        if (cancelled) return;
        setWings(data);
        if (data.length > 0) {
          setWing(data[0].wing);
          setFloor(data[0].floors[0]);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : 'Could not load wings.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Reload the map whenever the wing or floor changes.
  useEffect(() => {
    if (!wing || !floor) return;
    let cancelled = false;
    setLoading(true);
    setError(null);

    getFloor(wing, floor)
      .then((data) => {
        if (cancelled) return;
        setFloorData(data);
        setSelected(null); // a room from the previous floor is no longer on screen
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
  }, [wing, floor]);

  /**
   * Switching wing must also switch to a floor that wing actually has — otherwise moving from
   * wing E (which has a basement) to wing A while "BAS" is selected asks for a floor that does not
   * exist and lands on a 404.
   */
  const onWingChange = useCallback(
    (nextWing: string) => {
      const target = wings.find((w) => w.wing === nextWing);
      setWing(nextWing);
      if (target && !target.floors.includes(floor)) {
        setFloor(target.floors[0]);
      }
    },
    [wings, floor],
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
                onSelectRoom={setSelected}
              />
              <p className="mt-4 text-xs text-slate-400">
                Hover a room for its details, or click to select. {floorData.rooms.length} rooms on
                this floor.
              </p>
            </>
          )}
        </section>

        {selected && (
          <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
            <div className="mb-4 flex items-start justify-between">
              <div>
                <h2 className="text-xl font-semibold">Room {selected.roomNumber}</h2>
                <p className="text-sm text-slate-500">
                  {selected.roomType} · {selected.bathroomType} bathroom · {ROOM_STATUS_LABEL[selected.roomStatus]}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelected(null)}
                className="rounded-lg border border-slate-300 px-2.5 py-1 text-sm hover:bg-slate-100"
              >
                Close
              </button>
            </div>

            <ul className="space-y-2">
              {selected.beds.map((bed) => (
                <li
                  key={bed.bedId}
                  className="flex items-center justify-between rounded-lg border border-slate-200 px-4 py-2.5"
                >
                  <span className="font-medium">
                    Bed {selected.roomNumber}-{bed.bedLabel}
                  </span>
                  <span className="flex items-center gap-2 text-sm">
                    <span
                      className="h-2.5 w-2.5 rounded-full"
                      style={{ backgroundColor: BED_STATUS_STYLE[bed.status].fill }}
                      aria-hidden
                    />
                    {BED_STATUS_STYLE[bed.status].label}
                  </span>
                </li>
              ))}
            </ul>

            <p className="mt-4 text-xs text-slate-400">
              Browse-only for now. Requesting a bed arrives in Phase 4.
            </p>
          </section>
        )}
      </div>
    </main>
  );
}
