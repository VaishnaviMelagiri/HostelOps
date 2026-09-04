import { useEffect, useState } from 'react';
import { onConnectionChange } from './stompClient';

/**
 * Whether the live connection is currently up.
 *
 * Worth showing in the UI. When the socket is down the page is not wrong, just potentially stale —
 * and telling the user that is far better than letting them trust a screen that stopped updating.
 */
export function useConnectionState(): boolean {
  const [connected, setConnected] = useState(false);
  useEffect(() => onConnectionChange(setConnected), []);
  return connected;
}
