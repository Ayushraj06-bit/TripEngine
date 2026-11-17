import { cx } from './cx';

const WHOLE = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
});
const EXACT = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * Paise as rupees, Indian grouping: 124000 → ₹1,240, 8450 → ₹84.50. Money is integer paise all
 * the way from the backend (PROJECT_SPEC section 11); this is the only place it becomes text.
 */
export function formatRupees(paise: number): string {
  return (paise % 100 === 0 ? WHOLE : EXACT).format(paise / 100);
}

/** A balance that may be below zero, signed with a true minus: −₹50, not -₹50. */
export function formatBalance(paise: number): string {
  return paise < 0 ? `−${formatRupees(-paise)}` : formatRupees(paise);
}

export function Money({ paise, className }: { paise: number; className?: string }) {
  return <span className={cx('tabular-nums', className)}>{formatRupees(paise)}</span>;
}
