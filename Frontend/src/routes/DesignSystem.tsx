import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Button } from '@/ui/Button';
import { Card, ChoiceCard } from '@/ui/Card';
import { Countdown } from '@/ui/Countdown';
import { Field } from '@/ui/Field';
import { Icon, ICON_NAMES } from '@/ui/Icon';
import { Input } from '@/ui/Input';
import { Money } from '@/ui/Money';
import { OptionRow } from '@/ui/OptionRow';
import { Row } from '@/ui/Row';
import { SegmentedControl } from '@/ui/SegmentedControl';
import { Skeleton } from '@/ui/Skeleton';
import { Spinner } from '@/ui/Spinner';
import { toast } from '@/ui/toasts';
import { TopBar } from '@/ui/TopBar';

const TOKENS = ['ink', 'ink-soft', 'mute', 'paper', 'wash', 'signal', 'go', 'stop'] as const;

/** The swatch classes, spelled out so Tailwind sees them. */
const SWATCH: Record<(typeof TOKENS)[number], string> = {
  ink: 'bg-ink',
  'ink-soft': 'bg-ink-soft',
  mute: 'bg-mute',
  paper: 'bg-paper',
  wash: 'bg-wash',
  signal: 'bg-signal',
  go: 'bg-go',
  stop: 'bg-stop',
};

/** Read from the theme at run time: the values are written down in theme.css alone. */
function tokenValue(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(`--${name}`).trim();
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section
      aria-labelledby={`ds-${title}`}
      className="flex flex-col gap-4 border-t border-wash py-8"
    >
      <h2 id={`ds-${title}`} className="text-body font-semibold">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Caption({ children }: { children: ReactNode }) {
  return <p className="text-footnote text-ink-soft">{children}</p>;
}

/**
 * Every primitive in every state, in one place to review. Sample content here is labelled as
 * such; the app's screens never show made-up values.
 */
export function DesignSystem() {
  const [tier, setTier] = useState('auto');
  const [account, setAccount] = useState<'RIDER' | 'DRIVER'>('RIDER');
  const [phone, setPhone] = useState('98765 43210');
  const [countdownFrom] = useState(() => Date.now() + 2 * 60_000);

  return (
    <main className="mx-auto flex max-w-2xl flex-col px-4 pb-16">
      <title>Design system · RideFlow</title>
      <header className="flex flex-col gap-2 py-8">
        <h1 className="text-title font-semibold">Design system</h1>
        <Caption>
          Every primitive in every state. The sheet and the map, together, are on{' '}
          <Link to="/design-system/map" className="font-semibold text-ink underline">
            their own page
          </Link>
          .
        </Caption>
      </header>

      <Section title="Colour">
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {TOKENS.map((name) => (
            <li key={name} className="flex flex-col gap-1">
              <span className={`h-14 rounded-card border border-mute/30 ${SWATCH[name]}`} />
              <span className="text-footnote font-semibold">--{name}</span>
              <span className="text-footnote text-ink-soft tabular-nums">{tokenValue(name)}</span>
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Type">
        <p className="text-large font-semibold tabular-nums">
          <Money paise={14800} /> · 4:59
        </p>
        <Caption>
          Large title, 34/41: a dark screen’s title, and the two numbers that matter - the fare on a
          tier card, the arrival countdown.
        </Caption>
        <p className="text-title font-semibold">Choose a ride</p>
        <Caption>Title, 22/28: section heads, and the name in the top bar.</Caption>
        <p className="text-body font-semibold">Confirm Auto</p>
        <p className="text-body">Your driver is on the way.</p>
        <Caption>Body, 17/22: what is read, at 400 and at 600 for emphasis.</Caption>
        <p className="text-subhead">Use the phone number you signed up with.</p>
        <Caption>Subhead, 15/20: the lead under a title, and the links in a bar.</Caption>
        <p className="text-footnote text-ink-soft">Prices valid for 4:59</p>
        <Caption>
          Footnote, 13/18: labels and hints. Apple’s text styles, in the device’s own font - San
          Francisco on Apple devices, Segoe UI on Windows, Roboto on Android. Weights 400 and 600
          only; figures tabular wherever they count.
        </Caption>
      </Section>

      <Section title="Button">
        <div className="flex flex-wrap gap-3">
          <Button>Confirm Auto</Button>
          <Button variant="secondary">Change</Button>
          <Button variant="quiet">Skip</Button>
        </div>
        <div className="flex flex-wrap gap-3">
          <Button disabled>Confirm Auto</Button>
          <Button variant="secondary" disabled>
            Change
          </Button>
          <Button loading>Confirm Auto</Button>
        </div>
        <Button size="large" block>
          Confirm Auto
        </Button>
        <div className="flex flex-wrap gap-3">
          <Button capsule>Sign up</Button>
          <Button capsule variant="secondary">
            Sign in
          </Button>
        </div>
        <div data-surface="ink" className="flex flex-wrap gap-3 rounded-card bg-ink p-4">
          <Button surface="ink">Accept ride</Button>
          <Button surface="ink" variant="secondary">
            Decline
          </Button>
          <Button surface="ink" variant="quiet">
            Details
          </Button>
          <Button surface="ink" loading>
            Accept ride
          </Button>
        </div>
        <Caption>
          On the white sheet the primary button is ink; on ink surfaces it is signal. Large, for the
          one action that ends a screen; capsules, for chips and the actions in a bar.
        </Caption>
      </Section>

      <Section title="Field">
        <Field label="Phone number" hint="10 digits. +91 is added for you.">
          {(control) => (
            <Input
              {...control}
              inputMode="tel"
              value={phone}
              onChange={(event) => {
                setPhone(event.target.value);
              }}
            />
          )}
        </Field>
        <Field label="Empty, with a placeholder">
          {(control) => <Input {...control} placeholder="Where to?" />}
        </Field>
        <Field label="Password" error="Use 8 to 72 characters.">
          {(control) => <Input {...control} type="password" defaultValue="short" />}
        </Field>
        <Field label="Disabled">
          {(control) => <Input {...control} disabled defaultValue="Not editable" />}
        </Field>
        <div data-surface="ink" className="flex flex-col gap-4 rounded-card bg-ink p-4">
          <Field label="Where to?" surface="ink">
            {(control) => (
              <Input
                {...control}
                surface="ink"
                placeholder="Where to?"
                leading={<span className="block size-3 bg-paper" />}
              />
            )}
          </Field>
          <Field label="Password, on ink" error="Use 8 to 72 characters." surface="ink">
            {(control) => <Input {...control} surface="ink" type="password" defaultValue="short" />}
          </Field>
        </div>
        <Caption>
          On ink an error is light text beside a stop-red mark: red text on ink is too faint to read
          at this size.
        </Caption>
      </Section>

      <Section title="Segmented control">
        <SegmentedControl
          label="Sample account type"
          value={account}
          onChange={setAccount}
          segments={[
            { value: 'RIDER', label: 'Rider' },
            { value: 'DRIVER', label: 'Driver' },
          ]}
        />
        <Caption>
          One of a few choices, all in view. A single tab stop; the arrow keys move and choose.
        </Caption>
      </Section>

      <Section title="Option row">
        <ul aria-label="Sample options" className="flex flex-col">
          <li>
            <OptionRow icon="locate" name="Use my location" onChoose={() => undefined} />
          </li>
          <li>
            <OptionRow
              icon="drop"
              name="Indiranagar"
              detail="Bengaluru East City Corporation, Bengaluru"
              onChoose={() => undefined}
            />
          </li>
          <li>
            <OptionRow
              icon="drop"
              name="Kempegowda International Airport"
              detail="Outside the area RideFlow serves"
              disabled
              onChoose={() => undefined}
            />
          </li>
        </ul>
        <Caption>
          Sample places. A row that cannot be chosen stays in the list, with its reason.
        </Caption>
      </Section>

      <Section title="Bar and materials">
        <div className="overflow-hidden rounded-card border border-wash">
          <TopBar
            surface="paper"
            actions={
              <Button capsule variant="secondary">
                Sign out
              </Button>
            }
          />
        </div>
        <div data-surface="ink" className="overflow-hidden rounded-card bg-ink">
          <TopBar
            surface="ink"
            actions={
              <Button capsule surface="ink" variant="secondary">
                Sign in
              </Button>
            }
          />
        </div>
        <div className="relative grid h-28 grid-cols-4 overflow-hidden rounded-card">
          <span className="bg-signal" />
          <span className="bg-go" />
          <span className="bg-stop" />
          <span className="bg-wash" />
          <p className="absolute inset-x-0 top-0 flex h-14 items-center px-4 text-subhead font-semibold material">
            material
          </p>
          <p className="absolute inset-x-0 bottom-0 flex h-14 items-center px-4 text-subhead font-semibold text-paper material-ink">
            material-ink
          </p>
        </div>
        <Caption>
          Frosted glass for bars over the map and the dark screens, so what passes beneath still
          shows through. Solid instead when the device asks for reduced transparency.
        </Caption>
      </Section>

      <Section title="Row">
        <div className="flex flex-col">
          <Row label="Pickup" value="Connaught Place" />
          <Row
            label="Drop"
            value="Cyber Hub, Gurugram"
            action={
              <Button variant="quiet" className="px-3">
                Change
              </Button>
            }
          />
          <Row label="Wallet" value={<Money paise={124000} />} numeric />
          <Row label="Arrives" value={<Skeleton className="h-4 w-20" />} />
        </div>
        <Caption>
          Sample content. A value not yet known is a skeleton, never a placeholder number.
        </Caption>
      </Section>

      <Section title="Card">
        <Card>
          <p className="text-body">Inset on wash.</p>
        </Card>
        <Card outlined>
          <p className="text-body">
            Outlined. Neither carries a shadow; the sheet has the only one.
          </p>
        </Card>
        <div role="radiogroup" aria-label="Sample ride tiers" className="flex flex-col gap-2">
          {[
            {
              id: 'bike',
              name: 'Bike',
              note: 'Cheapest, 1 rider',
              icon: 'bike' as const,
              paise: 8400,
            },
            {
              id: 'auto',
              name: 'Auto',
              note: 'Three-wheeler, 3 riders',
              icon: 'auto' as const,
              paise: 14800,
            },
            {
              id: 'car',
              name: 'Car',
              note: 'Air-conditioned, 4 riders',
              icon: 'car' as const,
              paise: 22600,
            },
          ].map((option) => (
            <ChoiceCard
              key={option.id}
              selected={tier === option.id}
              onClick={() => {
                setTier(option.id);
              }}
            >
              <Icon name={option.icon} size={24} />
              <span className="flex flex-1 flex-col">
                <span className="text-body font-semibold">{option.name}</span>
                <span className="text-footnote text-ink-soft">{option.note}</span>
              </span>
              <Money paise={option.paise} className="text-body font-semibold" />
            </ChoiceCard>
          ))}
          <ChoiceCard selected={false} disabled>
            <span className="text-body">Unavailable option</span>
          </ChoiceCard>
        </div>
        <Caption>Sample tiers. The selected one is outlined in signal.</Caption>
      </Section>

      <Section title="Skeleton">
        <div aria-busy="true" aria-label="Loading sample" className="flex flex-col gap-2">
          <Skeleton className="h-16 w-full rounded-card" />
          <Skeleton className="h-16 w-full rounded-card" />
          <Skeleton className="h-4 w-32" />
        </div>
        <Caption>Still: motion is kept for what is actually moving.</Caption>
      </Section>

      <Section title="Spinner">
        <div className="flex items-center gap-4">
          <Spinner label="Loading" />
          <Spinner size={24} label="Loading" />
        </div>
        <Caption>Only while a request is in flight; still under reduced motion.</Caption>
      </Section>

      <Section title="Toast">
        <div className="flex flex-wrap gap-3">
          <Button
            variant="secondary"
            onClick={() => {
              toast('Wallet topped up', 'success');
            }}
          >
            Success
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              toast('Your driver’s connection is weak. The trip continues.');
            }}
          >
            Info
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              toast('No connection. Check your network and try again.', 'error', {
                label: 'Retry',
                onClick: () => undefined,
              });
            }}
          >
            Error with action
          </Button>
        </div>
        <Caption>
          At the top of the screen: the sheet owns the bottom. Errors are announced at once.
        </Caption>
      </Section>

      <Section title="Money and time">
        <p className="text-body">
          <Money paise={124000} /> · <Money paise={8450} /> · <Money paise={0} />
        </p>
        <p className="text-body font-semibold">
          Prices valid for <Countdown until={countdownFrom} label="Prices valid for" />
        </p>
        <Caption>Paise from the backend become rupees in one place, with Indian grouping.</Caption>
      </Section>

      <Section title="Icon">
        <ul className="grid grid-cols-4 gap-3 sm:grid-cols-8">
          {ICON_NAMES.map((name) => (
            <li key={name} className="flex flex-col items-center gap-1 text-ink-soft">
              <Icon name={name} size={24} />
              <span className="text-footnote">{name}</span>
            </li>
          ))}
        </ul>
      </Section>
    </main>
  );
}
