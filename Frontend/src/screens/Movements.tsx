import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import type { Schemas } from '@/api/types';
import { Button } from '@/ui/Button';
import { formatRupees } from '@/ui/Money';

type Movement = Schemas['Movement'];

/** Under the wallet's key, so whatever refreshes the balance refreshes these pages too. */
export const WALLET_PAGES = ['wallet', 'pages'] as const;

const WHAT: Record<NonNullable<Movement['type']>, string> = {
  TOPUP: 'Top-up',
  TRIP_CHARGE: 'Ride',
  CASH_COMMISSION: 'RideFlow’s commission on a cash ride',
  CANCELLATION_FEE: 'Cancellation fee',
  REFUND: 'Refund',
};

const WHEN = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
});

/**
 * The signed-in user's account - a rider's wallet or a driver's earnings - newest first, a page at
 * a time. The balance is the first page's: the server's, never added up here.
 */
export function useWalletPages() {
  return useInfiniteQuery({
    queryKey: WALLET_PAGES,
    queryFn: ({ pageParam }) =>
      api.get('/api/v1/wallet', { query: pageParam ? { cursor: pageParam } : {} }),
    initialPageParam: '',
    getNextPageParam: (page) => page.nextCursor,
  });
}

/** Money in and out of the account, with "Show earlier" for as long as there is more. */
export function Movements({ pages }: { pages: ReturnType<typeof useWalletPages> }) {
  const movements = pages.data?.pages.flatMap((page) => page.movements ?? []) ?? [];
  return (
    <section aria-labelledby="activity" className="flex flex-col gap-2">
      <h2 id="activity" className="text-title font-semibold">
        Activity
      </h2>
      {movements.length === 0 ? (
        <p className="text-subhead text-paper/70">Nothing yet.</p>
      ) : (
        <ul>
          {movements.map((m) => (
            <li
              key={m.entryId}
              className="flex items-center justify-between gap-4 border-b border-paper/12 py-3"
            >
              <span className="flex flex-col">
                <span className="text-body">{m.type ? WHAT[m.type] : 'Payment'}</span>
                {m.createdAt && (
                  <span className="text-footnote text-paper/70">
                    {WHEN.format(new Date(m.createdAt))}
                  </span>
                )}
              </span>
              <span className="text-body tabular-nums">
                {m.direction === 'CREDIT' ? '+' : '−'}
                {formatRupees(m.amountPaise ?? 0)}
              </span>
            </li>
          ))}
        </ul>
      )}
      {pages.hasNextPage && (
        <Button
          surface="ink"
          variant="secondary"
          loading={pages.isFetchingNextPage}
          onClick={() => {
            void pages.fetchNextPage();
          }}
        >
          Show earlier
        </Button>
      )}
    </section>
  );
}
