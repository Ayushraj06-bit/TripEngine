import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { Surface } from './Button';
import { cx } from './cx';

/**
 * The bar across the top: the name, and at most two actions. Frosted glass, so whatever is beneath -
 * the map, the welcome canvas - shows through as the page scrolls under it.
 */
export function TopBar({ surface = 'ink', actions }: { surface?: Surface; actions?: ReactNode }) {
  return (
    <header
      data-surface={surface}
      className={cx(
        'sticky top-0 z-30 flex min-h-14 items-center justify-between gap-4 px-4 pt-[env(safe-area-inset-top)]',
        surface === 'ink' ? 'material-ink text-paper' : 'material text-ink',
      )}
    >
      <Link
        to="/welcome"
        className="flex min-h-11 items-center rounded-input text-title font-semibold"
      >
        RideFlow
      </Link>
      {actions && <nav className="flex items-center gap-2">{actions}</nav>}
    </header>
  );
}
