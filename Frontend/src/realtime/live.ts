import { create } from 'zustand';
import { StompClient, type SocketFactory } from './stomp';

/**
 * The app's one live connection, while someone is signed in (ADR 0022). It is never the source of
 * truth: every screen reads the server over REST first, and again whenever the connection comes
 * back, so a message lost while it was down costs a moment, not data.
 */

export type LiveStatus = 'off' | 'connecting' | 'open' | 'reconnecting';

export const useLive = create<{ status: LiveStatus }>()(() => ({ status: 'off' }));

/** What every pushed message looks like: the backend's RealtimeMessage envelope. */
export interface Envelope {
  type: string;
  tripId?: string | null;
  seq?: number | null;
  sentAt?: string;
  payload: unknown;
}

type Handler = (message: Envelope) => void;

let factory: SocketFactory = (url) => new WebSocket(url) as unknown as ReturnType<SocketFactory>;
let client: StompClient | null = null;
let tokenFor: ((renew: boolean) => Promise<string | null>) | null = null;
let attempt = 0;
// Open at least once since starting: the next open is a reconnection.
let wasOpen = false;
// Bumped by every start and stop, so a connection begun before a stop never finishes after it.
let generation = 0;
let retry: ReturnType<typeof setTimeout> | null = null;
const listeners = new Map<string, Set<Handler>>();
const unsubscribers = new Map<string, () => void>();
const reconnected = new Set<() => void>();

/** For tests: sockets that talk to the test instead of the network. */
export function useSocketFactoryForTests(next: SocketFactory): void {
  factory = next;
}

function url(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws`;
}

function deliver(destination: string, body: string): void {
  let message: Envelope;
  try {
    message = JSON.parse(body) as Envelope;
  } catch {
    return;
  }
  for (const handler of listeners.get(destination) ?? []) handler(message);
}

function subscribeAll(): void {
  for (const destination of listeners.keys()) subscribeOne(destination);
}

function subscribeOne(destination: string): void {
  if (!client || useLive.getState().status !== 'open' || unsubscribers.has(destination)) return;
  unsubscribers.set(
    destination,
    client.subscribe(destination, (body) => {
      deliver(destination, body);
    }),
  );
}

/** One second, two, four, eight, then fifteen at most, each a little early or late. */
export function backoffMs(n: number): number {
  const base = Math.min(15_000, 1000 * 2 ** n);
  return Math.round(base * (0.8 + Math.random() * 0.4));
}

async function connect(renew: boolean): Promise<void> {
  if (!tokenFor) return;
  const run = generation;
  const token = await tokenFor(renew).catch(() => null);
  if (run !== generation) return;
  if (!token) {
    // The session is over; the session layer sends the user to sign in.
    stopLive();
    return;
  }
  useLive.setState({ status: wasOpen ? 'reconnecting' : 'connecting' });
  unsubscribers.clear();
  client = new StompClient(url(), token, factory, {
    onConnected: () => {
      useLive.setState({ status: 'open' });
      subscribeAll();
      if (wasOpen) for (const callback of reconnected) callback();
      wasOpen = true;
      attempt = 0;
    },
    onClosed: (refused) => {
      client = null;
      unsubscribers.clear();
      if (!tokenFor) return;
      useLive.setState({ status: 'reconnecting' });
      const delay = backoffMs(attempt);
      attempt += 1;
      retry = setTimeout(() => {
        retry = null;
        void connect(refused);
      }, delay);
    },
  });
}

/**
 * Connects, and keeps reconnecting until stopLive. `token` gives the access token to connect
 * with; after the server refuses one, it is asked again with `renew` true, so a session that ran
 * out while the connection was down is renewed first.
 */
export function startLive(token: (renew: boolean) => Promise<string | null>): void {
  if (tokenFor) return;
  tokenFor = token;
  generation += 1;
  attempt = 0;
  wasOpen = false;
  void connect(false);
}

export function stopLive(): void {
  tokenFor = null;
  generation += 1;
  if (retry) clearTimeout(retry);
  retry = null;
  const closing = client;
  client = null;
  unsubscribers.clear();
  closing?.close();
  attempt = 0;
  wasOpen = false;
  useLive.setState({ status: 'off' });
}

/** Messages on `destination`, for as long as the returned function is not called. */
export function listen(destination: string, handler: Handler): () => void {
  let set = listeners.get(destination);
  if (!set) {
    set = new Set();
    listeners.set(destination, set);
  }
  set.add(handler);
  subscribeOne(destination);
  return () => {
    set.delete(handler);
    if (set.size === 0) {
      listeners.delete(destination);
      unsubscribers.get(destination)?.();
      unsubscribers.delete(destination);
    }
  };
}

/** Sends now, or not at all: false when there is no open connection. */
export function publish(destination: string, body: unknown): boolean {
  if (!client || useLive.getState().status !== 'open') return false;
  client.send(destination, body);
  return true;
}

/** Called each time the connection comes back after a drop: the moment to read REST again. */
export function onReconnect(callback: () => void): () => void {
  reconnected.add(callback);
  return () => {
    reconnected.delete(callback);
  };
}
