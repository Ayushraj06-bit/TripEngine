import type { ReactNode } from 'react';
import { cx } from './cx';

interface RowProps {
  label: string;
  /** Null while the value is not yet known: the row shows that honestly, never a placeholder. */
  value: ReactNode;
  /** A trailing action, such as "Change". */
  action?: ReactNode;
  /** Figures that count or change: fares, ETAs, balances. */
  numeric?: boolean;
}

/** One labelled line in the sheet: "Pickup  Connaught Place". */
export function Row({ label, value, action, numeric = false }: RowProps) {
  return (
    <div className="flex min-h-11 items-center gap-3">
      <span className="w-16 shrink-0 text-footnote text-ink-soft">{label}</span>
      <span className={cx('min-w-0 flex-1 truncate text-body text-ink', numeric && 'tabular-nums')}>
        {value}
      </span>
      {action}
    </div>
  );
}
