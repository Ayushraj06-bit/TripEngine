import { Link, Navigate } from 'react-router';
import { describeError } from '@/api/errors';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { cx } from '@/ui/cx';
import { DarkScreen, FormAlert } from '../common';
import { SignOutButton } from '../SignOutButton';
import { OnlineToggle } from './OnlineToggle';
import { useActiveTrip, useDriverProfile } from '../queries';

const STATUS = {
  OFFLINE: { label: 'You’re offline', dot: 'bg-mute' },
  AVAILABLE: { label: 'You’re online', dot: 'bg-go' },
  ON_TRIP: { label: 'You’re on a trip', dot: 'bg-signal' },
} as const;

/**
 * An approved driver's home: online or offline. Where the server says the driver is - their trip,
 * their vehicle, their approval - wins over this page, every time it loads.
 */
export function DriverHome() {
  const profile = useDriverProfile();
  const trip = useActiveTrip();

  if (profile.isPending || trip.isPending) return <Splash />;
  if (profile.isError || trip.isError) {
    return (
      <DarkScreen title="Driving" actions={<SignOutButton />}>
        <FormAlert message={describeError(profile.error ?? trip.error).message} />
        <Button
          surface="ink"
          variant="secondary"
          onClick={() => {
            void profile.refetch();
            void trip.refetch();
          }}
        >
          Try again
        </Button>
      </DarkScreen>
    );
  }

  const driver = profile.data;
  if (trip.data) return <Navigate to={`/driver/trip/${trip.data.id}`} replace />;
  if (!driver.vehicle) return <Navigate to="/driver/vehicle" replace />;
  if (driver.approvalStatus !== 'APPROVED') return <Navigate to="/driver/approval" replace />;

  const status = STATUS[driver.status ?? 'OFFLINE'];
  return (
    <DarkScreen title="Driving" actions={<SignOutButton />}>
      <section aria-live="polite" className="flex items-center gap-3 rounded-card bg-paper/6 p-4">
        <span aria-hidden="true" className={cx('size-3 rounded-full', status.dot)} />
        <span className="text-body font-semibold">{status.label}</span>
      </section>
      <OnlineToggle profile={driver} />
      <p className="text-footnote text-paper/70">
        Ride offers, and sharing your location while online, arrive in a later stage of this app.
      </p>
      <Link to="/driver/vehicle" className="self-start text-subhead text-paper underline">
        Your vehicle
      </Link>
    </DarkScreen>
  );
}
