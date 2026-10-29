import { decode, encode, type Frame, type SocketLike } from '@/realtime/stomp';

/**
 * The backend's /ws, played by the test: it answers CONNECT, remembers what was subscribed and
 * sent, and pushes whatever a test asks it to - or drops the connection, or refuses it.
 */
class FakeSocket implements SocketLike {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readonly subscriptions = new Map<string, string>();
  private closed = false;

  constructor(private readonly server: FakeLiveServer) {
    queueMicrotask(() => this.onopen?.());
  }

  send(data: string): void {
    if (this.closed) return;
    for (const frame of decode(data)) {
      this.server.frames.push(frame);
      if (frame.command === 'CONNECT') {
        this.server.tokens.push(frame.headers['Authorization'] ?? '');
        if (this.server.refuseNext) {
          this.server.refuseNext = false;
          this.reply({ command: 'ERROR', headers: { message: 'Unauthorized' }, body: '' });
        } else if (this.server.answering) {
          this.reply({ command: 'CONNECTED', headers: { version: '1.2' }, body: '' });
        }
      } else if (frame.command === 'SUBSCRIBE') {
        this.subscriptions.set(frame.headers['destination'] ?? '', frame.headers['id'] ?? '');
      }
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    queueMicrotask(() => this.onclose?.());
  }

  reply(frame: Frame): void {
    queueMicrotask(() => this.onmessage?.({ data: encode(frame) }));
  }

  get isOpen(): boolean {
    return !this.closed;
  }
}

export class FakeLiveServer {
  sockets: FakeSocket[] = [];
  frames: Frame[] = [];
  tokens: string[] = [];
  /** False: CONNECT goes unanswered, as when the server is down. */
  answering = true;
  refuseNext = false;

  readonly factory = (): SocketLike => {
    const socket = new FakeSocket(this);
    this.sockets.push(socket);
    return socket;
  };

  reset(): void {
    this.sockets = [];
    this.frames = [];
    this.tokens = [];
    this.answering = true;
    this.refuseNext = false;
  }

  private get current(): FakeSocket | undefined {
    return this.sockets.findLast((socket) => socket.isOpen);
  }

  /** Whether the client is subscribed to `destination` on the open connection. */
  subscribed(destination: string): boolean {
    return this.current?.subscriptions.has(destination) ?? false;
  }

  /** Pushes a message, in the backend's envelope, to whoever subscribed to `destination`. */
  push(destination: string, message: object): void {
    const socket = this.current;
    const id = socket?.subscriptions.get(destination);
    if (!socket || id === undefined) throw new Error(`Nobody is subscribed to ${destination}`);
    socket.reply({
      command: 'MESSAGE',
      headers: { subscription: id, destination, 'content-type': 'application/json' },
      body: JSON.stringify(message),
    });
  }

  /** The server goes away: the open connection closes. */
  drop(): void {
    this.current?.close();
  }

  /** What the client sent to `destination`, parsed. */
  sentTo(destination: string): unknown[] {
    return this.frames
      .filter((f) => f.command === 'SEND' && f.headers['destination'] === destination)
      .map((f) => JSON.parse(f.body) as unknown);
  }
}

export const liveServer = new FakeLiveServer();
