/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { TRIP_RULES } from './rules';

// Tests run from Frontend/, so the backend's configuration sits one level up.
const CONFIG = readFileSync(
  resolve(process.cwd(), '../backend/src/main/resources/application.yml'),
  'utf8',
);

function backend(key: string): number {
  const found = new RegExp(`\\n\\s+${key}:\\s*(\\d+)`).exec(CONFIG);
  if (!found?.[1]) throw new Error(`${key} is not in application.yml`);
  return Number(found[1]);
}

describe('the trip rules the app counts down to', () => {
  it('are the backend’s', () => {
    expect(TRIP_RULES).toEqual({
      riderCancelGraceSeconds: backend('rider-cancel-grace-seconds'),
      freeWaitMinutes: backend('free-wait-minutes'),
      noShowAfterMinutes: backend('no-show-after-minutes'),
      arrivalGeofenceMeters: backend('arrival-geofence-meters'),
    });
  });
});
