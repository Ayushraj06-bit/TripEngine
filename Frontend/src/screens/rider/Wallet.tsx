import { describeError } from '@/api/errors';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { formatRupees } from '@/ui/Money';
import { BarLink, DarkScreen, FormAlert } from '../common';
import { Movements, useWalletPages } from '../Movements';
import { SignOutButton } from '../SignOutButton';

/** The rider's wallet: what is in it, and what went in and out. */
export function Wallet() {
  const pages = useWalletPages();
  if (pages.isPending) return <Splash />;

  const actions = (
    <>
      <BarLink to="/">Done</BarLink>
      <SignOutButton />
    </>
  );
  if (pages.isError) {
    return (
      <DarkScreen title="Wallet" actions={actions}>
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
    <DarkScreen title="Wallet" actions={actions}>
      <section aria-label="Balance" className="flex flex-col gap-1 rounded-card bg-paper/6 p-4">
        <p className="text-footnote text-paper/70">Balance</p>
        <p className="text-large font-semibold tabular-nums">
          {balance < 0 ? `−${formatRupees(-balance)}` : formatRupees(balance)}
        </p>
        {balance < 0 && (
          // The backend refuses every booking, cash ones too, until the wallet is back to zero.
          <p className="text-body">
            An earlier ride took your wallet below zero. Add {formatRupees(-balance)} or more to
            book again.
          </p>
        )}
      </section>
      <Movements pages={pages} />
    </DarkScreen>
  );
}
