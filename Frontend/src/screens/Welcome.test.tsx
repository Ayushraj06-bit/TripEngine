import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderApp, resetApp, signInWith } from '@/test/app';
import { backendFor, INDIRANAGAR, placesAre, RIDER } from '@/test/backend';

beforeEach(resetApp);

describe('the welcome screen', () => {
  it('is where a signed-out visitor starts', async () => {
    const { router } = renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Go anywhere' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/welcome');
  });

  it('shows no prices before there is a fare to show', async () => {
    renderApp('/welcome');
    await screen.findByRole('heading', { name: 'Ways to ride' });
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });

  it('keeps where the rider is going through signing in, and looks it up', async () => {
    backendFor(RIDER);
    placesAre([INDIRANAGAR]);
    const user = userEvent.setup();
    const { router } = renderApp('/welcome');
    await user.type(await screen.findByLabelText('Where to?'), 'Indiranagar');
    await user.click(screen.getByRole('button', { name: 'See prices' }));

    expect(await screen.findByText('Sign in to see prices to Indiranagar.')).toBeInTheDocument();
    await signInWith('98765 43210', 'correct-horse', user);

    await waitFor(() => {
      expect(router.state.location.search).toBe('?step=where-to&field=drop');
    });
    expect(await screen.findByRole('searchbox', { name: 'Drop' })).toHaveValue('Indiranagar');
    expect(
      await screen.findByRole('list', { name: 'Places matching “Indiranagar”' }),
    ).toBeInTheDocument();
  });

  it('starts a would-be driver on a driver’s account', async () => {
    renderApp('/welcome');
    await userEvent.click(await screen.findByRole('link', { name: 'Sign up to drive' }));
    expect(await screen.findByRole('radio', { name: 'Driver' })).toBeChecked();
  });
});
