# 1. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

PROJECT_SPEC section 13 makes an ADR part of the definition of done for any
significant decision. We need one consistent place and format for them, decided
before the first decision has to be written down.

## Decision

We record architecture decisions as numbered Markdown files in `docs/adr/`,
named `NNNN-kebab-case-title.md`, following Michael Nygard's format: Context,
Decision, Consequences.

An ADR is immutable once accepted. If a later decision changes it, we write a
new ADR and set the old one's status to `Superseded by NNNN`.

A decision is "significant" when it is hard to reverse, when it constrains later
phases, or when a reasonable engineer would ask "why was it done this way?".

## Consequences

- The reasoning behind a choice survives after the people involved forget it.
- Every phase carries a small, fixed documentation cost.
- Reviewers can challenge the decision rather than the code that follows from it.
