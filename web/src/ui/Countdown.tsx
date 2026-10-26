import { useEffect, useEffectEvent, useState } from 'react';
import { cx } from './cx';

/** Whole seconds left until `until`, never below zero. */
export function secondsLeft(until: number, now: number): number {
  return Math.max(0, Math.ceil((until - now) / 1000));
}

/** 4:59, 0:07, 1:02:03. */
export function clockText(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

interface CountdownProps {
  /** The moment it reaches zero: an ISO string from the backend, or epoch milliseconds. */
  until: string | number;
  /** What is counting down, for screen readers: "Prices valid for". */
  label: string;
  onElapsed?: () => void;
  className?: string;
}

/**
 * Time left, in tabular figures so the digits do not shift as they count. Computed from the clock
 * on every tick rather than decremented, so a throttled background tab is right when it returns.
 * role="timer" is not announced on every tick; it is read when the user reaches it.
 */
export function Countdown({ until, label, onElapsed, className }: CountdownProps) {
  const target = typeof until === 'number' ? until : Date.parse(until);
  const [left, setLeft] = useState(() => secondsLeft(target, Date.now()));
  const elapsed = useEffectEvent(() => {
    onElapsed?.();
  });

  useEffect(() => {
    let fired = false;
    const tick = () => {
      const remaining = secondsLeft(target, Date.now());
      setLeft(remaining);
      if (remaining === 0 && !fired) {
        fired = true;
        elapsed();
      }
    };
    tick();
    const timer = setInterval(tick, 500);
    return () => {
      clearInterval(timer);
    };
  }, [target]);

  return (
    <span
      role="timer"
      aria-label={`${label} ${clockText(left)}`}
      className={cx('tabular-nums', className)}
    >
      {clockText(left)}
    </span>
  );
}
