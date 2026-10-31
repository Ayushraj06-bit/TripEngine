import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderApp, resetApp, signedInAs } from '@/test/app';
import { backendFor, RIDER, trip, tripAnswers, tripsAre } from '@/test/backend';

beforeEach(() => {
  resetApp();
  backendFor(RIDER);
  signedInAs(RIDER);
});

const at = (iso: string) => ({ requestedAt: iso });

describe('the rider’s trips', () => {
  it('are a tap away from the map, newest first, with what each came to', async () => {
    tripsAre([
      trip('trip-2', 'COMPLETED', { ...at('2026-10-30T09:00:00Z'), finalFarePaise: 31200 }),
      trip('trip-1', 'CANCELLED', { ...at('2026-10-29T09:00:00Z'), cancellationFeePaise: 5000 }),
      trip('trip-0', 'NO_DRIVER_FOUND', at('2026-10-28T09:00:00Z')),
    ]);
    renderApp('/');
    await userEvent.click(await screen.findByRole('link', { name: 'Your trips' }));

    const rows = within(await screen.findByRole('list')).getAllByRole('link');
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringMatching(/^Completed · Auto.*₹312$/),
      expect.stringMatching(/^Cancelled · Auto.*₹50$/),
      expect.stringMatching(/^No driver found · Auto.*[^₹\d]$/),
    ]);
    expect(rows[0]).toHaveAttribute('href', '/trip/trip-2');
  });

  it('show earlier ones a page at a time', async () => {
    tripsAre([trip('trip-2', 'COMPLETED')], [trip('trip-1', 'CANCELLED')]);
    renderApp('/trips');
    await userEvent.click(await screen.findByRole('button', { name: 'Show earlier' }));
    expect(await screen.findByText(/^Cancelled/)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Show earlier' })).not.toBeInTheDocument();
  });

  it('open the trip itself', async () => {
    tripsAre([trip('trip-2', 'COMPLETED', { finalFarePaise: 31200, paymentMethod: 'CASH' })]);
    tripAnswers(trip('trip-2', 'COMPLETED', { finalFarePaise: 31200, paymentMethod: 'CASH' }));
    renderApp('/trips');
    await userEvent.click(await screen.findByRole('link', { name: /^Completed/ }));
    expect(await screen.findByRole('heading', { name: 'Trip completed' })).toBeVisible();
  });

  it('say so when there are none', async () => {
    tripsAre([]);
    renderApp('/trips');
    expect(await screen.findByText('No trips yet.')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Done' })).toHaveAttribute('href', '/');
  });
});
