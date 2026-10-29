import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { NOMINATIM } from '@/places/nominatim';
import { useBookingDraft } from '@/state/bookingDraft';
import { deviceAt, locationFails, renderApp, resetApp, signedInAs } from '@/test/app';
import {
  addressIs,
  AIRPORT,
  at,
  backendFor,
  INDIRANAGAR,
  MG_ROAD,
  nearbyAre,
  placesAre,
  RIDER,
} from '@/test/backend';
import { server } from '@/test/server';

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
});

const MG = { name: 'MG Road', detail: 'Shanthala Nagar, Ashok Nagar, Bengaluru', at: at(MG_ROAD) };

function pickupIsMgRoad() {
  useBookingDraft.getState().setStop('pickup', MG);
}

async function openWhereTo(user = userEvent.setup()) {
  await user.click(await screen.findByRole('button', { name: 'Where to?' }));
  return user;
}

function field(name: 'Pickup' | 'Drop') {
  return screen.getByRole('searchbox', { name });
}

describe('the pickup', () => {
  it('starts at the rider’s location, asked for when “Where to?” opens', async () => {
    deviceAt({ lat: 12.9716, lng: 77.5946 });
    renderApp('/');
    await openWhereTo();
    await waitFor(() => {
      expect(field('Pickup')).toHaveValue('Your location');
    });
    expect(useBookingDraft.getState().pickup?.at).toEqual({ lat: 12.9716, lng: 77.5946 });
  });

  it('asks the rider to check a rough location on the map', async () => {
    deviceAt({ lat: 12.9716, lng: 77.5946 }, 450);
    renderApp('/');
    await openWhereTo();
    expect(
      await screen.findByText(
        'Your location is approximate, to within 450 m. Check the pickup on the map.',
      ),
    ).toBeInTheDocument();
  });

  it.each([
    [1, 'Location is off for RideFlow. Search for your pickup, or choose it on the map.'],
    [2, 'Your location isn’t available. Search for your pickup, or choose it on the map.'],
    [3, 'Finding your location took too long. Try again, or choose your pickup on the map.'],
  ] as const)('says what to do when location fails (code %i)', async (code, words) => {
    locationFails(code);
    renderApp('/');
    await openWhereTo();
    expect(await screen.findByText(words)).toBeInTheDocument();
    expect(field('Pickup')).toHaveValue('');
  });

  it('is not set from a location outside the service area', async () => {
    deviceAt({ lat: 12.2958, lng: 76.6394 }); // Mysuru
    renderApp('/');
    await openWhereTo();
    expect(
      await screen.findByText(
        'You’re outside the area RideFlow serves. Choose a pickup in Bengaluru.',
      ),
    ).toBeInTheDocument();
    expect(useBookingDraft.getState().pickup).toBeNull();
  });
});

describe('the drop', () => {
  beforeEach(pickupIsMgRoad);

  it('is found by search, and the ride is ready with drivers counted', async () => {
    placesAre([INDIRANAGAR]);
    nearbyAre({ BIKE: [at(MG_ROAD), at(MG_ROAD)], CAR: [at(MG_ROAD)] });
    const { router } = renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'Indiranagar{Enter}');
    const results = await screen.findByRole('list', { name: 'Places matching “Indiranagar”' });
    await user.click(within(results).getByRole('button', { name: /Indiranagar/ }));

    await waitFor(() => {
      expect(router.state.location.search).toBe('');
    });
    const sheet = screen.getByRole('region', { name: 'Your ride' });
    expect(within(sheet).getByText('MG Road')).toBeInTheDocument();
    expect(within(sheet).getByText('Indiranagar')).toBeInTheDocument();
    expect(await within(sheet).findByText('2 bikes and 1 car near your pickup.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'See prices' })).toBeEnabled();
  });

  it('survives a reload: the choice is in the tab’s session storage', async () => {
    placesAre([INDIRANAGAR]);
    renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'Indiranagar{Enter}');
    await user.click(await screen.findByRole('button', { name: /^Indiranagar/ }));
    await screen.findByRole('region', { name: 'Your ride' });
    const stored = JSON.parse(sessionStorage.getItem('rideflow-booking-draft') ?? '{}') as {
      state?: { drop?: { name: string } };
    };
    expect(stored.state?.drop?.name).toBe('Indiranagar');
  });

  it('shows a place outside the service area, and says why it cannot be chosen', async () => {
    placesAre([AIRPORT]);
    renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'airport{Enter}');
    const airport = await screen.findByRole('button', { name: /Kempegowda International Airport/ });
    expect(airport).toBeDisabled();
    expect(airport).toHaveTextContent('Outside the area RideFlow serves');
  });

  it('refuses the pickup’s own spot', async () => {
    placesAre([MG_ROAD]);
    renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'MG Road{Enter}');
    await user.click(await screen.findByRole('button', { name: /^MG Road/ }));
    expect(
      await screen.findByText('That’s where your pickup is. Choose a drop further away.'),
    ).toBeInTheDocument();
    expect(useBookingDraft.getState().drop).toBeNull();
  });

  it('says when nothing matches', async () => {
    placesAre([]);
    renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'Xyzzy{Enter}');
    expect(
      await screen.findByText(
        'No places match “Xyzzy”. Check the spelling, or choose it on the map.',
      ),
    ).toBeInTheDocument();
  });

  it.each([
    [() => new HttpResponse(null, { status: 429 }), 'Place search is busy.'],
    [() => HttpResponse.error(), 'No connection. Check your network and search again.'],
  ])('keeps the map as a way out when search fails', async (answer, words) => {
    server.use(http.get(`${NOMINATIM}/search`, answer));
    renderApp('/');
    const user = await openWhereTo();
    await user.type(field('Drop'), 'Indiranagar{Enter}');
    expect(await screen.findByText(new RegExp(words))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Choose on the map' })).toBeEnabled();
  });

  it('can be set on the map, named by the address under the pin', async () => {
    useBookingDraft.getState().setStop('drop', {
      name: 'Indiranagar',
      detail: '',
      at: at(INDIRANAGAR),
    });
    addressIs({
      ...INDIRANAGAR,
      name: '100 Feet Road',
      display_name: '100 Feet Road, Indiranagar, Bengaluru, India',
    });
    const { router } = renderApp('/');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Change drop' }));
    await user.click(await screen.findByRole('button', { name: 'Choose on the map' }));

    expect(router.state.location.search).toBe('?step=map&field=drop');
    expect(await screen.findByText('100 Feet Road')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm drop' }));
    await waitFor(() => {
      expect(useBookingDraft.getState().drop?.name).toBe('100 Feet Road');
    });
    expect(router.state.location.search).toBe('');
  });
});

describe('moving between steps', () => {
  it('closes “Where to?” back to the map', async () => {
    const { router } = renderApp('/');
    const user = await openWhereTo();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(await screen.findByRole('button', { name: 'Where to?' })).toBeInTheDocument();
    expect(router.state.location.search).toBe('');
  });
});
