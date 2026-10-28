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

interface BookingDraft {
  /** What the rider typed for pickup and drop, before a place is chosen. */
  pickupQuery: string;
  dropQuery: string;
  pickup: Stop | null;
  drop: Stop | null;
  setPickupQuery: (query: string) => void;
  setDropQuery: (query: string) => void;
  setStop: (kind: StopKind, stop: Stop | null) => void;
  clear: () => void;
}

/**
 * The booking the rider has started, kept across a detour to sign in and across a reload of the
 * tab - sessionStorage, which ends with the tab. It holds what they typed and the places they
 * chose, never anything about the session itself.
 */
export const useBookingDraft = create<BookingDraft>()(
  persist(
    (set) => ({
      pickupQuery: '',
      dropQuery: '',
      pickup: null,
      drop: null,
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
      clear: () => {
        set({ pickupQuery: '', dropQuery: '', pickup: null, drop: null });
      },
    }),
    {
      name: 'rideflow-booking-draft',
      storage: createJSONStorage(() => sessionStorage),
      partialize: ({ pickupQuery, dropQuery, pickup, drop }) => ({
        pickupQuery,
        dropQuery,
        pickup,
        drop,
      }),
    },
  ),
);
