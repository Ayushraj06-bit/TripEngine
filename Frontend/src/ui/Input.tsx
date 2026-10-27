import type { InputHTMLAttributes, ReactNode } from 'react';
import type { Surface } from './Button';
import { cx } from './cx';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  /** On the white sheet, or on the dark welcome and sign-in screens. */
  surface?: Surface;
  /** A mark inside the field, before the text: the pickup's ring, the drop's square. */
  leading?: ReactNode;
  /** A control inside the field, after the text: show password, use my location. */
  trailing?: ReactNode;
}

const SURFACE: Record<Surface, string> = {
  paper: 'bg-wash text-ink placeholder:text-mute focus:border-ink',
  ink: 'bg-paper/10 text-paper placeholder:text-paper/50 focus:border-signal',
};

/** A text input, 17 px like an iOS text field. Use it inside a Field, which labels it. */
export function Input({ surface = 'paper', leading, trailing, className, ...rest }: InputProps) {
  const input = (
    <input
      className={cx(
        'min-h-12 w-full rounded-input border border-transparent px-3 text-body',
        'aria-[invalid=true]:border-stop disabled:opacity-50',
        SURFACE[surface],
        leading !== undefined && 'pl-11',
        trailing !== undefined && 'pr-12',
        className,
      )}
      {...rest}
    />
  );
  if (leading === undefined && trailing === undefined) return input;
  return (
    <div className="relative">
      {leading !== undefined && (
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 left-0 flex w-11 items-center justify-center"
        >
          {leading}
        </span>
      )}
      {input}
      {trailing !== undefined && (
        <span className="absolute inset-y-0 right-0 flex items-center pr-1">{trailing}</span>
      )}
    </div>
  );
}
