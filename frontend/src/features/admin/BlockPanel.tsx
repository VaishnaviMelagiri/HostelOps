import { Link } from 'react-router-dom';
import type { BlockedBed } from '../../api/types';
import { formatDateTime } from '../../lib/format';

interface Props {
  blocked: BlockedBed[];
  busyBedId: number | null;
  onUnblock: (bedId: number) => void;
}

/**
 * Beds currently out of service, with a way to return them.
 *
 * Unblocking happens here; BLOCKING happens on the map. That split is deliberate rather than a
 * missing feature: to block a bed you must first choose one, and the map already answers "which
 * beds are free right now" far better than a room-number text box would. Duplicating that as a
 * form here would mean a second, worse bed picker to keep in step with the first.
 */
export function BlockPanel({ blocked, busyBedId, onUnblock }: Props) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <h2 className="font-medium">Out of service</h2>
          <p className="text-sm text-slate-500">
            {blocked.length === 0
              ? 'No beds are blocked.'
              : `${blocked.length} bed${blocked.length === 1 ? '' : 's'} withdrawn for maintenance.`}
          </p>
        </div>
        <Link
          to="/map"
          className="shrink-0 rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium hover:bg-slate-100"
        >
          Block a bed →
        </Link>
      </div>

      {blocked.length > 0 && (
        <ul className="space-y-2">
          {blocked.map((bed) => (
            <li
              key={bed.bedId}
              className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3"
            >
              <div>
                <p className="font-medium">
                  Room {bed.roomNumber}, bed {bed.bedLabel}
                  <span className="ml-2 text-sm font-normal text-slate-500">
                    wing {bed.wing} · {bed.floor}
                  </span>
                </p>
                <p className="text-xs text-slate-500">
                  {bed.reason ?? 'No reason recorded'} · since {formatDateTime(bed.blockedAt)}
                </p>
              </div>
              <button
                type="button"
                disabled={busyBedId !== null}
                onClick={() => onUnblock(bed.bedId)}
                className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium hover:bg-slate-100 disabled:opacity-60"
              >
                {busyBedId === bed.bedId ? 'Working…' : 'Return to service'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
