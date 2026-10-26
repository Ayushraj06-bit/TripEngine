/**
 * A failed call, as the backend described it: an RFC 9457 ProblemDetail with the extra `code`
 * field PROJECT_SPEC section 10 adds.
 *
 * Two codes come from this side, not the server: NETWORK when no answer arrived at all, and
 * UNKNOWN when one arrived but carried no code.
 */
export class ApiError extends Error {
  override readonly name = 'ApiError';

  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string | undefined,
    /** Every other property on the problem: `distanceMeters`, `tripStatus`, `attemptsRemaining`... */
    readonly properties: Readonly<Record<string, unknown>> = {},
    /** From the Retry-After header or the problem's `retryAfterSeconds`. */
    readonly retryAfterSeconds?: number,
  ) {
    super(`${code}${detail ? `: ${detail}` : ''}`);
  }

  /** A number the problem carries, such as `distanceMeters`, if it carries one. */
  number(property: string): number | undefined {
    const value = this.properties[property];
    return typeof value === 'number' ? value : undefined;
  }
}

/** When the body carries no code, the status still says something useful. */
const CODE_BY_STATUS: Readonly<Record<number, string>> = {
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  429: 'RATE_LIMITED',
  503: 'SERVICE_UNAVAILABLE',
};

const RESERVED = new Set(['type', 'title', 'status', 'detail', 'instance', 'code']);

function retryAfter(response: Response, body: Record<string, unknown>): number | undefined {
  const header = response.headers.get('Retry-After');
  if (header !== null && /^\d+$/.test(header.trim())) return Number(header.trim());
  const property = body['retryAfterSeconds'];
  return typeof property === 'number' ? property : undefined;
}

/** Turns a non-2xx response into an ApiError, whatever shape its body has. */
export async function problemFrom(response: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {};
  if ((response.headers.get('Content-Type') ?? '').includes('json')) {
    try {
      const parsed: unknown = await response.json();
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        body = parsed as Record<string, unknown>;
      }
    } catch {
      // A malformed body is still a failure with a status; the code falls back below.
    }
  }
  const code =
    typeof body['code'] === 'string'
      ? body['code']
      : (CODE_BY_STATUS[response.status] ??
        (response.status >= 500 ? 'INTERNAL_ERROR' : 'UNKNOWN'));
  const detail = typeof body['detail'] === 'string' ? body['detail'] : undefined;
  const properties = Object.fromEntries(Object.entries(body).filter(([key]) => !RESERVED.has(key)));
  return new ApiError(response.status, code, detail, properties, retryAfter(response, body));
}

/** No response at all: offline, DNS, the connection dropped. */
export function networkError(): ApiError {
  return new ApiError(0, 'NETWORK', undefined);
}
