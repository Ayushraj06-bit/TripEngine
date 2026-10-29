import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useBookingDraft } from '@/state/bookingDraft';
import { renderApp, resetApp, screenAlert, signedInAs } from '@/test/app';
import {
  at,
  backendFor,
  booked,
  bookingAnswers,
  INDIRANAGAR,
  MG_ROAD,
  nearbyAre,
  problem,
  quote,
  quotesAre,
  RIDER,
  seen,
  tripIs,
  walletHas,
} from '@/test/backend';
import { server } from '@/test/server';

const PRICES = [quote('BIKE', 8400), quote('AUTO', 14800), quote('CAR', 22600)];

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
  walletHas(124000);
  const draft = useBookingDraft.getState();
  draft.setStop('pickup', { name: 'MG Road', detail: '', at: at(MG_ROAD) });
  draft.setStop('drop', { name: 'Indiranagar', detail: '', at: at(INDIRANAGAR) });
});

afterEach(() => {
  vi.useRealTimers();
});

const bookings = () => seen.filter((r) => r.method === 'POST' && r.path === '/api/v1/trips');
const keyOf = (i: number) => bookings()[i]?.headers.get('Idempotency-Key');

async function confirm(user = userEvent.setup()) {
  await user.click(await screen.findByRole('button', { name: /^Confirm/ }));
  return user;
}

describe('the prices', () => {
  it('show each ride’s fare, the trip, and how many are nearby', async () => {
    quotesAre(PRICES);
    nearbyAre({ AUTO: [at(MG_ROAD), at(MG_ROAD)] });
    renderApp('/?step=prices');

    const rides = await screen.findByRole('radiogroup', { name: 'Rides' });
    expect(within(rides).getByRole('radio', { name: /Bike.*₹84/ })).toBeChecked();
    expect(await within(rides).findByRole('radio', { name: /Auto.*2 nearby.*₹148/ })).toBeVisible();
    expect(
      within(rides).getByRole('radio', { name: /Car.*none nearby right now.*₹226/ }),
    ).toBeVisible();
    expect(screen.getByText('4.2 km · about 18 min')).toBeInTheDocument();
    expect(seen.find((r) => r.path === '/api/v1/fares/quotes')?.body).toEqual({
      pickup: at(MG_ROAD),
      drop: at(INDIRANAGAR),
    });
  });

  it('name the ride the button books', async () => {
    quotesAre(PRICES);
    const user = userEvent.setup();
    renderApp('/?step=prices');
    expect(await screen.findByRole('button', { name: 'Confirm Bike' })).toBeEnabled();
    await user.click(screen.getByRole('radio', { name: /Car/ }));
    expect(screen.getByRole('button', { name: 'Confirm Car' })).toBeEnabled();
  });

  it('say so when prices are higher than usual', async () => {
    quotesAre(PRICES.map((q) => ({ ...q, surgeMultiplier: 1.4 })));
    renderApp('/?step=prices');
    expect(await screen.findByText(/Prices are higher right now/)).toHaveTextContent('1.4×');
  });

  it('show how a fare is made, when asked', async () => {
    quotesAre(PRICES);
    const user = userEvent.setup();
    renderApp('/?step=prices');
    await user.click(await screen.findByText('How this fare is made'));
    const lines = screen.getByText('Base fare').closest('dl');
    expect(lines).toHaveTextContent('Base fare₹30');
    expect(lines).toHaveTextContent('Distance₹44');
    expect(lines).toHaveTextContent('Total₹84');
  });

  it('count down on the server’s clock, not the phone’s', async () => {
    const serverNow = Date.now() + 3_600_000;
    quotesAre(() =>
      HttpResponse.json(
        {
          quotes: [
            quote('AUTO', 14800, { expiresAt: new Date(serverNow + 300_000).toISOString() }),
          ],
        },
        { headers: { Date: new Date(serverNow).toUTCString() } },
      ),
    );
    renderApp('/?step=prices');
    // Five minutes on the server, though the phone thinks they ended an hour ago - to within the
    // half second the Date header's whole seconds allow.
    expect(await screen.findByRole('timer')).toHaveAccessibleName(
      /Prices valid for (4:59|5:00|5:01)/,
    );
  });

  it('expire, and are asked for again before anyone books', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let asked = 0;
    quotesAre(() => {
      asked += 1;
      return HttpResponse.json({
        quotes: [quote('BIKE', 8400, { expiresAt: new Date(Date.now() + 3000).toISOString() })],
      });
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) });
    renderApp('/?step=prices');
    await screen.findByRole('button', { name: 'Confirm Bike' });

    act(() => {
      vi.advanceTimersByTime(4000);
    });
    expect(screen.getByText(/Prices have expired/)).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Bike/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Get new prices' }));

    expect(await screen.findByRole('button', { name: 'Confirm Bike' })).toBeEnabled();
    expect(asked).toBe(2);
  });

  it('send a rider back to change a trip the backend will not price', async () => {
    quotesAre(() => problem(422, 'TRIP_TOO_SHORT'));
    const user = userEvent.setup();
    const { router } = renderApp('/?step=prices');
    expect(await screenAlert()).toHaveTextContent('Pickup and drop are too close for a ride.');
    await user.click(screen.getByRole('button', { name: 'Change pickup or drop' }));
    expect(router.state.location.search).toBe('?step=where-to&field=drop');
  });
});

describe('booking', () => {
  beforeEach(() => {
    quotesAre(PRICES);
    tripIs('trip-1', 'REQUESTED');
  });

  it('books the chosen ride once, and opens it', async () => {
    bookingAnswers(() => booked('trip-1'));
    const { router } = renderApp('/?step=prices');
    const user = userEvent.setup();
    await screen.findByRole('button', { name: 'Confirm Bike' });
    await user.dblClick(screen.getByRole('button', { name: 'Confirm Bike' }));

    expect(await screen.findByRole('heading', { name: 'Finding your driver' })).toBeVisible();
    expect(router.state.location.pathname).toBe('/trip/trip-1');
    expect(bookings()).toHaveLength(1);
    expect(bookings()[0]?.body).toEqual({
      quoteId: 'quote-bike',
      vehicleType: 'BIKE',
      paymentMethod: 'CASH',
    });
    expect(keyOf(0)).toMatch(/^[0-9a-f-]{36}$/);
    // The attempt is done with; the stops wait until a driver is on the way.
    expect(useBookingDraft.getState()).toMatchObject({
      attempt: null,
      pickup: { name: 'MG Road' },
    });
  });

  it('resends the same booking after the connection drops, so it is placed once', async () => {
    let calls = 0;
    bookingAnswers(() => (++calls === 1 ? HttpResponse.error() : booked('trip-1')));
    renderApp('/?step=prices');
    await confirm();
    expect(await screenAlert()).toHaveTextContent('No connection.');
    // Kept in the tab, so a reload would resend it too.
    expect(sessionStorage.getItem('rideflow-booking-draft')).toContain(keyOf(0) ?? 'no key');

    await confirm();
    expect(await screen.findByRole('heading', { name: 'Finding your driver' })).toBeVisible();
    expect(keyOf(1)).toBe(keyOf(0));
  });

  it('opens the ride when it was placed, though the answer was lost', async () => {
    let placed = false;
    bookingAnswers(() => {
      placed = true;
      return HttpResponse.error();
    });
    server.use(
      http.get('/api/v1/trips/active', () =>
        placed
          ? HttpResponse.json({ id: 'trip-1', status: 'REQUESTED' })
          : new HttpResponse(null, { status: 204 }),
      ),
    );
    const { router } = renderApp('/?step=prices');
    await confirm();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/trip/trip-1');
    });
  });

  it('gets new prices, and a new attempt, when the old ones expired on the server', async () => {
    let calls = 0;
    bookingAnswers(() => (++calls === 1 ? problem(410, 'QUOTE_EXPIRED') : booked('trip-1')));
    renderApp('/?step=prices');
    await confirm();
    expect(await screenAlert()).toHaveTextContent('These prices have expired.');
    await waitFor(() => {
      expect(seen.filter((r) => r.path === '/api/v1/fares/quotes')).toHaveLength(2);
    });

    await confirm();
    await screen.findByRole('heading', { name: 'Finding your driver' });
    expect(keyOf(1)).not.toBe(keyOf(0));
  });

  it('says why when an earlier ride is unpaid', async () => {
    bookingAnswers(() => problem(422, 'OUTSTANDING_BALANCE'));
    renderApp('/?step=prices');
    await confirm();
    expect(await screenAlert()).toHaveTextContent(
      'An earlier ride is still unpaid. Top up your wallet to book again.',
    );
  });

  it('opens the ride already under way, rather than booking another', async () => {
    let refused = false;
    bookingAnswers(() => {
      refused = true;
      return problem(409, 'ACTIVE_TRIP_EXISTS');
    });
    server.use(
      http.get('/api/v1/trips/active', () =>
        refused
          ? HttpResponse.json({ id: 'trip-1', status: 'REQUESTED' })
          : new HttpResponse(null, { status: 204 }),
      ),
    );
    const { router } = renderApp('/?step=prices');
    await confirm();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/trip/trip-1');
    });
  });

  it('pays from the wallet, saying what happens when it is short', async () => {
    walletHas(4000);
    bookingAnswers(() => booked('trip-1'));
    const user = userEvent.setup();
    renderApp('/?step=prices');
    await user.click(await screen.findByRole('radio', { name: 'Wallet' }));
    expect(
      await screen.findByText(
        'Your wallet has ₹40. It can go below zero for this ride, and you’ll top it up before the next one.',
      ),
    ).toBeInTheDocument();
    await confirm(user);
    await screen.findByRole('heading', { name: 'Finding your driver' });
    expect(bookings()[0]?.body).toMatchObject({ paymentMethod: 'WALLET' });
  });
});
