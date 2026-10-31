import type { LatLngTuple, Marker as LeafletMarker } from 'leaflet';
import { useEffect, useRef, useState } from 'react';
import { Marker } from 'react-leaflet';
import { between, easeOut } from './glide';
import { prefersReducedMotion, type LatLng } from './MapCanvas';
import { PIN_ICONS } from './MapPin';

/** Half the time between two positions: the car settles before the next one arrives. */
const GLIDE_MS = 1000;

/**
 * The driver, moved from each position to the next along a short glide rather than jumping - or
 * jumping, for anyone who asks for less motion. Leaflet moves the marker frame by frame; React only
 * says where it is going. Dimmed while its position is stale.
 */
export function DriverMarker({
  at,
  label,
  stale = false,
}: {
  at: LatLng;
  label: string;
  stale?: boolean;
}) {
  const marker = useRef<LeafletMarker>(null);
  // Where it is first drawn, as the same array every render: react-leaflet moves a marker back to
  // any position it is handed anew. From then on, only the glide moves it.
  const [first] = useState<LatLngTuple>(() => [at.lat, at.lng]);
  const drawn = useRef(at);
  const { lat, lng } = at;

  useEffect(() => {
    const shown = marker.current;
    if (!shown) return;
    const from = drawn.current;
    const to = { lat, lng };
    if (prefersReducedMotion()) {
      drawn.current = to;
      shown.setLatLng([lat, lng]);
      return;
    }
    // Each frame reads the same clock as the start: a frame's own timestamp may count from
    // elsewhere.
    const start = performance.now();
    let frame = requestAnimationFrame(function step() {
      const t = Math.min(1, (performance.now() - start) / GLIDE_MS);
      drawn.current = between(from, to, easeOut(t));
      shown.setLatLng([drawn.current.lat, drawn.current.lng]);
      if (t < 1) frame = requestAnimationFrame(step);
    });
    return () => {
      cancelAnimationFrame(frame);
    };
  }, [lat, lng]);

  return (
    <Marker
      ref={marker}
      position={first}
      icon={PIN_ICONS.driver}
      opacity={stale ? 0.45 : 1}
      title={label}
      alt={label}
      keyboard={false}
    />
  );
}
