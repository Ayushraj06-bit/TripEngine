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
  movement,
  VEHICLE,
  walletIs,
} from '@/test/backend';

beforeEach(() => {
  resetApp();
  backendFor(DRIVER);
  signedInAs(DRIVER);
  driverProfileIs({ approvalStatus: 'APPROVED', status: 'OFFLINE', vehicle: VEHICLE });
  deviceAt(at(MG_ROAD));
});

describe('the driver’s earnings', () => {
  it('are a tap away from the driving screen', async () => {
    walletIs({
      account: 'DRIVER_EARNINGS',
      balancePaise: 24000,
      movements: [movement('TRIP_CHARGE', 'CREDIT', 24000)],
    });
    renderApp('/driver');
    await userEvent.click(await screen.findByRole('link', { name: 'Earnings' }));
    const balance = await screen.findByRole('region', { name: 'Balance' });
    expect(balance).toHaveTextContent('₹240');
    expect(balance).toHaveTextContent('RideFlow owes you this');
    expect(screen.getByRole('listitem')).toHaveTextContent(/^Ride.*\+₹240$/);
  });

  it('say what the driver owes in commission on cash rides', async () => {
    walletIs({
      account: 'DRIVER_EARNINGS',
      balancePaise: -6200,
      movements: [movement('CASH_COMMISSION', 'DEBIT', 6200)],
    });
    renderApp('/driver/earnings');
    const balance = await screen.findByRole('region', { name: 'Balance' });
    expect(balance).toHaveTextContent('−₹62');
    expect(balance).toHaveTextContent('You owe RideFlow this in commission on cash rides.');
    expect(screen.getByText('RideFlow’s commission on a cash ride')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Done' })).toHaveAttribute('href', '/driver');
  });
});
