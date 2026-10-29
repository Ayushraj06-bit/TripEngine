import { useEffect } from 'react';
import { renewSession } from '@/api/client';
import { useSession } from '@/state/session';
import { startLive, stopLive, useLive } from './live';

/**
 * Keeps the live connection open while someone is signed in, and says so when it is down. Nothing
 * else changes while it reconnects: the screen shows what REST last said, and reads it again the
 * moment the connection is back.
 */
export function LiveConnection() {
  const signedIn = useSession((state) => state.status === 'signed-in');
  const status = useLive((state) => state.status);

  useEffect(() => {
    if (!signedIn) return;
    startLive((renew) =>
      renew ? renewSession() : Promise.resolve(useSession.getState().accessToken),
    );
    return stopLive;
  }, [signedIn]);

  if (status !== 'reconnecting') return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 top-[calc(3.5rem+env(safe-area-inset-top))] z-50 flex justify-center px-4 pt-2">
      <p
        role="status"
        className="rounded-full border border-mute/40 bg-paper px-4 py-2 text-footnote font-semibold text-ink"
      >
        Reconnecting…
      </p>
    </div>
  );
}
