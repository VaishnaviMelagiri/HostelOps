import type { Occupancy } from '../../api/types';
import { BED_STATUS_STYLE } from '../../lib/status';

/** Bed counts across all six wings. Counts only — this view names nobody. */
export function OccupancyOverview({ occupancy }: { occupancy: Occupancy }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 className="mb-4 font-medium">Occupancy</h2>

      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tile label="Available" value={occupancy.available} colour={BED_STATUS_STYLE.AVAILABLE.fill} />
        <Tile label="Pending" value={occupancy.pending} colour={BED_STATUS_STYLE.PENDING.fill} />
        <Tile label="Allocated" value={occupancy.allocated} colour={BED_STATUS_STYLE.ALLOCATED.fill} />
        <Tile label="Blocked" value={occupancy.blocked} colour={BED_STATUS_STYLE.BLOCKED.fill} />
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[520px] text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-xs uppercase tracking-wide text-slate-500">
              <th className="pb-2 pr-3 font-medium">Wing</th>
              <th className="pb-2 pr-3 font-medium">Type</th>
              <th className="pb-2 pr-3 text-right font-medium">Beds</th>
              <th className="pb-2 pr-3 text-right font-medium">Free</th>
              <th className="pb-2 pr-3 text-right font-medium">Pending</th>
              <th className="pb-2 pr-3 text-right font-medium">Taken</th>
              <th className="pb-2 pr-3 text-right font-medium">Blocked</th>
              <th className="pb-2 font-medium">Occupancy</th>
            </tr>
          </thead>
          <tbody>
            {occupancy.byWing.map((wing) => (
              <tr key={wing.wing} className="border-b border-slate-100 last:border-0">
                <td className="py-2 pr-3 font-semibold">{wing.wing}</td>
                <td className="py-2 pr-3 text-slate-500">
                  {wing.roomType} · {wing.bathroomType}
                </td>
                <td className="py-2 pr-3 text-right">{wing.totalBeds}</td>
                <td className="py-2 pr-3 text-right">{wing.available}</td>
                <td className="py-2 pr-3 text-right">{wing.pending}</td>
                <td className="py-2 pr-3 text-right">{wing.allocated}</td>
                <td className="py-2 pr-3 text-right">{wing.blocked}</td>
                <td className="py-2">
                  <div className="flex items-center gap-2">
                    {/* Blocked beds are excluded from the denominator - they are not lettable,
                        so counting them as "not yet occupied" would understate real occupancy. */}
                    <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-200">
                      <div
                        className="h-full rounded-full bg-status-allocated"
                        style={{ width: `${wing.occupancyPercent}%` }}
                      />
                    </div>
                    <span className="text-xs tabular-nums text-slate-500">
                      {wing.occupancyPercent}%
                    </span>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-slate-200 font-semibold">
              <td className="pt-2 pr-3">All</td>
              <td className="pt-2 pr-3" />
              <td className="pt-2 pr-3 text-right">{occupancy.totalBeds}</td>
              <td className="pt-2 pr-3 text-right">{occupancy.available}</td>
              <td className="pt-2 pr-3 text-right">{occupancy.pending}</td>
              <td className="pt-2 pr-3 text-right">{occupancy.allocated}</td>
              <td className="pt-2 pr-3 text-right">{occupancy.blocked}</td>
              <td className="pt-2" />
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}

function Tile({ label, value, colour }: { label: string; value: number; colour: string }) {
  return (
    <div className="rounded-lg border border-slate-200 p-3">
      <div className="flex items-center gap-2">
        <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: colour }} aria-hidden />
        <span className="text-xs text-slate-500">{label}</span>
      </div>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
    </div>
  );
}
