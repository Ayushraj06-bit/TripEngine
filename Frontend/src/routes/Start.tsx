import { Link } from 'react-router';

/** The front door until Stage 1 replaces it with welcome and sign in. */
export function Start() {
  return (
    <main className="flex min-h-dvh flex-col justify-center gap-4 p-6">
      <title>RideFlow</title>
      <h1 className="text-title font-semibold">RideFlow</h1>
      <p className="text-body text-ink-soft">Signing in arrives in the next stage.</p>
      <Link
        to="/design-system"
        className="inline-flex min-h-11 items-center text-body font-semibold text-ink underline"
      >
        Open the design system
      </Link>
    </main>
  );
}
