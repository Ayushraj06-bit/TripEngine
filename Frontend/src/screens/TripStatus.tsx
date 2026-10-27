import { useQuery } from '@tanstack/react-query';
import { Link, Navigate, useParams } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import type { Schemas } from '@/api/types';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { DarkScreen, FormAlert } from './common';
import { SignOutButton } from './SignOutButton';

type Status = NonNullable<Schemas['TripView']['status']>;

const WORDS: Record<Status, { rider: string; driver: string }> = {
  REQUESTED: { rider: 'Finding your driver', driver: 'Waiting for the trip to start' },
  DRIVER_ASSIGNED: { rider: 'Your driver is on the way', driver: 'Head to the pickup' },
  DRIVER_ARRIVED: { rider: 'Your driver has arrived', driver: 'You’re at the pickup' },
  IN_PROGRESS: { rider: 'On your way', driver: 'On the way to the drop' },
  COMPLETED: { rider: 'Trip completed', driver: 'Trip completed' },
  CANCELLED: { rider: 'Trip cancelled', driver: 'Trip cancelled' },
  NO_DRIVER_FOUND: { rider: 'No driver was found', driver: 'Trip ended' },
};

/**
 * A trip, where it stands now. Opening the app mid-ride lands here; the live map of it arrives in
 * Stage 6.
 */
export function TripStatus({ as }: { as: 'rider' | 'driver' }) {
  const { tripId = '' } = useParams();
  const trip = useQuery({
    queryKey: ['trip', tripId],
    queryFn: () => api.get('/api/v1/trips/{tripId}', { path: { tripId } }),
    refetchInterval: 10_000,
  });
  const home = as === 'driver' ? '/driver' : '/';

  if (trip.isPending) return <Splash />;
  if (trip.isError) {
    const described = describeError(trip.error);
    if (described.code === 'NOT_FOUND') return <Navigate to={home} replace />;
    return (
      <DarkScreen title="Your trip" actions={<SignOutButton />}>
        <FormAlert message={described.message} />
        <Button
          surface="ink"
          variant="secondary"
          onClick={() => {
            void trip.refetch();
          }}
        >
          Try again
        </Button>
      </DarkScreen>
    );
  }

  const status = trip.data.status;
  return (
    <DarkScreen
      title={status ? WORDS[status][as] : 'Your trip'}
      lead="Following the trip live on the map arrives in a later stage of this app."
      actions={<SignOutButton />}
    >
      <Link
        to={home}
        className="flex min-h-11 items-center self-start rounded-full bg-paper/12 px-5 text-body font-semibold"
      >
        Home
      </Link>
    </DarkScreen>
  );
}
