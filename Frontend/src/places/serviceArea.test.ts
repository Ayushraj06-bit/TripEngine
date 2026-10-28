/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { insideServiceArea, metresBetween, SERVICE_AREA } from './serviceArea';

// Tests run from Frontend/, so the backend's configuration sits one level up.
const CONFIG = resolve(process.cwd(), '../backend/src/main/resources/application.yml');

/** The vertices under `service-area:` in the backend's application.yml. */
function backendServiceArea() {
  const yaml = readFileSync(CONFIG, 'utf8');
  const block = /\n\s*service-area:\s*\n\s*vertices:\s*\n((?:\s*- \{[^}]*\}\s*\n)+)/.exec(yaml);
  const lines = block?.[1] ?? '';
  return [...lines.matchAll(/lat:\s*(-?[\d.]+),\s*lng:\s*(-?[\d.]+)/g)].map(([, lat, lng]) => ({
    lat: Number(lat),
    lng: Number(lng),
  }));
}

describe('the service area', () => {
  it('is the backend’s, vertex for vertex', () => {
    const backend = backendServiceArea();
    expect(backend.length).toBeGreaterThanOrEqual(3);
    expect(SERVICE_AREA).toEqual(backend);
  });

  it('holds the city and nothing beyond it', () => {
    expect(insideServiceArea({ lat: 12.9756, lng: 77.6069 })).toBe(true); // MG Road
    expect(insideServiceArea({ lat: 13.1986, lng: 77.7066 })).toBe(false); // the airport, north of it
    expect(insideServiceArea({ lat: 12.2958, lng: 76.6394 })).toBe(false); // Mysuru
  });

  it('counts a point exactly on an edge as inside, as the backend does', () => {
    expect(insideServiceArea({ lat: 13.14, lng: 77.6 })).toBe(true);
    expect(insideServiceArea({ lat: 12.82, lng: 77.46 })).toBe(true);
  });

  it('works for a shape that is not a box', () => {
    const triangle = [
      { lat: 0, lng: 0 },
      { lat: 0, lng: 10 },
      { lat: 10, lng: 0 },
    ];
    expect(insideServiceArea({ lat: 2, lng: 2 }, triangle)).toBe(true);
    expect(insideServiceArea({ lat: 8, lng: 8 }, triangle)).toBe(false);
  });
});

describe('metresBetween', () => {
  it('measures over the Earth’s surface', () => {
    // MG Road to Indiranagar is about 3.7 km in a straight line.
    const d = metresBetween({ lat: 12.9756, lng: 77.6069 }, { lat: 12.9784, lng: 77.6408 });
    expect(d).toBeGreaterThan(3600);
    expect(d).toBeLessThan(3800);
    expect(metresBetween({ lat: 12.97, lng: 77.6 }, { lat: 12.97, lng: 77.6 })).toBe(0);
  });
});
