/**
 * The server's clock, as this device sees it. A phone's clock can be minutes out; a countdown to a
 * time the server set ("prices valid until 10:42:07") must run on the server's clock, or a phone
 * running slow would offer prices the server has already let go.
 *
 * Every response's Date header says what time it was on the server, to the second. The difference
 * from this device's clock at that moment is kept, and server times are moved by it.
 */
let offsetMs = 0;

/** From each response: the server's Date header, and when it arrived here. */
export function heardFromServer(dateHeader: string | null, receivedAt: number = Date.now()): void {
  const serverTime = Date.parse(dateHeader ?? '');
  if (Number.isNaN(serverTime)) return;
  // The header is cut to the whole second: on average the server was half a second further on.
  offsetMs = serverTime + 500 - receivedAt;
}

/** A moment the server named, as epoch milliseconds on this device's clock. */
export function onThisClock(serverTime: string): number {
  return Date.parse(serverTime) - offsetMs;
}

export function resetServerClockForTests(): void {
  offsetMs = 0;
}
