import { create } from 'zustand';

interface SessionState {
  /**
   * The access token, in memory only: never localStorage or sessionStorage, where any injected
   * script could read it. A reload loses it, and the refresh token - an httpOnly cookie from
   * Stage 1, which no script can read - gets a new one silently.
   */
  accessToken: string | null;
  signedIn: (accessToken: string) => void;
  signedOut: () => void;
}

export const useSession = create<SessionState>()((set) => ({
  accessToken: null,
  signedIn: (accessToken) => {
    set({ accessToken });
  },
  signedOut: () => {
    set({ accessToken: null });
  },
}));
