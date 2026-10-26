import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import { Spinner } from './Spinner';

type Variant = 'primary' | 'secondary' | 'quiet';
/** What the button sits on: the white sheet, or an ink surface over the map. */
type Surface = 'paper' | 'ink';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  surface?: Surface;
  /** In flight: the label stays, so the button keeps its width, and it cannot be pressed again. */
  loading?: boolean;
  /** Full width: the primary button pinned at the bottom of the sheet. */
  block?: boolean;
  children: ReactNode;
}

const VARIANTS: Record<Surface, Record<Variant, string>> = {
  paper: {
    // Ink on the white sheet: amber stays reserved for what is selected or live.
    primary: 'bg-ink text-paper active:bg-ink-soft text-emph',
    secondary: 'bg-paper text-ink border border-mute/40 active:bg-wash text-body',
    quiet: 'bg-transparent text-ink-soft active:bg-wash text-body',
  },
  ink: {
    primary: 'bg-signal text-ink active:bg-signal/85 text-emph',
    secondary: 'bg-transparent text-paper border border-paper/40 active:bg-paper/10 text-body',
    quiet: 'bg-transparent text-paper/80 active:bg-paper/10 text-body',
  },
};

const DISABLED: Record<Variant, string> = {
  primary: 'disabled:bg-wash disabled:text-mute',
  secondary: 'disabled:text-mute disabled:border-wash',
  quiet: 'disabled:text-mute',
};

export function Button({
  variant = 'primary',
  surface = 'paper',
  loading = false,
  block = false,
  disabled,
  className,
  children,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled === true || loading}
      aria-busy={loading || undefined}
      className={cx(
        // 44 px: the minimum tap target.
        'inline-flex min-h-11 items-center justify-center gap-2 rounded-card px-5 font-semibold',
        VARIANTS[surface][variant],
        // Busy is not unavailable: a loading button keeps its colour, only the cursor waits.
        loading ? 'cursor-progress' : 'cursor-pointer disabled:cursor-not-allowed',
        disabled === true && DISABLED[variant],
        block && 'w-full',
        className,
      )}
      {...rest}
    >
      {loading && <Spinner size={16} />}
      {children}
    </button>
  );
}
