import { cx } from './cx';

/**
 * Turns only while a request is in flight, and stops turning when the user asks for reduced
 * motion. With a label it is announced; inside a button that is already marked busy, it is not.
 */
export function Spinner({ label, size = 20 }: { label?: string; size?: 16 | 20 | 24 }) {
  const ring = (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className="motion-safe:animate-spin"
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
  if (!label) return ring;
  return (
    <span role="status" className={cx('inline-flex items-center')}>
      {ring}
      <span className="sr-only">{label}</span>
    </span>
  );
}
