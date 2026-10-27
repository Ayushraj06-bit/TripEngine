import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { activeTrip } from '@/auth/landing';

export const DRIVER_PROFILE = ['driver', 'me'] as const;
export const ACTIVE_TRIP = ['trip', 'active'] as const;

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
