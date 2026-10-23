# 18. Packaging and delivery: one image recipe, generated demo secrets, a staged pipeline

- **Status:** Accepted
- **Date:** 2026-10-24

## Context

Phase 11 asks for container images of the backend and the simulator, a demo started with one
command, a CI/CD pipeline that publishes images, and nightly runs of the slow suites. The images must
run as a non-root user, size their heap from the container, stay small and report their health.

## Decision

### One Dockerfile for both applications

`infra/Dockerfile` builds either one; `MODULE=simulator` picks the simulator.
- **Build stage:** Maven builds the jar, and Spring Boot's tools jar mode splits it into layers. They
  are copied least to most often changed, so a code change rebuilds only the last.
- **Runtime:** a Java runtime cut by jlink to the modules the application uses, on Alpine.
  - **Which modules:** those jdeps finds, plus those libraries load by name at run time: elliptic
    curves for TLS, the zip file system, DNS, extra charsets.
- **Hardening:** a non-root user; the heap a share of the container's memory limit; an out-of-memory
  error ends the process, so the orchestrator restarts it.
- **Health:** checked on `/readyz`, so a container is healthy only once it can serve.

Rejected: **buildpacks**. They give less control over the user, the runtime and the health check,
and a bigger image, for a download at every build.

### The demo's secrets are generated, never committed

The two backend instances must share a token signing key and a webhook secret, and the live map
needs an admin password.
- **How:** on first start, a one-shot container generates them into a volume as files named for the
  variables the applications read. The applications import that directory as a Spring config tree.
- **Where they end:** they never reach the repository and never leave the machine;
  `docker compose down -v` discards them.
- **The admin login:** `demo-info` prints it with the URLs.

Rejected: **demo values in the compose file.** Committed credentials are a backdoor the day someone
deploys the demo as it is. **Exporting them by hand**, as before, made the demo several commands.

### A pipeline in stages

1. **CI on every push:** build, unit tests, integration tests and the coverage gate, as separate
   steps.
   - **Which tests are integration:** those tagged `integration`, the ones that start Spring or
     containers.
   - **Proof the split is complete:** the unit stage passes with no Docker running.
2. **Images:** built on every push, then run before anything is published: the one-command demo
   starts on them and is checked - the URLs it prints answer, and the simulator's trips are
   booked. Pushed to the GitHub Container Registry only from `main` and from `phase-*` tags,
   tagged `sha-<short>`, the branch or tag, and `latest` on main.
3. **Nightly, and on demand:** the end-to-end and chaos suites.

Rejected: **running the end-to-end and chaos suites on every push.** They take minutes and need the
whole stack, and every change already passes the integration suite on real infrastructure.

## Consequences

- A library that loads a JDK module by name the list does not cover would fail at run time, not at
  build time. The demo in the image job starts both images and drives real traffic through them
  before they are pushed, which is where that would show; the list then grows by one name.
- The images are private, like the repository. Pulling them needs a token with `read:packages`.
- The demo runs the `sim` profile: no per-address limits, and the mock gateway on. It is a demo,
  and `docs/deploy.md` says what a real deployment changes.
