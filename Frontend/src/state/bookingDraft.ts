import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface BookingDraft {
  /** What the rider typed for pickup and drop, before a place is chosen (Stage 3). */
  pickupQuery: string;
  dropQuery: string;
  setPickupQuery: (query: string) => void;
  setDropQuery: (query: string) => void;
  clear: () => void;
}

/**
 * The booking the rider has started, kept across a detour to sign in and across a reload of the
 * tab - sessionStorage, which ends with the tab. It holds only what they typed, nothing that
 * identifies them; the session itself is never stored in the browser.
 */
export const useBookingDraft = create<BookingDraft>()(
  persist(
    (set) => ({
      pickupQuery: '',
      dropQuery: '',
      setPickupQuery: (pickupQuery) => {
        set({ pickupQuery });
      },
      setDropQuery: (dropQuery) => {
        set({ dropQuery });
      },
      clear: () => {
        set({ pickupQuery: '', dropQuery: '' });
      },
    }),
    {
      name: 'rideflow-booking-draft',
      storage: createJSONStorage(() => sessionStorage),
      partialize: ({ pickupQuery, dropQuery }) => ({ pickupQuery, dropQuery }),
    },
  ),
);
