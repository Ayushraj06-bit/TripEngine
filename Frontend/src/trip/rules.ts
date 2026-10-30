/**
 * The backend's trip rules (`rideflow.trip` in application.yml) that the app counts down to,
 * copied here because no response states them. rules.test.ts reads application.yml and fails
 * when the two differ.
 */
export const TRIP_RULES = {
  /** A rider may cancel free for this long after a driver is assigned. */
  riderCancelGraceSeconds: 120,
  /** Waiting at the pickup is free for this long; after that it is charged by the minute. */
  freeWaitMinutes: 3,
  /** A driver may cancel for a no-show once they have waited this long at the pickup. */
  noShowAfterMinutes: 5,
  /** A driver may mark arrival within this distance of the pickup. */
  arrivalGeofenceMeters: 150,
} as const;
