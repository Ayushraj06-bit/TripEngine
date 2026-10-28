import { useSession } from '@/state/session';
import { networkError, problemFrom } from './problem';
import { heardFromServer } from './serverClock';
import type { BodyPart, Method, PathFor, PathPart, QueryPart, ResponseBody } from './types';

/**
 * The one way the app talks to the backend.
 *
 * - Attaches the access token.
 * - On a 401, renews the session once and retries the original request once. Requests that fail
 *   while a renewal is under way wait for that same renewal: only one ever runs at a time,
 *   because the backend rotates refresh tokens, and a second, concurrent renewal would present a
 *   token that was just spent - which the backend treats as theft, revoking the whole session.
 * - Turns every failure into an ApiError carrying the backend's `code` (problem.ts); the words
 *   the user sees for each code live in errors.ts.
 */

export interface AuthHooks {
  /** Gets a new access token, or null when the session cannot be renewed. */
  refresh: () => Promise<string | null>;
  /** The session is over: the app sends the user to sign in. */
  onSessionExpired: () => void;
}

let hooks: AuthHooks = {
  refresh: () => Promise.resolve(null),
  onSessionExpired: () => undefined,
};

/** Installed once at start-up by the session layer (Stage 1). */
export function configureAuth(next: AuthHooks): void {
  hooks = next;
}

let renewing: Promise<string | null> | null = null;

/** One renewal at a time, shared by everyone waiting on it. */
function renewOnce(): Promise<string | null> {
  renewing ??= hooks
    .refresh()
    .catch(() => null)
    .then((token) => {
      if (token) useSession.getState().signedIn(token);
      else useSession.getState().signedOut();
      return token;
    })
    .finally(() => {
      renewing = null;
    });
  return renewing;
}

export type Options<M extends Method, P extends PathFor<M>> = {
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** False for calls that must never carry or renew a session, such as signing in. */
  auth?: boolean;
} & PathPart<P, M> &
  QueryPart<P, M> &
  BodyPart<P, M>;

function resolve(path: string, params?: object, query?: object): string {
  let resolved = path;
  for (const [name, value] of Object.entries(params ?? {})) {
    resolved = resolved.replace(`{${name}}`, encodeURIComponent(String(value)));
  }
  const target = new URL(resolved, window.location.href);
  for (const [name, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null) target.searchParams.set(name, String(value));
  }
  return target.href;
}

async function send(
  method: Method,
  target: string,
  body: unknown,
  headers: Record<string, string>,
  token: string | null,
  signal: AbortSignal | undefined,
): Promise<Response> {
  const all: Record<string, string> = { Accept: 'application/json', ...headers };
  if (body !== undefined) all['Content-Type'] = 'application/json';
  if (token) all['Authorization'] = `Bearer ${token}`;
  try {
    const response = await fetch(target, {
      method: method.toUpperCase(),
      headers: all,
      body: body === undefined ? null : JSON.stringify(body),
      signal: signal ?? null,
      credentials: 'same-origin',
    });
    heardFromServer(response.headers.get('Date'));
    return response;
  } catch (error) {
    // Cancelled on purpose - a newer search replaced this one - is not a failure to report.
    // Judged by the caller's own signal, not the error's class, which differs between runtimes.
    if (signal?.aborted) throw signal.reason ?? error;
    throw networkError();
  }
}

export async function request<M extends Method, P extends PathFor<M>>(
  method: M,
  path: P,
  options: Options<M, P>,
): Promise<ResponseBody<P, M>> {
  const loose = options as {
    path?: object;
    query?: object;
    body?: unknown;
    headers?: Record<string, string>;
    signal?: AbortSignal;
    auth?: boolean;
  };
  const target = resolve(path, loose.path, loose.query);
  const headers = loose.headers ?? {};
  const auth = loose.auth ?? true;
  const used = auth ? useSession.getState().accessToken : null;

  let response = await send(method, target, loose.body, headers, used, loose.signal);

  if (response.status === 401 && auth) {
    // Someone else may already have renewed the session since this request set off.
    const current = useSession.getState().accessToken;
    const token = current && current !== used ? current : await renewOnce();
    if (!token) {
      hooks.onSessionExpired();
      throw await problemFrom(response);
    }
    response = await send(method, target, loose.body, headers, token, loose.signal);
    if (response.status === 401) {
      useSession.getState().signedOut();
      hooks.onSessionExpired();
    }
  }

  if (!response.ok) throw await problemFrom(response);
  const text = response.status === 204 ? '' : await response.text();
  return (text ? JSON.parse(text) : undefined) as ResponseBody<P, M>;
}

/** Options may be left out only when nothing in them is required. */
type Rest<M extends Method, P extends PathFor<M>> =
  Partial<Options<M, P>> extends Options<M, P>
    ? [options?: Options<M, P>]
    : [options: Options<M, P>];

function bind<M extends Method>(method: M) {
  return <P extends PathFor<M>>(path: P, ...rest: Rest<M, P>): Promise<ResponseBody<P, M>> =>
    request(method, path, (rest[0] ?? {}) as Options<M, P>);
}

/** `api.get('/api/v1/trips/active')`, typed end to end from the backend's OpenAPI document. */
export const api = {
  get: bind('get'),
  post: bind('post'),
  put: bind('put'),
  delete: bind('delete'),
};
