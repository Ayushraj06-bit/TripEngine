import { describe, expect, it } from 'vitest';
import { between, easeOut } from './glide';

const FROM = { lat: 12.97, lng: 77.6 };
const TO = { lat: 12.98, lng: 77.64 };

describe('a glide', () => {
  it('starts where the car was drawn and ends where it was reported', () => {
    expect(between(FROM, TO, easeOut(0))).toEqual(FROM);
    expect(between(FROM, TO, easeOut(1))).toEqual(TO);
  });

  it('covers most of the way early, then settles', () => {
    expect(easeOut(0.5)).toBeGreaterThan(0.8);
    const halfway = between(FROM, TO, 0.5);
    expect(halfway.lat).toBeCloseTo(12.975);
    expect(halfway.lng).toBeCloseTo(77.62);
  });
});
