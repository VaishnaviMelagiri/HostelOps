import type { BedStatus } from '../../api/types';
import { BED_STATUS_STYLE } from '../../lib/status';

const ORDER: BedStatus[] = ['AVAILABLE', 'PENDING', 'ALLOCATED', 'BLOCKED'];

/**
 * What the colours mean.
 *
 * The legend describes BEDS, not rooms, because a bed is what actually gets requested, approved or
 * blocked. The room card is only a container.
 */
export function Legend() {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2">
      {ORDER.map((status) => (
        <li key={status} className="flex items-center gap-2" title={BED_STATUS_STYLE[status].help}>
          <span
            className="h-3 w-6 rounded-full"
            style={{ backgroundColor: BED_STATUS_STYLE[status].fill }}
            aria-hidden
          />
          <span className="text-sm text-slate-700">{BED_STATUS_STYLE[status].label}</span>
        </li>
      ))}
    </ul>
  );
}
