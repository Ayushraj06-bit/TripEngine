import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent } from 'react';
import { listen, onReconnect } from './live';

export const TRIP_UPDATES = '/user/queue/trip-updates';

// The highest seq applied, per stream - a trip's updates, or its driver's positions, counted apart
// as the backend says: compared across destinations, a mere position would hide a trip update.
// Delivery is not ordered, so anything at or below it is a duplicate or already overtaken.
const applied = new Map<string, number>();

export function resetTripLiveForTests(): void {
  applied.clear();
}

/**
 * Whether a message numbered `seq` is newer than everything applied so far on its stream - a
 * trip's id for its updates, `location:` and the id for its driver's positions; if so, it is now.
 */
export function isNewer(stream: string, seq: number): boolean {
  if ((applied.get(stream) ?? -1) >= seq) return false;
  applied.set(stream, seq);
  return true;
}

/**
 * A trip kept current: each pushed state replaces the one shown (each message is the whole trip as
 * the server has it, not a change), and the trip is read again over REST when the connection
 * comes back, in case something was pushed while it was down.
 */
export function useTripLive(
  tripId: string,
  queryKey: readonly unknown[],
  /** Told what happened - "TripReturnedToMatching" - for anything a new state alone cannot say. */
  onEvent?: (type: string) => void,
): void {
  const queries = useQueryClient();
  const happened = useEffectEvent((type: string) => {
    onEvent?.(type);
  });
  useEffect(() => {
    const stop = listen(TRIP_UPDATES, (message) => {
      if (message.tripId !== tripId || typeof message.seq !== 'number') return;
      if (!isNewer(tripId, message.seq)) return;
      queries.setQueryData(queryKey, message.payload);
      happened(message.type);
    });
    const stopReconnect = onReconnect(() => {
      void queries.invalidateQueries({ queryKey });
    });
    return () => {
      stop();
      stopReconnect();
    };
    // queryKey is built from tripId by the caller.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queries, tripId]);
}
