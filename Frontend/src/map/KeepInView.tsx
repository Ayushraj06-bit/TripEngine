import type { Map as LeafletMap } from 'leaflet';
import { useEffect, useEffectEvent, useRef } from 'react';
import { useMap } from 'react-leaflet';
import { useLayout, type MapInset } from '@/ui/layout';
import { fitInView, useMapSize, type LatLng } from './MapCanvas';

/** How near the edge of the uncovered map the mover may come before the map follows: half the
 * margin a fit leaves, so it is not refitted at every step. */
const EDGE = 24;
/** Below the top bar. */
const TOP = 64;

function inView(map: LeafletMap, at: LatLng, inset: MapInset): boolean {
  const p = map.latLngToContainerPoint([at.lat, at.lng]);
  const size = map.getSize();
  return (
    p.x >= inset.left + EDGE &&
    p.x <= size.x - EDGE &&
    p.y >= TOP &&
    p.y <= size.y - inset.bottom - EDGE
  );
}

/**
 * Keeps the stops that matter now, and whoever is moving towards them, in the part of the map the
 * sheet leaves uncovered. Fitted when the stops change, the mover first appears, or the sheet or
 * the window moves; after that only when the mover nears the edge - never at every position, which
 * would take the map away from anyone looking around it.
 */
export function KeepInView({ stops, mover }: { stops: readonly LatLng[]; mover: LatLng | null }) {
  const map = useMap();
  const bottom = useLayout((state) => state.inset.bottom);
  const left = useLayout((state) => state.inset.left);
  const size = useMapSize();
  const fitted = useRef('');
  // Callers rebuild their arrays on every render: what counts is the positions.
  const key = `${stops.map((p) => `${String(p.lat)},${String(p.lng)}`).join(';')}|${mover ? 'moving' : ''}|${String(bottom)},${String(left)}|${size}`;
  const moved = mover ? `${String(mover.lat)},${String(mover.lng)}` : '';

  const keep = useEffectEvent(() => {
    const points = mover ? [...stops, mover] : stops;
    if (points.length === 0) return;
    if (fitted.current === key && mover && inView(map, mover, { bottom, left })) return;
    fitted.current = key;
    fitInView(map, points, { bottom, left });
  });
  useEffect(() => {
    keep();
  }, [key, moved]);
  return null;
}
