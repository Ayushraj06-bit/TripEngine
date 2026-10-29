import { beforeEach, describe, expect, it } from 'vitest';
import { deviceAt, locationFails, resetApp } from '@/test/app';
import { locate } from './location';

beforeEach(resetApp);

describe('locate', () => {
  it('answers with the position and how sure the device is of it', async () => {
    deviceAt({ lat: 12.9716, lng: 77.5946 }, 35);
    await expect(locate()).resolves.toEqual({
      ok: true,
      at: { lat: 12.9716, lng: 77.5946 },
      accuracyMetres: 35,
    });
  });

  it.each([
    [1, 'denied'],
    [2, 'unavailable'],
    [3, 'timeout'],
  ] as const)('turns error %i into “%s”, never a rejection', async (code, reason) => {
    locationFails(code);
    await expect(locate()).resolves.toEqual({ ok: false, reason });
  });

  it('says unavailable where the browser has no location at all', async () => {
    await expect(locate()).resolves.toEqual({ ok: false, reason: 'unavailable' });
  });

  it('says the page is not secure, rather than that location is off, when that is why', async () => {
    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
    try {
      locationFails(1);
      await expect(locate()).resolves.toEqual({ ok: false, reason: 'insecure' });
    } finally {
      Reflect.deleteProperty(window, 'isSecureContext');
    }
  });
});
