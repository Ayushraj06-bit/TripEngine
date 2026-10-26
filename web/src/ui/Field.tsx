import { useId, type ReactNode } from 'react';

/** What a Field hands its control, so the label, hint and error are announced with it. */
export interface FieldControlProps {
  id: string;
  'aria-describedby': string | undefined;
  'aria-invalid': true | undefined;
}

interface FieldProps {
  label: string;
  /** Stated up front - password rules, the expected format - not only after a failure. */
  hint?: string;
  /** Shown inline, under the control, the moment there is one. */
  error?: string | undefined;
  children: (control: FieldControlProps) => ReactNode;
}

export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-meta text-ink-soft">
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint && (
        <p id={hintId} className="text-meta text-ink-soft">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-meta text-stop">
          {error}
        </p>
      )}
    </div>
  );
}
