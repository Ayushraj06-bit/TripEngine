import { divIcon } from 'leaflet';
import { Marker } from 'react-leaflet';
import type { LatLng } from './MapCanvas';

export type PinKind = 'pickup' | 'drop' | 'driver';

/*
 * Drawn with the theme's classes, not images: the pickup a ring, the drop a square - shape tells
 * them apart without relying on colour - and the driver the one amber thing on the map.
 */
const HTML: Record<PinKind, string> = {
  pickup: '<span class="block size-5 rounded-full border-[5px] border-ink bg-paper"></span>',
  drop: '<span class="block size-4 bg-ink outline-2 outline-paper"></span>',
  driver: '<span class="block size-6 rounded-full border-[3px] border-paper bg-signal"></span>',
};

const SIZE: Record<PinKind, number> = { pickup: 20, drop: 16, driver: 24 };

const ICONS: Record<PinKind, ReturnType<typeof divIcon>> = {
  pickup: icon('pickup'),
  drop: icon('drop'),
  driver: icon('driver'),
};

function icon(kind: PinKind) {
  const size = SIZE[kind];
  return divIcon({
    className: 'rf-pin',
    html: HTML[kind],
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  });
}

const LABEL: Record<PinKind, string> = { pickup: 'Pickup', drop: 'Drop', driver: 'Your driver' };

export function MapPin({ kind, at, label }: { kind: PinKind; at: LatLng; label?: string }) {
  return (
    <Marker
      position={[at.lat, at.lng]}
      icon={ICONS[kind]}
      title={label ?? LABEL[kind]}
      alt={label ?? LABEL[kind]}
      keyboard={false}
    />
  );
}
