import { useEffect, useRef } from 'react';
import type { Claim, MyAllocation, RoomCell } from '../../api/types';
import { BED_STATUS_STYLE, ROOM_STATUS_LABEL } from '../../lib/status';

interface Props {
  room: RoomCell;
  allocation: MyAllocation;
  canRequest: boolean;
  busyBedId: number | null;
  error: string | null;
  onRequest: (bedId: number) => void;
  onCancel: (requestId: number) => void;
  onClose: () => void;
}

/**
 * The popover that opens when a room is clicked: what the room is, the state of each bed, and the
 * one action the signed-in student may take on it.
 */
export function RoomPopover({
  room,
  allocation,
  canRequest,
  busyBedId,
  error,
  onRequest,
  onCancel,
  onClose,
}: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // Escape closes it, which is what any keyboard user will try first.
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    closeRef.current?.focus();
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const myClaim: Claim | null = allocation.claim;

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/30 p-4 sm:items-center">
      {/* Clicking the dimmed backdrop closes; clicking the card must not, hence stopPropagation. */}
      <div className="absolute inset-0" onClick={onClose} aria-hidden />

      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Room ${room.roomNumber}`}
        className="relative w-full max-w-md rounded-xl border border-slate-200 bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between">
          <div>
            <h2 className="text-xl font-semibold">Room {room.roomNumber}</h2>
            <p className="text-sm text-slate-500">
              {room.roomType} · {room.bathroomType} bathroom · {ROOM_STATUS_LABEL[room.roomStatus]}
            </p>
            <p className="text-xs text-slate-400">
              {room.capacity} bed{room.capacity > 1 ? 's' : ''}
            </p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-300 px-2.5 py-1 text-sm hover:bg-slate-100"
          >
            Close
          </button>
        </div>

        <ul className="space-y-2">
          {room.beds.map((bed) => {
            const isMine = myClaim?.bedId === bed.bedId;
            const iHoldSomething = allocation.state !== 'NONE';
            const requestable = canRequest && bed.status === 'AVAILABLE' && !iHoldSomething;

            return (
              <li key={bed.bedId} className="rounded-lg border border-slate-200 px-4 py-3">
                <div className="flex items-center justify-between">
                  <span className="font-medium">
                    Bed {room.roomNumber}-{bed.bedLabel}
                  </span>
                  <span className="flex items-center gap-2 text-sm">
                    <span
                      className="h-2.5 w-2.5 rounded-full"
                      style={{ backgroundColor: BED_STATUS_STYLE[bed.status].fill }}
                      aria-hidden
                    />
                    {BED_STATUS_STYLE[bed.status].label}
                    {isMine && <span className="text-xs text-slate-400">(yours)</span>}
                  </span>
                </div>

                {/* Only ever one action per bed, and only when it genuinely applies. */}
                {requestable && (
                  <button
                    type="button"
                    disabled={busyBedId !== null}
                    onClick={() => onRequest(bed.bedId)}
                    className="mt-3 w-full rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium
                               text-white hover:bg-blue-700 disabled:opacity-60"
                  >
                    {busyBedId === bed.bedId ? 'Requesting…' : 'Request this bed'}
                  </button>
                )}

                {isMine && myClaim?.status === 'PENDING' && (
                  <button
                    type="button"
                    disabled={busyBedId !== null}
                    onClick={() => onCancel(myClaim.requestId)}
                    className="mt-3 w-full rounded-lg border border-slate-300 px-4 py-2 text-sm
                               font-medium hover:bg-slate-100 disabled:opacity-60"
                  >
                    Cancel my request
                  </button>
                )}

                {canRequest && bed.status === 'AVAILABLE' && iHoldSomething && !isMine && (
                  <p className="mt-2 text-xs text-slate-500">
                    You already have{' '}
                    {allocation.state === 'ALLOCATED' ? 'an allocated bed' : 'a pending request'} (
                    {myClaim?.roomNumber}-{myClaim?.bedLabel}). Cancel it first to request a
                    different bed.
                  </p>
                )}
              </li>
            );
          })}
        </ul>

        {error && (
          <p role="alert" className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
            {error}
          </p>
        )}

        {!canRequest && (
          <p className="mt-4 text-xs text-slate-400">
            Your account can view the map but not request beds.
          </p>
        )}
      </div>
    </div>
  );
}
