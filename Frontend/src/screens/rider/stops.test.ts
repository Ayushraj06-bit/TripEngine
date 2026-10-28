import { describe, expect, it } from 'vitest';
import { nearbyWords, position, tooClose } from './stops';

const HERE = { lat: 12.9756, lng: 77.6069 };

describe('nearbyWords', () => {
  it('counts what the backend reported, type by type', () => {
    const bike = { type: 'BIKE' as const, at: HERE };
    const auto = { type: 'AUTO' as const, at: HERE };
    const car = { type: 'CAR' as const, at: HERE };
    expect(nearbyWords([bike, bike, bike, auto, auto, car])).toBe(
      '3 bikes, 2 autos and 1 car near your pickup.',
    );
    expect(nearbyWords([car])).toBe('1 car near your pickup.');
    expect(nearbyWords([])).toBe('No drivers near your pickup right now.');
  });
});

describe('tooClose', () => {
  it('refuses a stop on top of the other one, and allows one down the road', () => {
    const pickup = { name: 'MG Road', detail: '', at: HERE };
    expect(tooClose('drop', { lat: 12.9757, lng: 77.607 }, pickup)).toBe(
      'That’s where your pickup is. Choose a drop further away.',
    );
    expect(tooClose('drop', { lat: 12.98, lng: 77.61 }, pickup)).toBeNull();
    expect(tooClose('pickup', HERE, null)).toBeNull();
  });
});

describe('position', () => {
  it('reads like a map app', () => {
    expect(position({ lat: 12.97161, lng: 77.59457 })).toBe('12.9716° N, 77.5946° E');
  });
});
