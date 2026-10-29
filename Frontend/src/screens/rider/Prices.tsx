import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { describeError, type Recovery } from '@/api/errors';
import type { VehicleType } from '@/map/NearbyDrivers';
import { useBookingDraft, type PaymentMethod } from '@/state/bookingDraft';
import { Button } from '@/ui/Button';
import { ChoiceCard } from '@/ui/Card';
import { Countdown } from '@/ui/Countdown';
import { Icon, type IconName } from '@/ui/Icon';
import { formatRupees, Money } from '@/ui/Money';
import { SegmentedControl } from '@/ui/SegmentedControl';
import { Skeleton } from '@/ui/Skeleton';
import { FormAlert } from '../common';
import { useNearbyDrivers, useQuotes, useWallet } from '../queries';
import { placeBooking } from './booking';
import { FareBreakdown } from './FareBreakdown';
import { distanceText, minutesText, surgeText, type PricedQuote } from './fare';

const TIER: Record<VehicleType, { name: string; icon: IconName; seats: string }> = {
  BIKE: { name: 'Bike', icon: 'bike', seats: '1 rider' },
  AUTO: { name: 'Auto', icon: 'auto', seats: 'Up to 3 riders' },
  CAR: { name: 'Car', icon: 'car', seats: 'Up to 4 riders' },
};

interface Failure {
  message: string;
  recovery: Recovery;
}

/**
 * The fare for each ride type, and the booking. Prices count down to the moment the server lets
 * them go, on the server's clock; once they have gone, the rider gets new ones before booking.
 */
export function Prices({
  onChangeStops,
  onBooked,
}: {
  onChangeStops: () => void;
  onBooked: (tripId: string) => void;
}) {
  const pickup = useBookingDraft((s) => s.pickup);
  const drop = useBookingDraft((s) => s.drop);
  const payment = useBookingDraft((s) => s.payment);
  const setPayment = useBookingDraft((s) => s.setPayment);
  const quotes = useQuotes(pickup?.at ?? null, drop?.at ?? null);
  const wallet = useWallet();
  const nearby = useNearbyDrivers(pickup?.at ?? null);
  const [picked, setPicked] = useState<VehicleType | null>(null);
  // Which set of prices ran out: a new set, fetched again, is not expired.
  const [expiredFor, setExpiredFor] = useState<number | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  // Pressed once, Confirm stays pressed until that booking fails: a second tap that lands after
  // the first succeeded, before the trip opens, must not start another.
  const placing = useRef(false);

  const book = useMutation({
    mutationFn: placeBooking,
    onSuccess: onBooked,
    onError: (error) => {
      const described = describeError(error);
      setFailure({ message: described.message, recovery: described.recovery });
      if (described.recovery === 'requote') void quotes.refetch();
    },
  });

  const list: PricedQuote[] = quotes.data ?? [];
  const expired = expiredFor !== null && expiredFor === quotes.dataUpdatedAt;
  const chosen = list.find((q) => q.vehicleType === picked) ?? list[0];
  const until = list.length ? Math.min(...list.map((q) => q.validUntil)) : 0;
  const surge = surgeText(list);
  const balance = wallet.data?.balancePaise;
  const fare = chosen?.totalPaise ?? 0;

  function confirm() {
    if (placing.current || !chosen?.quoteId || !chosen.vehicleType) return;
    placing.current = true;
    setFailure(null);
    book.mutate(
      { quoteId: chosen.quoteId, vehicleType: chosen.vehicleType, paymentMethod: payment },
      {
        onError: () => {
          placing.current = false;
        },
      },
    );
  }

  function newPrices() {
    setFailure(null);
    void quotes.refetch();
  }

  if (quotes.isError) {
    const described = describeError(quotes.error);
    return (
      <div className="flex flex-col gap-4 pb-4">
        <h2 className="text-title font-semibold">Choose a ride</h2>
        <FormAlert surface="paper" message={described.message} />
        {described.recovery === 'edit' ? (
          <Button size="large" block onClick={onChangeStops}>
            Change pickup or drop
          </Button>
        ) : (
          <Button size="large" block onClick={newPrices}>
            Try again
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 className="text-title font-semibold">Choose a ride</h2>
        {chosen && (
          <p className="text-subhead text-ink-soft">
            <span className="tabular-nums">
              {distanceText(chosen.distanceMeters ?? 0)} · about{' '}
              {minutesText(chosen.durationSeconds ?? 0)}
            </span>
            {' · '}
            {expired ? (
              'Prices have expired'
            ) : (
              <>
                Prices valid for{' '}
                <Countdown
                  key={quotes.dataUpdatedAt}
                  until={until}
                  label="Prices valid for"
                  onElapsed={() => {
                    setExpiredFor(quotes.dataUpdatedAt);
                  }}
                />
              </>
            )}
          </p>
        )}
        {surge && (
          <p className="text-subhead text-ink">
            Prices are higher right now: <span className="tabular-nums">{surge}</span>
          </p>
        )}
      </div>

      {quotes.isPending || quotes.isFetching ? (
        <div aria-busy="true" aria-label="Getting prices" className="flex flex-col gap-2">
          <Skeleton className="h-18 w-full rounded-card" />
          <Skeleton className="h-18 w-full rounded-card" />
          <Skeleton className="h-18 w-full rounded-card" />
        </div>
      ) : (
        <div role="radiogroup" aria-label="Rides" className="flex flex-col gap-2">
          {list.map((quote) => {
            const type = quote.vehicleType ?? 'CAR';
            const tier = TIER[type];
            const around = nearby?.filter((d) => d.type === type).length;
            return (
              <ChoiceCard
                key={quote.quoteId}
                selected={quote === chosen}
                disabled={expired || book.isPending}
                onClick={() => {
                  setPicked(type);
                }}
              >
                <Icon name={tier.icon} size={24} />
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="text-body font-semibold">{tier.name}</span>
                  <span className="text-footnote text-ink-soft">
                    {tier.seats}
                    {around === undefined
                      ? ''
                      : around === 0
                        ? ' · none nearby right now'
                        : ` · ${String(around)} nearby`}
                  </span>
                </span>
                <Money paise={quote.totalPaise ?? 0} className="text-body font-semibold" />
              </ChoiceCard>
            );
          })}
        </div>
      )}

      {chosen && !quotes.isFetching && <FareBreakdown quote={chosen} />}

      <div className="flex flex-col gap-2">
        <SegmentedControl<PaymentMethod>
          label="Payment"
          value={payment}
          onChange={setPayment}
          segments={[
            { value: 'CASH', label: 'Cash' },
            { value: 'WALLET', label: 'Wallet' },
          ]}
        />
      </div>

      {failure && (
        <div className="flex flex-col gap-2">
          <FormAlert surface="paper" message={failure.message} />
          {failure.recovery === 'edit' && (
            <Button variant="secondary" onClick={onChangeStops}>
              Change pickup or drop
            </Button>
          )}
        </div>
      )}

      {/* Pinned to the bottom of the sheet as its content scrolls beneath, with what the wallet
          means for this ride right above the button it qualifies - never scrolled out of sight. */}
      <div className="sticky bottom-0 -mx-4 bg-paper px-4 pt-2 pb-[max(1rem,env(safe-area-inset-bottom))]">
        {payment === 'WALLET' && (
          <p aria-live="polite" className="pb-2 text-footnote text-ink-soft">
            {balance === undefined
              ? wallet.isError
                ? 'Your wallet balance isn’t available right now.'
                : 'Checking your wallet…'
              : balance >= fare
                ? `Your wallet has ${formatRupees(balance)}.`
                : `Your wallet has ${formatRupees(balance)}. It can go below zero for this ride, and you’ll top it up before the next one.`}
          </p>
        )}
        {expired ? (
          <Button size="large" block onClick={newPrices}>
            Get new prices
          </Button>
        ) : (
          <Button
            size="large"
            block
            loading={book.isPending}
            disabled={!chosen || quotes.isFetching}
            onClick={confirm}
          >
            {chosen?.vehicleType ? `Confirm ${TIER[chosen.vehicleType].name}` : 'Confirm'}
          </Button>
        )}
      </div>
    </div>
  );
}
