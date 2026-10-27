import { beforeEach, describe, expect, it } from 'vitest';
import { resetApp, signedInAs } from '@/test/app';
import { activeTripIs, backendFor, DRIVER, driverProfileIs, RIDER, VEHICLE } from '@/test/backend';
import { landingFor, safeReturnTo } from './landing';

beforeEach(resetApp);

describe('where a user lands after signing in', () => {
  it('takes a rider home, or back to the page they were sent from', async () => {
    backendFor(RIDER);
    signedInAs(RIDER);
    await expect(landingFor(RIDER)).resolves.toBe('/');
    await expect(landingFor(RIDER, '/design-system')).resolves.toBe('/design-system');
  });

  it('opens straight into a ride under way, whatever page they came from', async () => {
    backendFor(RIDER);
    activeTripIs('trip-7');
    signedInAs(RIDER);
    await expect(landingFor(RIDER, '/design-system')).resolves.toBe('/trip/trip-7');
  });

  it('takes a driver through their vehicle and approval before driving', async () => {
    backendFor(DRIVER);
    signedInAs(DRIVER);

    driverProfileIs({ approvalStatus: 'PENDING', status: 'OFFLINE' });
    await expect(landingFor(DRIVER)).resolves.toBe('/driver/vehicle');

    driverProfileIs({ approvalStatus: 'PENDING', status: 'OFFLINE', vehicle: VEHICLE });
    await expect(landingFor(DRIVER)).resolves.toBe('/driver/approval');

    driverProfileIs({ approvalStatus: 'APPROVED', status: 'OFFLINE', vehicle: VEHICLE });
    await expect(landingFor(DRIVER)).resolves.toBe('/driver');
  });

  it('opens a driver mid-trip into the trip', async () => {
    backendFor(DRIVER);
    activeTripIs('trip-9', 'DRIVER_ASSIGNED');
    signedInAs(DRIVER);
    await expect(landingFor(DRIVER)).resolves.toBe('/driver/trip/trip-9');
  });

  it('sends an admin to the admin notice', async () => {
    await expect(landingFor({ ...RIDER, role: 'ADMIN' })).resolves.toBe('/admin');
  });
});

describe('a page to come back to', () => {
  it.each([
    ['//evil.example', '/'],
    ['https://evil.example', '/'],
    ['/\\evil.example', '/'],
    ['/sign-in', '/'],
    ['/welcome', '/'],
    ['/driver', '/'],
    ['/admin', '/'],
    [null, '/'],
  ])('ignores %j for a rider', (raw, area) => {
    expect(safeReturnTo(raw, area as '/')).toBeNull();
  });

  it('keeps a page in the user’s own part of the app', () => {
    expect(safeReturnTo('/trip/abc?x=1', '/')).toBe('/trip/abc?x=1');
    expect(safeReturnTo('/driver/approval', '/driver')).toBe('/driver/approval');
    expect(safeReturnTo('/trip/abc', '/driver')).toBeNull();
  });
});
