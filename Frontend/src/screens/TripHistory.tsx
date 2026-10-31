import { useInfiniteQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import type { Schemas } from '@/api/types';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { formatRupees } from '@/ui/Money';
import { BarLink, DarkScreen, FormAlert } from './common';
import { SignOutButton } from './SignOutButton';

type Trip = Schemas['TripView'];

export const TRIPS = ['trips'] as const;

const STATUS: Record<NonNullable<Trip['status']>, string> = {
  REQUESTED: 'Finding a driver',
  DRIVER_ASSIGNED: 'Driver on the way',
  DRIVER_ARRIVED: 'Driver at the pickup',
  IN_PROGRESS: 'Under way',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  NO_DRIVER_FOUND: 'No driver found',
};

const RIDE: Record<string, string> = { BIKE: 'Bike', AUTO: 'Auto', CAR: 'Car' };

const WHEN = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

/** What the trip came to: the fare, or a cancellation fee; nothing when nothing was charged. */
function charged(trip: Trip): number | null {
  if (trip.status === 'COMPLETED') return trip.finalFarePaise ?? null;
  return trip.cancellationFeePaise ? trip.cancellationFeePaise : null;
}

/**
 * The signed-in user's trips, newest first, a page at a time - a rider's booked, or a driver's
 * driven. Each opens the trip itself, where a completed one can still be rated.
 */
function TripHistory({ home, tripPath }: { home: string; tripPath: (id: string) => string }) {
  const pages = useInfiniteQuery({
    queryKey: TRIPS,
    queryFn: ({ pageParam }) =>
      api.get('/api/v1/trips', { query: pageParam ? { cursor: pageParam } : {} }),
    initialPageParam: '',
    getNextPageParam: (page) => page.nextCursor,
  });
  if (pages.isPending) return <Splash />;

  const actions = (
    <>
      <BarLink to={home}>Done</BarLink>
      <SignOutButton />
    </>
  );
  if (pages.isError) {
    return (
      <DarkScreen title="Your trips" actions={actions}>
        <FormAlert message={describeError(pages.error).message} />
        <Button
          surface="ink"
          variant="secondary"
          onClick={() => {
            void pages.refetch();
          }}
        >
          Try again
        </Button>
      </DarkScreen>
    );
  }

  const trips = pages.data.pages.flatMap((page) => page.trips ?? []);
  return (
    <DarkScreen title="Your trips" actions={actions}>
      {trips.length === 0 ? (
        <p className="text-subhead text-paper/70">No trips yet.</p>
      ) : (
        <ul>
          {trips.map((trip) => {
            const paise = charged(trip);
            return (
              <li key={trip.id} className="border-b border-paper/12">
                <Link
                  to={tripPath(trip.id ?? '')}
                  className="flex min-h-14 items-center justify-between gap-4 py-3"
                >
                  <span className="flex flex-col">
                    <span className="text-body">
                      {trip.status ? STATUS[trip.status] : 'Trip'} ·{' '}
                      {RIDE[trip.vehicleType ?? ''] ?? 'Ride'}
                    </span>
                    {trip.requestedAt && (
                      <span className="text-footnote text-paper/70">
                        {WHEN.format(new Date(trip.requestedAt))}
                      </span>
                    )}
                  </span>
                  {paise !== null && (
                    <span className="text-body tabular-nums">{formatRupees(paise)}</span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {pages.hasNextPage && (
        <Button
          surface="ink"
          variant="secondary"
          loading={pages.isFetchingNextPage}
          onClick={() => {
            void pages.fetchNextPage();
          }}
        >
          Show earlier
        </Button>
      )}
    </DarkScreen>
  );
}

export function RiderTrips() {
  return <TripHistory home="/" tripPath={(id) => `/trip/${id}`} />;
}
