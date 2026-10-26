import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import { cx } from './cx';
import { useLayout, useMediaQuery, useViewportHeight, WIDE_QUERY } from './layout';

export type SheetHeight = 'peek' | 'half' | 'full';

const ORDER: readonly SheetHeight[] = ['peek', 'half', 'full'];
const SPOKEN: Record<SheetHeight, string> = { peek: 'collapsed', half: 'half open', full: 'open' };

/** The three heights in pixels for a viewport this tall. */
export function sheetHeights(viewport: number): Record<SheetHeight, number> {
  const full = Math.max(320, viewport - 48);
  const peek = Math.min(176, full);
  // Room for three tier cards, the payment row and the button on a 844 px phone.
  const half = Math.min(full, Math.max(peek + 120, Math.round(viewport * 0.56)));
  return { peek, half, full };
}

/** The height a drag released at `px` settles on: the nearest one, nudged by a flick. */
export function settle(
  px: number,
  heights: Record<SheetHeight, number>,
  velocity: number,
): SheetHeight {
  if (velocity > 0.6)
    return ORDER[Math.min(ORDER.length - 1, nearestIndex(px, heights) + 1)] ?? 'full';
  if (velocity < -0.6) return ORDER[Math.max(0, nearestIndex(px, heights) - 1)] ?? 'peek';
  return ORDER[nearestIndex(px, heights)] ?? 'half';
}

function nearestIndex(px: number, heights: Record<SheetHeight, number>): number {
  let best = 0;
  ORDER.forEach((height, index) => {
    if (Math.abs(heights[height] - px) < Math.abs(heights[ORDER[best] ?? 'peek'] - px))
      best = index;
  });
  return best;
}

function step(height: SheetHeight, by: 1 | -1): SheetHeight {
  const index = ORDER.indexOf(height) + by;
  return ORDER[Math.min(ORDER.length - 1, Math.max(0, index))] ?? height;
}

interface SheetProps {
  height: SheetHeight;
  onHeightChange: (height: SheetHeight) => void;
  /** What the sheet is for, for screen readers: "Choose a ride". */
  label: string;
  children: ReactNode;
  /** The one primary button, pinned to the bottom of the sheet. */
  footer?: ReactNode;
}

/**
 * Everything the user does sits in this sheet over the map. It moves only when the user moves
 * it - by dragging the handle, or with the keyboard on the handle: arrow up and down step through
 * the heights, Enter cycles them. Its size is published (layout.ts) so the map can keep pins in
 * the visible part. Moving between heights is the app's one orchestrated animation.
 */
export function Sheet({ height, onHeightChange, label, children, footer }: SheetProps) {
  const wide = useMediaQuery(WIDE_QUERY);
  const viewport = useViewportHeight();
  const heights = sheetHeights(viewport);
  const [dragPx, setDragPx] = useState<number | null>(null);
  const drag = useRef<{ startY: number; startPx: number; startT: number } | null>(null);
  // The click a browser fires at the end of a drag must not move the sheet a second time.
  const dragged = useRef(false);
  const setInset = useLayout((state) => state.setInset);
  const settled = heights[height];
  const shown = dragPx ?? settled;

  useEffect(() => {
    // From tablet width the sheet is a side panel: 420 px wide plus its 16 px margin.
    setInset(wide ? { bottom: 0, left: 436 } : { bottom: settled, left: 0 });
  }, [wide, settled, setInset]);

  function onPointerDown(event: PointerEvent<HTMLButtonElement>) {
    if (wide) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragged.current = false;
    drag.current = { startY: event.clientY, startPx: settled, startT: Date.now() };
  }

  function onPointerMove(event: PointerEvent<HTMLButtonElement>) {
    const current = drag.current;
    if (!current) return;
    const px = current.startPx - (event.clientY - current.startY);
    setDragPx(Math.min(heights.full, Math.max(heights.peek, px)));
  }

  function onPointerUp(event: PointerEvent<HTMLButtonElement>) {
    const current = drag.current;
    drag.current = null;
    if (!current) return;
    const moved = current.startY - event.clientY;
    const elapsed = Math.max(1, Date.now() - current.startT);
    setDragPx(null);
    // A tap is handled by onClick; only a real drag settles here.
    if (Math.abs(moved) < 6) return;
    dragged.current = true;
    const px = Math.min(heights.full, Math.max(heights.peek, current.startPx + moved));
    onHeightChange(settle(px, heights, moved / elapsed));
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      onHeightChange(step(height, 1));
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      onHeightChange(step(height, -1));
    }
  }

  return (
    <section
      aria-label={label}
      data-height={height}
      className={cx(
        'fixed inset-x-0 bottom-0 z-30 flex flex-col rounded-t-sheet bg-paper shadow-sheet',
        'md:top-4 md:bottom-4 md:left-4 md:w-105 md:rounded-sheet',
        dragPx === null &&
          'motion-safe:transition-[height] motion-safe:duration-300 motion-safe:ease-out',
      )}
      style={wide ? undefined : { height: shown }}
    >
      {!wide && (
        <button
          type="button"
          aria-label={`Resize panel, now ${SPOKEN[height]}`}
          aria-keyshortcuts="ArrowUp ArrowDown"
          // The whole top edge drags; the focus ring draws round the bar alone, not the full width.
          className="group flex min-h-11 w-full shrink-0 cursor-grab touch-none items-center justify-center rounded-t-sheet focus-visible:outline-none active:cursor-grabbing"
          onClick={() => {
            if (dragged.current) {
              dragged.current = false;
              return;
            }
            onHeightChange(ORDER[(ORDER.indexOf(height) + 1) % ORDER.length] ?? 'half');
          }}
          onKeyDown={onKeyDown}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={() => {
            drag.current = null;
            setDragPx(null);
          }}
        >
          <span
            aria-hidden="true"
            className="rounded-full px-3 py-2 group-focus-visible:outline-2 group-focus-visible:outline-ink"
          >
            <span className="block h-1 w-9 rounded-full bg-mute/50" />
          </span>
        </button>
      )}
      <div className={cx('min-h-0 flex-1 overflow-y-auto px-4', wide && 'pt-4')}>{children}</div>
      {footer && (
        <div className="shrink-0 px-4 pt-2 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {footer}
        </div>
      )}
    </section>
  );
}
