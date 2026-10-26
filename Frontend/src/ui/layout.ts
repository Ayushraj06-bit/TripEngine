import { useSyncExternalStore } from 'react';
import { create } from 'zustand';

/**
 * How much of the screen the sheet covers, so the map fits its pins into the part that is still
 * visible instead of under the sheet. On phones the sheet rises from the bottom; from tablet
 * width it becomes a panel down the left side.
 */
export interface MapInset {
  bottom: number;
  left: number;
}

interface LayoutState {
  inset: MapInset;
  setInset: (inset: MapInset) => void;
}

export const useLayout = create<LayoutState>()((set) => ({
  inset: { bottom: 0, left: 0 },
  setInset: (inset) => {
    set({ inset });
  },
}));

/** Where the sheet stops being a bottom sheet and becomes a side panel: Tailwind's md. */
export const WIDE_QUERY = '(min-width: 768px)';

function subscribeTo(query: string) {
  return (onChange: () => void) => {
    const list = window.matchMedia(query);
    list.addEventListener('change', onChange);
    return () => {
      list.removeEventListener('change', onChange);
    };
  };
}

export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    subscribeTo(query),
    () => window.matchMedia(query).matches,
    () => false,
  );
}

function subscribeToResize(onChange: () => void) {
  window.addEventListener('resize', onChange);
  return () => {
    window.removeEventListener('resize', onChange);
  };
}

/** The visible height, which on phones changes as the browser's bars come and go. */
export function useViewportHeight(): number {
  return useSyncExternalStore(
    subscribeToResize,
    () => window.innerHeight,
    () => 800,
  );
}
