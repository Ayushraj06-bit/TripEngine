import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import type { Schemas } from '@/api/types';
import { Button } from '@/ui/Button';
import { FormAlert } from '../common';
import { DRIVER_PROFILE } from '../queries';

/**
 * Why a driver cannot go online right now, from their profile - said before they try, rather
 * than left for the server to refuse. Null when nothing stands in the way.
 */
export function cannotGoOnline(profile: Schemas['DriverProfile']): string | null {
  if (!profile.vehicle) return 'Add your vehicle before going online.';
  if (profile.approvalStatus !== 'APPROVED') return 'You can go online once you’re approved.';
  if (profile.status === 'ON_TRIP') return 'You’re on a trip. Finish it before going offline.';
  return null;
}

/** Online or offline, with the reason when it cannot be changed, and the server's word if it refuses. */
export function OnlineToggle({ profile }: { profile: Schemas['DriverProfile'] }) {
  const queries = useQueryClient();
  const online = profile.status === 'AVAILABLE';
  const reason = cannotGoOnline(profile);

  const change = useMutation({
    mutationFn: () =>
      online ? api.post('/api/v1/drivers/me/offline') : api.post('/api/v1/drivers/me/online'),
    onSettled: () => queries.invalidateQueries({ queryKey: DRIVER_PROFILE }),
  });

  return (
    <div className="flex flex-col gap-3">
      {change.isError && <FormAlert message={describeError(change.error).message} />}
      <Button
        surface="ink"
        size="large"
        block
        variant={online ? 'secondary' : 'primary'}
        loading={change.isPending}
        disabled={reason !== null}
        aria-describedby={reason ? 'online-reason' : undefined}
        onClick={() => {
          change.mutate();
        }}
      >
        {online ? 'Go offline' : 'Go online'}
      </Button>
      {reason && (
        <p id="online-reason" className="text-center text-subhead text-paper/70">
          {reason}
        </p>
      )}
    </div>
  );
}
