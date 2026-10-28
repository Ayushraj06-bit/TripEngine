import { describe, expect, it } from 'vitest';
import { pinPoint } from './CentrePin';

describe('pinPoint', () => {
  it('sits in the middle of the map neither the bar nor the sheet covers', () => {
    expect(pinPoint({ x: 390, y: 844 }, { bottom: 176, left: 0 })).toEqual({ x: 195, y: 362 });
    expect(pinPoint({ x: 1280, y: 800 }, { bottom: 0, left: 436 })).toEqual({ x: 858, y: 428 });
  });
});
