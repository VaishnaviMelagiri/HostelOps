import { useEffect } from 'react';
import { subscribeTo } from './stompClient';

/** Mirrors `AdminQueueChangedPayload`. A signal — it carries no student, request or bed. */
export interface AdminQueueChanged {
  event: 'ADMIN_QUEUE_CHANGED';
  cause: string;
  at: string;
}

export const ADMIN_QUEUE_TOPIC = '/topic/admin/queue';

/**
 * Subscribes to the admin queue signal.
 *
 * The message says only that the queue moved. The handler refetches
 * `GET /api/admin/requests` over authenticated REST, which is where identities are disclosed and
 * permission-checked — so a broadcast topic never carries a student's name even to admins.
 *
 * Subscribing without `REQUEST_QUEUE_READ` is refused by the server at SUBSCRIBE time, so
 * `enabled` here is only about not asking pointlessly, never about security.
 */
export function useAdminQueueSignal(
  enabled: boolean,
  onChanged: (event: AdminQueueChanged) => void,
): void {
  useEffect(() => {
    if (!enabled) return;
    return subscribeTo(ADMIN_QUEUE_TOPIC, (body) => onChanged(body as AdminQueueChanged));
  }, [enabled, onChanged]);
}
