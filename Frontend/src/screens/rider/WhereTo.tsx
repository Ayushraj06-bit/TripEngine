import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { locate, ROUGH_METRES, type Located, type LocationFailure } from '@/places/location';
import { PlacesError, searchPlaces, type Place, type PlacesFailure } from '@/places/nominatim';
import { insideServiceArea } from '@/places/serviceArea';
import { useBookingDraft, type Stop, type StopKind } from '@/state/bookingDraft';
import { Icon } from '@/ui/Icon';
import { Input } from '@/ui/Input';
import { OptionRow } from '@/ui/OptionRow';
import { Spinner } from '@/ui/Spinner';
import { otherKind, tooClose } from './stops';

const LOCATION_WORDS: Record<LocationFailure | 'outside', string> = {
  denied: 'Location is off for RideFlow. Search for your pickup, or choose it on the map.',
  unavailable: 'Your location isn’t available. Search for your pickup, or choose it on the map.',
  timeout: 'Finding your location took too long. Try again, or choose your pickup on the map.',
  outside: 'You’re outside the area RideFlow serves. Choose a pickup in Bengaluru.',
};

const SEARCH_WORDS: Record<PlacesFailure, string> = {
  offline: 'No connection. Check your network and search again.',
  busy: 'Place search is busy. Try again in a moment, or choose the place on the map.',
  unavailable: 'Place search isn’t available right now. Choose the place on the map.',
};

const LABEL: Record<StopKind, string> = { pickup: 'Pickup', drop: 'Drop' };

interface Submitted {
  kind: StopKind;
  query: string;
}

function Note({ children, busy = false }: { children: ReactNode; busy?: boolean }) {
  return (
    <p className="flex items-center gap-2 px-2 py-3 text-subhead text-ink-soft">
      {busy && <Spinner />}
      {children}
    </p>
  );
}

/**
 * Where from and where to. The pickup starts as the rider's own location, asked for here, in
 * context. Each field searches when the rider presses Search - never as they type (ADR 0020) -
 * and either can be chosen on the map instead.
 */
export function WhereTo({
  initial,
  onChooseOnMap,
  onDone,
  onClose,
}: {
  /** The field to start in. */
  initial: StopKind;
  onChooseOnMap: (kind: StopKind) => void;
  /** Both stops are chosen. */
  onDone: () => void;
  onClose: () => void;
}) {
  const pickup = useBookingDraft((s) => s.pickup);
  const drop = useBookingDraft((s) => s.drop);
  const pickupQuery = useBookingDraft((s) => s.pickupQuery);
  const dropQuery = useBookingDraft((s) => s.dropQuery);
  const setPickupQuery = useBookingDraft((s) => s.setPickupQuery);
  const setDropQuery = useBookingDraft((s) => s.setDropQuery);
  const setStop = useBookingDraft((s) => s.setStop);

  const [active, setActive] = useState<StopKind>(initial);
  // A drop typed on the welcome screen is searched for at once: the rider already asked.
  const [submitted, setSubmitted] = useState<Submitted | null>(() => {
    const draft = useBookingDraft.getState();
    return initial === 'drop' && !draft.drop && draft.dropQuery.trim()
      ? { kind: 'drop', query: draft.dropQuery }
      : null;
  });
  const [locating, setLocating] = useState(() => useBookingDraft.getState().pickup === null);
  const [locationNote, setLocationNote] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const fields = { pickup: useRef<HTMLInputElement>(null), drop: useRef<HTMLInputElement>(null) };

  const results = useQuery({
    queryKey: ['places', submitted?.query.trim().toLowerCase()],
    queryFn: ({ signal }) => searchPlaces(submitted?.query ?? '', signal),
    enabled: submitted !== null && submitted.query.trim() !== '',
    retry: false,
    staleTime: Infinity,
  });

  function found(answer: Located) {
    setLocating(false);
    // The rider chose a pickup while the device was still answering: theirs wins.
    if (useBookingDraft.getState().pickup) return;
    if (!answer.ok) {
      setLocationNote(LOCATION_WORDS[answer.reason]);
      return;
    }
    if (!insideServiceArea(answer.at)) {
      setLocationNote(LOCATION_WORDS.outside);
      return;
    }
    const rough = answer.accuracyMetres > ROUGH_METRES;
    const metres = String(Math.round(answer.accuracyMetres / 10) * 10);
    setStop('pickup', {
      name: 'Your location',
      detail: rough ? `Approximate, within ${metres} m` : '',
      at: answer.at,
    });
    setLocationNote(
      rough
        ? `Your location is approximate, to within ${metres} m. Check the pickup on the map.`
        : null,
    );
  }

  // Asked once, as the sheet opens, unless a pickup is already chosen.
  useEffect(() => {
    if (useBookingDraft.getState().pickup) return;
    let live = true;
    void locate().then((answer) => {
      if (live) found(answer);
    });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once, on opening
  }, []);

  useEffect(() => {
    fields[active].current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the refs never change
  }, [active]);

  function locateAgain() {
    setLocating(true);
    setLocationNote(null);
    setStop('pickup', null);
    void locate().then(found);
  }

  function choose(kind: StopKind, stop: Stop) {
    const other = kind === 'pickup' ? drop : pickup;
    const clash = tooClose(kind, stop.at, other);
    setProblem(clash);
    if (clash) return;
    setStop(kind, stop);
    setSubmitted(null);
    if (kind === 'pickup') setLocationNote(null);
    if (other) onDone();
    else setActive(otherKind(kind));
  }

  function field(kind: StopKind) {
    const value = kind === 'pickup' ? pickupQuery : dropQuery;
    const chosen = kind === 'pickup' ? pickup : drop;
    return (
      <form
        role="search"
        aria-label={LABEL[kind]}
        onSubmit={(event) => {
          event.preventDefault();
          setActive(kind);
          setProblem(null);
          setSubmitted({ kind, query: value });
        }}
      >
        <Input
          ref={fields[kind]}
          type="search"
          enterKeyHint="search"
          autoComplete="off"
          aria-label={LABEL[kind]}
          placeholder={
            kind === 'pickup'
              ? locating
                ? 'Finding your location…'
                : 'Pickup location'
              : 'Where to?'
          }
          value={value}
          onFocus={() => {
            setActive(kind);
          }}
          onChange={(event) => {
            if (kind === 'pickup') setPickupQuery(event.target.value);
            else setDropQuery(event.target.value);
            // Typing over a chosen place starts a new search for that field.
            if (chosen) setStop(kind, null);
          }}
          leading={
            kind === 'pickup' ? (
              <span className="block size-3 rounded-full border-[3px] border-ink" />
            ) : (
              <span className="block size-3 bg-ink" />
            )
          }
          trailing={
            <button
              type="submit"
              aria-label={`Search for ${kind === 'pickup' ? 'a pickup' : 'a drop'}`}
              className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-input text-ink-soft"
            >
              <Icon name="search" />
            </button>
          }
        />
      </form>
    );
  }

  const shown = submitted?.kind === active ? submitted : null;
  const error = results.error instanceof PlacesError ? results.error.reason : 'unavailable';

  return (
    <div className="flex flex-col gap-4 pb-4">
      <div className="flex items-center justify-between">
        <h2 className="text-title font-semibold">Plan your ride</h2>
        <button
          type="button"
          aria-label="Close"
          onClick={onClose}
          className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-full bg-wash text-ink"
        >
          <Icon name="close" />
        </button>
      </div>

      <div className="relative flex flex-col gap-2">
        <span aria-hidden="true" className="absolute top-6 bottom-6 left-[21px] w-px bg-mute/60" />
        {field('pickup')}
        {field('drop')}
      </div>

      <div aria-live="polite" className="flex flex-col gap-1">
        {problem && <p className="px-2 text-subhead text-stop">{problem}</p>}
        {locationNote && <p className="px-2 text-subhead text-ink-soft">{locationNote}</p>}
      </div>

      <ul aria-label={`Ways to choose your ${active}`} className="flex flex-col">
        {active === 'pickup' && (
          <li>
            <OptionRow
              icon="locate"
              name={locating ? 'Finding your location…' : 'Use my location'}
              disabled={locating}
              onChoose={locateAgain}
            />
          </li>
        )}
        <li>
          <OptionRow
            icon={active === 'pickup' ? 'pickup' : 'drop'}
            name="Choose on the map"
            onChoose={() => {
              onChooseOnMap(active);
            }}
          />
        </li>
      </ul>

      <div aria-live="polite">
        {shown === null ? (
          <Note>Search for a place, or choose it on the map.</Note>
        ) : results.isFetching ? (
          <Note busy>Searching for “{shown.query.trim()}”…</Note>
        ) : results.isError ? (
          <Note>{SEARCH_WORDS[error]}</Note>
        ) : results.data && results.data.length === 0 ? (
          <Note>
            No places match “{shown.query.trim()}”. Check the spelling, or choose it on the map.
          </Note>
        ) : (
          <ul aria-label={`Places matching “${shown.query.trim()}”`} className="flex flex-col">
            {(results.data ?? []).map((place: Place) => (
              <li key={`${place.name}-${String(place.at.lat)}-${String(place.at.lng)}`}>
                <OptionRow
                  icon={active === 'pickup' ? 'pickup' : 'drop'}
                  name={place.name}
                  detail={place.served ? place.detail : 'Outside the area RideFlow serves'}
                  disabled={!place.served}
                  onChoose={() => {
                    choose(active, { name: place.name, detail: place.detail, at: place.at });
                  }}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
