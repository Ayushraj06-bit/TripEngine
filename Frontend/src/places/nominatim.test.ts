import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { AIRPORT, INDIRANAGAR, MG_ROAD, type RawPlace } from '@/test/backend';
import { server } from '@/test/server';
import { NOMINATIM, PlacesError, placeAt, searchPlaces, resetPlacesForTests } from './nominatim';

beforeEach(resetPlacesForTests);

/** Answers every search with `places`, and keeps each request's address and time. */
function searchAnswers(places: RawPlace[] | (() => Response)) {
  const asked: { url: URL; at: number }[] = [];
  server.use(
    http.get(`${NOMINATIM}/search`, ({ request }) => {
      asked.push({ url: new URL(request.url), at: Date.now() });
      return typeof places === 'function' ? places() : HttpResponse.json(places);
    }),
  );
  return asked;
}

describe('searching for a place', () => {
  it('asks for places in India, nearest Bengaluru first, in English', async () => {
    const asked = searchAnswers([INDIRANAGAR]);
    await searchPlaces('  Indiranagar ');
    const params = asked[0]?.url.searchParams;
    expect(params?.get('q')).toBe('Indiranagar');
    expect(params?.get('countrycodes')).toBe('in');
    expect(params?.get('viewbox')).toBe('77.46,13.14,77.78,12.82');
    expect(params?.get('format')).toBe('jsonv2');
    expect(params?.get('accept-language')).toBe('en');
  });

  it('names each place briefly, and says where it is', async () => {
    searchAnswers([INDIRANAGAR]);
    expect(await searchPlaces('Indiranagar')).toEqual([
      {
        name: 'Indiranagar',
        detail: 'Bengaluru East City Corporation, Bengaluru, Bangalore North',
        at: { lat: 12.9783692, lng: 77.6408356 },
        served: true,
      },
    ]);
  });

  it('keeps places outside the service area, marked and last', async () => {
    searchAnswers([AIRPORT, MG_ROAD]);
    const places = await searchPlaces('airport');
    expect(places.map((p) => [p.name, p.served])).toEqual([
      ['MG Road', true],
      ['Kempegowda International Airport', false],
    ]);
  });

  it('shows a place once, though Nominatim lists it twice', async () => {
    searchAnswers([INDIRANAGAR, INDIRANAGAR, MG_ROAD]);
    expect((await searchPlaces('Indiranagar')).map((p) => p.name)).toEqual([
      'Indiranagar',
      'MG Road',
    ]);
  });

  it('never asks the same question twice', async () => {
    const asked = searchAnswers([INDIRANAGAR]);
    await searchPlaces('Indiranagar');
    await searchPlaces(' indiranagar');
    expect(asked).toHaveLength(1);
  });

  it('asks at most once a second, as Nominatim’s policy requires', async () => {
    const asked = searchAnswers([]);
    await Promise.all([searchPlaces('Koramangala'), searchPlaces('Jayanagar')]);
    expect(asked).toHaveLength(2);
    const [first, second] = asked;
    expect((second?.at ?? 0) - (first?.at ?? 0)).toBeGreaterThanOrEqual(950);
  });

  it.each([
    ['busy', () => new HttpResponse(null, { status: 429 })],
    ['unavailable', () => new HttpResponse(null, { status: 503 })],
    ['unavailable', () => new HttpResponse('<html>', { status: 200 })],
    ['offline', () => HttpResponse.error()],
  ] as const)('says the search is %s when it fails that way', async (reason, answer) => {
    searchAnswers(answer);
    await expect(searchPlaces('Indiranagar')).rejects.toEqual(new PlacesError(reason));
  });
});

describe('the address under a pin', () => {
  it('names it, keeping the pin’s own position', async () => {
    server.use(http.get(`${NOMINATIM}/reverse`, () => HttpResponse.json(INDIRANAGAR)));
    const pin = { lat: 12.97837, lng: 77.64084 };
    expect(await placeAt(pin)).toMatchObject({ name: 'Indiranagar', at: pin, served: true });
  });

  it('names a building with no name by its number and street', async () => {
    server.use(
      http.get(`${NOMINATIM}/reverse`, () =>
        HttpResponse.json({
          lat: '12.9611',
          lon: '77.6387',
          display_name:
            '83, 5th Main Road, Domlur 2nd Stage, Domlur, Bengaluru, Karnataka, 560071, India',
        }),
      ),
    );
    expect(await placeAt({ lat: 12.9611, lng: 77.6387 })).toMatchObject({
      name: '83, 5th Main Road',
      detail: 'Domlur 2nd Stage, Domlur, Bengaluru',
    });
  });

  it('is null where there is no address, and asked for once', async () => {
    let asked = 0;
    server.use(
      http.get(`${NOMINATIM}/reverse`, () => {
        asked += 1;
        return HttpResponse.json({ error: 'Unable to geocode' });
      }),
    );
    expect(await placeAt({ lat: 12.9, lng: 77.6 })).toBeNull();
    expect(await placeAt({ lat: 12.9, lng: 77.6 })).toBeNull();
    expect(asked).toBe(1);
  });
});
