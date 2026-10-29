import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { CentrePin } from '@/map/CentrePin';
import { CITY_CENTRE, MapCanvas, type LatLng } from '@/map/MapCanvas';
import { MapPin } from '@/map/MapPin';
import { NearbyDrivers } from '@/map/NearbyDrivers';
import { useBookingDraft, type Stop, type StopKind } from '@/state/bookingDraft';
import { useSession } from '@/state/session';
import { Button } from '@/ui/Button';
import { Icon } from '@/ui/Icon';
import { Row } from '@/ui/Row';
import { Sheet, type SheetHeight } from '@/ui/Sheet';
import { TopBar } from '@/ui/TopBar';
import { ACTIVE_TRIP, useActiveTrip, useNearbyDrivers } from '../queries';
import { SignOutButton } from '../SignOutButton';
import { ChooseOnMap } from './ChooseOnMap';
import { Prices } from './Prices';
import { nearbyWords } from './stops';
import { WhereTo } from './WhereTo';

type Step = 'home' | 'where-to' | 'map' | 'prices';

/** The step lives in the address, so Back and a reload both keep the rider where they were. */
function stepFrom(params: URLSearchParams): { step: Step; kind: StopKind } {
  const step = params.get('step');
  const kind = params.get('field') === 'pickup' ? 'pickup' : 'drop';
  return {
    step: step === 'where-to' || step === 'map' || step === 'prices' ? step : 'home',
    kind,
  };
}

const HEIGHT: Record<Step | 'summary', SheetHeight> = {
  home: 'peek',
  summary: 'half',
  'where-to': 'full',
  map: 'peek',
  prices: 'half',
};

/**
 * The rider's map. A trip under way opens instead - the server decides, every time this loads.
 * Otherwise: where to, chosen by search or on the map, with the drivers around the pickup.
 */
export function RiderHome() {
  const trip = useActiveTrip();
  const queries = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const { step, kind } = stepFrom(params);
  const name = useSession((state) => state.user?.name ?? '');
  const pickup = useBookingDraft((s) => s.pickup);
  const drop = useBookingDraft((s) => s.drop);
  const dropQuery = useBookingDraft((s) => s.dropQuery);
  const setStop = useBookingDraft((s) => s.setStop);
  const nearby = useNearbyDrivers(step === 'map' ? null : (pickup?.at ?? null));
  const [pinAt, setPinAt] = useState<LatLng | null>(null);
  const [heights, setHeights] = useState<Partial<Record<string, SheetHeight>>>({});

  if (trip.data) return <Navigate to={`/trip/${trip.data.id}`} replace />;
  // Prices need both stops: a reload or a link that arrives without them starts at where to.
  if (step === 'prices' && (!pickup || !drop))
    return <Navigate to="/?step=where-to&field=drop" replace />;

  const ready = pickup !== null && drop !== null;
  const view = step === 'home' && ready ? 'summary' : step;
  const height = heights[view] ?? HEIGHT[view];

  function open(next: Step, field?: StopKind) {
    void navigate(field ? `/?step=${next}&field=${field}` : `/?step=${next}`);
  }

  /** Back where the rider came from - or home, when this step was the first page opened. */
  function back() {
    if (location.key === 'default') void navigate('/', { replace: true });
    else void navigate(-1);
  }

  function chosenOnMap(stop: Stop) {
    setStop(kind, stop);
    const other = kind === 'pickup' ? drop : pickup;
    if (other) void navigate('/', { replace: true });
    else
      void navigate(`/?step=where-to&field=${kind === 'pickup' ? 'drop' : 'pickup'}`, {
        replace: true,
      });
  }

  const start = (kind === 'pickup' ? pickup : drop)?.at ?? pickup?.at ?? CITY_CENTRE;
  const fit = step === 'map' ? [] : [pickup?.at, drop?.at].filter((p): p is LatLng => !!p);

  return (
    <>
      <title>RideFlow</title>
      <TopBar surface="paper" actions={<SignOutButton surface="paper" />} />
      <main>
        <MapCanvas
          label={
            step === 'map'
              ? `Map. Move it to put the pin on your ${kind}. With a keyboard, use the arrow keys.`
              : 'Map of the city'
          }
          center={pickup?.at ?? CITY_CENTRE}
          zoom={13}
          fit={fit}
        >
          {step !== 'map' && pickup && <MapPin kind="pickup" at={pickup.at} />}
          {step !== 'map' && drop && <MapPin kind="drop" at={drop.at} />}
          {step !== 'map' && nearby && <NearbyDrivers drivers={nearby} />}
          {step === 'map' && <CentrePin key={kind} kind={kind} start={start} onSettle={setPinAt} />}
        </MapCanvas>
        <Sheet
          height={height}
          onHeightChange={(next) => {
            setHeights({ ...heights, [view]: next });
          }}
          label={
            step === 'where-to'
              ? 'Plan your ride'
              : step === 'map'
                ? `Set your ${kind}`
                : step === 'prices'
                  ? 'Choose a ride'
                  : ready
                    ? 'Your ride'
                    : 'Where to?'
          }
          footer={
            view === 'summary' ? (
              <Button
                size="large"
                block
                onClick={() => {
                  open('prices');
                }}
              >
                See prices
              </Button>
            ) : undefined
          }
        >
          {step === 'where-to' ? (
            <WhereTo
              initial={kind}
              onChooseOnMap={(field) => {
                open('map', field);
              }}
              onDone={() => {
                void navigate('/', { replace: true });
              }}
              onClose={back}
            />
          ) : step === 'map' ? (
            <ChooseOnMap kind={kind} at={pinAt} onConfirm={chosenOnMap} />
          ) : step === 'prices' ? (
            <Prices
              onChangeStops={() => {
                open('where-to', 'drop');
              }}
              onBooked={(tripId) => {
                void queries.invalidateQueries({ queryKey: ACTIVE_TRIP });
                void navigate(`/trip/${tripId}`, { replace: true });
              }}
            />
          ) : ready ? (
            <div className="flex flex-col gap-1 pb-4">
              <Row
                label="Pickup"
                value={pickup.name}
                action={
                  <Button
                    variant="quiet"
                    className="px-3"
                    aria-label="Change pickup"
                    onClick={() => {
                      open('where-to', 'pickup');
                    }}
                  >
                    Change
                  </Button>
                }
              />
              <Row
                label="Drop"
                value={drop.name}
                action={
                  <Button
                    variant="quiet"
                    className="px-3"
                    aria-label="Change drop"
                    onClick={() => {
                      open('where-to', 'drop');
                    }}
                  >
                    Change
                  </Button>
                }
              />
              <p aria-live="polite" className="pt-2 text-subhead text-ink-soft">
                {nearby ? nearbyWords(nearby) : ''}
              </p>
            </div>
          ) : (
            <div className="flex flex-col gap-3 pb-4">
              <p className="text-subhead text-ink-soft">
                {name ? `Hello, ${name.split(' ')[0] ?? name}.` : 'Hello.'}
              </p>
              <button
                type="button"
                onClick={() => {
                  open('where-to', pickup || !drop ? 'drop' : 'pickup');
                }}
                className="flex min-h-12 w-full cursor-pointer items-center gap-3 rounded-input bg-wash px-3 text-left text-body"
              >
                <Icon name="search" />
                <span className={dropQuery ? 'truncate text-ink' : 'text-mute'}>
                  {dropQuery || 'Where to?'}
                </span>
              </button>
            </div>
          )}
        </Sheet>
      </main>
    </>
  );
}
