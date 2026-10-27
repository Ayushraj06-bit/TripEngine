import { DarkScreen } from './common';
import { SignOutButton } from './SignOutButton';

/** This app is for riders and drivers; an admin works from the live map. */
export function AdminNotice() {
  return (
    <DarkScreen
      title="Admin"
      lead="This app is for riders and drivers. Approving drivers, surge and the ledger are on the live map and the admin API."
      actions={<SignOutButton />}
    >
      <a
        href="/admin/live-map.html"
        className="flex min-h-11 items-center self-start rounded-full bg-paper/12 px-5 text-body font-semibold"
      >
        Open the live map
      </a>
    </DarkScreen>
  );
}
