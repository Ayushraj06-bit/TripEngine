import type { LatLng } from '@/map/MapCanvas';
import { insideServiceArea, SERVICE_BOUNDS } from './serviceArea';

/**
 * Finding places, through OpenStreetMap's Nominatim (ADR 0020). The backend has no place search,
 * so what the rider types goes to OpenStreetMap's servers: only when they press Search, never as
 * they type, which Nominatim's usage policy forbids, and at most one request a second, the
 * policy's limit. Answers are kept for the session, so the same question is never asked twice.
 */
export const NOMINATIM = 'https://nominatim.openstreetmap.org';

export interface Place {
  /** "Indiranagar" */
  name: string;
  /** "Bengaluru East, Bengaluru": where it is, to tell two of the same name apart. */
  detail: string;
  at: LatLng;
  /** Inside the area RideFlow serves. Places outside it are shown, but cannot be chosen. */
  served: boolean;
}

export type PlacesFailure = 'offline' | 'busy' | 'unavailable';

export class PlacesError extends Error {
  constructor(readonly reason: PlacesFailure) {
    super(`Place search failed: ${reason}`);
  }
}

const GAP_MS = 1000;
let nextSlot = 0;
// ponytail: kept for the tab's life, unbounded; a rider asks a handful of questions per session.
const searches = new Map<string, Place[]>();
const names = new Map<string, Place | null>();

/** Forget every answer and every queued turn, so a test starts from nothing. */
export function resetPlacesForTests(): void {
  nextSlot = 0;
  searches.clear();
  names.clear();
}

/** Waits for this request's turn: one a second, however many are asked for at once. */
async function turn(signal?: AbortSignal): Promise<void> {
  const now = Date.now();
  const slot = Math.max(now, nextSlot);
  nextSlot = slot + GAP_MS;
  if (slot <= now) return;
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, slot - now);
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer);
        reject(signal.reason instanceof Error ? signal.reason : new Error('Aborted'));
      },
      { once: true },
    );
  });
}

async function ask(
  path: '/search' | '/reverse',
  params: Record<string, string>,
  signal?: AbortSignal,
): Promise<unknown> {
  await turn(signal);
  const query = new URLSearchParams({ format: 'jsonv2', 'accept-language': 'en', ...params });
  let response: Response;
  try {
    response = await fetch(`${NOMINATIM}${path}?${query.toString()}`, { signal: signal ?? null });
  } catch (error) {
    if (signal?.aborted) throw error;
    throw new PlacesError('offline');
  }
  if (response.status === 429) throw new PlacesError('busy');
  if (!response.ok) throw new PlacesError('unavailable');
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new PlacesError('unavailable');
  }
}

interface RawPlace {
  lat: string;
  lon: string;
  name?: string;
  display_name: string;
}

function isRawPlace(value: unknown): value is RawPlace {
  if (value === null || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v['lat'] === 'string' &&
    typeof v['lon'] === 'string' &&
    typeof v['display_name'] === 'string'
  );
}

/** What adds nothing to an address in Bengaluru: the country, the state, a PIN code. */
const SAID_ALREADY = /^(India|Karnataka|\d{6})$/;

export function placeFrom(raw: RawPlace): Place {
  const parts = raw.display_name.split(', ');
  const own = raw.name?.trim();
  // An address with no name of its own starts with a house number: "83, 5th Main Road", not "83".
  const numbered = !own && parts.length > 1 && /^\d/.test(parts[0] ?? '');
  const name = own || (numbered ? parts.slice(0, 2).join(', ') : parts[0] || raw.display_name);
  const rest = own
    ? parts.filter((part, i) => !(i === 0 && part === own))
    : parts.slice(numbered ? 2 : 1);
  const detail = rest
    .filter((part) => !SAID_ALREADY.test(part))
    .slice(0, 3)
    .join(', ');
  const at = { lat: Number(raw.lat), lng: Number(raw.lon) };
  return { name, detail, at, served: insideServiceArea(at) };
}

/**
 * Places matching `query`, nearest the city first. Places outside the service area stay in the
 * list, marked, so the rider learns why the airport cannot be chosen rather than not finding it.
 */
export async function searchPlaces(query: string, signal?: AbortSignal): Promise<Place[]> {
  const key = query.trim().toLowerCase();
  const known = searches.get(key);
  if (known) return known;
  const { west, north, east, south } = SERVICE_BOUNDS;
  const raw = await ask(
    '/search',
    {
      q: query.trim(),
      countrycodes: 'in',
      viewbox: `${String(west)},${String(north)},${String(east)},${String(south)}`,
      limit: '8',
    },
    signal,
  );
  const named = new Set<string>();
  const places = (Array.isArray(raw) ? raw : [])
    .filter(isRawPlace)
    .map(placeFrom)
    // A suburb, its bus stop and its station often share a name and an address: one row each.
    .filter((place) => {
      const key = `${place.name}|${place.detail}`;
      if (named.has(key)) return false;
      named.add(key);
      return true;
    });
  // A stable sort: the places that can be chosen first, each group in Nominatim's order.
  places.sort((a, b) => Number(b.served) - Number(a.served));
  searches.set(key, places);
  return places;
}

/** The address under a pin, or null where there is none. The place keeps the pin's position. */
export async function placeAt(at: LatLng, signal?: AbortSignal): Promise<Place | null> {
  const key = `${at.lat.toFixed(5)},${at.lng.toFixed(5)}`;
  const known = names.get(key);
  if (known !== undefined) return known;
  const raw = await ask(
    '/reverse',
    { lat: String(at.lat), lon: String(at.lng), zoom: '18' },
    signal,
  );
  const place = isRawPlace(raw) ? { ...placeFrom(raw), at, served: insideServiceArea(at) } : null;
  names.set(key, place);
  return place;
}
