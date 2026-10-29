/**
 * The little of STOMP 1.2 the app speaks to the backend's /ws: connect with the access token,
 * subscribe to its own queues, send a driver's position, and keep the line alive with heartbeats.
 * Reconnecting is the caller's business (live.ts); this is one connection, start to end.
 */

export interface Frame {
  command: string;
  headers: Record<string, string>;
  body: string;
}

/** What the client needs of a WebSocket; a test hands it a fake. */
export interface SocketLike {
  send: (data: string) => void;
  close: () => void;
  onopen: (() => void) | null;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code?: number }) => void) | null;
  onerror: (() => void) | null;
}

export type SocketFactory = (url: string) => SocketLike;

const ESCAPES: [RegExp, string][] = [
  [/\\/g, '\\\\'],
  [/\r/g, '\\r'],
  [/\n/g, '\\n'],
  [/:/g, '\\c'],
];

function escapeHeader(value: string): string {
  return ESCAPES.reduce(
    (text, [pattern, replacement]) => text.replace(pattern, replacement),
    value,
  );
}

function unescapeHeader(value: string): string {
  return value.replace(/\\([\\rnc])/g, (_, c: string) =>
    c === 'r' ? '\r' : c === 'n' ? '\n' : c === 'c' ? ':' : '\\',
  );
}

export function encode({ command, headers, body }: Frame): string {
  // CONNECT's headers are sent as they are: STOMP 1.2 leaves them unescaped.
  const plain = command === 'CONNECT';
  const lines = Object.entries(headers).map(
    ([name, value]) =>
      `${plain ? name : escapeHeader(name)}:${plain ? value : escapeHeader(value)}`,
  );
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

/** Every whole frame in `data`; the newlines between frames are heartbeats, and are skipped. */
export function decode(data: string): Frame[] {
  return data
    .split('\0')
    .map((chunk) => chunk.replace(/^[\r\n]+/, ''))
    .filter((chunk) => chunk.length > 0)
    .map((chunk) => {
      const split = chunk.indexOf('\n\n');
      const head = split === -1 ? chunk : chunk.slice(0, split);
      const body = split === -1 ? '' : chunk.slice(split + 2);
      const [command = '', ...lines] = head.split(/\r?\n/);
      const headers: Record<string, string> = {};
      for (const line of lines) {
        const colon = line.indexOf(':');
        if (colon < 0) continue;
        const name = unescapeHeader(line.slice(0, colon));
        // A repeated header keeps its first value, as the spec says.
        headers[name] ??= unescapeHeader(line.slice(colon + 1));
      }
      return { command, headers, body };
    });
}

export interface StompHandlers {
  /** The server accepted the connection. */
  onConnected: () => void;
  /**
   * The connection ended: `refused` when the server said no (ERROR), such as to a stale token;
   * `expired` when the server closed it because the token it opened with ran out.
   */
  onClosed: (refused: boolean, expired: boolean) => void;
}

const HEARTBEAT_MS = 10_000;

/**
 * The close code the backend sends when the access token a session opened with runs out
 * (ExpiringSessions: POLICY_VIOLATION, "Access token expired") - every 15 minutes, by design.
 */
const TOKEN_EXPIRED = 1008;

export class StompClient {
  private readonly socket: SocketLike;
  private readonly handlers = new Map<string, (body: string) => void>();
  private nextId = 0;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private refused = false;
  private closed = false;

  constructor(url: string, token: string, factory: SocketFactory, events: StompHandlers) {
    this.socket = factory(url);
    this.socket.onopen = () => {
      this.write({
        command: 'CONNECT',
        headers: {
          'accept-version': '1.2',
          host: new URL(url).host,
          'heart-beat': `${String(HEARTBEAT_MS)},${String(HEARTBEAT_MS)}`,
          Authorization: `Bearer ${token}`,
        },
        body: '',
      });
    };
    this.socket.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      for (const frame of decode(event.data)) {
        if (frame.command === 'CONNECTED') {
          // An empty line now and then, so neither end takes a quiet line for a dead one.
          this.heartbeat = setInterval(() => {
            this.socket.send('\n');
          }, HEARTBEAT_MS);
          events.onConnected();
        } else if (frame.command === 'MESSAGE') {
          this.handlers.get(frame.headers['subscription'] ?? '')?.(frame.body);
        } else if (frame.command === 'ERROR') {
          this.refused = true;
          this.socket.close();
        }
      }
    };
    this.socket.onclose = (event) => {
      this.stopHeartbeat();
      if (this.closed) return;
      this.closed = true;
      events.onClosed(this.refused, event.code === TOKEN_EXPIRED);
    };
    this.socket.onerror = () => {
      this.socket.close();
    };
  }

  /** Delivers each message's body to `handler`; the returned function stops it. */
  subscribe(destination: string, handler: (body: string) => void): () => void {
    const id = `sub-${String(this.nextId++)}`;
    this.handlers.set(id, handler);
    this.write({ command: 'SUBSCRIBE', headers: { id, destination }, body: '' });
    return () => {
      this.handlers.delete(id);
      this.write({ command: 'UNSUBSCRIBE', headers: { id }, body: '' });
    };
  }

  send(destination: string, body: unknown): void {
    this.write({
      command: 'SEND',
      headers: { destination, 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
  }

  close(): void {
    this.closed = true;
    this.stopHeartbeat();
    this.socket.close();
  }

  private write(frame: Frame): void {
    try {
      this.socket.send(encode(frame));
    } catch {
      // The socket is closing; onclose follows and the caller reconnects.
    }
  }

  private stopHeartbeat(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
  }
}
