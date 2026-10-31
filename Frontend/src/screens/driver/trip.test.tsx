import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Schemas } from '@/api/types';
import { TRIP_UPDATES } from '@/realtime/useTripLive';
import { deviceAt, renderApp, resetApp, screenAlert, signedInAs } from '@/test/app';
import {
  at,
  backendFor,
  cancellingAnswers,
  DRIVER,
  driverProfileIs,
  INDIRANAGAR,
  MG_ROAD,
  problem,
  seen,
  trip,
  tripAction,
  tripAnswers,
  VEHICLE,
} from '@/test/backend';
import { liveServer } from '@/test/liveServer';
import { LOCATION_DESTINATION } from './useShareLocation';

const STOPS = {
  pickup: at(MG_ROAD),
  drop: at(INDIRANAGAR),
  vehicleType: 'CAR',
  paymentMethod: 'CASH',
  quotedFarePaise: 30900,
} as const;

function onTrip(status: Schemas['TripView']['status'], extra: Partial<Schemas['TripView']> = {}) {
  return trip('trip-9', status, { ...STOPS, ...extra });
}

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

beforeEach(() => {
  resetApp();
  backendFor(DRIVER);
  signedInAs(DRIVER);
  driverProfileIs({ approvalStatus: 'APPROVED', status: 'ON_TRIP', vehicle: VEHICLE });
  deviceAt(at(MG_ROAD));
});

describe('on the way to the pickup', () => {
  it('keeps sharing the driver’s position on the trip screen', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/driver/trip/trip-9');
    await screen.findByRole('heading', { name: 'Head to the pickup' });
    await waitFor(() => {
      expect(liveServer.sentTo(LOCATION_DESTINATION).length).toBeGreaterThan(0);
    });
  });

  it('shows the driver on the map, with directions to the pickup, then the drop', async () => {
    const pickup = at(MG_ROAD);
    const drop = at(INDIRANAGAR);
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/driver/trip/trip-9');
    const directions = await screen.findByRole('link', {
      name: 'Open in Maps for directions to the pickup',
    });
    expect(directions).toHaveAttribute(
      'href',
      `https://www.google.com/maps/dir/?api=1&destination=${String(pickup.lat)},${String(pickup.lng)}&travelmode=driving`,
    );
    expect(directions).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('region', { name: 'Map of your trip' })).toBeInTheDocument();
    expect(await screen.findByTitle('You')).toBeInTheDocument();

    await waitFor(() => {
      expect(liveServer.subscribed(TRIP_UPDATES)).toBe(true);
    });
    act(() => {
      liveServer.push(TRIP_UPDATES, {
        type: 'TripStarted',
        tripId: 'trip-9',
        seq: 5,
        payload: onTrip('IN_PROGRESS'),
      });
    });
    expect(
      await screen.findByRole('link', { name: 'Open in Maps for directions to the drop' }),
    ).toHaveAttribute('href', expect.stringContaining(`${String(drop.lat)},${String(drop.lng)}`));
  });

  it('opens Apple Maps on an Apple device', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15',
    );
    const pickup = at(MG_ROAD);
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/driver/trip/trip-9');
    expect(await screen.findByRole('link', { name: /^Open in Maps/ })).toHaveAttribute(
      'href',
      `https://maps.apple.com/?daddr=${String(pickup.lat)},${String(pickup.lng)}&dirflg=d`,
    );
  });

  it('says how far the pickup is, and marks arrival', async () => {
    deviceAt({ lat: 12.98, lng: 77.6069 });
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    tripAction('arrive', () => HttpResponse.json(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(0) })));
    renderApp('/driver/trip/trip-9');
    expect(await screen.findByText('Pickup 490 m away.')).toBeVisible();
    expect(screen.getByRole('region', { name: 'This ride' })).toHaveTextContent('Car · Cash');

    await userEvent.click(screen.getByRole('button', { name: 'I’ve arrived' }));
    expect(await screen.findByRole('heading', { name: 'You’re at the pickup' })).toBeVisible();
  });

  it('says how far away the driver is when arrival is refused', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    tripAction('arrive', () => problem(422, 'NOT_AT_PICKUP', { distanceMeters: 640 }));
    renderApp('/driver/trip/trip-9');
    await userEvent.click(await screen.findByRole('button', { name: 'I’ve arrived' }));
    expect(await screenAlert()).toHaveTextContent(
      'You’re 640 m from the pickup. Get closer to mark arrival.',
    );
  });

  it('hands the trip back to matching when the driver leaves it', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    cancellingAnswers(() => HttpResponse.json(onTrip('REQUESTED')));
    const user = userEvent.setup();
    const { router } = renderApp('/driver/trip/trip-9');
    await user.click(await screen.findByRole('button', { name: 'Leave this trip' }));
    expect(screen.getByRole('alertdialog', { name: 'Leave this trip?' })).toHaveTextContent(
      'RideFlow will find the rider another driver.',
    );
    await user.click(screen.getByRole('button', { name: 'Leave trip' }));

    expect(
      await screen.findByText(
        'You’ve left this trip. RideFlow is finding the rider another driver.',
      ),
    ).toBeVisible();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/driver');
    });
  });
});

describe('at the pickup', () => {
  it('starts with the rider’s code, typed as they read it out', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(1) }));
    tripAction('start', () => HttpResponse.json(onTrip('IN_PROGRESS')));
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.type(await screen.findByLabelText('Rider’s code'), '48 21');
    await user.click(screen.getByRole('button', { name: 'Start trip' }));

    expect(await screen.findByRole('heading', { name: 'On the way to the drop' })).toBeVisible();
    expect(seen.find((r) => r.path.endsWith('/start'))?.body).toEqual({ otp: '4821' });
  });

  it('checks the code has four digits before sending it', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(1) }));
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.type(await screen.findByLabelText('Rider’s code'), '48');
    await user.click(screen.getByRole('button', { name: 'Start trip' }));
    expect(screen.getByLabelText('Rider’s code')).toHaveAccessibleDescription(
      /Enter the rider’s 4-digit code\.$/,
    );
    expect(seen.some((r) => r.path.endsWith('/start'))).toBe(false);
  });

  it('says how many tries are left after a wrong code', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(1) }));
    tripAction('start', () => problem(422, 'OTP_INVALID', { attemptsRemaining: 2 }));
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.type(await screen.findByLabelText('Rider’s code'), '1111');
    await user.click(screen.getByRole('button', { name: 'Start trip' }));
    await waitFor(() => {
      expect(screen.getByLabelText('Rider’s code')).toHaveAccessibleDescription(
        /That code doesn’t match\. 2 tries left\.$/,
      );
    });
  });

  it('waits out a lock after too many wrong codes', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(1) }));
    tripAction('start', () => problem(429, 'OTP_LOCKED', {}, { 'Retry-After': '120' }));
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.type(await screen.findByLabelText('Rider’s code'), '1111');
    await user.click(screen.getByRole('button', { name: 'Start trip' }));
    expect(
      await screen.findByRole('button', { name: /^Try again in (1:59|2:00)$/ }),
    ).toBeDisabled();
  });

  it('offers a no-show only once the driver has waited long enough', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(2) }));
    renderApp('/driver/trip/trip-9');
    expect(
      await screen.findByRole('timer', { name: /You can cancel for a no-show in (2:59|3:00)/ }),
    ).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Rider didn’t come' })).not.toBeInTheDocument();
  });

  it('cancels for a no-show, after asking', async () => {
    tripAnswers(onTrip('DRIVER_ARRIVED', { arrivedAt: ago(6) }));
    cancellingAnswers(() =>
      HttpResponse.json(onTrip('CANCELLED', { cancelledBy: 'DRIVER', cancellationFeePaise: 5000 })),
    );
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.click(await screen.findByRole('button', { name: 'Rider didn’t come' }));
    expect(screen.getByRole('alertdialog', { name: 'Cancel for a no-show?' })).toHaveTextContent(
      'The rider will be charged the cancellation fee.',
    );
    await user.click(screen.getByRole('button', { name: 'Cancel for a no-show' }));
    expect(
      await screen.findByText(
        'You cancelled this trip for a no-show. The rider is charged the cancellation fee.',
      ),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: 'Back to driving' })).toBeVisible();
  });
});

describe('to the drop', () => {
  it('completes after asking, and says what to collect', async () => {
    tripAnswers(onTrip('IN_PROGRESS'));
    tripAction('complete', () => HttpResponse.json(onTrip('COMPLETED', { finalFarePaise: 31200 })));
    const user = userEvent.setup();
    renderApp('/driver/trip/trip-9');
    await user.click(await screen.findByRole('button', { name: 'Complete trip' }));
    const asking = screen.getByRole('alertdialog', { name: 'Complete the trip?' });
    expect(seen.some((r) => r.path.endsWith('/complete'))).toBe(false);
    await user.click(within(asking).getByRole('button', { name: 'Complete trip' }));

    expect(await screen.findByRole('heading', { name: 'Trip completed' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Fare' })).toHaveTextContent(
      'Collect ₹312 in cash from the rider.',
    );
  });

  it('shows the rider cancelling while the driver drives to them', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/driver/trip/trip-9');
    await screen.findByRole('heading', { name: 'Head to the pickup' });
    await waitFor(() => {
      expect(liveServer.subscribed(TRIP_UPDATES)).toBe(true);
    });
    act(() => {
      liveServer.push(TRIP_UPDATES, {
        type: 'TripCancelled',
        tripId: 'trip-9',
        seq: 4,
        payload: onTrip('CANCELLED', { cancelledBy: 'RIDER' }),
      });
    });
    expect(await screen.findByText('The rider cancelled this trip.')).toBeVisible();
  });
});
