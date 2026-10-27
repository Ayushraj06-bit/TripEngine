import { ApiError } from './problem';

/**
 * What the user reads for every error code the backend can send, and what they can do next - in
 * one place, so the same failure always reads the same way. Each message says what happened and
 * what to do; none apologises, and none blames the user.
 *
 * errors.test.ts reads the backend's sources and fails when a code it can return is neither here
 * nor in NOT_SHOWN_TO_USERS.
 */

/** The way out a screen offers next to the message. */
export type Recovery =
  'retry' | 'sign-in' | 'edit' | 'requote' | 'top-up' | 'open-trip' | 'wait' | 'refresh' | 'none';

interface Copy {
  message: string | ((error: ApiError) => string);
  recovery: Recovery;
}

/** "1 second", "45 seconds", "3 minutes". */
export function waitText(seconds: number): string {
  if (seconds < 60) {
    const whole = Math.max(1, Math.round(seconds));
    return whole === 1 ? '1 second' : `${whole} seconds`;
  }
  const minutes = Math.ceil(seconds / 60);
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

/** The clock time `seconds` from now, as people in India read it: "2:32 pm". */
export function clockTimeIn(seconds: number, now: number = Date.now()): string {
  return new Date(now + seconds * 1000).toLocaleTimeString('en-IN', {
    hour: 'numeric',
    minute: '2-digit',
  });
}

function after(error: ApiError, otherwise: string): string {
  return error.retryAfterSeconds === undefined
    ? otherwise
    : `in ${waitText(error.retryAfterSeconds)}`;
}

export const ERROR_COPY: Readonly<Record<string, Copy>> = {
  // Reaching the server at all
  NETWORK: { message: 'No connection. Check your network and try again.', recovery: 'retry' },
  SERVICE_UNAVAILABLE: {
    message: 'The service is briefly unavailable. Try again in a moment.',
    recovery: 'retry',
  },
  INTERNAL_ERROR: { message: 'That didn’t go through. Try again.', recovery: 'retry' },
  UNKNOWN: { message: 'That didn’t go through. Try again.', recovery: 'retry' },
  RATE_LIMITED: {
    message: (e) => `Too many attempts in a short time. Try again ${after(e, 'shortly')}.`,
    recovery: 'wait',
  },

  // Signing in and staying signed in
  UNAUTHENTICATED: { message: 'Your session has ended. Sign in to continue.', recovery: 'sign-in' },
  REFRESH_TOKEN_INVALID: {
    message: 'Your session has ended. Sign in to continue.',
    recovery: 'sign-in',
  },
  INVALID_CREDENTIALS: {
    message: 'That phone number and password don’t match an account. Check both and try again.',
    recovery: 'edit',
  },
  ACCOUNT_LOCKED: {
    message: (e) =>
      e.retryAfterSeconds === undefined
        ? 'This account is locked after several failed sign-ins. Try again later.'
        : `This account is locked after several failed sign-ins. Try again at ${clockTimeIn(e.retryAfterSeconds)}.`,
    recovery: 'wait',
  },
  PHONE_INVALID: {
    message: 'Enter a 10-digit Indian mobile number, such as 98765 43210.',
    recovery: 'edit',
  },
  PHONE_ALREADY_REGISTERED: {
    message: 'An account already uses this number. Sign in instead.',
    recovery: 'sign-in',
  },
  FORBIDDEN: { message: 'This account can’t do that.', recovery: 'none' },

  // Generic request problems
  VALIDATION_FAILED: {
    message: 'Some details need another look. Check the highlighted fields.',
    recovery: 'edit',
  },
  NOT_FOUND: { message: 'That isn’t available any more.', recovery: 'refresh' },
  CONFLICT: {
    message: 'That changed in the meantime. Refresh and try again.',
    recovery: 'refresh',
  },
  BUSINESS_RULE_VIOLATED: { message: 'That can’t be done right now.', recovery: 'none' },
  CONCURRENT_UPDATE: { message: 'That changed at the same moment. Try again.', recovery: 'retry' },
  INVALID_CURSOR: { message: 'The list was updated. Reload it to continue.', recovery: 'refresh' },

  // Fares and booking
  QUOTE_EXPIRED: {
    message: 'These prices have expired. Get new prices to book.',
    recovery: 'requote',
  },
  QUOTE_NOT_FOUND: {
    message: 'These prices have expired. Get new prices to book.',
    recovery: 'requote',
  },
  QUOTE_VEHICLE_TYPE_MISMATCH: {
    message: 'That price was for a different ride type. Get new prices to book.',
    recovery: 'requote',
  },
  OUTSIDE_SERVICE_AREA: {
    message: 'That place is outside the area RideFlow serves. Choose a point inside the city.',
    recovery: 'edit',
  },
  TRIP_TOO_SHORT: {
    message: 'Pickup and drop are too close for a ride. Choose a drop further away.',
    recovery: 'edit',
  },
  TRIP_TOO_LONG: {
    message: 'That trip is longer than RideFlow covers. Choose a closer drop.',
    recovery: 'edit',
  },
  ACTIVE_TRIP_EXISTS: { message: 'You already have a ride in progress.', recovery: 'open-trip' },
  OUTSTANDING_BALANCE: {
    message: 'An earlier ride is still unpaid. Top up your wallet, or choose cash, to book.',
    recovery: 'top-up',
  },
  IDEMPOTENCY_KEY_IN_FLIGHT: {
    message: 'Your booking is still being placed. It will show here in a moment.',
    recovery: 'wait',
  },

  // The trip itself
  TRIP_INVALID_TRANSITION: {
    message: 'The trip has already moved on. Showing where it is now.',
    recovery: 'refresh',
  },
  TRIP_CONCURRENT_UPDATE: {
    message: 'The trip changed at the same moment. Showing where it is now.',
    recovery: 'refresh',
  },
  TRIP_NOT_COMPLETED: { message: 'A trip can be rated once it’s completed.', recovery: 'none' },
  ALREADY_RATED: { message: 'This trip already has your rating.', recovery: 'none' },
  RATING_WINDOW_CLOSED: {
    message: 'Ratings close 7 days after a trip ends.',
    recovery: 'none',
  },

  // Wallet
  TOPUP_AMOUNT_OUT_OF_RANGE: {
    message: 'That amount is outside the top-up limits. Choose a different amount.',
    recovery: 'edit',
  },
  IDEMPOTENCY_KEY_REUSED: {
    message: 'That top-up was already started. Start a new one to continue.',
    recovery: 'retry',
  },
  PAYMENT_GATEWAY_UNAVAILABLE: {
    message: 'Payments are briefly unavailable. Try again shortly, or pay cash.',
    recovery: 'retry',
  },

  // Driving
  DRIVER_NOT_APPROVED: {
    message: 'Your account is still being reviewed. You can go online once it’s approved.',
    recovery: 'none',
  },
  DRIVER_HAS_NO_VEHICLE: { message: 'Add your vehicle before going online.', recovery: 'edit' },
  DRIVER_ON_TRIP: { message: 'Finish your current trip first.', recovery: 'open-trip' },
  DRIVER_HAS_ACTIVE_TRIP: {
    message: 'You’re already on a trip. Finish it before taking another.',
    recovery: 'open-trip',
  },
  PLATE_NUMBER_INVALID: {
    message: 'Enter the plate as it’s printed, such as KA 01 AB 1234.',
    recovery: 'edit',
  },
  PLATE_ALREADY_REGISTERED: {
    message: 'This plate is registered to another driver. Check the number.',
    recovery: 'edit',
  },
  OFFER_EXPIRED: {
    message: 'That offer has expired. The next one will appear here.',
    recovery: 'none',
  },
  OFFER_NOT_PENDING: {
    message: 'That offer is no longer open. The next one will appear here.',
    recovery: 'none',
  },
  RIDE_NO_LONGER_AVAILABLE: {
    message: 'That ride is no longer available. The next one will appear here.',
    recovery: 'none',
  },
  POSITION_UNKNOWN: {
    message: 'Your location hasn’t reached RideFlow recently. Check that location is on.',
    recovery: 'retry',
  },
  NOT_AT_PICKUP: {
    message: (e) => {
      const metres = e.number('distanceMeters');
      return metres === undefined
        ? 'You’re not at the pickup yet. Get closer to mark arrival.'
        : `You’re ${metres} m from the pickup. Get closer to mark arrival.`;
    },
    recovery: 'none',
  },
  NO_SHOW_TOO_EARLY: {
    message: 'You can cancel for a no-show once the free waiting time is over.',
    recovery: 'wait',
  },
  OTP_INVALID: {
    message: (e) => {
      const left = e.number('attemptsRemaining');
      return left === undefined
        ? 'That code doesn’t match. Ask the rider to read it again.'
        : `That code doesn’t match. ${left === 1 ? '1 try' : `${left} tries`} left.`;
    },
    recovery: 'edit',
  },
  OTP_LOCKED: {
    message: (e) => `Too many wrong codes. Try again ${after(e, 'shortly')}.`,
    recovery: 'wait',
  },
};

/**
 * Codes the backend can return that never reach this app: admin tools, the payment gateway's
 * webhook, and requests the client never makes.
 */
export const NOT_SHOWN_TO_USERS: readonly string[] = [
  'ALREADY_REFUNDED',
  'AMOUNT_MISMATCH',
  'INVALID_CELL',
  'INVALID_OVERRIDE_EXPIRY',
  'INVALID_SIGNATURE',
  'MALFORMED_WEBHOOK',
  'METHOD_NOT_ALLOWED',
  'NOT_REFUNDABLE',
  'OVERRIDE_OUT_OF_RANGE',
  'REJECTION_REASON_REQUIRED',
  'UNSUPPORTED_MEDIA_TYPE',
];

export interface Described {
  code: string;
  message: string;
  recovery: Recovery;
}

const FALLBACK: Copy = { message: 'That didn’t go through. Try again.', recovery: 'retry' };

/** The words and the way out for any failure. Unmapped codes still offer a retry, and are logged. */
export function describeError(error: unknown): Described {
  if (!(error instanceof ApiError)) {
    console.error('Unexpected error', error);
    return { code: 'UNKNOWN', ...resolve(FALLBACK, undefined) };
  }
  const copy = ERROR_COPY[error.code];
  if (!copy) console.warn(`No copy for error code ${error.code}`);
  return { code: error.code, ...resolve(copy ?? FALLBACK, error) };
}

function resolve(copy: Copy, error: ApiError | undefined): Omit<Described, 'code'> {
  const message =
    typeof copy.message === 'function'
      ? copy.message(error ?? new ApiError(0, 'UNKNOWN', undefined))
      : copy.message;
  return { message, recovery: copy.recovery };
}
