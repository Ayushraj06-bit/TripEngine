import { useQueries, useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { activeTrip } from '@/auth/landing';
import type { LatLng } from '@/map/MapCanvas';
import type { NearbyDriver, VehicleType } from '@/map/NearbyDrivers';

export const DRIVER_PROFILE = ['driver', 'me'] as const;
export const ACTIVE_TRIP = ['trip', 'active'] as const;

const VEHICLE_TYPES: readonly VehicleType[] = ['BIKE', 'AUTO', 'CAR'];

/** The signed-in driver's profile: approval, status and vehicle, as the server has them now. */
export function useDriverProfile(options: { refetchInterval?: number } = {}) {
  return useQuery({
    queryKey: DRIVER_PROFILE,
    queryFn: () => api.get('/api/v1/drivers/me'),
    ...options,
  });
}

/** The trip under way for the signed-in user, or null. */
export function useActiveTrip() {
  return useQuery({ queryKey: ACTIVE_TRIP, queryFn: activeTrip });
}

/**
 * Drivers of every type around `at`, asked again every 15 seconds while the rider looks. Null
 * while there is nothing to ask about, or before the first answer.
 */
export function useNearbyDrivers(at: LatLng | null): NearbyDriver[] | null {
  return useQueries({
    queries: VEHICLE_TYPES.map((type) => ({
      queryKey: ['nearby', type, at?.lat, at?.lng],
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const found = await api.get('/api/v1/drivers/nearby', {
          query: { lat: at?.lat ?? 0, lng: at?.lng ?? 0, type },
          signal,
        });
        return found.flatMap((d) =>
          d.lat === undefined || d.lng === undefined
            ? []
            : [{ type, at: { lat: d.lat, lng: d.lng } }],
        );
      },
      enabled: at !== null,
      refetchInterval: 15_000,
    })),
    combine: (results) =>
      results.every((r) => r.isSuccess) ? results.flatMap((r) => r.data) : null,
  });
}
