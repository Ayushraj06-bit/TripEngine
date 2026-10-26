import type { InputHTMLAttributes } from 'react';
import { cx } from './cx';

export type InputProps = InputHTMLAttributes<HTMLInputElement>;

/** A text input. Use it inside a Field, which gives it a label and its error. */
export function Input({ className, ...rest }: InputProps) {
  return (
    <input
      className={cx(
        'min-h-11 w-full rounded-input border border-transparent bg-wash px-3 text-body text-ink',
        'placeholder:text-mute focus:border-ink',
        'aria-[invalid=true]:border-stop disabled:text-mute',
        className,
      )}
      {...rest}
    />
  );
}
