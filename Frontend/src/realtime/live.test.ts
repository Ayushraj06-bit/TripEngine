import { act, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { liveServer } from '@/test/liveServer';
import { backoffMs, listen, onReconnect, publish, startLive, stopLive, useLive } from './live';

afterEach(() => {
  vi.useRealTimers();
});

const token = (renew: boolean) => Promise.resolve(renew ? 'renewed-token' : 'first-token');

async function opened() {
  await waitFor(() => {
    expect(useLive.getState().status).toBe('open');
  });
}

describe('the live connection', () => {
  it('connects with the access token, and subscribes what is listened to', async () => {
    const heard: unknown[] = [];
    listen('/user/queue/trip-updates', (message) => heard.push(message));
    startLive(token);
    await opened();
    expect(liveServer.tokens).toEqual(['Bearer first-token']);
    expect(liveServer.subscribed('/user/queue/trip-updates')).toBe(true);

    liveServer.push('/user/queue/trip-updates', { type: 'TripStarted', seq: 3, payload: {} });
    await waitFor(() => {
      expect(heard).toEqual([{ type: 'TripStarted', seq: 3, payload: {} }]);
    });
  });

  it('sends only while open', async () => {
    expect(publish('/app/driver/location', { lat: 1 })).toBe(false);
    startLive(token);
    await opened();
    expect(publish('/app/driver/location', { lat: 1 })).toBe(true);
    expect(liveServer.sentTo('/app/driver/location')).toEqual([{ lat: 1 }]);
  });

  it('reconnects after a drop, subscribes again, and says it is back', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const back = vi.fn();
    onReconnect(back);
    listen('/user/queue/offers', () => undefined);
    startLive(token);
    await opened();

    act(() => {
      liveServer.drop();
    });
    await waitFor(() => {
      expect(useLive.getState().status).toBe('reconnecting');
    });
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    await opened();
    expect(back).toHaveBeenCalledOnce();
    expect(liveServer.subscribed('/user/queue/offers')).toBe(true);
  });

  it('renews the session before trying again when the server refuses the token', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    liveServer.refuseNext = true;
    startLive(token);
    await waitFor(() => {
      expect(useLive.getState().status).toBe('reconnecting');
    });
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    await opened();
    expect(liveServer.tokens).toEqual(['Bearer first-token', 'Bearer renewed-token']);
  });

  it('stops for good when stopped', async () => {
    startLive(token);
    await opened();
    stopLive();
    expect(useLive.getState().status).toBe('off');
    expect(publish('/app/driver/location', {})).toBe(false);
  });

  it('waits longer each time, up to fifteen seconds', () => {
    expect(backoffMs(0)).toBeGreaterThanOrEqual(800);
    expect(backoffMs(0)).toBeLessThanOrEqual(1200);
    expect(backoffMs(3)).toBeGreaterThanOrEqual(6400);
    expect(backoffMs(10)).toBeLessThanOrEqual(18_000);
    expect(backoffMs(10)).toBeGreaterThanOrEqual(12_000);
  });
});
