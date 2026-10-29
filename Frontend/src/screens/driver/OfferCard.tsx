import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import { onThisClock } from '@/api/serverClock';
import type { LatLng } from '@/map/MapCanvas';
import { metresBetween } from '@/places/serviceArea';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { Money } from '@/ui/Money';
import { FormAlert } from '../common';
import { distanceText } from '../rider/fare';
import { CURRENT_OFFER, type Offer } from './useOffer';

const RIDE: Record<string, string> = { BIKE: 'Bike', AUTO: 'Auto', CAR: 'Car' };

/**
 * A ride offered to the driver: roughly where the pickup is (the backend rounds it, so a driver who
 * only looks learns nothing precise about the rider), the estimated fare, and the seconds left to
 * take it. Accepting opens the trip; letting it run out, or declining, passes it on.
 */
export function OfferCard({ offer, from }: { offer: Offer; from: LatLng | null }) {
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [expired, setExpired] = useState(false);
  const offerId = offer.offerId ?? '';

  function gone() {
    queries.setQueryData(CURRENT_OFFER, null);
  }

  const accept = useMutation({
    mutationFn: () => api.post('/api/v1/offers/{offerId}/accept', { path: { offerId } }),
    onSuccess: (accepted) => {
      gone();
      if (accepted.tripId) void navigate(`/driver/trip/${accepted.tripId}`);
    },
  });
  const decline = useMutation({
    mutationFn: () => api.post('/api/v1/offers/{offerId}/reject', { path: { offerId } }),
    onSettled: gone,
  });

  const away =
    from && offer.pickup?.lat !== undefined && offer.pickup.lng !== undefined
      ? metresBetween(from, { lat: offer.pickup.lat, lng: offer.pickup.lng })
      : null;
  const failure = accept.error ?? null;
  const busy = accept.isPending || decline.isPending;

  return (
    <section
      aria-label="Ride offer"
      aria-live="assertive"
      className="flex flex-col gap-4 rounded-sheet bg-paper p-5 text-ink"
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex flex-col">
          <h2 className="text-title font-semibold">
            {RIDE[offer.vehicleType ?? ''] ?? 'Ride'} request
          </h2>
          <p className="text-subhead text-ink-soft">
            {away === null ? 'Pickup nearby' : `Pickup ${distanceText(away)} away`}
          </p>
        </div>
        <Money paise={offer.estimatedFarePaise ?? 0} className="text-title font-semibold" />
      </div>

      {expired ? (
        <p className="text-subhead text-ink-soft">
          This offer has expired. The next one will appear here.
        </p>
      ) : (
        <p className="text-subhead text-ink-soft">
          Expires in{' '}
          <Countdown
            until={onThisClock(offer.expiresAt ?? '')}
            label="Expires in"
            onElapsed={() => {
              setExpired(true);
            }}
          />
        </p>
      )}

      {failure && <FormAlert surface="paper" message={describeError(failure).message} />}

      {expired || failure ? (
        <Button variant="secondary" block onClick={gone}>
          Close
        </Button>
      ) : (
        <div className="flex gap-3">
          <Button
            variant="secondary"
            size="large"
            className="flex-1"
            disabled={busy}
            onClick={() => {
              decline.mutate();
            }}
          >
            Decline
          </Button>
          <Button
            size="large"
            className="flex-[2]"
            loading={accept.isPending}
            disabled={busy}
            onClick={() => {
              accept.mutate();
            }}
          >
            Accept
          </Button>
        </div>
      )}
    </section>
  );
}
