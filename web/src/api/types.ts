import type { components, paths } from './schema';

/** Every schema the backend publishes, by name: `Schemas['TripView']`. */
export type Schemas = components['schemas'];

export type Path = keyof paths;
export type Method = 'get' | 'post' | 'put' | 'delete';

/** The paths that answer `method`. */
export type PathFor<M extends Method> = {
  [P in Path]: paths[P][M] extends { responses: unknown } ? P : never;
}[Path];

type Operation<P extends Path, M extends Method> = NonNullable<paths[P][M]>;

type Content<T> = T extends { content: infer C } ? C[keyof C] : undefined;

type Success = 200 | 201 | 202 | 204;

/** What a successful call returns: the body of its 2xx response, whatever its media type. */
export type ResponseBody<P extends Path, M extends Method> =
  Operation<P, M> extends { responses: infer R } ? Content<R[Extract<keyof R, Success>]> : never;

type Parameters<P extends Path, M extends Method> =
  Operation<P, M> extends { parameters: infer X } ? X : never;

/*
 * openapi-typescript writes `path: {...}` when an operation has path parameters and `path?: never`
 * when it has none; likewise for `query` (`query?: {...}` when every query parameter is optional)
 * and `requestBody`. Each part below keeps exactly that: required where the API requires it.
 */

export type PathPart<P extends Path, M extends Method> =
  Parameters<P, M> extends { path: infer X } ? { path: X } : { path?: undefined };

export type QueryPart<P extends Path, M extends Method> =
  Parameters<P, M> extends { query: infer X }
    ? { query: X }
    : Parameters<P, M> extends { query?: infer X }
      ? { query?: X }
      : { query?: undefined };

export type BodyPart<P extends Path, M extends Method> =
  Operation<P, M> extends { requestBody?: never }
    ? { body?: undefined }
    : Operation<P, M> extends { requestBody: { content: infer C } }
      ? { body: C[keyof C] }
      : Operation<P, M> extends { requestBody?: { content: infer C } }
        ? { body?: C[keyof C] }
        : { body?: undefined };
