import { describe, expect, it } from 'vitest';
import { displayPhone, normalisePhone } from './phone';

describe('normalisePhone', () => {
  it.each([
    ['98765 43210', '+919876543210'],
    ['9876543210', '+919876543210'],
    ['+91 98765-43210', '+919876543210'],
    ['09876543210', '+919876543210'],
    ['0091 9876543210', '+919876543210'],
    ['(98765) 43210', '+919876543210'],
    ['  98765.43210  ', '+919876543210'],
    ['+44 20 7946 0958', '+442079460958'],
  ])('reads %s as %s, the way the backend does', (typed, e164) => {
    expect(normalisePhone(typed)).toMatchObject({ ok: true, e164 });
  });

  it('shows an Indian number grouped, so it can be checked at a glance', () => {
    expect(normalisePhone('09876543210')).toMatchObject({ display: '+91 98765 43210' });
    expect(displayPhone('+442079460958')).toBe('+442079460958');
  });

  it.each([
    ['', 'Enter your mobile number.'],
    ['   ', 'Enter your mobile number.'],
    ['98765', 'Enter a 10-digit mobile number, or include the country code.'],
    ['919876543210', 'Enter a 10-digit mobile number, or include the country code.'],
    ['+0 98765 43210', 'That doesn’t look like a phone number. Check the digits.'],
    ['+91 98765 4321O', 'That doesn’t look like a phone number. Check the digits.'],
  ])('refuses %j, and says why', (typed, reason) => {
    expect(normalisePhone(typed)).toEqual({ ok: false, reason });
  });
});
