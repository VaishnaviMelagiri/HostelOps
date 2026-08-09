import type { RoomCell as RoomCellData } from '../../api/types';
import { BED_STATUS_STYLE, ROOM_STATUS_TINT } from '../../lib/status';

/** Drawing constants, in SVG user units. The browser scales the whole drawing to fit. */
export const CELL_WIDTH = 92;
export const CELL_HEIGHT = 66;
export const CELL_GAP = 10;
/** Extra space between the two rows, standing in for the corridor between them. */
export const CORRIDOR = 34;

interface Props {
  room: RoomCellData;
  selected: boolean;
  onSelect: (room: RoomCellData) => void;
}

/**
 * One room, drawn as an SVG group.
 *
 * <p>The room card is only faintly tinted; the real colour is on the small pills inside, one per
 * bed. That is deliberate and reflects the point of the whole project: a request is for a *bed*,
 * not a room. A Double with one bed taken and one free looks visibly half-taken, which is exactly
 * what it is — colouring the whole room would hide the free bed.
 */
export function RoomCellShape({ room, selected, onSelect }: Props) {
  // Grid coordinates are 1-based; subtract 1 before multiplying so column 1 sits at x = 0.
  const x = (room.cell.col - 1) * (CELL_WIDTH + CELL_GAP);
  const y = (room.cell.row - 1) * (CELL_HEIGHT + CELL_GAP) + (room.cell.row === 2 ? CORRIDOR : 0);

  const bedPillWidth = room.beds.length === 1 ? 56 : 30;
  const bedPillGap = 6;
  const bedsWidth = room.beds.length * bedPillWidth + (room.beds.length - 1) * bedPillGap;
  const bedsStartX = x + (CELL_WIDTH - bedsWidth) / 2;

  return (
    <g
      onClick={() => onSelect(room)}
      className="cursor-pointer"
      role="button"
      tabIndex={0}
      aria-label={`Room ${room.roomNumber}, ${room.roomType}, ${room.bathroomType} bathroom`}
      onKeyDown={(event) => {
        // Keyboard users get the same access as mouse users.
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect(room);
        }
      }}
    >
      {/* A native SVG <title> becomes the browser's hover tooltip with no JavaScript at all,
          and screen readers announce it too. */}
      <title>
        {`Room ${room.roomNumber} — ${room.roomType} (${room.capacity} bed${
          room.capacity > 1 ? 's' : ''
        }), ${room.bathroomType} bathroom`}
      </title>

      <rect
        x={x}
        y={y}
        width={CELL_WIDTH}
        height={CELL_HEIGHT}
        rx={8}
        fill={ROOM_STATUS_TINT[room.roomStatus]}
        stroke={selected ? '#0f172a' : '#cbd5e1'}
        strokeWidth={selected ? 2.5 : 1}
      />

      <text
        x={x + CELL_WIDTH / 2}
        y={y + 21}
        textAnchor="middle"
        className="select-none"
        fontSize={15}
        fontWeight={600}
        fill="#0f172a"
      >
        {room.roomNumber}
      </text>

      <text
        x={x + CELL_WIDTH / 2}
        y={y + 34}
        textAnchor="middle"
        className="select-none"
        fontSize={9}
        fill="#64748b"
      >
        {room.bathroomType === 'Attached' ? 'attached bath' : 'common bath'}
      </text>

      {room.beds.map((bed, index) => (
        <g key={bed.bedId}>
          <rect
            x={bedsStartX + index * (bedPillWidth + bedPillGap)}
            y={y + CELL_HEIGHT - 22}
            width={bedPillWidth}
            height={15}
            rx={7.5}
            fill={BED_STATUS_STYLE[bed.status].fill}
          />
          <text
            x={bedsStartX + index * (bedPillWidth + bedPillGap) + bedPillWidth / 2}
            y={y + CELL_HEIGHT - 11}
            textAnchor="middle"
            className="select-none"
            fontSize={9}
            fontWeight={600}
            fill="#ffffff"
          >
            {bed.bedLabel}
          </text>
        </g>
      ))}
    </g>
  );
}
