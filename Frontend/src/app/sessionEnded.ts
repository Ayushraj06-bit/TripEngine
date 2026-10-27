import type { QueryClient } from '@tanstack/react-query';
import type { createBrowserRouter } from 'react-router';
import { toast } from '@/ui/toasts';

type Router = Pick<ReturnType<typeof createBrowserRouter>, 'state' | 'navigate'>;

/**
 * A session that can no longer be renewed: say so, forget everything fetched for it, and go to
 * sign in - which brings the user back to where they were, with any booking they had started
 * still in its draft.
 */
export function sendToSignIn(router: Router, queries: QueryClient): void {
  const { pathname, search } = router.state.location;
  queries.clear();
  toast('Your session has ended. Sign in to continue.');
  void router.navigate(`/sign-in?returnTo=${encodeURIComponent(pathname + search)}`, {
    replace: true,
  });
}
