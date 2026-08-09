import type { WingSummary } from '../../api/types';

interface Props {
  wings: WingSummary[];
  selectedWing: string;
  selectedFloor: string;
  onWingChange: (wing: string) => void;
  onFloorChange: (floor: string) => void;
}

/** Full floor names, so "BAS" is not a puzzle. */
const FLOOR_NAMES: Record<string, string> = {
  BAS: 'Basement',
  GF: 'Ground',
  FF: 'First',
  SF: 'Second',
  TF: 'Third',
};

export function WingFloorPicker({
  wings,
  selectedWing,
  selectedFloor,
  onWingChange,
  onFloorChange,
}: Props) {
  const wing = wings.find((w) => w.wing === selectedWing);

  return (
    <div className="space-y-4">
      <div>
        <span className="mb-2 block text-xs font-semibold uppercase tracking-wide text-slate-500">
          Wing
        </span>
        <div className="flex flex-wrap gap-2">
          {wings.map((w) => (
            <button
              key={w.wing}
              type="button"
              onClick={() => onWingChange(w.wing)}
              className={`rounded-lg border px-3 py-2 text-left transition ${
                w.wing === selectedWing
                  ? 'border-slate-900 bg-slate-900 text-white'
                  : 'border-slate-300 bg-white hover:border-slate-400'
              }`}
            >
              <span className="block text-sm font-semibold">Wing {w.wing}</span>
              <span
                className={`block text-xs ${
                  w.wing === selectedWing ? 'text-slate-300' : 'text-slate-500'
                }`}
              >
                {w.roomType} · {w.bathroomType}
              </span>
            </button>
          ))}
        </div>
      </div>

      <div>
        <span className="mb-2 block text-xs font-semibold uppercase tracking-wide text-slate-500">
          Floor
        </span>
        <div className="flex flex-wrap gap-2">
          {/* Reversed so the top floor appears at the top of the list, like a real building.
              The API returns them bottom-up (BAS, GF, FF, SF, TF). */}
          {wing &&
            [...wing.floors].reverse().map((floor) => (
              <button
                key={floor}
                type="button"
                onClick={() => onFloorChange(floor)}
                className={`rounded-lg border px-3 py-1.5 text-sm transition ${
                  floor === selectedFloor
                    ? 'border-slate-900 bg-slate-900 text-white'
                    : 'border-slate-300 bg-white hover:border-slate-400'
                }`}
              >
                {FLOOR_NAMES[floor] ?? floor}
              </button>
            ))}
        </div>
      </div>

      {wing && (
        <p className="text-sm text-slate-500">
          Wing {wing.wing} — {wing.totalRooms} rooms, {wing.totalBeds} beds across{' '}
          {wing.floors.length} floors. {wing.block}
        </p>
      )}
    </div>
  );
}
