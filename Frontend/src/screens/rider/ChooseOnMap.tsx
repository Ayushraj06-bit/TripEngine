import { useQuery } from '@tanstack/react-query';
import type { LatLng } from '@/map/MapCanvas';
import { placeAt } from '@/places/nominatim';
import { insideServiceArea } from '@/places/serviceArea';
import { useBookingDraft, type Stop, type StopKind } from '@/state/bookingDraft';
import { Button } from '@/ui/Button';
import { position, tooClose } from './stops';

/**
 * The sheet while the rider moves the map under the pin: what is under it, by name once the
 * address arrives and by position until then, and the button that takes it. A pin outside the
 * service area, or on top of the other stop, cannot be taken - and the sheet says why.
 */
export function ChooseOnMap({
  kind,
  at,
  onConfirm,
}: {
  kind: StopKind;
  /** The point under the pin, once the map has settled. */
  at: LatLng | null;
  onConfirm: (stop: Stop) => void;
}) {
  const other = useBookingDraft((s) => (kind === 'pickup' ? s.drop : s.pickup));
  const address = useQuery({
    queryKey: ['place-at', at?.lat.toFixed(5), at?.lng.toFixed(5)],
    queryFn: ({ signal }) => (at ? placeAt(at, signal) : null),
    enabled: at !== null,
    retry: false,
    staleTime: Infinity,
  });

  const served = at !== null && insideServiceArea(at);
  const clash = at && served ? tooClose(kind, at, other) : null;
  const name = address.data?.name ?? 'Pin on the map';
  const detail = address.data?.detail || (at ? position(at) : '');
  const status = !at
    ? 'Finding the pin…'
    : !served
      ? 'Outside the area RideFlow serves. Move the pin into Bengaluru.'
      : (clash ?? (address.isFetching ? 'Finding the address…' : null));

  return (
    <div className="flex flex-col gap-3 pb-4">
      <div aria-live="polite" className="flex min-h-11 flex-col justify-center">
        <p className="truncate text-body font-semibold">
          {at && served ? name : `Set your ${kind}`}
        </p>
        <p className="truncate text-footnote text-ink-soft">{status ?? detail}</p>
      </div>
      <Button
        size="large"
        block
        disabled={!at || !served || clash !== null}
        onClick={() => {
          if (at)
            onConfirm({ name, detail: address.data ? address.data.detail : position(at), at });
        }}
      >
        {kind === 'pickup' ? 'Confirm pickup' : 'Confirm drop'}
      </Button>
    </div>
  );
}
