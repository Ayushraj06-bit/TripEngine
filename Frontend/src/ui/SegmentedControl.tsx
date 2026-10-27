import { useRef, type KeyboardEvent } from 'react';
import type { Surface } from './Button';
import { cx } from './cx';

export interface Segment<T extends string> {
  value: T;
  label: string;
}

interface SegmentedControlProps<T extends string> {
  /** What is being chosen, for screen readers: "Account type". */
  label: string;
  segments: readonly Segment<T>[];
  value: T;
  onChange: (value: T) => void;
  surface?: Surface;
}

const TRACK: Record<Surface, string> = { paper: 'bg-wash', ink: 'bg-paper/10' };
const SELECTED: Record<Surface, string> = {
  paper: 'bg-paper text-ink ring-1 ring-mute/30',
  ink: 'bg-paper text-ink',
};
const UNSELECTED: Record<Surface, string> = { paper: 'text-ink-soft', ink: 'text-paper/80' };

/**
 * Apple's segmented control: one of a few, side by side in a capsule. A radio group to assistive
 * technology - one tab stop, the arrow keys move between segments and choose as they go.
 */
export function SegmentedControl<T extends string>({
  label,
  segments,
  value,
  onChange,
  surface = 'paper',
}: SegmentedControlProps<T>) {
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const step =
      event.key === 'ArrowRight' || event.key === 'ArrowDown'
        ? 1
        : event.key === 'ArrowLeft' || event.key === 'ArrowUp'
          ? -1
          : 0;
    if (step === 0) return;
    event.preventDefault();
    const next = (index + step + segments.length) % segments.length;
    const segment = segments[next];
    if (!segment) return;
    onChange(segment.value);
    buttons.current[next]?.focus();
  }

  return (
    <div
      role="radiogroup"
      aria-label={label}
      className={cx('flex rounded-full p-1', TRACK[surface])}
    >
      {segments.map((segment, index) => {
        const selected = segment.value === value;
        return (
          <button
            key={segment.value}
            ref={(element) => {
              buttons.current[index] = element;
            }}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => {
              onChange(segment.value);
            }}
            onKeyDown={(event) => {
              onKeyDown(event, index);
            }}
            className={cx(
              'min-h-11 flex-1 cursor-pointer rounded-full px-4 text-subhead font-semibold',
              'motion-safe:transition-colors motion-safe:duration-200',
              selected ? SELECTED[surface] : UNSELECTED[surface],
            )}
          >
            {segment.label}
          </button>
        );
      })}
    </div>
  );
}
