import { create } from 'zustand';
import type { Schemas } from '@/api/types';

export type Role = 'RIDER' | 'DRIVER' | 'ADMIN';

export interface SessionUser {
  id: string;
  name: string;
  phone: string;
  role: Role;
}

/** Signed in, signed out, or not known yet: a reload is renewing the session from its cookie. */
export type SessionStatus = 'restoring' | 'signed-in' | 'signed-out';

interface SessionState {
  status: SessionStatus;
  /**
   * The access token, in memory only: never localStorage or sessionStorage, where any injected
   * script could read it. A reload loses it, and the refresh token - an httpOnly cookie no script
   * can read (ADR 0017) - gets a new one silently.
   */
  accessToken: string | null;
  user: SessionUser | null;
  /**
   * Signed in on a public page that is now sending the user on: it decides where - their trip,
   * or the page they came from - so the public pages must not send them home first.
   */
  handoff: boolean;
  /** A new access token for the same session, after a renewal. */
  signedIn: (accessToken: string) => void;
  /** The user behind the session, once known: the moment the app counts as signed in. */
  identified: (user: SessionUser, handoff?: boolean) => void;
  /** The user has arrived where the sign-in was sending them. */
  arrived: () => void;
  signedOut: () => void;
}

export const useSession = create<SessionState>()((set) => ({
  status: 'restoring',
  accessToken: null,
  user: null,
  handoff: false,
  signedIn: (accessToken) => {
    set({ accessToken });
  },
  identified: (user, handoff = false) => {
    set({ user, status: 'signed-in', handoff });
  },
  arrived: () => {
    set({ handoff: false });
  },
  signedOut: () => {
    set({ accessToken: null, user: null, status: 'signed-out', handoff: false });
  },
}));

/** The profile the backend returns, checked: every field the app relies on must be there. */
export function sessionUser(profile: Schemas['UserProfile']): SessionUser {
  const { id, name, phone, role } = profile;
  if (!id || !name || !phone || !role) throw new Error('The profile is missing a field');
  return { id, name, phone, role };
}
