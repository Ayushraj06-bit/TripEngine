import { Outlet, useOutletContext } from 'react-router';
import { useDriverProfile } from '../queries';
import { useShareLocation, type Sharing } from './useShareLocation';

/**
 * Around every driver screen: an approved driver who is online, or on a trip, shares their position
 * the whole time - not only on the home screen. Arrival is checked against the latest position, and
 * the final fare against the route driven, so a trip screen that stopped sharing would leave the
 * driver unable to mark arrival and the rider's fare without a route.
 */
export function DriverPresence() {
  const profile = useDriverProfile();
  const driver = profile.data;
  const active =
    driver?.approvalStatus === 'APPROVED' &&
    (driver.status === 'AVAILABLE' || driver.status === 'ON_TRIP');
  const sharing = useShareLocation(active);
  return <Outlet context={sharing} />;
}

/** How sharing the driver's position is going, for the screen inside DriverPresence. */
export function useSharing(): Sharing {
  return useOutletContext<Sharing>();
}
