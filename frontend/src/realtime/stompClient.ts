import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';

/**
 * One shared STOMP connection for the whole app.
 *
 * A module-level singleton rather than a connection per component: a browser limits how many
 * sockets a page may open, and every subscription can share one anyway. Components subscribe and
 * unsubscribe; the socket itself is opened once at sign-in and closed at sign-out.
 */

const WS_URL =
  import.meta.env.VITE_WS_URL ??
  `${(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/^http/, 'ws')}/ws`;

let client: Client | null = null;
let connected = false;

/** Subscriptions asked for before the socket finished connecting, replayed on connect. */
const pending = new Map<string, (message: IMessage) => void>();
const active = new Map<string, StompSubscription>();

/** Notified whenever the connection comes up or goes down, so the UI can show it and refetch. */
type ConnectionListener = (isConnected: boolean) => void;
const connectionListeners = new Set<ConnectionListener>();

function notify(state: boolean) {
  connected = state;
  connectionListeners.forEach((listener) => listener(state));
}

export function onConnectionChange(listener: ConnectionListener): () => void {
  connectionListeners.add(listener);
  listener(connected);
  return () => connectionListeners.delete(listener);
}

export function isConnected(): boolean {
  return connected;
}

/**
 * Opens the socket, authenticating with the same JWT the REST calls use.
 *
 * The token goes in a STOMP CONNECT header rather than an HTTP one because the browser WebSocket
 * API gives no way to set headers on the handshake — there is no equivalent of
 * `fetch(url, { headers })`. The server reads it from the frame instead.
 */
export function connectStomp(token: string): void {
  if (client) return;

  client = new Client({
    brokerURL: WS_URL,
    connectHeaders: { Authorization: `Bearer ${token}` },
    // Retry a dropped connection. Real networks drop sockets — laptops sleep, wifi switches — and
    // a real-time feature that silently stops working after the first blip is worse than none,
    // because the page then looks up to date while quietly being stale.
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
  });

  client.onConnect = () => {
    // Re-apply every subscription. After a reconnect the server knows nothing about the old
    // session, so subscriptions have to be re-registered or the socket would sit there silent.
    pending.forEach((handler, destination) => {
      active.set(destination, client!.subscribe(destination, handler));
    });
    notify(true);
  };

  client.onWebSocketClose = () => {
    active.clear();
    notify(false);
  };

  client.onStompError = () => notify(false);

  client.activate();
}

export function disconnectStomp(): void {
  pending.clear();
  active.clear();
  void client?.deactivate();
  client = null;
  notify(false);
}

/**
 * Subscribes to a destination. Returns an unsubscribe function.
 *
 * Safe to call before the socket is up: the subscription is remembered and applied on connect.
 */
export function subscribeTo(destination: string, handler: (body: unknown) => void): () => void {
  const wrapped = (message: IMessage) => {
    try {
      handler(JSON.parse(message.body));
    } catch {
      // A malformed frame must not take the subscription down with it.
    }
  };

  pending.set(destination, wrapped);
  if (client && connected) {
    active.set(destination, client.subscribe(destination, wrapped));
  }

  return () => {
    pending.delete(destination);
    active.get(destination)?.unsubscribe();
    active.delete(destination);
  };
}
