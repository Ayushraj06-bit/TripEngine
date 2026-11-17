/// <reference types="node" />
import { ReadableStream, TransformStream, WritableStream } from 'node:stream/web';

/*
 * The tests run in vmThreads (vite.config.ts): one jsdom per worker, each test file still in a
 * sandbox of its own. That sandbox has no web streams, which MSW needs to answer requests, so
 * Node's are handed in - in a setup file of their own, listed first, so they are there before
 * anything that might need them is imported.
 */
Object.assign(globalThis, { ReadableStream, TransformStream, WritableStream });
