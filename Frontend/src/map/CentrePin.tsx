import { useEffect, useEffectEvent, useState } from 'react';
import { useMap } from 'react-leaflet';
import type { StopKind } from '@/state/bookingDraft';
import { useLayout, type MapInset } from '@/ui/layout';
import type { LatLng } from './MapCanvas';

/** The top bar's height: the pin sits in the middle of the map that is actually visible. */
const BAR = 56;

/** Where the pin's tip is, in the map's pixels: the centre of the part neither bar nor sheet covers. */
export function pinPoint(
  size: { x: number; y: number },
  inset: MapInset,
): { x: number; y: number } {
  return {
    x: inset.left + (size.x - inset.left) / 2,
    y: BAR + (size.y - BAR - inset.bottom) / 2,
  };
}

/**
 * Choosing a place by moving the map under a fixed pin, as in the ride apps riders know. When it
 * starts, `start` is brought under the pin; each time the map settles, the point under the pin is
 * reported. The map moves with a finger, a mouse, or the arrow keys once it has focus.
 */
export function CentrePin({
  kind,
  start,
  onSettle,
}: {
  kind: StopKind;
  start: LatLng;
  onSettle: (at: LatLng) => void;
}) {
  const map = useMap();
  const inset = useLayout((state) => state.inset);
  const [size, setSize] = useState(() => map.getSize());
  const point = pinPoint(size, inset);

  const settled = useEffectEvent(() => {
    const now = map.getSize();
    // Not laid out (a hidden tab, a test): nothing has moved since the start was reported.
    if (now.x === 0 || now.y === 0) return;
    const tip = pinPoint(now, useLayout.getState().inset);
    const at = map.containerPointToLatLng([tip.x, tip.y]);
    onSettle({ lat: at.lat, lng: at.lng });
  });

  const begin = useEffectEvent(() => {
    const now = map.getSize();
    const tip = pinPoint(now, useLayout.getState().inset);
    map.setView([start.lat, start.lng], Math.max(map.getZoom(), 16), { animate: false });
    // setView centres the start; shift it from the map's centre to the pin's.
    map.panBy([now.x / 2 - tip.x, now.y / 2 - tip.y], { animate: false });
    onSettle(start);
  });

  useEffect(() => {
    begin();
    const onMoveEnd = () => {
      settled();
    };
    const onResize = () => {
      setSize(map.getSize());
    };
    map.on('moveend', onMoveEnd);
    map.on('resize', onResize);
    return () => {
      map.off('moveend', onMoveEnd);
      map.off('resize', onResize);
    };
  }, [map]);

  // The sheet moved: what is under the pin changed with it.
  useEffect(() => {
    settled();
  }, [inset.bottom, inset.left]);

  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute z-1000 flex -translate-x-1/2 -translate-y-full flex-col items-center"
      style={{ left: point.x, top: point.y }}
    >
      {kind === 'pickup' ? (
        <span className="block size-6 rounded-full border-[6px] border-ink bg-paper" />
      ) : (
        <span className="block size-5 bg-ink outline-2 outline-paper" />
      )}
      <span className="block h-4 w-0.5 bg-ink" />
    </div>
  );
}
