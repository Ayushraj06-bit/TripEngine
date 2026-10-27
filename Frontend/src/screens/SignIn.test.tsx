import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useBookingDraft } from '@/state/bookingDraft';
import { renderApp, resetApp, screenAlert, signInWith } from '@/test/app';
import { activeTripIs, backendFor, problem, RIDER, seen } from '@/test/backend';
import { server } from '@/test/server';

beforeEach(resetApp);

afterEach(() => {
  vi.useRealTimers();
});

describe('signing in', () => {
  it('says why it is asked, when the rider wanted prices', async () => {
    useBookingDraft.getState().setDropQuery('Kempegowda Airport');
    renderApp('/sign-in?returnTo=%2F', { reason: 'see-prices' });
    expect(
      await screen.findByText('Sign in to see prices to Kempegowda Airport.'),
    ).toBeInTheDocument();
  });

  it.each(['98765 43210', '+91 98765-43210', '09876543210'])(
    'reads %s as +919876543210 and goes home',
    async (typed) => {
      backendFor(RIDER);
      const { router } = renderApp('/sign-in');
      await signInWith(typed);
      await waitFor(() => {
        expect(router.state.location.pathname).toBe('/');
      });
      expect(seen[0]?.body).toEqual({ phone: '+919876543210', password: 'correct-horse' });
    },
  );

  it('opens a ride under way rather than the map', async () => {
    backendFor(RIDER);
    activeTripIs('trip-7');
    const { router } = renderApp('/sign-in');
    await signInWith('98765 43210');
    expect(await screen.findByRole('heading', { name: 'On your way' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/trip/trip-7');
  });

  it.each([
    ['%2Fdesign-system', '/design-system'],
    ['%2F%2Fevil.example', '/'],
  ])('goes back to returnTo=%s only inside the app', async (returnTo, landed) => {
    backendFor(RIDER);
    const { router } = renderApp(`/sign-in?returnTo=${returnTo}`);
    await signInWith('98765 43210');
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(landed);
    });
  });

  it('checks the number before sending anything', async () => {
    renderApp('/sign-in');
    await signInWith('12345');
    expect(screen.getByLabelText('Phone number')).toHaveAccessibleDescription(
      'Enter a 10-digit mobile number, or include the country code.',
    );
    expect(seen).toHaveLength(0);
  });

  it('says when the password is wrong', async () => {
    backendFor(RIDER);
    renderApp('/sign-in');
    await signInWith('98765 43210', 'wrong-horse');
    expect(await screenAlert()).toHaveTextContent(
      'That phone number and password don’t match an account. Check both and try again.',
    );
  });

  it('keeps everything typed when the network drops', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.error()));
    renderApp('/sign-in');
    await signInWith('98765 43210');
    expect(await screenAlert()).toHaveTextContent(
      'No connection. Check your network and try again.',
    );
    expect(screen.getByLabelText('Phone number')).toHaveValue('98765 43210');
    expect(screen.getByLabelText('Password')).toHaveValue('correct-horse');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('says when a locked account unlocks, and waits until then', async () => {
    // 13:30 in India; the lock lifts 15 minutes later.
    vi.useFakeTimers({ now: new Date('2026-10-27T08:00:00Z'), shouldAdvanceTime: true });
    server.use(
      http.post('/api/v1/auth/login', () =>
        problem(
          401,
          'ACCOUNT_LOCKED',
          { retryAfterSeconds: 900, lockedUntil: '2026-10-27T08:15:00Z' },
          { 'Retry-After': '900' },
        ),
      ),
    );
    renderApp('/sign-in');
    await signInWith(
      '98765 43210',
      'correct-horse',
      userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) }),
    );

    expect(await screenAlert()).toHaveTextContent(
      'This account is locked after several failed sign-ins. Try again at 1:45 pm.',
    );
    expect(screen.getByRole('button', { name: 'Locked, unlocks in 15:00' })).toBeDisabled();
  });

  it('counts down after too many attempts, then lets the rider try again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(
      http.post('/api/v1/auth/login', () =>
        problem(429, 'RATE_LIMITED', {}, { 'Retry-After': '3' }),
      ),
    );
    renderApp('/sign-in');
    await signInWith(
      '98765 43210',
      'correct-horse',
      userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) }),
    );

    expect(await screenAlert()).toHaveTextContent(
      'Too many attempts in a short time. Try again in 3 seconds.',
    );
    expect(screen.getByRole('button', { name: 'Try again in 0:03' })).toBeDisabled();

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });
});
