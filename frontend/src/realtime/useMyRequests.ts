import { useEffect } from 'react';
import { MY_REQUESTS_QUEUE, type RequestResolved } from './events';
import { subscribeTo } from './stompClient';

/**
 * Subscribes to the signed-in student's own private queue.
 *
 * `enabled` is false for ADMIN and GUEST — neither can own a request, so nothing would ever arrive.
 */
export function useMyRequests(
  enabled: boolean,
  onResolved: (event: RequestResolved) => void,
): void {
  useEffect(() => {
    if (!enabled) return;
    return subscribeTo(MY_REQUESTS_QUEUE, (body) => onResolved(body as RequestResolved));
  }, [enabled, onResolved]);
}
