import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/problem';

/** Worth trying again: no answer at all, or the server failing. A 4xx will fail the same way. */
export function isTransient(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Reads are safe to repeat: twice more, backing off, and only for transient failures.
      retry: (failures, error) => failures < 2 && isTransient(error),
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      // Coming back to the tab re-reads from the server: REST is the source of truth.
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
    },
    mutations: {
      // Writes are never repeated on their own: a booking is retried, with its idempotency key,
      // only where the screen decides to.
      retry: false,
    },
  },
});
