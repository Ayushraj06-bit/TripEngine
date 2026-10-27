import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useSession, type Role } from '@/state/session';

/** Each role's own front page. */
export function homeFor(role: Role): string {
  return role === 'DRIVER' ? '/driver' : role === 'ADMIN' ? '/admin' : '/';
}

/**
 * While a reload renews the session: the dark canvas the first screen will sit on, and the name.
 * Nothing pretends to be content that has not loaded.
 */
export function Splash() {
  return (
    <main
      aria-busy="true"
      aria-label="Loading RideFlow"
      data-surface="ink"
      className="flex min-h-dvh items-center justify-center bg-ink text-paper"
    >
      <span className="text-title font-semibold">RideFlow</span>
    </main>
  );
}

/**
 * Screens for one role. Signed out, the front door goes to the welcome screen and any other
 * address to sign in, which brings the user back here afterwards. The wrong role goes home.
 */
export function RequireRole({ only }: { only: Role }) {
  const status = useSession((state) => state.status);
  const user = useSession((state) => state.user);
  const handoff = useSession((state) => state.handoff);
  const arrived = useSession((state) => state.arrived);
  const location = useLocation();
  const allowed = status === 'signed-in' && user?.role === only;

  useEffect(() => {
    if (allowed && handoff) arrived();
  }, [allowed, handoff, arrived]);

  if (status === 'restoring') return <Splash />;
  if (status === 'signed-out' || !user) {
    if (location.pathname === '/') return <Navigate to="/welcome" replace />;
    const back = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/sign-in?returnTo=${back}`} replace />;
  }
  if (user.role !== only) return <Navigate to={homeFor(user.role)} replace />;
  return <Outlet />;
}

/** Welcome, sign in and create account: for the signed-out. Anyone signed in goes home. */
export function PublicOnly() {
  const status = useSession((state) => state.status);
  const user = useSession((state) => state.user);
  const handoff = useSession((state) => state.handoff);
  if (status === 'restoring') return <Splash />;
  // A sign-in on this page is sending the user on itself; anyone else signed in goes home.
  if (status === 'signed-in' && user && !handoff)
    return <Navigate to={homeFor(user.role)} replace />;
  return <Outlet />;
}
