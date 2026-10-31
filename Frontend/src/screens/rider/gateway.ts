import { api } from '@/api/client';
import type { Schemas } from '@/api/types';

export type TopupStatus = NonNullable<Schemas['TopupView']['status']>;

/** How often the app asks how a payment went, and for how long before saying it is still open. */
const ASK_EVERY_MS = 2000;
const ASK_FOR_MS = 30_000;

/** The demo's test gateway: its "payment page" is an endpoint on the backend. */
const TEST_GATEWAY = /^\/mock-gateway\/payments\/([^/]+)\/pay$/;

/**
 * Pays at the gateway. The demo's test gateway is paid in place, through this origin; a real
 * gateway hosts its own page, so the rider is sent there, and false says the app has left.
 */
async function pay(paymentUrl: string): Promise<boolean> {
  const ref = TEST_GATEWAY.exec(new URL(paymentUrl, window.location.href).pathname)?.[1];
  if (ref === undefined) {
    window.location.assign(paymentUrl);
    return false;
  }
  await api.post('/mock-gateway/payments/{gatewayRef}/pay', {
    path: { gatewayRef: decodeURIComponent(ref) },
  });
  return true;
}

/**
 * Adds `amountPaise` to the wallet and answers how it ended. Started under `key`, paid, then asked
 * again under the same key - the backend answers the same top-up, with its status - every two
 * seconds until it has settled, or for thirty seconds, after which it is still PENDING.
 */
export async function topUp(amountPaise: number, key: string): Promise<TopupStatus> {
  const ask = () =>
    api.post('/api/v1/wallet/topups', {
      body: { amountPaise },
      headers: { 'Idempotency-Key': key },
    });
  const started = await ask();
  if (started.status !== 'PENDING') return started.status ?? 'PENDING';
  if (!started.paymentUrl || !(await pay(started.paymentUrl))) return 'PENDING';

  const until = Date.now() + ASK_FOR_MS;
  while (Date.now() < until) {
    await new Promise((resolve) => setTimeout(resolve, ASK_EVERY_MS));
    const now = await ask();
    if (now.status !== 'PENDING') return now.status ?? 'PENDING';
  }
  return 'PENDING';
}
