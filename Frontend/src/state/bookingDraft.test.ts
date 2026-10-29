import { beforeEach, describe, expect, it } from 'vitest';
import { useBookingDraft } from './bookingDraft';

const MG_ROAD = { name: 'MG Road', detail: 'Bengaluru', at: { lat: 12.9756, lng: 77.6069 } };

beforeEach(() => {
  useBookingDraft.getState().clear();
});

describe('the booking draft', () => {
  it('writes a chosen place’s name into its field', () => {
    useBookingDraft.getState().setStop('pickup', MG_ROAD);
    expect(useBookingDraft.getState()).toMatchObject({ pickup: MG_ROAD, pickupQuery: 'MG Road' });
  });

  it('leaves the typed text alone when a place is let go', () => {
    useBookingDraft.getState().setStop('drop', MG_ROAD);
    useBookingDraft.getState().setDropQuery('MG Ro');
    useBookingDraft.getState().setStop('drop', null);
    expect(useBookingDraft.getState()).toMatchObject({ drop: null, dropQuery: 'MG Ro' });
  });

  it('keeps places in the tab’s session storage, and nothing in local storage', () => {
    useBookingDraft.getState().setStop('drop', MG_ROAD);
    const stored = JSON.parse(sessionStorage.getItem('rideflow-booking-draft') ?? '{}') as {
      state?: { drop?: unknown };
    };
    expect(stored.state?.drop).toEqual(MG_ROAD);
    expect(localStorage.length).toBe(0);
  });

  it('forgets the ride after booking, but not how the rider likes to pay', () => {
    const draft = useBookingDraft.getState();
    draft.setStop('pickup', MG_ROAD);
    draft.setPayment('WALLET');
    draft.setAttempt({ key: 'k-1', request: '{}' });
    draft.clear();
    expect(useBookingDraft.getState()).toMatchObject({
      pickup: null,
      attempt: null,
      payment: 'WALLET',
    });
  });
});
