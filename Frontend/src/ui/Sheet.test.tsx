import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { setMedia } from '@/test/media';
import { useLayout, WIDE_QUERY } from './layout';
import { Sheet, settle, sheetHeights, type SheetHeight } from './Sheet';

function Harness({ start = 'half' as SheetHeight }) {
  const [height, setHeight] = useState<SheetHeight>(start);
  return (
    <Sheet
      height={height}
      onHeightChange={setHeight}
      label="Choose a ride"
      footer={<button type="button">Confirm Auto</button>}
    >
      <p>Tiers</p>
    </Sheet>
  );
}

function sheet() {
  return screen.getByRole('region', { name: 'Choose a ride' });
}

function handle() {
  return screen.getByRole('button', { name: /^Resize panel/ });
}

beforeEach(() => {
  window.innerHeight = 844;
  useLayout.setState({ inset: { bottom: 0, left: 0 } });
});

describe('sheet heights', () => {
  it('fit a 844 px phone', () => {
    expect(sheetHeights(844)).toEqual({ peek: 176, half: 473, full: 796 });
  });

  it('never exceed a very short screen', () => {
    const heights = sheetHeights(400);
    expect(heights.full).toBe(352);
    expect(heights.half).toBeLessThanOrEqual(heights.full);
  });

  it('settle a drag on the nearest height, or the next one on a flick', () => {
    const heights = sheetHeights(844);
    expect(settle(500, heights, 0)).toBe('half');
    expect(settle(700, heights, 0)).toBe('full');
    expect(settle(480, heights, 1.2)).toBe('full');
    expect(settle(480, heights, -1.2)).toBe('peek');
  });
});

describe('Sheet', () => {
  it('holds its content and pins the primary button to the bottom', () => {
    render(<Harness />);
    expect(sheet()).toHaveAttribute('data-height', 'half');
    expect(sheet()).toHaveStyle({ height: '473px' });
    expect(screen.getByText('Tiers')).toBeInTheDocument();
    expect(sheet().lastElementChild).toContainElement(
      screen.getByRole('button', { name: 'Confirm Auto' }),
    );
  });

  it('has the one shadow and the rounded top corners', () => {
    render(<Harness />);
    expect(sheet()).toHaveClass('shadow-sheet', 'rounded-t-sheet', 'bg-paper');
  });

  it('steps through its heights from the keyboard', async () => {
    render(<Harness start="peek" />);
    handle().focus();
    await userEvent.keyboard('{ArrowUp}');
    expect(sheet()).toHaveAttribute('data-height', 'half');
    await userEvent.keyboard('{ArrowUp}{ArrowUp}');
    expect(sheet()).toHaveAttribute('data-height', 'full');
    await userEvent.keyboard('{ArrowDown}');
    expect(sheet()).toHaveAttribute('data-height', 'half');
  });

  it('cycles its heights when the handle is pressed, saying where it is', async () => {
    render(<Harness start="half" />);
    expect(handle()).toHaveAccessibleName('Resize panel, now half open');
    await userEvent.click(handle());
    expect(sheet()).toHaveAttribute('data-height', 'full');
    expect(handle()).toHaveAccessibleName('Resize panel, now open');
    await userEvent.click(handle());
    expect(sheet()).toHaveAttribute('data-height', 'peek');
  });

  it('follows a slow drag and settles where it is let go, without stepping again', () => {
    vi.useFakeTimers();
    render(<Harness start="peek" />);
    fireEvent.pointerDown(handle(), { clientY: 700, pointerId: 1 });
    fireEvent.pointerMove(handle(), { clientY: 400, pointerId: 1 });
    expect(sheet()).toHaveStyle({ height: '476px' });
    vi.advanceTimersByTime(900);
    fireEvent.pointerUp(handle(), { clientY: 400, pointerId: 1 });
    // The click a browser fires at the end of a drag.
    fireEvent.click(handle());
    expect(sheet()).toHaveAttribute('data-height', 'half');
    vi.useRealTimers();
  });

  it('carries a fast flick on to the next height', () => {
    vi.useFakeTimers();
    render(<Harness start="peek" />);
    fireEvent.pointerDown(handle(), { clientY: 700, pointerId: 1 });
    vi.advanceTimersByTime(120);
    fireEvent.pointerUp(handle(), { clientY: 600, pointerId: 1 });
    expect(sheet()).toHaveAttribute('data-height', 'half');
    vi.useRealTimers();
  });

  it('tells the map how much of it is covered', () => {
    render(<Harness start="half" />);
    expect(useLayout.getState().inset).toEqual({ bottom: 473, left: 0 });
  });

  it('becomes a side panel on a wide screen, uncovering the bottom of the map', () => {
    setMedia(WIDE_QUERY);
    render(<Harness />);
    expect(screen.queryByRole('button', { name: /^Resize panel/ })).toBeNull();
    expect(useLayout.getState().inset).toEqual({ bottom: 0, left: 436 });
  });

  it('moves smoothly only when motion is welcome', () => {
    render(<Harness />);
    expect(sheet()).toHaveClass('motion-safe:transition-[height]');
  });
});
