import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '@/state/session';
import { renderApp, resetApp, signedInAs } from '@/test/app';
import { backendFor, DRIVER, driverProfileIs, RIDER, VEHICLE } from '@/test/backend';

beforeEach(resetApp);

describe('who sees what', () => {
  it('shows only the splash while a reload renews the session', async () => {
    useSession.setState({ status: 'restoring' });
    renderApp('/driver');
    expect(await screen.findByRole('main', { name: 'Loading RideFlow' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
  });

  it('sends the signed-out to sign in, to come back afterwards', async () => {
    const { router } = renderApp('/driver/approval');
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/sign-in');
    });
    expect(router.state.location.search).toBe('?returnTo=%2Fdriver%2Fapproval');
  });

  it('sends someone signed in from the public pages to their own home', async () => {
    backendFor(DRIVER);
    driverProfileIs({ approvalStatus: 'APPROVED', status: 'OFFLINE', vehicle: VEHICLE });
    signedInAs(DRIVER);
    const { router } = renderApp('/sign-in');
    expect(await screen.findByRole('heading', { name: 'Driving' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/driver');
  });

  it('keeps each role to its own screens', async () => {
    backendFor(RIDER);
    signedInAs(RIDER);
    const { router } = renderApp('/driver/approval');
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
  });
});
