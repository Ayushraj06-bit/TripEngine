import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clockText, Countdown, secondsLeft } from './Countdown';

const NOW = new Date('2026-10-27T10:00:00Z').getTime();

beforeEach(() => {
  vi.useFakeTimers({ now: NOW });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Countdown', () => {
  it.each([
    [299, '4:59'],
    [7, '0:07'],
    [3723, '1:02:03'],
    [0, '0:00'],
  ])('reads %i seconds as %s', (seconds, text) => {
    expect(clockText(seconds)).toBe(text);
  });

  it('rounds part of a second up, and never goes below zero', () => {
    expect(secondsLeft(NOW + 1500, NOW)).toBe(2);
    expect(secondsLeft(NOW - 5000, NOW)).toBe(0);
  });

  it('counts down in tabular figures, labelled for screen readers', () => {
    render(<Countdown until={NOW + 65_000} label="Prices valid for" />);
    const timer = screen.getByRole('timer');
    expect(timer).toHaveTextContent('1:05');
    expect(timer).toHaveClass('tabular-nums');
    expect(timer).toHaveAccessibleName('Prices valid for 1:05');

    act(() => {
      vi.advanceTimersByTime(6000);
    });
    expect(timer).toHaveTextContent('0:59');
  });

  it('takes the backend’s ISO timestamps', () => {
    render(<Countdown until={new Date(NOW + 30_000).toISOString()} label="Offer expires in" />);
    expect(screen.getByRole('timer')).toHaveTextContent('0:30');
  });

  it('says when it runs out, once', () => {
    const onElapsed = vi.fn();
    render(<Countdown until={NOW + 2000} label="Prices valid for" onElapsed={onElapsed} />);
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(onElapsed).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('timer')).toHaveTextContent('0:00');
  });

  it('is right again the moment a throttled tab wakes up', () => {
    render(<Countdown until={NOW + 300_000} label="Prices valid for" />);
    // A background tab may not run timers for minutes; the clock moves on regardless.
    vi.setSystemTime(NOW + 120_000);
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(screen.getByRole('timer')).toHaveTextContent('3:00');
  });
});
