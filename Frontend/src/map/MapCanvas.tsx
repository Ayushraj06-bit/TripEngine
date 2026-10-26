import 'leaflet/dist/leaflet.css';
import './map.css';
import { latLngBounds, type FitBoundsOptions } from 'leaflet';
import { useEffect, useEffectEvent, type ReactNode } from 'react';
import { AttributionControl, MapContainer, TileLayer, useMap } from 'react-leaflet';
import { useLayout, type MapInset } from '@/ui/layout';

/** A position as the backend sends it (its `Point` schema). */
export interface LatLng {
  lat: number;
  lng: number;
}

/** MG Road, Bengaluru: where the map opens when it knows nothing better. */
export const CITY_CENTRE: LatLng = { lat: 12.9756, lng: 77.6069 };

/**
 * Room for the attribution and toasts at the top and for the sheet wherever it is, plus enough
 * margin that a pin never sits against an edge.
 */
export function fitPadding(
  inset: MapInset,
): Pick<FitBoundsOptions, 'paddingTopLeft' | 'paddingBottomRight'> {
  return {
    paddingTopLeft: [inset.left + 48, 88],
    paddingBottomRight: [48, inset.bottom + 48],
  };
}

function prefersReducedMotion(): boolean {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Keeps `points` in view, in the part of the map the sheet does not cover. */
function FitToPoints({ points }: { points: readonly LatLng[] }) {
  const map = useMap();
  const bottom = useLayout((state) => state.inset.bottom);
  const left = useLayout((state) => state.inset.left);
  const fitNow = useEffectEvent(() => {
    if (points.length === 0) return;
    map.fitBounds(latLngBounds(points.map((p) => [p.lat, p.lng])), {
      ...fitPadding({ bottom, left }),
      maxZoom: 16,
      animate: !prefersReducedMotion(),
    });
  });
  // Callers rebuild `points` on every render: re-fit when the positions change, not the array.
  const key = points.map((p) => `${p.lat},${p.lng}`).join(';');
  useEffect(() => {
    fitNow();
  }, [key, bottom, left]);
  return null;
}

interface MapCanvasProps {
  /** Where the map opens. */
  center?: LatLng;
  zoom?: number;
  /** Points to keep in view, re-fitted when they change or the sheet moves. */
  fit?: readonly LatLng[];
  /** What the map shows, for screen readers: "Map of your pickup and drop". */
  label: string;
  /** Marker layers: pins, nearby drivers, the live driver. */
  children?: ReactNode;
}

/**
 * The full-bleed map behind every screen: OpenStreetMap tiles (free, no key), their attribution,
 * fitting that knows where the sheet is, and whatever marker layers a screen adds.
 */
export function MapCanvas({
  center = CITY_CENTRE,
  zoom = 14,
  fit = [],
  label,
  children,
}: MapCanvasProps) {
  return (
    <div className="rf-map fixed inset-0 z-0" role="region" aria-label={label}>
      <MapContainer
        center={[center.lat, center.lng]}
        zoom={zoom}
        zoomControl={false}
        attributionControl={false}
        className="h-full w-full"
      >
        <TileLayer
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          maxZoom={19}
        />
        <AttributionControl position="topright" prefix={false} />
        <FitToPoints points={fit} />
        {children}
      </MapContainer>
    </div>
  );
}
