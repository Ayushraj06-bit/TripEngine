import { useMutation } from '@tanstack/react-query';
import { useState, type SubmitEvent } from 'react';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import { onThisClock } from '@/api/serverClock';
import type { Schemas } from '@/api/types';
import { TRIP_RULES } from '@/trip/rules';
import { Button } from '@/ui/Button';
import { cx } from '@/ui/cx';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { FormAlert } from './common';

type Trip = Schemas['TripView'];

const MOST_CHARACTERS = 500;
const DAY_MS = 86_400_000;

/**
 * Trips rated in this tab. The backend says whether a trip has its rating only by refusing a
 * second one; once it has said so, or taken one, the form is not offered again.
 */
const rated = new Set<string>();

export function forgetRatingsForTests(): void {
  rated.clear();
}

/**
 * Rating the other side of a completed trip: one to five stars and, if they like, a few words.
 * Offered while the backend's window is open, with "Not now" as easy to reach as sending.
 */
export function RateTrip({ trip, question }: { trip: Trip; question: string }) {
  const id = trip.id ?? '';
  const [now] = useState(Date.now);
  const [open, setOpen] = useState(true);
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const [problem, setProblem] = useState<string>();
  const [said, setSaid] = useState(rated.has(id) ? 'You’ve rated this trip.' : '');

  const rate = useMutation({
    mutationFn: () =>
      api.post('/api/v1/trips/{tripId}/rating', {
        path: { tripId: id },
        body: { stars, comment: comment.trim() },
      }),
    onSuccess: () => {
      rated.add(id);
      setSaid('Thanks. Your rating has been sent.');
    },
    onError: (error) => {
      const described = describeError(error);
      if (described.code === 'ALREADY_RATED') {
        rated.add(id);
        setSaid('You’ve rated this trip.');
      } else {
        setProblem(described.message);
      }
    },
  });

  if (said) return <p className="text-body">{said}</p>;
  const closesAt = trip.completedAt
    ? onThisClock(trip.completedAt) + TRIP_RULES.ratingWindowDays * DAY_MS
    : 0;
  if (now >= closesAt) return null;

  if (!open) {
    return (
      <Button
        surface="ink"
        variant="secondary"
        className="self-start px-5"
        onClick={() => {
          setOpen(true);
        }}
      >
        Rate this trip
      </Button>
    );
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (stars === 0) {
      setProblem('Choose from 1 to 5 stars.');
      return;
    }
    setProblem(undefined);
    rate.mutate();
  }

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label="Rate this trip"
      className="flex flex-col gap-4 rounded-card bg-paper/6 p-4"
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="pb-2 text-body font-semibold">{question}</legend>
        <div className="flex gap-1">
          {[1, 2, 3, 4, 5].map((n) => (
            <label key={n} className="flex size-11 cursor-pointer items-center justify-center">
              <input
                type="radio"
                name={`stars-${id}`}
                value={n}
                checked={stars === n}
                onChange={() => {
                  setStars(n);
                  setProblem(undefined);
                }}
                className="peer sr-only"
              />
              <span
                aria-hidden="true"
                className={cx(
                  'rounded-input px-1 text-title leading-none peer-focus-visible:outline-2 peer-focus-visible:outline-signal',
                  n <= stars ? 'text-signal' : 'text-paper/30',
                )}
              >
                ★
              </span>
              <span className="sr-only">{n === 1 ? '1 star' : `${String(n)} stars`}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <Field
        label="A few words (optional)"
        hint={`${String(comment.length)} of ${String(MOST_CHARACTERS)} characters`}
        surface="ink"
      >
        {(control) => (
          <Input
            {...control}
            surface="ink"
            maxLength={MOST_CHARACTERS}
            value={comment}
            onChange={(event) => {
              setComment(event.target.value);
            }}
          />
        )}
      </Field>
      {problem && <FormAlert message={problem} />}
      <Button type="submit" surface="ink" size="large" block loading={rate.isPending}>
        Send rating
      </Button>
      <Button
        surface="ink"
        variant="secondary"
        block
        disabled={rate.isPending}
        onClick={() => {
          setOpen(false);
          setProblem(undefined);
        }}
      >
        Not now
      </Button>
    </form>
  );
}
