import { useState } from 'react';
import { Navigate } from 'react-router';
import { MapCanvas } from '@/map/MapCanvas';
import { useBookingDraft } from '@/state/bookingDraft';
import { useSession } from '@/state/session';
import { Icon } from '@/ui/Icon';
import { Sheet, type SheetHeight } from '@/ui/Sheet';
import { TopBar } from '@/ui/TopBar';
import { useActiveTrip } from '../queries';
import { SignOutButton } from '../SignOutButton';

/**
 * The rider's map. A rider with a trip under way is taken straight to it - the server decides,
 * every time this loads. Choosing a pickup and a drop arrives in Stages 2 and 3; until then the
 * sheet keeps what was typed on the welcome screen.
 */
export function RiderHome() {
  const trip = useActiveTrip();
  const name = useSession((state) => state.user?.name ?? '');
  const drop = useBookingDraft((state) => state.dropQuery);
  const [height, setHeight] = useState<SheetHeight>('peek');

  if (trip.data) return <Navigate to={`/trip/${trip.data.id}`} replace />;

  return (
    <>
      <title>RideFlow</title>
      <MapCanvas label="Map of the city" zoom={13} />
      <TopBar surface="paper" actions={<SignOutButton surface="paper" />} />
      <Sheet height={height} onHeightChange={setHeight} label="Where to?">
        <div className="flex flex-col gap-3 pb-4">
          <p className="text-subhead text-ink-soft">
            {name ? `Hello, ${name.split(' ')[0] ?? name}.` : 'Hello.'}
          </p>
          <div className="flex min-h-12 items-center gap-3 rounded-input bg-wash px-3 text-body">
            <Icon name="search" />
            <span className={drop ? 'text-ink' : 'text-mute'}>{drop || 'Where to?'}</span>
          </div>
          <p className="text-footnote text-ink-soft">
            Choosing your pickup and drop arrives in the next stages of this app.
          </p>
        </div>
      </Sheet>
    </>
  );
}
