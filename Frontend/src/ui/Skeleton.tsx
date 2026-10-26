import { cx } from './cx';

/**
 * Where a value will be once it is known. Still, not shimmering: motion in this app is kept for
 * the things that are actually moving. The region it sits in should carry aria-busy.
 */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cx('rounded-input bg-wash', className)} />;
}
