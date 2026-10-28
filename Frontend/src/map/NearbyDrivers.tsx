import { divIcon } from 'leaflet';
import { Marker } from 'react-leaflet';
import type { Schemas } from '@/api/types';
import { iconMarkup } from '@/ui/Icon';
import type { LatLng } from './MapCanvas';

export type VehicleType = NonNullable<Schemas['QuoteView']['vehicleType']>;

export interface NearbyDriver {
  type: VehicleType;
  at: LatLng;
}

const GLYPH = { BIKE: 'bike', AUTO: 'auto', CAR: 'car' } as const;

const ICONS = {
  BIKE: icon('BIKE'),
  AUTO: icon('AUTO'),
  CAR: icon('CAR'),
};

function icon(type: VehicleType) {
  return divIcon({
    className: 'rf-pin',
    html: `<span aria-hidden="true" class="flex size-6 items-center justify-center rounded-full border-2 border-paper bg-ink-soft text-paper">${iconMarkup(GLYPH[type], 16)}</span>`,
    iconSize: [24, 24],
    iconAnchor: [12, 12],
  });
}

/**
 * Drivers around the pickup, where the backend says they roughly are: positions only, no one
 * identifiable. Pictures, not controls - the sheet says how many there are in words.
 */
export function NearbyDrivers({ drivers }: { drivers: readonly NearbyDriver[] }) {
  return drivers.map((driver, i) => (
    <Marker
      // The backend sends no ids, on purpose; the order is all there is.
      key={`${driver.type}-${String(i)}`}
      position={[driver.at.lat, driver.at.lng]}
      icon={ICONS[driver.type]}
      interactive={false}
      keyboard={false}
    />
  ));
}
