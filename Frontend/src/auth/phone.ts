/**
 * Phone numbers, read the way people in India write them, normalised to E.164 before they are
 * sent - by the same rules as the backend's PhoneNumber, so what the app accepts the server does
 * too: 98765 43210, +91 98765-43210, 09876543210 and 0091 9876543210 are all +919876543210.
 */

const SEPARATORS = /[\s\-().]/g;
const E164 = /^\+[1-9]\d{7,14}$/;

export type Phone = { ok: true; e164: string; display: string } | { ok: false; reason: string };

export function normalisePhone(raw: string): Phone {
  let digits = raw.trim().replace(SEPARATORS, '');
  if (digits === '') return { ok: false, reason: 'Enter your mobile number.' };
  // 00 is the other way of writing a leading +.
  if (digits.startsWith('00')) digits = `+${digits.slice(2)}`;

  let candidate: string;
  if (digits.startsWith('+')) candidate = digits;
  else if (digits.length === 10) candidate = `+91${digits}`;
  // A domestic trunk prefix, as written on a business card.
  else if (digits.length === 11 && digits.startsWith('0')) candidate = `+91${digits.slice(1)}`;
  else return { ok: false, reason: 'Enter a 10-digit mobile number, or include the country code.' };

  if (!E164.test(candidate)) {
    return { ok: false, reason: 'That doesn’t look like a phone number. Check the digits.' };
  }
  return { ok: true, e164: candidate, display: displayPhone(candidate) };
}

/** +919876543210 → +91 98765 43210, so the user can check it at a glance. */
export function displayPhone(e164: string): string {
  const india = /^\+91(\d{5})(\d{5})$/.exec(e164);
  return india ? `+91 ${india[1] ?? ''} ${india[2] ?? ''}` : e164;
}
