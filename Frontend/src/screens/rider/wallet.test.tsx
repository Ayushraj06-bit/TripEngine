import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderApp, resetApp, screenAlert, signedInAs } from '@/test/app';
import { backendFor, movement, RIDER, seen, topupAnswers, walletIs } from '@/test/backend';
import { server } from '@/test/server';

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
});

afterEach(() => {
  vi.useRealTimers();
});

const topups = () => seen.filter((r) => r.path === '/api/v1/wallet/topups');

describe('the wallet', () => {
  it('shows the balance, and what went in and out, newest first', async () => {
    walletIs({
      balancePaise: 31600,
      movements: [movement('TRIP_CHARGE', 'DEBIT', 18400), movement('TOPUP', 'CREDIT', 50000)],
    });
    renderApp('/wallet');
    expect(await screen.findByRole('region', { name: 'Balance' })).toHaveTextContent('₹316');
    const rows = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringMatching(/^Ride.*−₹184$/),
      expect.stringMatching(/^Top-up.*\+₹500$/),
    ]);
  });

  it('shows earlier activity a page at a time', async () => {
    walletIs(
      { balancePaise: 50000, movements: [movement('TOPUP', 'CREDIT', 20000)] },
      { balancePaise: 50000, movements: [movement('REFUND', 'CREDIT', 3000)] },
    );
    renderApp('/wallet');
    await userEvent.click(await screen.findByRole('button', { name: 'Show earlier' }));
    expect(await screen.findByText('Refund')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Show earlier' })).not.toBeInTheDocument();
  });

  it('says how much to add when a ride took it below zero', async () => {
    walletIs({ balancePaise: -5000, movements: [movement('TRIP_CHARGE', 'DEBIT', 25000)] });
    renderApp('/wallet');
    const balance = await screen.findByRole('region', { name: 'Balance' });
    expect(balance).toHaveTextContent('−₹50');
    expect(balance).toHaveTextContent('Add ₹50 or more to book again.');
  });

  it('says so when there is nothing yet', async () => {
    walletIs({ balancePaise: 0, movements: [] });
    renderApp('/wallet');
    expect(await screen.findByText('Nothing yet.')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Done' })).toHaveAttribute('href', '/');
  });
});

describe('adding money', () => {
  it('pays at the gateway, and says when the money is in', async () => {
    walletIs({ balancePaise: 0, movements: [] });
    topupAnswers('PENDING', 'SUCCEEDED');
    renderApp('/wallet');
    await userEvent.click(await screen.findByRole('button', { name: 'Add ₹200' }));

    expect(await screen.findByText('₹200 added to your wallet.')).toBeVisible();
    expect(seen.some((r) => r.path === '/mock-gateway/payments/ref%3A1/pay')).toBe(true);
    // Asked again under the same key: the backend answers the same top-up, settled.
    const [started, settled] = topups();
    expect(started?.body).toEqual({ amountPaise: 20000 });
    expect(settled?.headers.get('Idempotency-Key')).toBe(started?.headers.get('Idempotency-Key'));
  });

  it('takes an amount of the rider’s own, in whole rupees', async () => {
    walletIs({ balancePaise: 0, movements: [] });
    topupAnswers('SUCCEEDED');
    const user = userEvent.setup();
    renderApp('/wallet');
    await user.click(await screen.findByRole('radio', { name: 'Other' }));
    await user.type(screen.getByLabelText('Amount in rupees'), '12.5');
    await user.click(screen.getByRole('button', { name: 'Add money' }));
    expect(screen.getByText('Enter an amount in whole rupees.')).toBeVisible();
    expect(topups()).toHaveLength(0);

    await user.clear(screen.getByLabelText('Amount in rupees'));
    await user.type(screen.getByLabelText('Amount in rupees'), '750');
    await user.click(screen.getByRole('button', { name: 'Add ₹750' }));
    expect(await screen.findByText('₹750 added to your wallet.')).toBeVisible();
  });

  it('tries again under the same key when an answer was lost, so it is paid once', async () => {
    walletIs({ balancePaise: 0, movements: [] });
    topupAnswers('SUCCEEDED');
    let lost: string | null = null;
    server.use(
      http.post(
        '/api/v1/wallet/topups',
        ({ request }) => {
          lost = request.headers.get('Idempotency-Key');
          return HttpResponse.error();
        },
        { once: true },
      ),
    );
    const user = userEvent.setup();
    renderApp('/wallet');
    await user.click(await screen.findByRole('button', { name: 'Add ₹200' }));
    expect(await screenAlert()).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Add ₹200' }));
    expect(await screen.findByText('₹200 added to your wallet.')).toBeVisible();
    expect(lost).not.toBeNull();
    expect(topups()[0]?.headers.get('Idempotency-Key')).toBe(lost);
  });

  it('says so when the payment did not go through', async () => {
    walletIs({ balancePaise: 0, movements: [] });
    topupAnswers('PENDING', 'FAILED');
    renderApp('/wallet');
    await userEvent.click(await screen.findByRole('button', { name: 'Add ₹200' }));
    expect(
      await screen.findByText('The payment didn’t go through, so nothing was added.'),
    ).toBeVisible();
  });

  it('says it is still being confirmed, rather than guess, after thirty seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    walletIs({ balancePaise: 0, movements: [] });
    topupAnswers('PENDING');
    renderApp('/wallet');
    await userEvent.click(await screen.findByRole('button', { name: 'Add ₹200' }));
    expect(await screen.findByText('Waiting for the payment to be confirmed…')).toBeVisible();

    for (let second = 0; second < 32; second += 2) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000);
      });
    }
    await waitFor(() => {
      expect(
        screen.getByText(
          'The payment is still being confirmed. Your balance will show it once it is.',
        ),
      ).toBeVisible();
    });
  });
});
