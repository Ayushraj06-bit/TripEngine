import type { Surface } from './Button';
import { cx } from './cx';

/**
 * Where a value will be once it is known. Still, not shimmering: motion in this app is kept for
 * the things that are actually moving. The region it sits in should carry aria-busy.
 */
export function Skeleton({
  className,
  surface = 'paper',
}: {
  className?: string;
  surface?: Surface;
}) {
  return (
    <div
      aria-hidden="true"
      className={cx('rounded-input', surface === 'ink' ? 'bg-paper/8' : 'bg-wash', className)}
    />
  );
}
