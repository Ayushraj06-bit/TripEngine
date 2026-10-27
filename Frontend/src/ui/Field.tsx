import { useId, type ReactNode } from 'react';
import type { Surface } from './Button';
import { cx } from './cx';
import { Icon } from './Icon';

/** What a Field hands its control, so the label, hint and error are announced with it. */
export interface FieldControlProps {
  id: string;
  'aria-describedby': string | undefined;
  'aria-invalid': true | undefined;
}

interface FieldProps {
  label: string;
  /** Stated up front - password rules, the expected format - not only after a failure. */
  hint?: ReactNode;
  /** Shown inline, under the control, the moment there is one. */
  error?: string | undefined;
  surface?: Surface;
  children: (control: FieldControlProps) => ReactNode;
}

/**
 * A labelled control with its hint and error. On ink, the error is light text beside a stop-red
 * mark: stop-red text on ink falls short of the contrast small text needs.
 */
export function Field({ label, hint, error, surface = 'paper', children }: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  const quiet = surface === 'ink' ? 'text-paper/70' : 'text-ink-soft';
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className={cx('text-footnote', quiet)}>
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && (
        <p id={hintId} className={cx('text-footnote', quiet)}>
          {hint}
        </p>
      )}
      {error && (
        <p
          id={errorId}
          className={cx(
            'flex items-start gap-1.5 text-footnote',
            surface === 'ink' ? 'text-paper' : 'text-stop',
          )}
        >
          <span className="mt-px shrink-0 text-stop">
            <Icon name="alert" size={16} />
          </span>
          {error}
        </p>
      )}
    </div>
  );
}
