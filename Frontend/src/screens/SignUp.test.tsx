import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderApp, resetApp } from '@/test/app';
import { backendFor, DRIVER, driverProfileIs, problem, RIDER, seen } from '@/test/backend';
import { server } from '@/test/server';

beforeEach(resetApp);

async function fillIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Name'), 'Asha Rao');
  await user.type(screen.getByLabelText('Phone number'), '98765 43210');
  await user.type(screen.getByLabelText('Password'), 'correct-horse');
}

describe('creating an account', () => {
  it('states the rules before anything is typed', async () => {
    renderApp('/sign-up');
    expect(await screen.findByLabelText('Password')).toHaveAccessibleDescription(
      '8 to 72 characters.',
    );
    expect(screen.getByLabelText('Phone number')).toHaveAccessibleDescription(
      '10 digits. +91 is added for you.',
    );
  });

  it('shows the number it will use, however it was typed', async () => {
    renderApp('/sign-up');
    await userEvent.type(await screen.findByLabelText('Phone number'), '09876543210');
    expect(screen.getByLabelText('Phone number')).toHaveAccessibleDescription(
      'We’ll use +91 98765 43210',
    );
  });

  it('marks each missing detail before sending anything', async () => {
    renderApp('/sign-up');
    await userEvent.click(await screen.findByRole('button', { name: 'Create account' }));
    expect(screen.getByLabelText('Name')).toHaveAccessibleDescription('Enter your name.');
    expect(screen.getByLabelText('Phone number')).toHaveAccessibleDescription(
      /Enter your mobile number\.$/,
    );
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      /Use 8 to 72 characters\.$/,
    );
    expect(seen).toHaveLength(0);
  });

  it('creates a rider and goes home', async () => {
    backendFor(RIDER);
    const user = userEvent.setup();
    const { router } = renderApp('/sign-up');
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
    expect(seen[0]).toMatchObject({
      path: '/api/v1/auth/register/rider',
      body: { name: 'Asha Rao', phone: '+919876543210', password: 'correct-horse' },
    });
  });

  it('creates a driver and asks for their vehicle next', async () => {
    backendFor(DRIVER);
    driverProfileIs({ approvalStatus: 'PENDING', status: 'OFFLINE' });
    const user = userEvent.setup();
    renderApp('/sign-up?as=driver');
    expect(await screen.findByRole('radio', { name: 'Driver' })).toBeChecked();
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByRole('heading', { name: 'Add your vehicle' })).toBeInTheDocument();
    expect(seen[0]?.path).toBe('/api/v1/auth/register/driver');
  });

  it('offers to sign in when the number already has an account, with it filled in', async () => {
    server.use(
      http.post('/api/v1/auth/register/:role', () => problem(409, 'PHONE_ALREADY_REGISTERED')),
    );
    const user = userEvent.setup();
    renderApp('/sign-up');
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await user.click(await screen.findByRole('link', { name: 'Sign in with +91 98765 43210' }));
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByLabelText('Phone number')).toHaveValue('+91 98765 43210');
  });

  it('shows the server’s objections under the fields they are about', async () => {
    server.use(
      http.post('/api/v1/auth/register/:role', () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'name', message: 'must not contain only symbols' }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderApp('/sign-up');
    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => {
      expect(screen.getByLabelText('Name')).toHaveAccessibleDescription(
        'Must not contain only symbols.',
      );
    });
  });
});
