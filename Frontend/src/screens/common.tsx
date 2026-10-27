import { useState, type ReactNode } from 'react';
import type { Surface } from '@/ui/Button';
import { cx } from '@/ui/cx';
import { Icon } from '@/ui/Icon';
import { Input, type InputProps } from '@/ui/Input';
import { TopBar } from '@/ui/TopBar';

/**
 * The dark screens - signing in, creating an account, a driver's onboarding: the ink canvas, the
 * frosted bar, a large title, one column of content. Apple's large-title layout, at phone width
 * first and centred on wider screens.
 */
export function DarkScreen({
  title,
  lead,
  actions,
  children,
}: {
  title: string;
  lead?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div data-surface="ink" className="min-h-dvh bg-ink text-paper">
      <title>{`${title} · RideFlow`}</title>
      <TopBar surface="ink" actions={actions} />
      <main className="mx-auto flex w-full max-w-md flex-col gap-6 px-4 pt-6 pb-[max(2rem,env(safe-area-inset-bottom))]">
        <header className="flex flex-col gap-2">
          <h1 className="text-large font-semibold">{title}</h1>
          {lead && <p className="text-subhead text-paper/70">{lead}</p>}
        </header>
        {children}
      </main>
    </div>
  );
}

/** What went wrong with the whole form, announced as it appears. */
export function FormAlert({ message, surface = 'ink' }: { message: ReactNode; surface?: Surface }) {
  return (
    <div
      role="alert"
      className={cx(
        'flex items-start gap-2 rounded-card p-3 text-subhead',
        surface === 'ink' ? 'bg-stop/20 text-paper' : 'bg-stop/10 text-ink',
      )}
    >
      <span className="mt-0.5 shrink-0 text-stop">
        <Icon name="alert" />
      </span>
      <div className="flex flex-col gap-1">{message}</div>
    </div>
  );
}

/** A password field that can be shown, so a long password can be checked before it is sent. */
export function PasswordInput(props: Omit<InputProps, 'type' | 'trailing'>) {
  const [shown, setShown] = useState(false);
  return (
    <Input
      {...props}
      type={shown ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          aria-label="Show password"
          aria-pressed={shown}
          onClick={() => {
            setShown(!shown);
          }}
          className={cx(
            'flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-input',
            props.surface === 'ink' ? 'text-paper/70' : 'text-ink-soft',
          )}
        >
          <Icon name={shown ? 'eyeOff' : 'eye'} />
        </button>
      }
    />
  );
}
