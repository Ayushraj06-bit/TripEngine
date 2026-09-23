# Load tests

k6 scripts that measure the targets in PROJECT_SPEC section 2. Results are
recorded in `docs/performance.md` — measured numbers only, never estimates.

## Running

```bash
k6 run load-tests/<script>.js
```

Point the scripts at the stack with `-e BASE_URL=http://localhost:8080`.

## Status

Empty. The scripts are written in Phase 10, once there is an API to drive. Each
script states which target from PROJECT_SPEC section 2 it measures.
