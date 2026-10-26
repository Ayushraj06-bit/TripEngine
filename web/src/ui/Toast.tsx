import { cx } from './cx';
import { Icon } from './Icon';
import { useToasts, type ToastItem } from './toasts';

const BAR: Record<ToastItem['tone'], string> = {
  info: 'border-l-mute',
  success: 'border-l-go',
  error: 'border-l-stop',
};

function ToastCard({ toast }: { toast: ToastItem }) {
  const dismiss = useToasts((state) => state.dismiss);
  return (
    <div
      data-surface="ink"
      className={cx(
        'pointer-events-auto flex items-center gap-2 rounded-card border-l-4 bg-ink py-2 pr-2 pl-4 text-body text-paper',
        BAR[toast.tone],
      )}
    >
      <p className="flex-1 py-2">{toast.message}</p>
      {toast.action && (
        <button
          type="button"
          className="min-h-11 cursor-pointer rounded-input px-3 font-semibold text-signal"
          onClick={() => {
            toast.action?.onClick();
            dismiss(toast.id);
          }}
        >
          {toast.action.label}
        </button>
      )}
      <button
        type="button"
        aria-label="Dismiss"
        className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-input text-paper/70"
        onClick={() => {
          dismiss(toast.id);
        }}
      >
        <Icon name="close" />
      </button>
    </div>
  );
}

/**
 * Where toasts appear: at the top, because the sheet owns the bottom of the screen. Errors are
 * announced at once; everything else waits for the screen reader to finish.
 */
export function Toaster() {
  const toasts = useToasts((state) => state.toasts);
  const errors = toasts.filter((t) => t.tone === 'error');
  const others = toasts.filter((t) => t.tone !== 'error');
  return (
    <div className="pointer-events-none fixed inset-x-0 top-0 z-50 flex flex-col gap-2 p-4 pt-[max(1rem,env(safe-area-inset-top))]">
      <div role="alert" className="flex flex-col gap-2">
        {errors.map((t) => (
          <ToastCard key={t.id} toast={t} />
        ))}
      </div>
      <div role="status" className="flex flex-col gap-2">
        {others.map((t) => (
          <ToastCard key={t.id} toast={t} />
        ))}
      </div>
    </div>
  );
}
