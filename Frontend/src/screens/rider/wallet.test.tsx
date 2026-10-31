import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderApp, resetApp, signedInAs } from '@/test/app';
import { backendFor, movement, RIDER, walletIs } from '@/test/backend';

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
});

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
