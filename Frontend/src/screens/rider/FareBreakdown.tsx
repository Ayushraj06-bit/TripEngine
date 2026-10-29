import { Icon } from '@/ui/Icon';
import { Money } from '@/ui/Money';
import { fareLines, type Quote } from './fare';

/**
 * How a fare is made, line by line from the backend's breakdown, closed until asked for. A native
 * disclosure: the keyboard and screen readers already know how to open it.
 */
export function FareBreakdown({ quote }: { quote: Quote }) {
  return (
    <details className="group rounded-card bg-wash px-4">
      <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between text-subhead font-semibold [&::-webkit-details-marker]:hidden">
        How this fare is made
        <span className="text-ink-soft transition-transform group-open:rotate-90 motion-reduce:transition-none">
          <Icon name="chevron" size={16} />
        </span>
      </summary>
      <dl className="flex flex-col gap-1 pb-3">
        {fareLines(quote).map((line) => (
          <div key={line.label} className="flex justify-between gap-4 text-subhead">
            <dt className="text-ink-soft">{line.label}</dt>
            <dd>
              <Money paise={line.paise} />
            </dd>
          </div>
        ))}
        <div className="mt-1 flex justify-between gap-4 border-t border-mute/30 pt-2 text-body font-semibold">
          <dt>Total</dt>
          <dd>
            <Money paise={quote.totalPaise ?? 0} />
          </dd>
        </div>
      </dl>
    </details>
  );
}
