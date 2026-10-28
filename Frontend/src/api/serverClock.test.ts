import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { server } from '@/test/server';
import { api } from './client';
import { heardFromServer, onThisClock, resetServerClockForTests } from './serverClock';

beforeEach(resetServerClockForTests);

describe('the server’s clock', () => {
  it('moves a server time onto this device’s clock', () => {
    // This device is 10 minutes behind the server.
    const here = Date.parse('2026-10-29T10:00:00Z');
    heardFromServer('Thu, 29 Oct 2026 10:10:00 GMT', here);
    // Half a second added: the header is cut to the whole second.
    expect(onThisClock('2026-10-29T10:15:00Z')).toBe(Date.parse('2026-10-29T10:05:00Z') - 500);
  });

  it('keeps what it knew when a response has no Date', () => {
    heardFromServer('Thu, 29 Oct 2026 10:10:00 GMT', Date.parse('2026-10-29T10:10:00Z'));
    heardFromServer(null);
    heardFromServer('not a date');
    expect(onThisClock('2026-10-29T10:10:01Z')).toBe(Date.parse('2026-10-29T10:10:00.500Z'));
  });

  it('is read from every response the client receives', async () => {
    server.use(
      http.get('/api/v1/wallet', () =>
        HttpResponse.json(
          {},
          { headers: { Date: new Date(Date.now() + 3_600_000).toUTCString() } },
        ),
      ),
    );
    await api.get('/api/v1/wallet');
    // An hour ahead, give or take the second the header is cut to.
    const shift = Date.parse('2026-10-29T12:00:00Z') - onThisClock('2026-10-29T12:00:00Z');
    expect(shift).toBeGreaterThan(3_598_000);
    expect(shift).toBeLessThan(3_602_000);
  });
});
