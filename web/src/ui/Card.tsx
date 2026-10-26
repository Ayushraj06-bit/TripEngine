import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cx } from './cx';

/**
 * A grouped block inside the sheet. Inset on wash or outlined - never a shadow: the sheet has the
 * only one.
 */
export function Card({
  children,
  outlined = false,
  className,
}: {
  children: ReactNode;
  outlined?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cx(
        'rounded-card p-4',
        outlined ? 'border border-mute/30 bg-paper' : 'bg-wash',
        className,
      )}
    >
      {children}
    </div>
  );
}

interface ChoiceCardProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'role'> {
  selected: boolean;
  children: ReactNode;
}

/**
 * One option among several - a ride tier. The selected one is outlined in signal; the group it
 * sits in carries role="radiogroup" and its label.
 */
export function ChoiceCard({ selected, children, className, ...rest }: ChoiceCardProps) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className={cx(
        'flex min-h-11 w-full items-center gap-3 rounded-card border-2 p-4 text-left',
        'cursor-pointer disabled:cursor-not-allowed disabled:text-mute',
        selected ? 'border-signal bg-paper' : 'border-transparent bg-wash active:bg-wash/60',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}
