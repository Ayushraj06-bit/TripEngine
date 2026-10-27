import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { useBookingDraft } from '@/state/bookingDraft';
import { renderApp, resetApp, signedInAs, signInWith } from '@/test/app';
import { backendFor, problem, RIDER } from '@/test/backend';
import { server } from '@/test/server';

beforeEach(resetApp);

describe('a session that ends mid-use', () => {
  it('sends the rider to sign in, then back where they were, with their booking kept', async () => {
    backendFor(RIDER);
    signedInAs(RIDER);
    useBookingDraft.getState().setDropQuery('Kempegowda Airport');
    // The refresh cookie has expired too: backendFor answers every renewal with 401.
    let ended = true;
    server.use(
      http.get('/api/v1/trips/:tripId', ({ params }) => {
        if (params['tripId'] === 'active') return undefined;
        return ended
          ? problem(401, 'UNAUTHENTICATED')
          : HttpResponse.json({ id: 'trip-7', status: 'COMPLETED' });
      }),
    );

    const { router } = renderApp('/trip/trip-7');

    expect(
      await screen.findByText('Your session has ended. Sign in to continue.'),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/sign-in');
    });
    expect(router.state.location.search).toBe('?returnTo=%2Ftrip%2Ftrip-7');
    expect(useBookingDraft.getState().dropQuery).toBe('Kempegowda Airport');

    ended = false;
    await signInWith('98765 43210');
    expect(await screen.findByRole('heading', { name: 'Trip completed' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/trip/trip-7');
  });
});
