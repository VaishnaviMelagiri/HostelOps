import type { FloorRooms, RoomCell as RoomCellData } from '../../api/types';
import {
  CELL_GAP,
  CELL_HEIGHT,
  CELL_WIDTH,
  CORRIDOR,
  RoomCellShape,
} from './RoomCell';

interface Props {
  floor: FloorRooms;
  selectedRoomId: number | null;
  onSelectRoom: (room: RoomCellData) => void;
}

/**
 * Draws one floor as SVG.
 *
 * The backend sends grid coordinates, never pixels, so every size here is a frontend decision.
 * Swapping this for canvas or a CSS grid later would need no backend change at all — that is what
 * "renderer-agnostic payload" bought us.
 *
 * SVG suits this well: shapes stay crisp at any zoom, each room is a real DOM element that can take
 * a click and a keyboard focus, and the `viewBox` lets the browser scale the whole drawing to
 * whatever width is available.
 */
export function FloorMap({ floor, selectedRoomId, onSelectRoom }: Props) {
  const width = floor.grid.columns * CELL_WIDTH + (floor.grid.columns - 1) * CELL_GAP;
  const height =
    floor.grid.rows * CELL_HEIGHT + (floor.grid.rows - 1) * CELL_GAP + (floor.grid.rows > 1 ? CORRIDOR : 0);

  const corridorY = CELL_HEIGHT + CELL_GAP + CORRIDOR / 2 - 6;

  return (
    <div className="overflow-x-auto">
      <svg
        // viewBox defines the coordinate space; width/height below scale it to the container.
        viewBox={`-4 -4 ${width + 8} ${height + 8}`}
        className="h-auto w-full min-w-[560px]"
        role="img"
        aria-label={`Floor map of wing ${floor.wing}, floor ${floor.floor}`}
      >
        {floor.grid.rows > 1 && (
          <>
            <line
              x1={0}
              y1={corridorY}
              x2={width}
              y2={corridorY}
              stroke="#e2e8f0"
              strokeWidth={2}
              strokeDasharray="6 6"
            />
            <text x={width / 2} y={corridorY - 5} textAnchor="middle" fontSize={9} fill="#94a3b8">
              corridor
            </text>
          </>
        )}

        {floor.rooms.map((room) => (
          <RoomCellShape
            key={room.roomId}
            room={room}
            selected={room.roomId === selectedRoomId}
            onSelect={onSelectRoom}
          />
        ))}
      </svg>
    </div>
  );
}
