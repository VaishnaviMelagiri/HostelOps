import { useEffect } from 'react';
import { floorTopic, type BedStatusChanged } from './events';
import { subscribeTo } from './stompClient';

/**
 * Subscribes to one floor's public topic while the component is showing that floor.
 *
 * Re-subscribes when the wing or floor changes and unsubscribes on unmount, so a browser only ever
 * receives traffic for the floor actually on screen — not all 26 of them.
 */
export function useFloorTopic(
  wing: string | null,
  floor: string | null,
  onChange: (event: BedStatusChanged) => void,
): void {
  useEffect(() => {
    if (!wing || !floor) return;
    return subscribeTo(floorTopic(wing, floor), (body) => onChange(body as BedStatusChanged));
  }, [wing, floor, onChange]);
}
