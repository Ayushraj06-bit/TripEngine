import { api } from '@/api/client';
import { ApiError } from '@/api/problem';
import { activeTrip } from '@/auth/landing';
import type { VehicleType } from '@/map/NearbyDrivers';
import { useBookingDraft, type Attempt, type PaymentMethod } from '@/state/bookingDraft';

export interface BookingRequest {
  quoteId: string;
  vehicleType: VehicleType;
  paymentMethod: PaymentMethod;
}

/**
 * A new Idempotency-Key, a version 4 UUID. crypto.randomUUID exists only on secure pages;
 * getRandomValues everywhere, so a phone on the office network can still book.
 */
export function newKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** This request's attempt: the one already under way if it was for the same request, else new. */
function attemptFor(request: BookingRequest): Attempt {
  const draft = useBookingDraft.getState();
  const body = JSON.stringify(request);
  if (draft.attempt?.request === body) return draft.attempt;
  const attempt = { key: newKey(), request: body };
  draft.setAttempt(attempt);
  return attempt;
}

function booked(tripId: string): string {
  useBookingDraft.getState().clear();
  return tripId;
}

/** The trip under way, if the server has one: the answer when a booking's own answer was lost. */
async function placedAlready(): Promise<string | null> {
  try {
    return (await activeTrip())?.id ?? null;
  } catch {
    return null;
  }
}

/**
 * Books the ride and answers with the trip's id, or throws why it was not booked.
 *
 * One Idempotency-Key per attempt, kept in the tab: a double tap, a retry after the connection
 * dropped, or a reload mid-booking sends the same key, and the backend books once. A request the
 * server refused outright gets a new key next time - the backend keeps the refusal with the key,
 * and the same key would only be refused again.
 */
export async function placeBooking(request: BookingRequest): Promise<string> {
  const attempt = attemptFor(request);
  try {
    const trip = await api.post('/api/v1/trips', {
      headers: { 'Idempotency-Key': attempt.key },
      body: request,
    });
    if (trip.id) return booked(trip.id);
    throw new ApiError(500, 'INTERNAL_ERROR', 'The booking came back without a trip');
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;

    if (error.code === 'IDEMPOTENCY_KEY_IN_FLIGHT') {
      // This very booking is still being placed: give it the moment the server asks for.
      await new Promise((resolve) => setTimeout(resolve, (error.retryAfterSeconds ?? 1) * 1000));
    }
    // No answer, a server failure, a booking still in flight, or a ride already under way:
    // whatever happened, a trip the server has is the answer.
    const unsure = error.status === 0 || error.status >= 500;
    if (
      unsure ||
      error.code === 'IDEMPOTENCY_KEY_IN_FLIGHT' ||
      error.code === 'ACTIVE_TRIP_EXISTS'
    ) {
      const tripId = await placedAlready();
      if (tripId) return booked(tripId);
    }
    // Refused for good: the next try is a new attempt. Unsure: the next try resends this one.
    if (!unsure && error.code !== 'IDEMPOTENCY_KEY_IN_FLIGHT') {
      useBookingDraft.getState().setAttempt(null);
    }
    throw error;
  }
}
