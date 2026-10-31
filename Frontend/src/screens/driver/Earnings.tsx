import { describeError } from '@/api/errors';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { formatRupees } from '@/ui/Money';
import { BarLink, DarkScreen, FormAlert } from '../common';
import { Movements, useWalletPages } from '../Movements';
import { SignOutButton } from '../SignOutButton';

/**
 * The driver's earnings account: fares paid by wallet come in after RideFlow's commission, and
 * RideFlow's commission on cash rides - the driver collected all of it - goes out. Read only.
 */
export function Earnings() {
  const pages = useWalletPages();
  if (pages.isPending) return <Splash />;

  const actions = (
    <>
      <BarLink to="/driver">Done</BarLink>
      <SignOutButton />
    </>
  );
  if (pages.isError) {
    return (
      <DarkScreen title="Earnings" actions={actions}>
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

  const balance = pages.data.pages[0]?.balancePaise ?? 0;
  return (
    <DarkScreen title="Earnings" actions={actions}>
      <section aria-label="Balance" className="flex flex-col gap-1 rounded-card bg-paper/6 p-4">
        <p className="text-footnote text-paper/70">Balance</p>
        <p className="text-large font-semibold tabular-nums">
          {balance < 0 ? `−${formatRupees(-balance)}` : formatRupees(balance)}
        </p>
        <p className="text-body">
          {balance < 0
            ? 'You owe RideFlow this in commission on cash rides.'
            : 'RideFlow owes you this from rides paid by wallet, after its commission.'}
        </p>
      </section>
      <Movements pages={pages} />
    </DarkScreen>
  );
}
