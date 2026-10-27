import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Schemas } from '@/api/types';
import { renderApp, resetApp, screenAlert, signedInAs } from '@/test/app';
import {
  activeTripIs,
  backendFor,
  DRIVER,
  driverProfileIs,
  problem,
  VEHICLE,
} from '@/test/backend';
import { server } from '@/test/server';
import { cannotGoOnline } from './OnlineToggle';

type Profile = Schemas['DriverProfile'];

const PENDING: Profile = { approvalStatus: 'PENDING', status: 'OFFLINE' };
const APPROVED: Profile = { approvalStatus: 'APPROVED', status: 'OFFLINE', vehicle: VEHICLE };

beforeEach(() => {
  resetApp();
  backendFor(DRIVER);
  signedInAs(DRIVER);
});

/** A driver profile the server changes as the driver acts on it. */
function profileServer(initial: Profile) {
  let profile = initial;
  server.use(
    http.get('/api/v1/drivers/me', () => HttpResponse.json(profile)),
    http.put('/api/v1/drivers/me/vehicle', async ({ request }) => {
      profile = { ...profile, vehicle: (await request.json()) as Profile['vehicle'] };
      return HttpResponse.json(profile);
    }),
    http.post('/api/v1/drivers/me/online', () => {
      profile = { ...profile, status: 'AVAILABLE' };
      return HttpResponse.json(profile);
    }),
  );
}

describe('a new driver', () => {
  it('adds their vehicle, then waits for approval', async () => {
    profileServer(PENDING);
    const user = userEvent.setup();
    renderApp('/driver/vehicle');
    await user.click(await screen.findByRole('radio', { name: 'Auto' }));
    await user.type(screen.getByLabelText('Plate number'), 'KA01AB1234');
    await user.type(screen.getByLabelText('Model'), 'Bajaj RE');
    await user.type(screen.getByLabelText('Colour'), 'Green');
    await user.click(screen.getByRole('button', { name: 'Add vehicle' }));

    expect(
      await screen.findByRole('heading', { name: 'We’re checking your details' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Auto KA01AB1234')).toBeInTheDocument();
  });

  it('marks a plate the server refuses', async () => {
    driverProfileIs(PENDING);
    server.use(
      http.put('/api/v1/drivers/me/vehicle', () => problem(409, 'PLATE_ALREADY_REGISTERED')),
    );
    const user = userEvent.setup();
    renderApp('/driver/vehicle');
    await user.type(await screen.findByLabelText('Plate number'), 'KA01AB1234');
    await user.type(screen.getByLabelText('Model'), 'Bajaj RE');
    await user.type(screen.getByLabelText('Colour'), 'Green');
    await user.click(screen.getByRole('button', { name: 'Add vehicle' }));

    expect(await screen.findByText(/This plate is registered to another driver/)).toBeVisible();
    expect(screen.getByLabelText('Plate number')).toHaveAttribute('aria-invalid', 'true');
  });

  it('is asked for a vehicle before anything else', async () => {
    driverProfileIs(PENDING);
    renderApp('/driver/approval');
    expect(await screen.findByRole('heading', { name: 'Add your vehicle' })).toBeInTheDocument();
  });

  it('cannot go online before approval, and is told why', async () => {
    driverProfileIs({ ...PENDING, vehicle: VEHICLE });
    renderApp('/driver/approval');
    const goOnline = await screen.findByRole('button', { name: 'Go online' });
    expect(goOnline).toBeDisabled();
    expect(goOnline).toHaveAccessibleDescription('You can go online once you’re approved.');
  });

  it('reads the reason an application was turned down', async () => {
    driverProfileIs({
      approvalStatus: 'REJECTED',
      status: 'OFFLINE',
      vehicle: VEHICLE,
      rejectionReason: 'The plate in the photo is unreadable.',
    });
    renderApp('/driver/approval');
    expect(
      await screen.findByRole('heading', { name: 'Your application wasn’t approved' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Reason' })).toHaveTextContent(
      'The plate in the photo is unreadable.',
    );
  });
});

describe('an approved driver', () => {
  it('goes online', async () => {
    profileServer(APPROVED);
    renderApp('/driver');
    await userEvent.click(await screen.findByRole('button', { name: 'Go online' }));
    expect(await screen.findByText('You’re online')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go offline' })).toBeEnabled();
  });

  it('is told why, when the server refuses', async () => {
    driverProfileIs(APPROVED);
    server.use(http.post('/api/v1/drivers/me/online', () => problem(409, 'DRIVER_NOT_APPROVED')));
    renderApp('/driver');
    await userEvent.click(await screen.findByRole('button', { name: 'Go online' }));
    expect(await screenAlert()).toHaveTextContent(
      'Your account is still being reviewed. You can go online once it’s approved.',
    );
  });

  it('opens straight into a trip under way', async () => {
    driverProfileIs({ ...APPROVED, status: 'ON_TRIP' });
    activeTripIs('trip-9', 'DRIVER_ASSIGNED');
    const { router } = renderApp('/driver');
    expect(await screen.findByRole('heading', { name: 'Head to the pickup' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/driver/trip/trip-9');
  });
});

describe('the reason a driver cannot go online', () => {
  it('names the first thing in the way', () => {
    expect(cannotGoOnline(PENDING)).toBe('Add your vehicle before going online.');
    expect(cannotGoOnline({ ...PENDING, vehicle: VEHICLE })).toBe(
      'You can go online once you’re approved.',
    );
    expect(cannotGoOnline({ ...APPROVED, status: 'ON_TRIP' })).toBe(
      'You’re on a trip. Finish it before going offline.',
    );
    expect(cannotGoOnline(APPROVED)).toBeNull();
  });
});
