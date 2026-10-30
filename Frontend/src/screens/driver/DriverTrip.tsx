import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type SubmitEvent } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import { ApiError } from '@/api/problem';
import { onThisClock } from '@/api/serverClock';
import type { Schemas } from '@/api/types';
import { Splash } from '@/auth/guards';
import type { LatLng } from '@/map/MapCanvas';
import { metresBetween } from '@/places/serviceArea';
import { useTripLive } from '@/realtime/useTripLive';
import { TRIP_RULES } from '@/trip/rules';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { formatRupees } from '@/ui/Money';
import { toast } from '@/ui/toasts';
import { ConfirmStep, DarkScreen, FormAlert } from '../common';
import { DRIVER_PROFILE } from '../queries';
import { distanceText } from '../rider/fare';
import { SignOutButton } from '../SignOutButton';
import { useSharing } from './DriverPresence';

type Trip = Schemas['TripView'];

const TITLE: Partial<Record<NonNullable<Trip['status']>, string>> = {
  DRIVER_ASSIGNED: 'Head to the pickup',
  DRIVER_ARRIVED: 'You’re at the pickup',
  IN_PROGRESS: 'On the way to the drop',
  COMPLETED: 'Trip completed',
  CANCELLED: 'Trip cancelled',
};

const RIDE: Record<string, string> = { BIKE: 'Bike', AUTO: 'Auto', CAR: 'Car' };

function point(p: Trip['pickup']): LatLng | null {
  return p?.lat === undefined || p.lng === undefined ? null : { lat: p.lat, lng: p.lng };
}

/** The ride at a glance: what kind, how it is paid, and roughly what it pays. */
function RideFacts({ trip }: { trip: Trip }) {
  return (
    <section aria-label="This ride" className="flex flex-col gap-1 rounded-card bg-paper/6 p-4">
      <p className="text-body font-semibold">
        {RIDE[trip.vehicleType ?? ''] ?? 'Ride'} ·{' '}
        {trip.paymentMethod === 'WALLET' ? 'Wallet' : 'Cash'}
      </p>
      {trip.quotedFarePaise !== undefined && (
        <p className="text-subhead text-paper/70">
          Quoted at {formatRupees(trip.quotedFarePaise)}; the final fare comes from the route
          driven.
        </p>
      )}
    </section>
  );
}

/** The rider's code, typed as they read it - spaces and all - and sent as four digits. */
function StartWithCode({ tripId, onStarted }: { tripId: string; onStarted: (trip: Trip) => void }) {
  const [code, setCode] = useState('');
  const [problem, setProblem] = useState<string>();
  const [lockedUntil, setLockedUntil] = useState<number>();
  const start = useMutation({
    mutationFn: (otp: string) =>
      api.post('/api/v1/trips/{tripId}/start', { path: { tripId }, body: { otp } }),
    onSuccess: onStarted,
    onError: (error) => {
      setProblem(describeError(error).message);
      if (error instanceof ApiError && error.code === 'OTP_LOCKED') {
        setLockedUntil(Date.now() + (error.retryAfterSeconds ?? 120) * 1000);
      }
    },
  });
  const digits = code.replace(/\D/g, '');

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (digits.length !== 4) {
      setProblem('Enter the rider’s 4-digit code.');
      return;
    }
    setProblem(undefined);
    start.mutate(digits);
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-4">
      <Field
        label="Rider’s code"
        hint="Ask the rider to read out the 4 digits on their screen."
        error={problem}
        surface="ink"
      >
        {(control) => (
          <Input
            {...control}
            surface="ink"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={9}
            className="tracking-[0.3em] tabular-nums"
            value={code}
            onChange={(event) => {
              setCode(event.target.value);
            }}
          />
        )}
      </Field>
      <Button
        type="submit"
        surface="ink"
        size="large"
        block
        loading={start.isPending}
        disabled={lockedUntil !== undefined}
      >
        {lockedUntil !== undefined ? (
          <>
            {/* The timer's own label reads this; said twice otherwise. */}
            <span aria-hidden="true">Try again in </span>
            <Countdown
              until={lockedUntil}
              label="Try again in"
              onElapsed={() => {
                setLockedUntil(undefined);
                setProblem(undefined);
              }}
            />
          </>
        ) : (
          'Start trip'
        )}
      </Button>
    </form>
  );
}

/**
 * The driver's trip, step by step: to the pickup, waiting there, starting with the rider's code, to
 * the drop, done. Each step is the server's: the screen moves when the trip does, pushed live, and
 * every action that cannot be undone is asked about first.
 */
export function DriverTrip() {
  const { tripId = '' } = useParams();
  const navigate = useNavigate();
  const queries = useQueryClient();
  const sharing = useSharing();
  const queryKey = ['trip', tripId] as const;
  const trip = useQuery({
    queryKey,
    queryFn: () => api.get('/api/v1/trips/{tripId}', { path: { tripId } }),
  });
  useTripLive(tripId, queryKey);
  const [asking, setAsking] = useState<'leave' | 'no-show' | 'complete' | null>(null);
  const [noShowOpen, setNoShowOpen] = useState(false);
  const [freeWaitOver, setFreeWaitOver] = useState(false);

  function moved(view: Trip) {
    queries.setQueryData(queryKey, view);
    setAsking(null);
    void queries.invalidateQueries({ queryKey: DRIVER_PROFILE });
  }

  const arrive = useMutation({
    mutationFn: () => api.post('/api/v1/trips/{tripId}/arrive', { path: { tripId } }),
    onSuccess: moved,
  });
  const complete = useMutation({
    mutationFn: () => api.post('/api/v1/trips/{tripId}/complete', { path: { tripId } }),
    onSuccess: moved,
  });
  const cancel = useMutation({
    mutationFn: () =>
      api.post('/api/v1/trips/{tripId}/cancel', { path: { tripId }, body: { reason: '' } }),
    onSuccess: (view) => {
      if (view.status === 'REQUESTED') {
        // Left before arriving: the trip goes back to matching, without this driver.
        toast('You’ve left this trip. RideFlow is finding the rider another driver.');
        void queries.invalidateQueries({ queryKey: DRIVER_PROFILE });
        void navigate('/driver', { replace: true });
        return;
      }
      moved(view);
    },
  });

  if (trip.isPending) return <Splash />;
  if (trip.isError) {
    const described = describeError(trip.error);
    if (described.code === 'NOT_FOUND' || described.code === 'FORBIDDEN') {
      return <Navigate to="/driver" replace />;
    }
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
  const status = view.status;
  // Not, or no longer, this driver's to drive.
  if (!status || status === 'REQUESTED' || status === 'NO_DRIVER_FOUND') {
    return <Navigate to="/driver" replace />;
  }

  const here = sharing.state === 'sharing' || sharing.state === 'rough' ? sharing.at : null;
  const target = point(status === 'IN_PROGRESS' ? view.drop : view.pickup);
  const away = here && target ? metresBetween(here, target) : null;
  const failure = arrive.error ?? complete.error ?? cancel.error;
  const arrivedAt = view.arrivedAt ? onThisClock(view.arrivedAt) : null;

  const lead =
    status === 'DRIVER_ASSIGNED'
      ? away === null
        ? 'Finding your location…'
        : away <= TRIP_RULES.arrivalGeofenceMeters
          ? 'You’re at the pickup. Mark your arrival.'
          : `Pickup ${distanceText(away)} away.`
      : status === 'DRIVER_ARRIVED'
        ? 'Ask the rider for their code, then start the trip.'
        : status === 'IN_PROGRESS'
          ? away === null
            ? 'Finding your location…'
            : `Drop ${distanceText(away)} away.`
          : undefined;

  return (
    <DarkScreen title={TITLE[status] ?? 'Your trip'} lead={lead} actions={<SignOutButton />}>
      {status !== 'COMPLETED' && status !== 'CANCELLED' && <RideFacts trip={view} />}
      {failure && <FormAlert message={describeError(failure).message} />}

      {status === 'DRIVER_ASSIGNED' &&
        (asking === 'leave' ? (
          <ConfirmStep
            question="Leave this trip?"
            detail="RideFlow will find the rider another driver."
            confirm="Leave trip"
            keep="Keep trip"
            busy={cancel.isPending}
            onConfirm={() => {
              cancel.mutate();
            }}
            onKeep={() => {
              setAsking(null);
            }}
          />
        ) : (
          <>
            <Button
              surface="ink"
              size="large"
              block
              loading={arrive.isPending}
              onClick={() => {
                arrive.mutate();
              }}
            >
              I’ve arrived
            </Button>
            <Button
              surface="ink"
              variant="secondary"
              block
              onClick={() => {
                setAsking('leave');
              }}
            >
              Leave this trip
            </Button>
          </>
        ))}

      {status === 'DRIVER_ARRIVED' && (
        <>
          {arrivedAt !== null && !freeWaitOver && (
            <p className="text-subhead text-paper/70">
              Free waiting ends in{' '}
              <Countdown
                until={arrivedAt + TRIP_RULES.freeWaitMinutes * 60_000}
                label="Free waiting ends in"
                onElapsed={() => {
                  setFreeWaitOver(true);
                }}
              />
              ; after that the rider pays for waiting.
            </p>
          )}
          <StartWithCode tripId={tripId} onStarted={moved} />
          {asking === 'no-show' ? (
            <ConfirmStep
              question="Cancel for a no-show?"
              detail="The rider will be charged the cancellation fee."
              confirm="Cancel for a no-show"
              keep="Keep waiting"
              busy={cancel.isPending}
              onConfirm={() => {
                cancel.mutate();
              }}
              onKeep={() => {
                setAsking(null);
              }}
            />
          ) : noShowOpen ? (
            <Button
              surface="ink"
              variant="secondary"
              block
              onClick={() => {
                setAsking('no-show');
              }}
            >
              Rider didn’t come
            </Button>
          ) : (
            arrivedAt !== null && (
              <p className="text-subhead text-paper/70">
                If the rider doesn’t come, you can cancel for a no-show in{' '}
                <Countdown
                  until={arrivedAt + TRIP_RULES.noShowAfterMinutes * 60_000}
                  label="You can cancel for a no-show in"
                  onElapsed={() => {
                    setNoShowOpen(true);
                  }}
                />
              </p>
            )
          )}
        </>
      )}

      {status === 'IN_PROGRESS' &&
        (asking === 'complete' ? (
          <ConfirmStep
            question="Complete the trip?"
            detail="Do it at the drop: the fare is worked out from the route you drove."
            confirm="Complete trip"
            keep="Not yet"
            busy={complete.isPending}
            onConfirm={() => {
              complete.mutate();
            }}
            onKeep={() => {
              setAsking(null);
            }}
          />
        ) : (
          <Button
            surface="ink"
            size="large"
            block
            onClick={() => {
              setAsking('complete');
            }}
          >
            Complete trip
          </Button>
        ))}

      {status === 'COMPLETED' && (
        <section aria-label="Fare" className="flex flex-col gap-1 rounded-card bg-paper/6 p-4">
          <p className="text-large font-semibold tabular-nums">
            {formatRupees(view.finalFarePaise ?? 0)}
          </p>
          <p className="text-body">
            {view.paymentMethod === 'WALLET'
              ? 'Paid from the rider’s wallet. Nothing to collect.'
              : `Collect ${formatRupees(view.finalFarePaise ?? 0)} in cash from the rider.`}
          </p>
        </section>
      )}

      {status === 'CANCELLED' && (
        <p className="text-body">
          {view.cancelledBy === 'RIDER'
            ? 'The rider cancelled this trip.'
            : view.cancelledBy === 'DRIVER'
              ? 'You cancelled this trip for a no-show. The rider is charged the cancellation fee.'
              : 'RideFlow cancelled this trip.'}
        </p>
      )}

      {(status === 'COMPLETED' || status === 'CANCELLED') && (
        <Link
          to="/driver"
          className="flex min-h-12.5 items-center justify-center rounded-card bg-signal text-body font-semibold text-ink"
        >
          Back to driving
        </Link>
      )}
    </DarkScreen>
  );
}
