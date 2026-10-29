import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { api } from '@/api/client';
import type { Schemas } from '@/api/types';
import { listen, onReconnect } from '@/realtime/live';

export type Offer = Schemas['OfferView'];

export const CURRENT_OFFER = ['offer', 'current'] as const;
export const OFFERS = '/user/queue/offers';

/**
 * The offer in front of the driver, or null. Pushed live as it is made or withdrawn, and read
 * over REST on opening, on coming back to the tab, and whenever the connection returns - a push
 * missed while it was down is never an offer missed.
 */
export function useOffer(enabled: boolean) {
  const queries = useQueryClient();
  const offer = useQuery({
    queryKey: CURRENT_OFFER,
    // A 204 when there is none.
    queryFn: async () => ((await api.get('/api/v1/offers/current')) as Offer | undefined) ?? null,
    enabled,
  });

  useEffect(() => {
    if (!enabled) return;
    const stop = listen(OFFERS, (message) => {
      if (message.type === 'OFFER') {
        queries.setQueryData(CURRENT_OFFER, message.payload);
      } else if (message.type === 'OFFER_WITHDRAWN') {
        const withdrawn = (message.payload as { offerId?: string }).offerId;
        const shown = queries.getQueryData<Offer | null>(CURRENT_OFFER);
        if (shown?.offerId === withdrawn) queries.setQueryData(CURRENT_OFFER, null);
      }
    });
    const stopReconnect = onReconnect(() => {
      void queries.invalidateQueries({ queryKey: CURRENT_OFFER });
    });
    return () => {
      stop();
      stopReconnect();
    };
  }, [enabled, queries]);

  return offer;
}
