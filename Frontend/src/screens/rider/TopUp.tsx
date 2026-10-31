import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type SubmitEvent } from 'react';
import { describeError } from '@/api/errors';
import { Button } from '@/ui/Button';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { formatRupees } from '@/ui/Money';
import { SegmentedControl } from '@/ui/SegmentedControl';
import { FormAlert } from '../common';
import { WALLET } from '../queries';
import { newKey } from './booking';
import { topUp, type TopupStatus } from './gateway';

type Choice = '100' | '200' | '500' | 'other';

const SEGMENTS = [
  { value: '100', label: '₹100' },
  { value: '200', label: '₹200' },
  { value: '500', label: '₹500' },
  { value: 'other', label: 'Other' },
] as const;

function told(status: TopupStatus, paise: number): string {
  return status === 'SUCCEEDED'
    ? `${formatRupees(paise)} added to your wallet.`
    : status === 'FAILED'
      ? 'The payment didn’t go through, so nothing was added.'
      : 'The payment is still being confirmed. Your balance will show it once it is.';
}

/** Adding money: an amount, paid at the gateway, and how it ended - said, not guessed. */
export function TopUp() {
  const queries = useQueryClient();
  const [choice, setChoice] = useState<Choice>('200');
  const [other, setOther] = useState('');
  const [problem, setProblem] = useState<string>();
  const [outcome, setOutcome] = useState('');
  // The top-up under way. Tried again for the same amount after an error - an answer lost, the
  // gateway briefly down - it keeps its key, so the backend finds it and the rider pays once.
  const attempt = useRef<{ key: string; paise: number } | null>(null);
  const paise =
    choice === 'other' ? (/^\d+$/.test(other) ? Number(other) * 100 : null) : Number(choice) * 100;

  const add = useMutation({
    mutationFn: (amount: number) => {
      if (attempt.current?.paise !== amount) attempt.current = { key: newKey(), paise: amount };
      return topUp(amount, attempt.current.key);
    },
    onSuccess: (status, amount) => {
      // Settled or not, the backend has it: the next top-up is a new one.
      attempt.current = null;
      setOutcome(told(status, amount));
      void queries.invalidateQueries({ queryKey: WALLET });
    },
    onError: (error) => {
      setProblem(describeError(error).message);
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setOutcome('');
    if (paise === null) {
      setProblem('Enter an amount in whole rupees.');
      return;
    }
    setProblem(undefined);
    add.mutate(paise);
  }

  return (
    <section aria-labelledby="add-money" className="flex flex-col gap-3">
      <h2 id="add-money" className="text-title font-semibold">
        Add money
      </h2>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        <SegmentedControl
          label="Amount"
          segments={SEGMENTS}
          value={choice}
          onChange={(next) => {
            setChoice(next);
            setProblem(undefined);
          }}
          surface="ink"
        />
        {choice === 'other' && (
          <Field label="Amount in rupees" error={problem} surface="ink">
            {(control) => (
              <Input
                {...control}
                surface="ink"
                inputMode="numeric"
                value={other}
                onChange={(event) => {
                  setOther(event.target.value.trim());
                }}
              />
            )}
          </Field>
        )}
        {problem && choice !== 'other' && <FormAlert message={problem} />}
        <Button type="submit" surface="ink" size="large" block loading={add.isPending}>
          {paise ? `Add ${formatRupees(paise)}` : 'Add money'}
        </Button>
        <p aria-live="polite" className="text-subhead text-paper/70">
          {add.isPending ? 'Waiting for the payment to be confirmed…' : outcome}
        </p>
      </form>
    </section>
  );
}
