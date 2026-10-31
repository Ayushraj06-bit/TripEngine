import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { deviceAt, renderApp, resetApp, signedInAs } from '@/test/app';
import {
  at,
  backendFor,
  DRIVER,
  driverProfileIs,
  MG_ROAD,
  trip,
  tripAnswers,
  tripsAre,
  VEHICLE,
} from '@/test/backend';
import { ratingWords } from './DriverHome';

const APPROVED = { approvalStatus: 'APPROVED', status: 'OFFLINE', vehicle: VEHICLE } as const;

beforeEach(() => {
  resetApp();
  backendFor(DRIVER);
  signedInAs(DRIVER);
  deviceAt(at(MG_ROAD));
});

describe('the driver’s rating', () => {
  it('is on the driving screen, as the backend added it up', async () => {
    driverProfileIs({ ...APPROVED, averageRating: 4.825, ratingCount: 23 });
    renderApp('/driver');
    expect(await screen.findByText('Rated 4.8 by riders, from 23 ratings.')).toBeVisible();
  });

  it('reads well with one rating, or none', () => {
    expect(ratingWords({ ...APPROVED, averageRating: 5, ratingCount: 1 })).toBe(
      'Rated 5.0 by riders, from 1 rating.',
    );
    expect(ratingWords({ ...APPROVED, ratingCount: 0 })).toBe('No ratings from riders yet.');
  });
});

describe('the driver’s trips', () => {
  it('are a tap away from the driving screen, and open the trip', async () => {
    driverProfileIs(APPROVED);
    const done = trip('trip-9', 'COMPLETED', { finalFarePaise: 31200, paymentMethod: 'CASH' });
    tripsAre([done]);
    tripAnswers(done);
    renderApp('/driver');
    await userEvent.click(await screen.findByRole('link', { name: 'Your trips' }));
    const row = await screen.findByRole('link', { name: /^Completed · Auto.*₹312$/ });
    expect(row).toHaveAttribute('href', '/driver/trip/trip-9');
    await userEvent.click(row);
    expect(await screen.findByText('Collect ₹312 in cash from the rider.')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Back to driving' })).toBeVisible();
  });
});
