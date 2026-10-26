// The backend's OpenAPI document, kept as a committed snapshot that the client's types are
// generated from (npm run api:types).
//
//   node scripts/openapi.mjs fetch   writes src/api/openapi.json from the running backend
//   node scripts/openapi.mjs check   fails if the running backend no longer matches the snapshot
//
// The backend's address comes from API_DOCS_URL (default http://localhost:8080/v3/api-docs).
// Normalised so the file only changes when the API does: keys sorted, and "servers" dropped,
// since it names whichever host answered.
import { readFile, writeFile } from 'node:fs/promises';

const url = process.env.API_DOCS_URL ?? 'http://localhost:8080/v3/api-docs';
const snapshot = new URL('../src/api/openapi.json', import.meta.url);

function sortKeys(value) {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, sortKeys(value[key])]),
  );
}

async function current() {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  const document = await response.json();
  delete document.servers;
  return JSON.stringify(sortKeys(document), null, 2) + '\n';
}

const mode = process.argv[2];
if (mode === 'fetch') {
  await writeFile(snapshot, await current());
  console.log(`Wrote ${snapshot.pathname} from ${url}`);
} else if (mode === 'check') {
  const [live, committed] = await Promise.all([current(), readFile(snapshot, 'utf8')]);
  if (live !== committed) {
    console.error(
      'The backend API differs from web/src/api/openapi.json. Run `npm run api:update` in web/ and commit the result.',
    );
    process.exit(1);
  }
  console.log('The backend API matches the committed snapshot.');
} else {
  console.error('Usage: node scripts/openapi.mjs fetch|check');
  process.exit(2);
}
