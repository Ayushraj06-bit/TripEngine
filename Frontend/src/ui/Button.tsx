import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';
import { Spinner } from './Spinner';

type Variant = 'primary' | 'secondary' | 'quiet';
/** What the button sits on: the white sheet, or an ink surface. */
export type Surface = 'paper' | 'ink';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  surface?: Surface;
  /** 50 px: the one primary button pinned to the bottom of a sheet or screen, as on iOS. */
  size?: 'regular' | 'large';
  /** A capsule rather than a rounded rectangle: chips and the buttons in a top bar. */
  capsule?: boolean;
  /** In flight: the label stays, so the button keeps its width, and it cannot be pressed again. */
  loading?: boolean;
  /** Full width. */
  block?: boolean;
  children: ReactNode;
}

const VARIANTS: Record<Surface, Record<Variant, string>> = {
  paper: {
    // Ink on the white sheet: amber stays reserved for what is selected or live.
    primary: 'bg-ink text-paper active:bg-ink-soft',
    secondary: 'bg-wash text-ink active:bg-mute/25',
    quiet: 'bg-transparent text-ink active:bg-wash',
  },
  ink: {
    primary: 'bg-signal text-ink active:bg-signal/85',
    // Apple's grey fill on dark.
    secondary: 'bg-paper/12 text-paper active:bg-paper/20',
    quiet: 'bg-transparent text-paper active:bg-paper/10',
  },
};

const DISABLED: Record<Surface, string> = {
  paper: 'disabled:bg-wash disabled:text-mute',
  ink: 'disabled:bg-paper/8 disabled:text-paper/40',
};

export function Button({
  variant = 'primary',
  surface = 'paper',
  size = 'regular',
  capsule = false,
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
        // 44 px at least: the minimum tap target.
        'inline-flex items-center justify-center gap-2 px-5 text-body font-semibold',
        size === 'large' ? 'min-h-12.5' : 'min-h-11',
        capsule ? 'rounded-full' : 'rounded-card',
        VARIANTS[surface][variant],
        // Busy is not unavailable: a loading button keeps its colour, only the cursor waits.
        loading ? 'cursor-progress' : 'cursor-pointer disabled:cursor-not-allowed',
        disabled === true && DISABLED[surface],
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
