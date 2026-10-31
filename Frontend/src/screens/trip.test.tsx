import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LatLng } from '@/map/MapCanvas';
import { DRIVER_LOCATION } from '@/realtime/useDriverLocation';
import { TRIP_UPDATES } from '@/realtime/useTripLive';
import { useBookingDraft } from '@/state/bookingDraft';
import { renderApp, resetApp, signedInAs } from '@/test/app';
import {
  at,
  backendFor,
  cancellingAnswers,
  INDIRANAGAR,
  MG_ROAD,
  RIDER,
  seen,
  trip,
  tripAnswers,
} from '@/test/backend';
import { liveServer } from '@/test/liveServer';
import { setMedia } from '@/test/media';
import { server } from '@/test/server';

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
  const draft = useBookingDraft.getState();
  draft.setStop('pickup', { name: 'MG Road', detail: '', at: at(MG_ROAD) });
  draft.setStop('drop', { name: 'Indiranagar', detail: '', at: at(INDIRANAGAR) });
});

afterEach(() => {
  vi.useRealTimers();
});

async function live() {
  await waitFor(() => {
    expect(liveServer.subscribed(TRIP_UPDATES)).toBe(true);
  });
}

function pushed(seq: number, view: object) {
  act(() => {
    liveServer.push(TRIP_UPDATES, { type: 'TripEvent', tripId: 'trip-1', seq, payload: view });
  });
}

describe('waiting for a driver', () => {
  it('turns into the driver on the way the moment one accepts, with the rider’s code', async () => {
    tripAnswers(trip('trip-1', 'REQUESTED'));
    renderApp('/trip/trip-1');
    expect(await screen.findByRole('heading', { name: 'Finding your driver' })).toBeVisible();
    await live();

    const assignedAt = new Date().toISOString();
    pushed(2, trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt }));
    expect(await screen.findByRole('heading', { name: 'Your driver is on the way' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Your code' })).toHaveTextContent('4821');
    // A driver is on the way: the booking that led here is done with.
    expect(useBookingDraft.getState().pickup).toBeNull();
  });

  it('ignores an update older than what it shows, or one it has seen', async () => {
    tripAnswers(trip('trip-1', 'REQUESTED'));
    renderApp('/trip/trip-1');
    await live();
    pushed(
      5,
      trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt: new Date().toISOString() }),
    );
    await screen.findByRole('heading', { name: 'Your driver is on the way' });

    pushed(4, trip('trip-1', 'REQUESTED'));
    pushed(5, trip('trip-1', 'REQUESTED'));
    await act(() => Promise.resolve());
    expect(screen.getByRole('heading', { name: 'Your driver is on the way' })).toBeVisible();
  });

  it('reads the trip again when the connection comes back', async () => {
    let reads = 0;
    tripAnswers(() => {
      reads += 1;
      return reads === 1
        ? trip('trip-1', 'REQUESTED')
        : trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt: new Date().toISOString() });
    });
    renderApp('/trip/trip-1');
    await screen.findByRole('heading', { name: 'Finding your driver' });
    await live();

    act(() => {
      liveServer.drop();
    });
    expect(await screen.findByText('Reconnecting…')).toBeVisible();
    // Missed while down: the driver accepted. The read after reconnecting catches it.
    expect(
      await screen.findByRole('heading', { name: 'Your driver is on the way' }, { timeout: 4000 }),
    ).toBeVisible();
    expect(screen.queryByText('Reconnecting…')).not.toBeInTheDocument();
  });

  it('offers to try again when no driver was found, with the stops kept', async () => {
    tripAnswers(trip('trip-1', 'NO_DRIVER_FOUND'));
    const { router } = renderApp('/trip/trip-1');
    await userEvent.click(await screen.findByRole('link', { name: 'Try again' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?step=prices');
    });
    expect(useBookingDraft.getState().drop?.name).toBe('Indiranagar');
  });
});

describe('following the driver', () => {
  const STOPS = { pickup: at(MG_ROAD), drop: at(INDIRANAGAR) };
  const minuteAgo = () => new Date(Date.now() - 60_000).toISOString();

  function onTrip(status: 'DRIVER_ASSIGNED' | 'IN_PROGRESS', assignedAt = minuteAgo()) {
    return trip('trip-1', status, { ...STOPS, otp: '4821', assignedAt });
  }

  async function following() {
    await waitFor(() => {
      expect(liveServer.subscribed(DRIVER_LOCATION)).toBe(true);
    });
  }

  async function driverAt(
    seq: number,
    where: LatLng,
    etaSeconds: number | null = 230,
    phase = 'TO_PICKUP',
  ) {
    act(() => {
      liveServer.push(DRIVER_LOCATION, {
        type: 'DRIVER_LOCATION',
        tripId: 'trip-1',
        seq,
        payload: { ...where, phase, etaSeconds },
      });
    });
    // Delivered on the next microtask.
    await act(() => Promise.resolve());
  }

  /** Where a marker is drawn; jsdom has no 3D transforms, so Leaflet places it by left and top. */
  const spot = (title: string) => {
    const style = screen.getByTitle(title).style;
    return `${style.left},${style.top}`;
  };

  it('shows the driver on the map, and about how long until they arrive', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    expect(await screen.findByRole('region', { name: 'Map of your trip' })).toBeInTheDocument();
    await following();

    await driverAt(3, at(INDIRANAGAR));
    expect(await screen.findByText('Arriving in about 4 min.')).toBeVisible();
    expect(screen.getByTitle('Your driver')).toBeInTheDocument();
  });

  it('keeps to the newest position, whatever order they arrive in', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR));
    await screen.findByText('Arriving in about 4 min.');

    await driverAt(2, at(INDIRANAGAR), 600);
    await driverAt(3, at(INDIRANAGAR), 600);
    await act(() => Promise.resolve());
    expect(screen.getByText('Arriving in about 4 min.')).toBeVisible();
  });

  it('says how long to the drop once the trip is under way', async () => {
    tripAnswers(onTrip('IN_PROGRESS'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(5, at(MG_ROAD), 690, 'TO_DROP');
    expect(await screen.findByText('About 12 min to the drop.')).toBeVisible();
  });

  it('shows the driver without a time when the backend has no estimate', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR), null);
    expect(await screen.findByTitle('Your driver')).toBeInTheDocument();
    expect(screen.queryByText(/Arriving/)).not.toBeInTheDocument();
  });

  it('says when the driver’s position has gone quiet, and dims it where it was', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setMedia('(prefers-reduced-motion: reduce)');
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR));
    await driverAt(4, at(MG_ROAD));
    await screen.findByText('Arriving in about 4 min.');

    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(
      screen.getByText(/^Your driver’s position hasn’t updated since \d{1,2}:\d{2} [ap]m\.$/),
    ).toBeVisible();
    expect(screen.getByTitle('Your driver')).toHaveStyle({ opacity: '0.45' });
    expect(spot('Your driver')).toBe(spot('Pickup'));

    // The next position is news again.
    await driverAt(5, at(MG_ROAD));
    expect(await screen.findByText('Arriving in about 4 min.')).toBeVisible();
  });

  it('glides the car to each new position', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR));
    await screen.findByTitle('Your driver');

    // To the pickup itself, so the pickup's pin marks where the glide ends.
    await driverAt(4, at(MG_ROAD));
    expect(spot('Your driver')).not.toBe(spot('Pickup'));
    await waitFor(() => {
      expect(spot('Your driver')).toBe(spot('Pickup'));
    });
  });

  it('moves the car at once for anyone who asks for less motion', async () => {
    setMedia('(prefers-reduced-motion: reduce)');
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR));
    await screen.findByTitle('Your driver');

    await driverAt(4, at(MG_ROAD));
    expect(spot('Your driver')).toBe(spot('Pickup'));
  });

  it('does not show a driver who backed out as the new one', async () => {
    tripAnswers(onTrip('DRIVER_ASSIGNED'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(3, at(INDIRANAGAR));
    await screen.findByTitle('Your driver');

    pushed(4, trip('trip-1', 'REQUESTED', STOPS));
    await screen.findByRole('heading', { name: 'Finding your driver' });
    pushed(5, onTrip('DRIVER_ASSIGNED', new Date(Date.now() + 1000).toISOString()));
    await screen.findByRole('heading', { name: 'Your driver is on the way' });
    expect(screen.queryByTitle('Your driver')).not.toBeInTheDocument();
    expect(screen.queryByText(/Arriving/)).not.toBeInTheDocument();
  });

  it('leaves the map for the fare when the trip completes', async () => {
    tripAnswers(onTrip('IN_PROGRESS'));
    renderApp('/trip/trip-1');
    await following();
    await driverAt(5, at(INDIRANAGAR), 30, 'TO_DROP');
    await screen.findByText('About 1 min to the drop.');

    pushed(6, trip('trip-1', 'COMPLETED', { finalFarePaise: 31200, paymentMethod: 'CASH' }));
    expect(await screen.findByRole('heading', { name: 'Trip completed' })).toBeVisible();
    expect(screen.queryByRole('region', { name: 'Map of your trip' })).not.toBeInTheDocument();
  });
});

describe('at the pickup and after', () => {
  it('counts down the free waiting once the driver has arrived', async () => {
    tripAnswers(
      trip('trip-1', 'DRIVER_ARRIVED', {
        otp: '4821',
        assignedAt: new Date(Date.now() - 300_000).toISOString(),
        arrivedAt: new Date().toISOString(),
      }),
    );
    renderApp('/trip/trip-1');
    expect(
      await screen.findByRole('timer', { name: /Free waiting for (2:59|3:00)/ }),
    ).toBeVisible();
    expect(screen.getByText(/after that, waiting is charged by the minute/)).toBeVisible();
  });

  it('says what the ride cost, and how it was paid', async () => {
    tripAnswers(trip('trip-1', 'COMPLETED', { finalFarePaise: 31200, paymentMethod: 'CASH' }));
    renderApp('/trip/trip-1');
    expect(await screen.findByRole('region', { name: 'Fare' })).toHaveTextContent(
      '₹312Paid in cash to your driver.',
    );
    // Nothing to follow on a map once it is over.
    expect(screen.queryByRole('region', { name: 'Map of your trip' })).not.toBeInTheDocument();
  });

  it('says so when the wallet went below zero to pay', async () => {
    tripAnswers(
      trip('trip-1', 'COMPLETED', {
        finalFarePaise: 31200,
        paymentMethod: 'WALLET',
        paymentStatus: 'PENDING_SETTLEMENT',
      }),
    );
    renderApp('/trip/trip-1');
    expect(await screen.findByRole('region', { name: 'Fare' })).toHaveTextContent(
      'Charged to your wallet, which is now below zero. Top up before your next ride.',
    );
    expect(screen.getByRole('link', { name: 'Add money' })).toHaveAttribute('href', '/wallet');
  });

  it('says why it is finding a driver again, when the driver backed out', async () => {
    tripAnswers(
      trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt: new Date().toISOString() }),
    );
    renderApp('/trip/trip-1');
    await screen.findByRole('heading', { name: 'Your driver is on the way' });
    await live();
    act(() => {
      liveServer.push(TRIP_UPDATES, {
        type: 'TripReturnedToMatching',
        tripId: 'trip-1',
        seq: 3,
        payload: trip('trip-1', 'REQUESTED'),
      });
    });
    expect(await screen.findByRole('heading', { name: 'Finding your driver' })).toBeVisible();
    expect(
      screen.getByText('Your driver couldn’t make it. RideFlow is finding you another.'),
    ).toBeVisible();
  });
});

describe('cancelling', () => {
  it('is free, and immediate, while a driver is still being found', async () => {
    tripAnswers(trip('trip-1', 'REQUESTED'));
    cancellingAnswers(() =>
      HttpResponse.json(trip('trip-1', 'CANCELLED', { cancelledBy: 'RIDER' })),
    );
    renderApp('/trip/trip-1');
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel ride' }));
    expect(await screen.findByRole('heading', { name: 'Trip cancelled' })).toBeVisible();
    expect(screen.getByText('You cancelled this ride.')).toBeVisible();
    expect(seen.find((r) => r.path === '/api/v1/trips/trip-1/cancel')?.body).toEqual({
      reason: '',
    });
  });

  it('says how long it stays free once a driver is on the way', async () => {
    tripAnswers(
      trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt: new Date().toISOString() }),
    );
    renderApp('/trip/trip-1');
    expect(await screen.findByRole('timer')).toHaveAccessibleName(/Free to cancel for (1:59|2:00)/);
  });

  it('asks first, and shows the fee charged, once the free time is over', async () => {
    const assignedAt = new Date(Date.now() - 180_000).toISOString();
    tripAnswers(trip('trip-1', 'DRIVER_ASSIGNED', { otp: '4821', assignedAt }));
    cancellingAnswers(() =>
      HttpResponse.json(
        trip('trip-1', 'CANCELLED', { cancelledBy: 'RIDER', cancellationFeePaise: 5000 }),
      ),
    );
    const user = userEvent.setup();
    renderApp('/trip/trip-1');
    await user.click(await screen.findByRole('button', { name: 'Cancel ride' }));
    const asking = screen.getByRole('alertdialog', { name: 'Cancel this ride?' });
    expect(asking).toHaveTextContent('a cancellation fee may apply');
    expect(seen.some((r) => r.path.endsWith('/cancel'))).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Cancel ride' }));
    expect(
      await screen.findByText('You cancelled this ride. A cancellation fee of ₹50 was charged.'),
    ).toBeVisible();
  });

  it('can be thought better of', async () => {
    tripAnswers(
      trip('trip-1', 'DRIVER_ARRIVED', {
        otp: '4821',
        assignedAt: new Date(Date.now() - 600_000).toISOString(),
      }),
    );
    server.use(http.post('/api/v1/trips/:tripId/cancel', () => HttpResponse.error()));
    const user = userEvent.setup();
    renderApp('/trip/trip-1');
    await user.click(await screen.findByRole('button', { name: 'Cancel ride' }));
    await user.click(screen.getByRole('button', { name: 'Keep my ride' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Your driver has arrived' })).toBeVisible();
  });
});
