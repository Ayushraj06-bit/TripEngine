import { Link, Navigate } from 'react-router';
import { describeError } from '@/api/errors';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { Icon } from '@/ui/Icon';
import { DarkScreen, FormAlert } from '../common';
import { SignOutButton } from '../SignOutButton';
import { OnlineToggle } from './OnlineToggle';
import { useDriverProfile } from '../queries';

const TYPE: Record<string, string> = { BIKE: 'Bike', AUTO: 'Auto', CAR: 'Car' };

function Step({ done, children }: { done: boolean; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-3">
      <span
        className={
          done
            ? 'flex size-7 shrink-0 items-center justify-center rounded-full bg-go text-paper'
            : 'flex size-7 shrink-0 items-center justify-center rounded-full border-2 border-paper/30'
        }
      >
        {done && <Icon name="check" size={16} />}
        <span className="sr-only">{done ? 'Done:' : 'Waiting:'}</span>
      </span>
      <span className="pt-0.5 text-body">{children}</span>
    </li>
  );
}

/**
 * Waiting for an admin's approval. What has happened, what happens next, and nothing invented
 * about how long it takes. The page asks again every half minute and whenever the driver comes
 * back to it, and moves on by itself once they are approved.
 */
export function Approval() {
  const profile = useDriverProfile({ refetchInterval: 30_000 });

  if (profile.isPending) return <Splash />;
  if (profile.isError) {
    return (
      <DarkScreen title="Your application" actions={<SignOutButton />}>
        <FormAlert message={describeError(profile.error).message} />
        <Button
          surface="ink"
          variant="secondary"
          onClick={() => {
            void profile.refetch();
          }}
        >
          Try again
        </Button>
      </DarkScreen>
    );
  }

  const driver = profile.data;
  if (!driver.vehicle) return <Navigate to="/driver/vehicle" replace />;
  if (driver.approvalStatus === 'APPROVED') return <Navigate to="/driver" replace />;

  if (driver.approvalStatus === 'REJECTED') {
    return (
      <DarkScreen
        title="Your application wasn’t approved"
        lead="RideFlow reviewed your details and can’t approve this account."
        actions={<SignOutButton />}
      >
        {driver.rejectionReason && (
          <section aria-label="Reason" className="rounded-card bg-paper/6 p-4">
            <p className="text-footnote text-paper/70">Reason given</p>
            <p className="text-body">{driver.rejectionReason}</p>
          </section>
        )}
        <OnlineToggle profile={driver} />
      </DarkScreen>
    );
  }

  return (
    <DarkScreen
      title="We’re checking your details"
      lead="An admin approves every new driver before they go online. This page updates on its own."
      actions={<SignOutButton />}
    >
      <ol className="flex flex-col gap-4" aria-label="Your progress">
        <Step done>Account created</Step>
        <Step done>
          Vehicle added:{' '}
          <span className="tabular-nums">
            {TYPE[driver.vehicle.type ?? ''] ?? 'Vehicle'} {driver.vehicle.plateNumber}
          </span>
        </Step>
        <Step done={false}>Approval by RideFlow</Step>
      </ol>
      <Link
        to="/driver/vehicle"
        className="flex min-h-11 items-center self-start rounded-full bg-paper/12 px-5 text-body font-semibold"
      >
        Change vehicle
      </Link>
      <OnlineToggle profile={driver} />
    </DarkScreen>
  );
}
