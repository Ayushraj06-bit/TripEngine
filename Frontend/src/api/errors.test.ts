/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { clockTimeIn, describeError, ERROR_COPY, NOT_SHOWN_TO_USERS, waitText } from './errors';
import { ApiError } from './problem';

// Tests run from Frontend/, so the backend's sources sit one level up.
const BACKEND = resolve(process.cwd(), '../backend/src/main/java');

/** Codes the client makes up itself, never sent by the backend. */
const CLIENT_CODES = ['NETWORK', 'UNKNOWN'];

/** Every error code the backend's sources can send, read from the places codes are written. */
function backendCodes(): Set<string> {
  const codes = new Set<string>();
  const files = readdirSync(BACKEND, { recursive: true, encoding: 'utf8' }).filter((f) =>
    f.endsWith('.java'),
  );
  for (const file of files) {
    const source = readFileSync(join(BACKEND, file), 'utf8');
    const constants = new Map<string, string>();
    for (const [, name, value] of source.matchAll(
      /static final String ([A-Z_]+) = "([A-Z][A-Z0-9_]+)"/g,
    )) {
      if (name && value) constants.set(name, value);
    }
    const add = (value: string | undefined) => {
      if (value) codes.add(constants.get(value) ?? value);
    };
    // new BusinessException("CODE", ...), conflict("CODE", ...), businessRule(CODE_CONSTANT, ...)
    for (const [, code] of source.matchAll(
      /(?:new BusinessException|\bconflict|\bbusinessRule)\(\s*"?([A-Z][A-Z0-9_]+)"?\s*,/g,
    )) {
      add(code);
    }
    // problem.setProperty(CODE_PROPERTY, "CODE") in the exception handler
    for (const [, code] of source.matchAll(/CODE_PROPERTY,\s*"([A-Z][A-Z0-9_]+)"/g)) add(code);
    // Problems written by hand outside the advice, in filters
    for (const [, code] of source.matchAll(/"code":"([A-Z][A-Z0-9_]+)"/g)) add(code);
    if (file.endsWith('GlobalExceptionHandler.java')) {
      for (const value of constants.values()) codes.add(value);
    }
  }
  return codes;
}

describe('the error copy', () => {
  const codes = backendCodes();

  it('is read from the backend, not guessed', () => {
    // If these go missing, the extraction above broke, not the backend.
    expect(codes.size).toBeGreaterThan(40);
    for (const known of [
      'QUOTE_EXPIRED',
      'ACTIVE_TRIP_EXISTS',
      'TRIP_INVALID_TRANSITION',
      'OUTSTANDING_BALANCE',
      'UNAUTHENTICATED',
      'SERVICE_UNAVAILABLE',
    ]) {
      expect(codes).toContain(known);
    }
  });

  it('has words for every code the backend can send, or says why the app never sees it', () => {
    const unaccounted = [...codes].filter(
      (code) => !(code in ERROR_COPY) && !NOT_SHOWN_TO_USERS.includes(code),
    );
    expect(unaccounted).toEqual([]);
  });

  it('has no entries for codes that do not exist', () => {
    const invented = [...Object.keys(ERROR_COPY), ...NOT_SHOWN_TO_USERS].filter(
      (code) => !codes.has(code) && !CLIENT_CODES.includes(code),
    );
    expect(invented).toEqual([]);
  });

  it('never apologises, exclaims or blames', () => {
    const sample = new ApiError(429, 'X', undefined, {}, 30);
    for (const [code, copy] of Object.entries(ERROR_COPY)) {
      const text = typeof copy.message === 'function' ? copy.message(sample) : copy.message;
      expect(text, code).not.toMatch(/sorry|oops|apolog|!|your fault|you failed/i);
      expect(text.endsWith('.'), code).toBe(true);
    }
  });
});

describe('describeError', () => {
  it('uses the numbers the problem carries', () => {
    expect(
      describeError(new ApiError(422, 'NOT_AT_PICKUP', undefined, { distanceMeters: 420 })).message,
    ).toBe('You’re 420 m from the pickup. Get closer to mark arrival.');
    expect(describeError(new ApiError(429, 'RATE_LIMITED', undefined, {}, 17)).message).toBe(
      'Too many attempts in a short time. Try again in 17 seconds.',
    );
    expect(describeError(new ApiError(429, 'OTP_LOCKED', undefined, {}, 600)).message).toBe(
      'Too many wrong codes. Try again in 10 minutes.',
    );
    expect(
      describeError(new ApiError(422, 'OTP_INVALID', undefined, { attemptsRemaining: 1 })).message,
    ).toBe('That code doesn’t match. 1 try left.');
  });

  it('still reads well without them', () => {
    expect(describeError(new ApiError(422, 'NOT_AT_PICKUP', undefined)).message).toBe(
      'You’re not at the pickup yet. Get closer to mark arrival.',
    );
    expect(describeError(new ApiError(429, 'RATE_LIMITED', undefined)).message).toBe(
      'Too many attempts in a short time. Try again shortly.',
    );
  });

  it('gives each failure a way out', () => {
    expect(describeError(new ApiError(410, 'QUOTE_EXPIRED', undefined)).recovery).toBe('requote');
    expect(describeError(new ApiError(409, 'ACTIVE_TRIP_EXISTS', undefined)).recovery).toBe(
      'open-trip',
    );
    expect(describeError(new ApiError(422, 'OUTSTANDING_BALANCE', undefined)).recovery).toBe(
      'top-up',
    );
  });

  it('falls back to a retry for an unmapped code, and logs it', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const described = describeError(new ApiError(418, 'SOMETHING_NEW', undefined));
    expect(described).toEqual({
      code: 'SOMETHING_NEW',
      message: 'That didn’t go through. Try again.',
      recovery: 'retry',
    });
    expect(warn).toHaveBeenCalledWith('No copy for error code SOMETHING_NEW');
  });

  it('handles errors that did not come from the API', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(describeError(new TypeError('boom'))).toMatchObject({
      code: 'UNKNOWN',
      recovery: 'retry',
    });
  });
});

describe('a locked account', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('says when it unlocks, by the clock', () => {
    vi.useFakeTimers({ now: Date.parse('2026-10-27T08:00:00Z') }); // 1:30 pm in India
    expect(describeError(new ApiError(401, 'ACCOUNT_LOCKED', undefined, {}, 900)).message).toBe(
      'This account is locked after several failed sign-ins. Try again at 1:45 pm.',
    );
    expect(clockTimeIn(0, Date.parse('2026-10-27T03:00:00Z'))).toBe('8:30 am');
  });

  it('still reads well without it', () => {
    expect(describeError(new ApiError(401, 'ACCOUNT_LOCKED', undefined)).message).toBe(
      'This account is locked after several failed sign-ins. Try again later.',
    );
  });

  it('names the time a driver may cancel for a no-show', () => {
    const early = new ApiError(422, 'NO_SHOW_TOO_EARLY', undefined, {
      canCancelAt: '2026-10-27T08:15:00Z',
    });
    expect(describeError(early).message).toBe('You can cancel for a no-show from 1:45 pm.');
  });
});

describe('waitText', () => {
  it('says seconds under a minute and minutes after', () => {
    expect(waitText(1)).toBe('1 second');
    expect(waitText(0.2)).toBe('1 second');
    expect(waitText(45)).toBe('45 seconds');
    expect(waitText(60)).toBe('1 minute');
    expect(waitText(61)).toBe('2 minutes');
  });
});
