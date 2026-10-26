import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { resetMedia } from './media';
import { server } from './server';

beforeAll(() => {
  // A request no handler expects is a test bug, not a silent pass.
  server.listen({ onUnhandledFrame: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  resetMedia();
});

afterAll(() => {
  server.close();
});
