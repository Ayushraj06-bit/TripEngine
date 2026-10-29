import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { LatLng } from '@/map/MapCanvas';

/** A pickup or a drop the rider has chosen. */
export interface Stop {
  /** "Indiranagar", "Your location", or "Pin on the map". */
  name: string;
  /** Where it is, to tell it apart: "Bengaluru East, Bengaluru". Empty when there is nothing to add. */
  detail: string;
  at: LatLng;
}

export type StopKind = 'pickup' | 'drop';

export type PaymentMethod = 'CASH' | 'WALLET';

/**
 * One booking attempt: the Idempotency-Key it is sent with, and the request it was made for. A
 * retry of the same request reuses the key, so the backend books once however often it is sent.
 */
export interface Attempt {
  key: string;
  request: string;
}

interface BookingDraft {
  /** What the rider typed for pickup and drop, before a place is chosen. */
  pickupQuery: string;
  dropQuery: string;
  pickup: Stop | null;
  drop: Stop | null;
  setPickupQuery: (query: string) => void;
  setDropQuery: (query: string) => void;
  /** How the rider will pay: cash unless they choose the wallet. */
  payment: PaymentMethod;
  /** The booking being placed, until it is placed or refused. */
  attempt: Attempt | null;
  setStop: (kind: StopKind, stop: Stop | null) => void;
  setPayment: (payment: PaymentMethod) => void;
  setAttempt: (attempt: Attempt | null) => void;
  /** Ready for the next ride: everything forgotten but how the rider likes to pay. */
  clear: () => void;
}

/**
 * The booking the rider has started, kept across a detour to sign in and across a reload of the
 * tab - sessionStorage, which ends with the tab. It holds what they typed, the places they chose,
 * how they will pay and the booking being placed - never anything about the session itself.
 */
export const useBookingDraft = create<BookingDraft>()(
  persist(
    (set) => ({
      pickupQuery: '',
      dropQuery: '',
      pickup: null,
      drop: null,
      payment: 'CASH',
      attempt: null,
      setPickupQuery: (pickupQuery) => {
        set({ pickupQuery });
      },
      setDropQuery: (dropQuery) => {
        set({ dropQuery });
      },
      // Choosing a place writes its name into the field; letting one go leaves the text alone.
      setStop: (kind, stop) => {
        if (kind === 'pickup')
          set(stop ? { pickup: stop, pickupQuery: stop.name } : { pickup: null });
        else set(stop ? { drop: stop, dropQuery: stop.name } : { drop: null });
      },
      setPayment: (payment) => {
        set({ payment });
      },
      setAttempt: (attempt) => {
        set({ attempt });
      },
      clear: () => {
        set({ pickupQuery: '', dropQuery: '', pickup: null, drop: null, attempt: null });
      },
    }),
    {
      name: 'rideflow-booking-draft',
      storage: createJSONStorage(() => sessionStorage),
      partialize: ({ pickupQuery, dropQuery, pickup, drop, payment, attempt }) => ({
        pickupQuery,
        dropQuery,
        pickup,
        drop,
        payment,
        attempt,
      }),
    },
  ),
);
