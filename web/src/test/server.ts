import { setupServer } from 'msw/node';

/** The mocked backend. Each test adds the handlers it needs with server.use(...). */
export const server = setupServer();
