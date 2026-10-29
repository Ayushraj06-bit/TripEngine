import type { Schemas } from '@/api/types';

export type Quote = Schemas['QuoteView'];

/** "18 min", "1 h 5 min": the quote's own estimate, rounded up to the minute. */
export function minutesText(seconds: number): string {
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  if (minutes < 60) return `${String(minutes)} min`;
  const rest = minutes % 60;
  return rest === 0
    ? `${String(minutes / 60)} h`
    : `${String(Math.floor(minutes / 60))} h ${String(rest)} min`;
}

/** "4.2 km", "850 m". */
export function distanceText(metres: number): string {
  return metres < 1000
    ? `${String(Math.round(metres / 10) * 10)} m`
    : `${(metres / 1000).toFixed(1)} km`;
}

/** "1.4×", or null when prices are as usual. */
export function surgeText(quotes: readonly Quote[]): string | null {
  const highest = Math.max(1, ...quotes.map((q) => q.surgeMultiplier ?? 1));
  return highest > 1 ? `${highest.toFixed(1)}×` : null;
}

/** The lines of a fare, in the order the backend adds them up, leaving out what is zero. */
export function fareLines(quote: Quote): { label: string; paise: number }[] {
  const b = quote.breakdown ?? {};
  return [
    { label: 'Base fare', paise: b.basePaise ?? 0 },
    { label: 'Distance', paise: b.distancePaise ?? 0 },
    { label: 'Time', paise: b.timePaise ?? 0 },
    { label: 'Busy-time surcharge', paise: b.surgePaise ?? 0 },
    { label: 'Up to the minimum fare', paise: b.minimumFareTopUpPaise ?? 0 },
    { label: 'Rounding', paise: b.roundingPaise ?? 0 },
  ].filter((line) => line.paise !== 0);
}
