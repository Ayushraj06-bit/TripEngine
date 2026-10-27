import { lazy, Suspense, type SubmitEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useBookingDraft } from '@/state/bookingDraft';
import { Button } from '@/ui/Button';
import { Icon, type IconName } from '@/ui/Icon';
import { Input } from '@/ui/Input';
import { useMediaQuery } from '@/ui/layout';
import { TopBar } from '@/ui/TopBar';

/** The map beside the form, on wide screens only: phones never download the map for this page. */
const CityMap = lazy(() => import('./WelcomeMap'));

const WIDE = '(min-width: 1024px)';

const RIDES: { icon: IconName; name: string; note: string }[] = [
  { icon: 'bike', name: 'Bike', note: 'Cheapest, 1 rider' },
  { icon: 'auto', name: 'Auto', note: 'Three-wheeler, 3 riders' },
  { icon: 'car', name: 'Car', note: 'Air-conditioned, 4 riders' },
];

/**
 * The front door. Where to, straight away - the booking starts here, before any account, and what
 * is typed is kept through signing in (state/bookingDraft.ts). Dark, like the sign-in screens that
 * follow; the map screens after them are light.
 */
export function Welcome() {
  const navigate = useNavigate();
  const wide = useMediaQuery(WIDE);
  const pickup = useBookingDraft((state) => state.pickupQuery);
  const drop = useBookingDraft((state) => state.dropQuery);
  const setPickup = useBookingDraft((state) => state.setPickupQuery);
  const setDrop = useBookingDraft((state) => state.setDropQuery);

  function seePrices(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    void navigate('/sign-in?returnTo=%2F', { state: { reason: 'see-prices' } });
  }

  return (
    <div data-surface="ink" className="min-h-dvh bg-ink text-paper">
      <title>RideFlow · Go anywhere</title>
      <TopBar
        surface="ink"
        actions={
          <>
            <Link
              to="/sign-in"
              className="flex min-h-11 items-center rounded-full px-4 text-subhead font-semibold"
            >
              Sign in
            </Link>
            <Link
              to="/sign-up"
              className="flex min-h-11 items-center rounded-full bg-paper px-4 text-subhead font-semibold text-ink"
            >
              Sign up
            </Link>
          </>
        }
      />

      <main className="mx-auto flex w-full max-w-6xl flex-col gap-14 px-4 pt-8 pb-32 lg:pb-16">
        <section className="grid items-center gap-10 lg:grid-cols-2">
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-2">
              <h1 className="text-large font-semibold">Go anywhere</h1>
              <p className="text-subhead text-paper/70">
                Bikes, autos and cars across the city, with the fare before you book.
              </p>
            </div>

            <form onSubmit={seePrices} className="flex flex-col gap-3" aria-label="Start a ride">
              <div className="relative flex flex-col gap-2">
                {/* The line from the pickup's ring to the drop's square. */}
                <span
                  aria-hidden="true"
                  className="absolute top-6 bottom-6 left-[21px] w-px bg-paper/30"
                />
                <Input
                  surface="ink"
                  aria-label="Pickup location"
                  placeholder="Pickup location"
                  autoComplete="off"
                  value={pickup}
                  onChange={(event) => {
                    setPickup(event.target.value);
                  }}
                  leading={<span className="block size-3 rounded-full border-[3px] border-paper" />}
                />
                <Input
                  surface="ink"
                  aria-label="Where to?"
                  placeholder="Where to?"
                  autoComplete="off"
                  value={drop}
                  onChange={(event) => {
                    setDrop(event.target.value);
                  }}
                  leading={<span className="block size-3 bg-paper" />}
                />
              </div>
              {/* Pinned to the bottom on phones, in line on wide screens. */}
              <div className="fixed inset-x-0 bottom-0 z-30 border-t border-paper/10 material-ink px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] lg:static lg:border-0 lg:bg-transparent lg:p-0 lg:backdrop-blur-none">
                <Button type="submit" surface="ink" size="large" block>
                  See prices
                </Button>
              </div>
            </form>
          </div>

          {wide && (
            <div className="relative h-120 overflow-hidden rounded-sheet">
              <Suspense fallback={<div className="absolute inset-0 bg-paper/5" />}>
                <CityMap />
              </Suspense>
            </div>
          )}
        </section>

        <section aria-labelledby="ways-to-ride" className="flex flex-col gap-4">
          <h2 id="ways-to-ride" className="text-title font-semibold">
            Ways to ride
          </h2>
          <ul className="grid gap-3 md:grid-cols-3">
            {RIDES.map((ride) => (
              <li key={ride.name} className="flex items-center gap-4 rounded-card bg-paper/6 p-4">
                <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-paper/10">
                  <Icon name={ride.icon} size={24} />
                </span>
                <span className="flex flex-col">
                  <span className="text-body font-semibold">{ride.name}</span>
                  <span className="text-subhead text-paper/70">{ride.note}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section
          aria-labelledby="drive"
          className="flex flex-col items-start gap-3 rounded-sheet bg-paper/6 p-6"
        >
          <h2 id="drive" className="text-title font-semibold">
            Drive with RideFlow
          </h2>
          <p className="text-subhead text-paper/70">
            Your own bike, auto or car, on the hours you choose. Sign up, add your vehicle, and go
            online once you’re approved.
          </p>
          <Link
            to="/sign-up?as=driver"
            className="flex min-h-11 items-center rounded-full bg-paper/12 px-5 text-body font-semibold"
          >
            Sign up to drive
          </Link>
        </section>
      </main>
    </div>
  );
}
