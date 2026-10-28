import { useState } from 'react';
import { Link } from 'react-router';
import { MapCanvas, type LatLng } from '@/map/MapCanvas';
import { MapPin } from '@/map/MapPin';
import { NearbyDrivers, type NearbyDriver } from '@/map/NearbyDrivers';
import { Button } from '@/ui/Button';
import { Row } from '@/ui/Row';
import { Sheet, type SheetHeight } from '@/ui/Sheet';
import { TopBar } from '@/ui/TopBar';

/** Sample positions in Bengaluru: MG Road to Indiranagar, a driver between, and drivers nearby. */
const PICKUP: LatLng = { lat: 12.9756, lng: 77.6069 };
const DROP: LatLng = { lat: 12.9784, lng: 77.6408 };
const DRIVER: LatLng = { lat: 12.9722, lng: 77.6153 };
const NEARBY: NearbyDriver[] = [
  { type: 'BIKE', at: { lat: 12.9781, lng: 77.6012 } },
  { type: 'AUTO', at: { lat: 12.9719, lng: 77.6011 } },
  { type: 'CAR', at: { lat: 12.9802, lng: 77.6122 } },
];

/**
 * The sheet over the map, at its three heights. Moving the sheet re-fits the pins into the part
 * of the map still visible.
 */
export function DesignSystemMap() {
  const [height, setHeight] = useState<SheetHeight>('half');
  return (
    <>
      <title>Sheet and map · RideFlow</title>
      <MapCanvas label="Sample map with a pickup, a drop and a driver" fit={[PICKUP, DROP]}>
        <MapPin kind="pickup" at={PICKUP} />
        <MapPin kind="drop" at={DROP} />
        <MapPin kind="driver" at={DRIVER} />
        <NearbyDrivers drivers={NEARBY} />
      </MapCanvas>
      <TopBar
        surface="paper"
        actions={
          <Link
            to="/design-system"
            className="flex min-h-11 items-center rounded-full px-4 text-subhead font-semibold material"
          >
            Design system
          </Link>
        }
      />
      <Sheet
        height={height}
        onHeightChange={setHeight}
        label="Sample sheet"
        footer={<Button block>Confirm Auto</Button>}
      >
        <div className="flex flex-col gap-1 pb-2">
          <Row label="Pickup" value="MG Road" />
          <Row label="Drop" value="Indiranagar" />
        </div>
        <div className="flex flex-wrap gap-2 py-2" role="group" aria-label="Sheet height">
          {(['peek', 'half', 'full'] as const).map((h) => (
            <Button
              key={h}
              variant={h === height ? 'primary' : 'secondary'}
              aria-pressed={h === height}
              onClick={() => {
                setHeight(h);
              }}
            >
              {h}
            </Button>
          ))}
        </div>
        <p className="py-2 text-footnote text-ink-soft">
          Sample content. Drag the handle, or focus it and use the arrow keys.
        </p>
      </Sheet>
    </>
  );
}
