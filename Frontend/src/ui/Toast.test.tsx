import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Toaster } from './Toast';
import { TOAST_MS, toast, useToasts } from './toasts';

beforeEach(() => {
  useToasts.setState({ toasts: [] });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Toast', () => {
  it('announces information politely', () => {
    render(<Toaster />);
    act(() => {
      toast('Your driver’s connection is weak. The trip continues.');
    });
    expect(screen.getByRole('status')).toHaveTextContent('Your driver’s connection is weak.');
  });

  it('announces errors at once, marked in stop', () => {
    render(<Toaster />);
    act(() => {
      toast('No connection. Check your network and try again.', 'error');
    });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('No connection.');
    expect(alert.firstElementChild).toHaveClass('border-l-stop');
  });

  it('marks success in go', () => {
    render(<Toaster />);
    act(() => {
      toast('Wallet topped up', 'success');
    });
    expect(screen.getByText('Wallet topped up').parentElement).toHaveClass('border-l-go');
  });

  it('runs its action, then goes', async () => {
    const retry = vi.fn();
    render(<Toaster />);
    act(() => {
      toast('No connection.', 'error', { label: 'Retry', onClick: retry });
    });
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('No connection.')).toBeNull();
  });

  it('can be dismissed', async () => {
    render(<Toaster />);
    act(() => {
      toast('Wallet topped up', 'success');
    });
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText('Wallet topped up')).toBeNull();
  });

  it('goes on its own, errors staying longer', () => {
    vi.useFakeTimers();
    render(<Toaster />);
    act(() => {
      toast('Saved', 'success');
      toast('Failed.', 'error');
    });
    act(() => {
      vi.advanceTimersByTime(TOAST_MS.success);
    });
    expect(screen.queryByText('Saved')).toBeNull();
    expect(screen.getByText('Failed.')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(TOAST_MS.error - TOAST_MS.success);
    });
    expect(screen.queryByText('Failed.')).toBeNull();
  });

  it('keeps no more than three on screen', () => {
    render(<Toaster />);
    act(() => {
      ['one', 'two', 'three', 'four'].forEach((message) => toast(message));
    });
    expect(screen.queryByText('one')).toBeNull();
    expect(screen.getByText('four')).toBeInTheDocument();
  });
});
