import { useState, type SubmitEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { describeError } from '@/api/errors';
import { ApiError } from '@/api/problem';
import { register, type AccountRole } from '@/auth/auth';
import { landingFor } from '@/auth/landing';
import { normalisePhone } from '@/auth/phone';
import { Button } from '@/ui/Button';
import { Countdown } from '@/ui/Countdown';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { SegmentedControl } from '@/ui/SegmentedControl';
import { DarkScreen, FormAlert, PasswordInput } from './common';

/** The backend's rule (BCrypt reads no further than 72 bytes), stated before anyone types. */
const PASSWORD_RULE = '8 to 72 characters.';

type Fields = 'name' | 'phone' | 'password';

/** Field errors from a 400 VALIDATION_FAILED: the backend lists them field by field. */
function fieldErrors(error: unknown): Partial<Record<Fields, string>> {
  if (!(error instanceof ApiError)) return {};
  const list = error.properties['errors'];
  if (!Array.isArray(list)) return {};
  const found: Partial<Record<Fields, string>> = {};
  for (const item of list as unknown[]) {
    if (item === null || typeof item !== 'object') continue;
    const { field, message } = item as { field?: unknown; message?: unknown };
    if (
      (field === 'name' || field === 'phone' || field === 'password') &&
      typeof message === 'string'
    ) {
      found[field] = `${message.charAt(0).toUpperCase()}${message.slice(1)}.`;
    }
  }
  return found;
}

export function SignUp() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const returnTo = params.get('returnTo');
  const back = returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : '';
  const [role, setRole] = useState<AccountRole>(params.get('as') === 'driver' ? 'DRIVER' : 'RIDER');
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Partial<Record<Fields, string>>>({});
  const [taken, setTaken] = useState<string>();
  const [failure, setFailure] = useState<string>();
  const [waitUntil, setWaitUntil] = useState<number>();
  const [submitting, setSubmitting] = useState(false);

  const preview = normalisePhone(phone);

  function validate(): Partial<Record<Fields, string>> {
    const found: Partial<Record<Fields, string>> = {};
    if (!name.trim()) found.name = 'Enter your name.';
    else if (name.trim().length > 100) found.name = 'Use at most 100 characters.';
    if (!preview.ok) found.phone = preview.reason;
    if (password.length < 8 || password.length > 72)
      found.password = `Use ${PASSWORD_RULE.toLowerCase()}`;
    return found;
  }

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(undefined);
    setTaken(undefined);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0 || !preview.ok) return;

    setSubmitting(true);
    try {
      const user = await register(role, { name: name.trim(), phone: preview.e164, password });
      await navigate(role === 'DRIVER' ? '/driver/vehicle' : await landingFor(user, returnTo), {
        replace: true,
      });
    } catch (error) {
      const described = describeError(error);
      if (described.code === 'PHONE_ALREADY_REGISTERED') {
        setTaken(preview.display);
        setErrors({ phone: described.message });
      } else if (described.code === 'PHONE_INVALID') {
        setErrors({ phone: described.message });
      } else if (described.code === 'VALIDATION_FAILED' && Object.keys(fieldErrors(error)).length) {
        setErrors(fieldErrors(error));
      } else {
        setFailure(described.message);
      }
      if (error instanceof ApiError && error.retryAfterSeconds !== undefined) {
        setWaitUntil(Date.now() + error.retryAfterSeconds * 1000);
      }
    } finally {
      setSubmitting(false);
    }
  }

  // Cleared by the countdown when the wait is over.
  const waiting = waitUntil !== undefined;

  return (
    <DarkScreen
      title="Create account"
      lead={
        role === 'DRIVER'
          ? 'Next you’ll add your vehicle. You can drive once RideFlow approves your account.'
          : 'Book a ride in a minute, and follow it to your door.'
      }
      actions={
        <Link
          to={`/sign-in${back}`}
          className="flex min-h-11 items-center rounded-full bg-paper/12 px-4 text-subhead font-semibold"
        >
          Sign in
        </Link>
      }
    >
      <form onSubmit={(event) => void submit(event)} noValidate className="flex flex-col gap-5">
        <SegmentedControl
          label="Account type"
          surface="ink"
          value={role}
          onChange={setRole}
          segments={[
            { value: 'RIDER', label: 'Rider' },
            { value: 'DRIVER', label: 'Driver' },
          ]}
        />
        {failure && <FormAlert message={failure} />}
        <Field label="Name" error={errors.name} surface="ink">
          {(control) => (
            <Input
              {...control}
              surface="ink"
              autoComplete="name"
              value={name}
              onChange={(event) => {
                setName(event.target.value);
              }}
            />
          )}
        </Field>
        <Field
          label="Phone number"
          surface="ink"
          error={errors.phone}
          hint={
            preview.ok ? (
              <>
                We’ll use <span className="tabular-nums">{preview.display}</span>
              </>
            ) : (
              '10 digits. +91 is added for you.'
            )
          }
        >
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
        {taken && (
          <Link
            to={`/sign-in${back}`}
            state={{ phone: taken }}
            className="-mt-3 self-start text-subhead font-semibold text-paper underline underline-offset-4"
          >
            Sign in with {taken}
          </Link>
        )}
        <Field label="Password" hint={PASSWORD_RULE} error={errors.password} surface="ink">
          {(control) => (
            <PasswordInput
              {...control}
              surface="ink"
              autoComplete="new-password"
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
          {waiting ? (
            <>
              {/* The timer's own label reads this; said twice otherwise. */}
              <span aria-hidden="true">Try again in </span>
              <Countdown
                until={waitUntil}
                label="Try again in"
                onElapsed={() => {
                  setWaitUntil(undefined);
                }}
              />
            </>
          ) : role === 'DRIVER' ? (
            'Continue'
          ) : (
            'Create account'
          )}
        </Button>
      </form>
    </DarkScreen>
  );
}
