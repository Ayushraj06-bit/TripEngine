import { Link, Navigate } from 'react-router';
import { describeError } from '@/api/errors';
import { Splash } from '@/auth/guards';
import { useLive } from '@/realtime/live';
import { Button } from '@/ui/Button';
import { cx } from '@/ui/cx';
import { BarLink, DarkScreen, FormAlert } from '../common';
import { useActiveTrip, useDriverProfile } from '../queries';
import { SignOutButton } from '../SignOutButton';
import { OfferCard } from './OfferCard';
import { OnlineToggle } from './OnlineToggle';
import { useOffer } from './useOffer';
import { useSharing } from './DriverPresence';
import { ROUGHEST_METRES, type Sharing } from './useShareLocation';

const STATUS = {
  OFFLINE: { label: 'You’re offline', dot: 'bg-mute' },
  AVAILABLE: { label: 'You’re online', dot: 'bg-go' },
  ON_TRIP: { label: 'You’re on a trip', dot: 'bg-signal' },
} as const;

/**
 * What sharing the driver's position means right now, in words - never a silent failure. A
 * position found but not sent, while the connection is down, is not "sharing".
 */
function sharingWords(sharing: Sharing, connected: boolean): string | null {
  if (!connected && sharing.state !== 'off' && sharing.state !== 'failed') {
    return 'Not connected to RideFlow. Riders can’t be matched to you until the connection is back.';
  }
  switch (sharing.state) {
    case 'off':
      return null;
    case 'finding':
      return 'Finding your location…';
    case 'sharing':
      return 'Sharing your location with RideFlow while you’re online.';
    case 'rough':
      return `Your location is too rough to share (within ${String(Math.round(sharing.accuracyMetres))} m; RideFlow needs ${String(ROUGHEST_METRES)} m). Move into the open, or turn on precise location.`;
    case 'failed':
      return sharing.reason === 'denied'
        ? 'Location is off for RideFlow. Riders can’t be matched to you until it’s on.'
        : sharing.reason === 'insecure'
          ? 'Location needs a secure page (https), so it can’t be shared from here.'
          : 'Your location isn’t available. Riders can’t be matched to you until it is.';
  }
}

/**
 * An approved driver's home: online or offline, sharing their position while online, and the ride
 * offered to them. Where the server says the driver is - their trip, their vehicle, their approval
 * - wins over this page, every time it loads.
 */
export function DriverHome() {
  const profile = useDriverProfile();
  const trip = useActiveTrip();
  const driver = profile.data;
  const online =
    driver?.approvalStatus === 'APPROVED' && driver.status === 'AVAILABLE' && !trip.data;
  const sharing = useSharing();
  const offer = useOffer(online);
  // A renewal of the session's token is a blink, not an outage.
  const connected = useLive((state) => state.status === 'open' || state.status === 'renewing');

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

  if (trip.data) return <Navigate to={`/driver/trip/${trip.data.id}`} replace />;
  if (!profile.data.vehicle) return <Navigate to="/driver/vehicle" replace />;
  if (profile.data.approvalStatus !== 'APPROVED') return <Navigate to="/driver/approval" replace />;

  const status = STATUS[profile.data.status ?? 'OFFLINE'];
  const words = sharingWords(sharing, connected);
  const from = sharing.state === 'sharing' || sharing.state === 'rough' ? sharing.at : null;

  return (
    <DarkScreen
      title="Driving"
      actions={
        <>
          <BarLink to="/driver/earnings">Earnings</BarLink>
          <SignOutButton />
        </>
      }
    >
      {online && offer.data && (
        <OfferCard key={offer.data.offerId} offer={offer.data} from={from} />
      )}
      <section aria-live="polite" className="flex flex-col gap-2 rounded-card bg-paper/6 p-4">
        <span className="flex items-center gap-3">
          <span aria-hidden="true" className={cx('size-3 rounded-full', status.dot)} />
          <span className="text-body font-semibold">{status.label}</span>
        </span>
        {words && <span className="text-subhead text-paper/70">{words}</span>}
      </section>
      <OnlineToggle profile={profile.data} />
      {online && !offer.data && (
        <p className="text-footnote text-paper/70">
          Ride offers appear here as they come. Each one waits a few seconds for your answer.
        </p>
      )}
      <Link to="/driver/vehicle" className="self-start text-subhead text-paper underline">
        Your vehicle
      </Link>
    </DarkScreen>
  );
}
