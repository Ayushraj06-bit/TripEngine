import { beforeEach, describe, expect, it } from 'vitest';
import { isNewer, resetTripLiveForTests } from './useTripLive';

beforeEach(resetTripLiveForTests);

describe('which pushed trip update to apply', () => {
  it('only one numbered higher than any applied: no repeat, no straggler', () => {
    expect(isNewer('trip-1', 3)).toBe(true);
    expect(isNewer('trip-1', 3)).toBe(false);
    expect(isNewer('trip-1', 2)).toBe(false);
    // A gap is fine: each update is the whole trip, so the missing ones were overtaken.
    expect(isNewer('trip-1', 7)).toBe(true);
    expect(isNewer('trip-1', 6)).toBe(false);
  });

  it('counting each trip apart', () => {
    expect(isNewer('trip-1', 5)).toBe(true);
    expect(isNewer('trip-2', 1)).toBe(true);
    expect(isNewer('trip-1', 4)).toBe(false);
  });
});
