import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deviceAt, locationFails, moveDevice, renderApp, resetApp, signedInAs } from '@/test/app';
import {
  anOffer,
  at,
  backendFor,
  DRIVER,
  driverProfileIs,
  INDIRANAGAR,
  MG_ROAD,
  offerAnswers,
  offerIs,
  problem,
  trip,
  tripAnswers,
  VEHICLE,
} from '@/test/backend';
import { liveServer } from '@/test/liveServer';
import { OFFERS } from './useOffer';
import { LOCATION_DESTINATION } from './useShareLocation';

beforeEach(() => {
  resetApp();
  backendFor(DRIVER);
  signedInAs(DRIVER);
  driverProfileIs({ approvalStatus: 'APPROVED', status: 'AVAILABLE', vehicle: VEHICLE });
});

afterEach(() => {
  vi.useRealTimers();
});

type Sent = { lat: number; lng: number; accuracyMeters: number; seq: number };
const sent = () => liveServer.sentTo(LOCATION_DESTINATION) as Sent[];

async function offered(offer = anOffer()) {
  await waitFor(() => {
    expect(liveServer.subscribed(OFFERS)).toBe(true);
  });
  act(() => {
    liveServer.push(OFFERS, { type: 'OFFER', payload: offer });
  });
  return screen.findByRole('region', { name: 'Ride offer' });
}

describe('a driver online', () => {
  it('shares their position as soon as the connection is open', async () => {
    deviceAt(at(MG_ROAD), 20);
    renderApp('/driver');
    expect(
      await screen.findByText('Sharing your location with RideFlow while you’re online.'),
    ).toBeVisible();
    await waitFor(() => {
      expect(sent()).toHaveLength(1);
    });
    expect(sent()[0]).toMatchObject({ ...at(MG_ROAD), accuracyMeters: 20, seq: 0 });
  });

  it('sends as they move, at most every 2 seconds, and every 10 while still', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    deviceAt(at(MG_ROAD));
    renderApp('/driver');
    await waitFor(() => {
      expect(sent()).toHaveLength(1);
    });

    act(() => {
      moveDevice({ lat: 12.9757, lng: 77.607 });
    });
    expect(sent()).toHaveLength(1);
    act(() => {
      vi.advanceTimersByTime(2100);
      moveDevice(at(INDIRANAGAR));
    });
    expect(sent()).toHaveLength(2);

    // Still for ten seconds - checked once a second, so within eleven.
    act(() => {
      vi.advanceTimersByTime(11_000);
    });
    expect(sent()).toHaveLength(3);
    expect(sent()[2]).toMatchObject({ ...at(INDIRANAGAR), seq: 2 });
  });

  it('does not claim to share while the connection is down', async () => {
    deviceAt(at(MG_ROAD));
    renderApp('/driver');
    await screen.findByText('Sharing your location with RideFlow while you’re online.');
    act(() => {
      liveServer.answering = false;
      liveServer.drop();
    });
    expect(
      await screen.findByText(
        'Not connected to RideFlow. Riders can’t be matched to you until the connection is back.',
      ),
    ).toBeVisible();
    expect(screen.getByText('Reconnecting…')).toBeVisible();
  });

  it('holds back a position too rough to use, and says why', async () => {
    deviceAt(at(MG_ROAD), 250);
    renderApp('/driver');
    expect(
      await screen.findByText(/Your location is too rough to share \(within 250 m/),
    ).toBeVisible();
    expect(sent()).toHaveLength(0);
  });

  it('says riders can’t be matched while location is off', async () => {
    locationFails(1);
    renderApp('/driver');
    expect(
      await screen.findByText(
        'Location is off for RideFlow. Riders can’t be matched to you until it’s on.',
      ),
    ).toBeVisible();
  });
});

describe('a ride offer', () => {
  beforeEach(() => {
    deviceAt(at(MG_ROAD));
  });

  it('arrives live, and accepting opens the trip', async () => {
    offerAnswers('accept', () =>
      HttpResponse.json({ offerId: 'offer-1', status: 'ACCEPTED', tripId: 'trip-9' }),
    );
    tripAnswers(trip('trip-9', 'DRIVER_ASSIGNED'));
    const { router } = renderApp('/driver');
    const card = await offered();
    expect(card).toHaveTextContent('Auto request');
    expect(card).toHaveTextContent('₹148');
    expect(card).toHaveTextContent(/Pickup \d+ m away/);
    expect(within(card).getByRole('timer')).toHaveAccessibleName(/Expires in 0:1[12]/);

    await userEvent.click(within(card).getByRole('button', { name: 'Accept' }));
    expect(await screen.findByRole('heading', { name: 'Head to the pickup' })).toBeVisible();
    expect(router.state.location.pathname).toBe('/driver/trip/trip-9');
  });

  it('goes when declined', async () => {
    offerAnswers('reject', () => new HttpResponse(null, { status: 204 }));
    renderApp('/driver');
    const card = await offered();
    await userEvent.click(within(card).getByRole('button', { name: 'Decline' }));
    await waitFor(() => {
      expect(screen.queryByRole('region', { name: 'Ride offer' })).not.toBeInTheDocument();
    });
  });

  it('goes when the backend withdraws it', async () => {
    renderApp('/driver');
    await offered();
    act(() => {
      liveServer.push(OFFERS, { type: 'OFFER_WITHDRAWN', payload: { offerId: 'offer-1' } });
    });
    await waitFor(() => {
      expect(screen.queryByRole('region', { name: 'Ride offer' })).not.toBeInTheDocument();
    });
  });

  it('says when it has run out', async () => {
    renderApp('/driver');
    const card = await offered(anOffer({ expiresAt: new Date(Date.now() + 1200).toISOString() }));
    expect(
      await within(card).findByText(
        'This offer has expired. The next one will appear here.',
        {},
        { timeout: 4000 },
      ),
    ).toBeVisible();
    await userEvent.click(within(card).getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('region', { name: 'Ride offer' })).not.toBeInTheDocument();
  });

  it('says so when accepted too late', async () => {
    offerAnswers('accept', () => problem(409, 'OFFER_EXPIRED'));
    renderApp('/driver');
    const card = await offered();
    await userEvent.click(within(card).getByRole('button', { name: 'Accept' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'That offer has expired. The next one will appear here.',
    );
  });

  it('is shown when the app opens with one already waiting', async () => {
    offerIs(anOffer());
    renderApp('/driver');
    expect(await screen.findByRole('region', { name: 'Ride offer' })).toHaveTextContent(
      'Auto request',
    );
  });
});
