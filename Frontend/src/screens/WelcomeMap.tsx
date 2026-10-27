import { MapCanvas } from '@/map/MapCanvas';

/** The city, beside the welcome form on wide screens: a picture of where RideFlow runs. */
export default function WelcomeMap() {
  return <MapCanvas label="Map of the city RideFlow serves" zoom={12} contained still />;
}
