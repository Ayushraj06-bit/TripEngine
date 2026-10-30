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
import { TRIP_RULES } from '@/trip/rules';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { formatRupees } from '@/ui/Money';
import { toast } from '@/ui/toasts';
import { ConfirmStep, DarkScreen, FormAlert } from './common';
import { SignOutButton } from './SignOutButton';

type Trip = Schemas['TripView'];
type Status = NonNullable<Trip['status']>;

const TITLE: Record<Status, string> = {
  REQUESTED: 'Finding your driver',
  DRIVER_ASSIGNED: 'Your driver is on the way',
  DRIVER_ARRIVED: 'Your driver has arrived',
  IN_PROGRESS: 'On your way',
  COMPLETED: 'Trip completed',
  CANCELLED: 'Trip cancelled',
  NO_DRIVER_FOUND: 'No driver was found',
};

const LEAD: Partial<Record<Status, string>> = {
  REQUESTED: 'Asking the nearest drivers, one at a time. This page updates on its own.',
  DRIVER_ASSIGNED: 'Tell your driver this code when they arrive, so they know it’s you.',
  DRIVER_ARRIVED: 'Tell your driver this code to start the trip.',
  NO_DRIVER_FOUND:
    'None of the drivers nearby could take this ride. Your pickup and drop are kept.',
  // Only while the trip is moving: a finished trip has nothing to follow.
  IN_PROGRESS: 'Following the trip live on the map arrives in a later stage of this app.',
};

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
  const graceEnds = trip.assignedAt
    ? onThisClock(trip.assignedAt) + TRIP_RULES.riderCancelGraceSeconds * 1000
    : null;
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
        <ConfirmStep
          question="Cancel this ride?"
          detail="A driver is already on the way, so a cancellation fee may apply."
          confirm="Cancel ride"
          keep="Keep my ride"
          busy={cancel.isPending}
          onConfirm={() => {
            cancel.mutate();
          }}
          onKeep={() => {
            setAsking(false);
          }}
        />
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

/** Waiting at the pickup: free for a few minutes, then charged by the minute - said as it happens. */
function FreeWaiting({ arrivedAt }: { arrivedAt: string }) {
  const [over, setOver] = useState(false);
  return (
    <p className="text-subhead text-paper/70">
      {over ? (
        'Waiting is now charged by the minute.'
      ) : (
        <>
          Free waiting for{' '}
          <Countdown
            until={onThisClock(arrivedAt) + TRIP_RULES.freeWaitMinutes * 60_000}
            label="Free waiting for"
            onElapsed={() => {
              setOver(true);
            }}
          />
          ; after that, waiting is charged by the minute.
        </>
      )}
    </p>
  );
}

/** What the ride cost, and how it was paid. */
function Paid({ trip }: { trip: Trip }) {
  const fare = formatRupees(trip.finalFarePaise ?? 0);
  return (
    <section aria-label="Fare" className="flex flex-col gap-1 rounded-card bg-paper/6 p-4">
      <p className="text-large font-semibold tabular-nums">{fare}</p>
      <p className="text-body">
        {trip.paymentMethod !== 'WALLET'
          ? 'Paid in cash to your driver.'
          : trip.paymentStatus === 'PENDING_SETTLEMENT'
            ? 'Charged to your wallet, which is now below zero. Top up before your next ride.'
            : 'Paid from your wallet.'}
      </p>
    </section>
  );
}

/**
 * The rider's trip, where it stands now, kept current by the live connection and read again over
 * REST whenever that comes back. Following it on a map arrives in Stage 6.
 */
export function TripStatus() {
  const { tripId = '' } = useParams();
  const queries = useQueryClient();
  const queryKey = ['trip', tripId] as const;
  const trip = useQuery({
    queryKey,
    queryFn: () => api.get('/api/v1/trips/{tripId}', { path: { tripId } }),
  });
  useTripLive(tripId, queryKey, (type) => {
    // A new "Finding your driver" alone would not say why.
    if (type === 'TripReturnedToMatching') {
      toast('Your driver couldn’t make it. RideFlow is finding you another.');
    }
  });
  const status = trip.data?.status;

  useEffect(() => {
    if (status && UNDER_WAY.includes(status)) useBookingDraft.getState().clear();
  }, [status]);

  if (trip.isPending) return <Splash />;
  if (trip.isError) {
    const described = describeError(trip.error);
    if (described.code === 'NOT_FOUND') return <Navigate to="/" replace />;
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

  return (
    <DarkScreen
      title={status ? TITLE[status] : 'Your trip'}
      lead={status ? LEAD[status] : undefined}
      actions={<SignOutButton />}
    >
      {view.otp && (status === 'DRIVER_ASSIGNED' || status === 'DRIVER_ARRIVED') && (
        <section aria-label="Your code" className="rounded-card bg-paper/6 p-4">
          <p className="text-footnote text-paper/70">Your code</p>
          <p className="text-large font-semibold tracking-[0.3em] tabular-nums">{view.otp}</p>
        </section>
      )}

      {status === 'DRIVER_ARRIVED' && view.arrivedAt && <FreeWaiting arrivedAt={view.arrivedAt} />}

      {status === 'COMPLETED' && <Paid trip={view} />}

      {status === 'CANCELLED' && (
        <p className="text-body">
          {view.cancelledBy === 'RIDER'
            ? 'You cancelled this ride.'
            : view.cancelledBy === 'DRIVER'
              ? 'Your driver cancelled after waiting at the pickup.'
              : 'RideFlow cancelled this ride.'}
          {fee > 0 && ` A cancellation fee of ${formatRupees(fee)} was charged.`}
        </p>
      )}

      {status && CANCELLABLE.includes(status) && (
        <CancelRide
          trip={view}
          onCancelled={(cancelled) => {
            queries.setQueryData(queryKey, cancelled);
          }}
        />
      )}

      {status === 'NO_DRIVER_FOUND' && (
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
          to="/"
          className="flex min-h-11 items-center self-start rounded-full bg-paper/12 px-5 text-body font-semibold"
        >
          Home
        </Link>
      )}
    </DarkScreen>
  );
}
