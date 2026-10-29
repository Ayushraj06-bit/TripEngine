import { describe, expect, it } from 'vitest';
import { quote } from '@/test/backend';
import { distanceText, fareLines, minutesText, surgeText } from './fare';

describe('how a trip reads', () => {
  it('in minutes, rounded up', () => {
    expect(minutesText(1080)).toBe('18 min');
    expect(minutesText(20)).toBe('1 min');
    expect(minutesText(3900)).toBe('1 h 5 min');
    expect(minutesText(7200)).toBe('2 h');
  });

  it('in metres or kilometres', () => {
    expect(distanceText(4230)).toBe('4.2 km');
    expect(distanceText(846)).toBe('850 m');
  });

  it('with surge only when there is some', () => {
    expect(surgeText([quote('BIKE', 8400)])).toBeNull();
    expect(surgeText([quote('BIKE', 8400, { surgeMultiplier: 1.25 })])).toBe('1.3×');
  });

  it('line by line, leaving out what is zero', () => {
    const lines = fareLines(
      quote('AUTO', 14800, {
        breakdown: { basePaise: 3000, distancePaise: 9800, timePaise: 2000, roundingPaise: 0 },
      }),
    );
    expect(lines.map((l) => l.label)).toEqual(['Base fare', 'Distance', 'Time']);
  });
});
