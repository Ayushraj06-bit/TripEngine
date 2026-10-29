import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import { onThisClock } from '@/api/serverClock';
import type { Schemas } from '@/api/types';
import { Splash } from '@/auth/guards';
import { useTripLive } from '@/realtime/useTripLive';
import { useBookingDraft } from '@/state/bookingDraft';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { formatRupees } from '@/ui/Money';
import { DarkScreen, FormAlert } from './common';
import { SignOutButton } from './SignOutButton';

type Trip = Schemas['TripView'];
type Status = NonNullable<Trip['status']>;

const WORDS: Record<Status, { rider: string; driver: string }> = {
  REQUESTED: { rider: 'Finding your driver', driver: 'Waiting for the trip to start' },
  DRIVER_ASSIGNED: { rider: 'Your driver is on the way', driver: 'Head to the pickup' },
  DRIVER_ARRIVED: { rider: 'Your driver has arrived', driver: 'You’re at the pickup' },
  IN_PROGRESS: { rider: 'On your way', driver: 'On the way to the drop' },
  COMPLETED: { rider: 'Trip completed', driver: 'Trip completed' },
  CANCELLED: { rider: 'Trip cancelled', driver: 'Trip cancelled' },
  NO_DRIVER_FOUND: { rider: 'No driver was found', driver: 'Trip ended' },
};

const RIDER_LEAD: Partial<Record<Status, string>> = {
  REQUESTED: 'Asking the nearest drivers, one at a time. This page updates on its own.',
  DRIVER_ASSIGNED: 'Tell your driver this code when they arrive, so they know it’s you.',
  DRIVER_ARRIVED: 'Tell your driver this code to start the trip.',
  NO_DRIVER_FOUND:
    'None of the drivers nearby could take this ride. Your pickup and drop are kept.',
};

/** The backend's rider-cancel-grace-seconds: free to cancel this long after a driver is assigned. */
const GRACE_MS = 120_000;

/** The rider may cancel until the trip starts. */
const CANCELLABLE: readonly Status[] = ['REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_ARRIVED'];

const ENDED: readonly Status[] = ['COMPLETED', 'CANCELLED', 'NO_DRIVER_FOUND'];

/** Once a driver is on the way, the ride is real: the draft that led to it is done with. */
const UNDER_WAY: readonly Status[] = [
  'DRIVER_ASSIGNED',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
  'COMPLETED',
];

/**
 * Cancelling, with the fee rule said before it applies: free while a driver is still being found
 * and for two minutes after one is assigned, the rate card's fee after that. The fee charged is
 * the server's, shown once it has answered.
 */
function CancelRide({ trip, onCancelled }: { trip: Trip; onCancelled: (trip: Trip) => void }) {
  const graceEnds = trip.assignedAt ? onThisClock(trip.assignedAt) + GRACE_MS : null;
  const [graceOver, setGraceOver] = useState(false);
  const [asking, setAsking] = useState(false);
  const free = trip.status === 'REQUESTED' || (trip.status === 'DRIVER_ASSIGNED' && !graceOver);

  const cancel = useMutation({
    mutationFn: () =>
      api.post('/api/v1/trips/{tripId}/cancel', {
        path: { tripId: trip.id ?? '' },
        body: { reason: '' },
      }),
    onSuccess: onCancelled,
  });

  return (
    <div className="flex flex-col gap-3">
      {cancel.isError && <FormAlert message={describeError(cancel.error).message} />}
      {trip.status === 'DRIVER_ASSIGNED' && graceEnds !== null && !graceOver && (
        <p className="text-subhead text-paper/70">
          Free to cancel for{' '}
          <Countdown
            until={graceEnds}
            label="Free to cancel for"
            onElapsed={() => {
              setGraceOver(true);
            }}
          />
        </p>
      )}
      {asking ? (
        <div role="alertdialog" aria-label="Cancel this ride?" className="flex flex-col gap-3">
          <p className="text-body">
            Cancel this ride? A driver is already on the way, so a cancellation fee may apply.
          </p>
          <Button
            surface="ink"
            size="large"
            block
            loading={cancel.isPending}
            onClick={() => {
              cancel.mutate();
            }}
          >
            Cancel ride
          </Button>
          <Button
            surface="ink"
            variant="secondary"
            block
            onClick={() => {
              setAsking(false);
            }}
          >
            Keep my ride
          </Button>
        </div>
      ) : (
        <Button
          surface="ink"
          variant="secondary"
          block
          loading={cancel.isPending}
          onClick={() => {
            if (free) cancel.mutate();
            else setAsking(true);
          }}
        >
          Cancel ride
        </Button>
      )}
    </div>
  );
}

/**
 * A trip, where it stands now, kept current by the live connection and read again over REST
 * whenever that comes back. Following it on a map arrives in Stage 6.
 */
export function TripStatus({ as }: { as: 'rider' | 'driver' }) {
  const { tripId = '' } = useParams();
  const queries = useQueryClient();
  const queryKey = ['trip', tripId] as const;
  const trip = useQuery({
    queryKey,
    queryFn: () => api.get('/api/v1/trips/{tripId}', { path: { tripId } }),
  });
  useTripLive(tripId, queryKey);
  const status = trip.data?.status;

  useEffect(() => {
    if (as === 'rider' && status && UNDER_WAY.includes(status)) {
      useBookingDraft.getState().clear();
    }
  }, [as, status]);

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

  const view = trip.data;
  const fee = view.cancellationFeePaise ?? 0;
  const lead =
    as === 'rider'
      ? ((status && RIDER_LEAD[status]) ??
        'Following the trip live on the map arrives in a later stage of this app.')
      : 'Marking arrival and starting the trip arrive in the next stage of this app.';

  return (
    <DarkScreen
      title={status ? WORDS[status][as] : 'Your trip'}
      lead={lead}
      actions={<SignOutButton />}
    >
      {as === 'rider' &&
        view.otp &&
        (status === 'DRIVER_ASSIGNED' || status === 'DRIVER_ARRIVED') && (
          <section aria-label="Your code" className="rounded-card bg-paper/6 p-4">
            <p className="text-footnote text-paper/70">Your code</p>
            <p className="text-large font-semibold tracking-[0.3em] tabular-nums">{view.otp}</p>
          </section>
        )}

      {status === 'CANCELLED' && (
        <p className="text-body">
          {view.cancelledBy === 'RIDER'
            ? 'You cancelled this ride.'
            : view.cancelledBy === 'DRIVER'
              ? 'Your driver cancelled this ride.'
              : 'RideFlow cancelled this ride.'}
          {as === 'rider' && fee > 0 && ` A cancellation fee of ${formatRupees(fee)} was charged.`}
        </p>
      )}

      {as === 'rider' && status && CANCELLABLE.includes(status) && (
        <CancelRide
          trip={view}
          onCancelled={(cancelled) => {
            queries.setQueryData(queryKey, cancelled);
          }}
        />
      )}

      {as === 'rider' && status === 'NO_DRIVER_FOUND' && (
        <Link
          to="/?step=prices"
          className="flex min-h-12.5 items-center justify-center rounded-card bg-signal text-body font-semibold text-ink"
        >
          Try again
        </Link>
      )}

      {/* Only once it is over: home sends anyone with a trip under way straight back here. */}
      {status && ENDED.includes(status) && (
        <Link
          to={home}
          className="flex min-h-11 items-center self-start rounded-full bg-paper/12 px-5 text-body font-semibold"
        >
          Home
        </Link>
      )}
    </DarkScreen>
  );
}
