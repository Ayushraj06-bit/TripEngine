import { useEffect } from 'react';
import { useRouteError } from 'react-router';
import { Button } from '@/ui/Button';

/**
 * Shown in place of a route that failed to render, so one broken screen does not take the rest
 * of the app down with it.
 */
export function RouteError() {
  const error = useRouteError();
  useEffect(() => {
    console.error('Route failed to render', error);
  }, [error]);
  return (
    <main className="flex min-h-dvh flex-col items-start justify-center gap-4 p-6">
      <title>Something went wrong · RideFlow</title>
      <h1 className="text-title font-semibold">This screen didn’t load</h1>
      <p className="text-body text-ink-soft">
        Reload it to try again. Your ride, if you have one, is unaffected.
      </p>
      <Button
        onClick={() => {
          window.location.reload();
        }}
      >
        Reload
      </Button>
    </main>
  );
}
