import { describe, expect, it, vi } from 'vitest';
import { newKey } from './booking';

describe('newKey', () => {
  it('is a version 4 UUID, made by hand where the page is not secure', () => {
    const v4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(newKey()).toMatch(v4);
    vi.spyOn(crypto, 'randomUUID').mockImplementation(undefined as never);
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
    try {
      expect(newKey()).toMatch(v4);
      expect(newKey()).not.toBe(newKey());
    } finally {
      Reflect.deleteProperty(crypto, 'randomUUID');
    }
  });
});
