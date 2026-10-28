import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '@/app/router';
import { sendToSignIn } from '@/app/sessionEnded';
import { installAuth, resetSessionForTests } from '@/auth/auth';
import type { LatLng } from '@/map/MapCanvas';
import { resetPlacesForTests } from '@/places/nominatim';
import { useBookingDraft } from '@/state/bookingDraft';
import { useSession, type SessionUser } from '@/state/session';
import { useToasts } from '@/ui/toasts';
import { seen } from './backend';

/** Every test starts signed out, with nothing typed, nothing stored and nothing fetched. */
export function resetApp(): void {
  resetSessionForTests();
  useSession.setState({ status: 'signed-out', accessToken: null, user: null, handoff: false });
  useBookingDraft.getState().clear();
  useToasts.setState({ toasts: [] });
  sessionStorage.clear();
  localStorage.clear();
  seen.length = 0;
  resetPlacesForTests();
  Reflect.deleteProperty(navigator, 'geolocation');
}

/** As if `user` had signed in: the token in memory, the profile known. */
export function signedInAs(user: SessionUser): void {
  useSession.setState({
    status: 'signed-in',
    accessToken: `access-for-${user.id}`,
    user,
    handoff: false,
  });
}

/** The whole app, with its real routes and guards, opened at `path`. */
export function renderApp(path: string, state?: unknown) {
  const queries = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const url = new URL(path, 'http://localhost');
  const router = createMemoryRouter(routes, {
    initialEntries: [{ pathname: url.pathname, search: url.search, state }],
  });
  installAuth(() => {
    sendToSignIn(router, queries);
  });
  render(
    <QueryClientProvider client={queries}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { router, queries };
}

/** Types into the sign-in form and sends it. */
export async function signInWith(
  phone: string,
  password = 'correct-horse',
  user = userEvent.setup(),
) {
  await user.type(await screen.findByLabelText('Phone number'), phone);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

/** The screen's own alert. The toaster's live regions stay mounted, outside <main>, all along. */
export function screenAlert() {
  return within(screen.getByRole('main')).findByRole('alert');
}

function geolocation(answer: (ok: PositionCallback, fail: PositionErrorCallback) => void) {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: (ok: PositionCallback, fail: PositionErrorCallback) => {
        answer(ok, fail);
      },
    },
  });
}

/** The device says it is at `at`, to within `accuracy` metres. */
export function deviceAt(at: LatLng, accuracy = 15): void {
  geolocation((ok) => {
    ok({
      coords: { latitude: at.lat, longitude: at.lng, accuracy },
      timestamp: Date.now(),
    } as GeolocationPosition);
  });
}

/** The rider said no to sharing their location (1), or it could not be found (2), or in time (3). */
export function locationFails(code: 1 | 2 | 3): void {
  geolocation((_ok, fail) => {
    fail({ code, message: '' } as GeolocationPositionError);
  });
}
