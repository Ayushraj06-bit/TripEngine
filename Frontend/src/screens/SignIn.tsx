import { useState, type SubmitEvent } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { describeError } from '@/api/errors';
import { ApiError } from '@/api/problem';
import { signIn } from '@/auth/auth';
import { landingFor } from '@/auth/landing';
import { normalisePhone } from '@/auth/phone';
import { useBookingDraft } from '@/state/bookingDraft';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { DarkScreen, FormAlert, PasswordInput } from './common';

interface Arrival {
  /** Why sign in was asked for: "See prices" on the welcome screen. */
  reason?: 'see-prices';
  /** Carried over from a "Sign in instead" link. */
  phone?: string;
}

/** Waiting out a rate limit or a lock, until this moment. */
interface Wait {
  until: number;
  code: string;
}

export function SignIn() {
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const arrival = (location.state ?? {}) as Arrival;
  const returnTo = params.get('returnTo');
  const back = returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : '';
  const drop = useBookingDraft((state) => state.dropQuery);

  const [phone, setPhone] = useState(arrival.phone ?? '');
  const [password, setPassword] = useState('');
  const [phoneError, setPhoneError] = useState<string>();
  const [passwordError, setPasswordError] = useState<string>();
  const [failure, setFailure] = useState<string>();
  const [wait, setWait] = useState<Wait>();
  const [submitting, setSubmitting] = useState(false);

  const lead =
    arrival.reason === 'see-prices'
      ? `Sign in to see prices${drop.trim() ? ` to ${drop.trim()}` : ''}.`
      : 'Use the phone number you signed up with.';

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(undefined);
    const number = normalisePhone(phone);
    setPhoneError(number.ok ? undefined : number.reason);
    setPasswordError(password ? undefined : 'Enter your password.');
    if (!number.ok || !password) return;

    setSubmitting(true);
    try {
      const user = await signIn(number.e164, password);
      await navigate(await landingFor(user, returnTo), { replace: true });
    } catch (error) {
      const described = describeError(error);
      if (described.code === 'PHONE_INVALID') {
        setPhoneError(described.message);
      } else {
        setFailure(described.message);
      }
      // Too many attempts, or a lock: the button waits as long as the server says to.
      const seconds = error instanceof ApiError ? error.retryAfterSeconds : undefined;
      if (seconds !== undefined)
        setWait({ until: Date.now() + seconds * 1000, code: described.code });
    } finally {
      setSubmitting(false);
    }
  }

  // Cleared by the countdown when the wait is over.
  const waiting = wait !== undefined;

  return (
    <DarkScreen
      title="Sign in"
      lead={lead}
      actions={
        <Link
          to={`/sign-up${back}`}
          className="flex min-h-11 items-center rounded-full bg-paper/12 px-4 text-subhead font-semibold"
        >
          Create account
        </Link>
      }
    >
      <form onSubmit={(event) => void submit(event)} noValidate className="flex flex-col gap-5">
        {failure && <FormAlert message={failure} />}
        <Field label="Phone number" error={phoneError} surface="ink">
          {(control) => (
            <Input
              {...control}
              surface="ink"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              placeholder="98765 43210"
              value={phone}
              onChange={(event) => {
                setPhone(event.target.value);
              }}
            />
          )}
        </Field>
        <Field label="Password" error={passwordError} surface="ink">
          {(control) => (
            <PasswordInput
              {...control}
              surface="ink"
              autoComplete="current-password"
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
              }}
            />
          )}
        </Field>
        <Button
          type="submit"
          surface="ink"
          size="large"
          block
          loading={submitting}
          disabled={waiting}
        >
          {waiting && wait.code === 'RATE_LIMITED' ? (
            <>
              {/* The timer's own label reads this; said twice otherwise. */}
              <span aria-hidden="true">Try again in </span>
              <Countdown
                until={wait.until}
                label="Try again in"
                onElapsed={() => {
                  setWait(undefined);
                }}
              />
            </>
          ) : waiting ? (
            <>
              Locked
              <span className="sr-only">
                ,{' '}
                <Countdown
                  until={wait.until}
                  label="unlocks in"
                  onElapsed={() => {
                    setWait(undefined);
                  }}
                />
              </span>
            </>
          ) : (
            'Sign in'
          )}
        </Button>
        <p className="text-center text-subhead text-paper/70">
          New to RideFlow?{' '}
          <Link
            to={`/sign-up${back}`}
            className="font-semibold text-paper underline underline-offset-4"
          >
            Create an account
          </Link>
        </p>
      </form>
    </DarkScreen>
  );
}
