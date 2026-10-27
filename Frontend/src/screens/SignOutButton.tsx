import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { signOut } from '@/auth/auth';
import type { Surface } from '@/ui/Button';
import { cx } from '@/ui/cx';

/** Ends the session here and on the server, and forgets everything fetched for it. */
export function SignOutButton({ surface = 'ink' }: { surface?: Surface }) {
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      disabled={busy}
      aria-busy={busy || undefined}
      onClick={() => {
        setBusy(true);
        void signOut().then(() => {
          queries.clear();
          return navigate('/welcome', { replace: true });
        });
      }}
      className={cx(
        'flex min-h-11 cursor-pointer items-center rounded-full px-4 text-subhead font-semibold',
        surface === 'ink' ? 'bg-paper/12 text-paper' : 'material text-ink',
      )}
    >
      Sign out
    </button>
  );
}
