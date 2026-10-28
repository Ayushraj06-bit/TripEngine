import { cx } from './cx';
import { Icon, type IconName } from './Icon';

/**
 * One choice in a list: "Use my location", "Choose on the map", a place found by search. The whole
 * row is the button. A row that cannot be chosen stays in the list, greyed, with its reason as
 * the detail - so the rider sees why, rather than wondering where it went.
 */
export function OptionRow({
  icon,
  name,
  detail,
  disabled = false,
  onChoose,
}: {
  icon: IconName;
  name: string;
  detail?: string;
  disabled?: boolean;
  onChoose: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      // Read as "Indiranagar, Bengaluru": the two lines would otherwise run together.
      aria-label={detail ? `${name}, ${detail}` : undefined}
      onClick={onChoose}
      className="flex min-h-14 w-full cursor-pointer items-center gap-3 rounded-card px-2 text-left hover:bg-wash disabled:cursor-not-allowed disabled:hover:bg-transparent"
    >
      <span
        className={cx(
          'flex size-9 shrink-0 items-center justify-center rounded-full bg-wash',
          disabled ? 'text-mute' : 'text-ink',
        )}
      >
        <Icon name={icon} />
      </span>
      <span className="flex min-w-0 flex-col">
        <span className={cx('truncate text-body', disabled ? 'text-mute' : 'text-ink')}>
          {name}
        </span>
        {detail && <span className="truncate text-footnote text-ink-soft">{detail}</span>}
      </span>
    </button>
  );
}
