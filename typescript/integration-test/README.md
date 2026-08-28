# Integration tests

This workspace verifies built Fetcher packages against real HTTP services and
generated Wow clients. It is intentionally separate from deterministic package
unit tests.

## What runs

- Core Fetcher and decorator requests against JSONPlaceholder.
- Wow commands, snapshots, load-state, and event streams against the Wow example
  server.
- Generated Wow clients under `src/generated/`.
- OpenAI-compatible streaming and non-streaming calls when LLM test variables
  are available.

## Prerequisites

- Install root dependencies.
- Build all packages before running tests.
- Allow outbound access for JSONPlaceholder tests.
- Run MongoDB plus `ghcr.io/ahoo-wang/wow-example-server:8.13.0` on port 8080
  for generation and Wow tests. The CI workflow is the canonical service setup.
- Provide LLM test variables only through the environment; never commit values.

## Generate and test

From the repository root:

```bash
pnpm install --frozen-lockfile
pnpm build
pnpm --dir integration-test generate
pnpm test:it
```

`generate` reads `http://localhost:8080/v3/api-docs` and replaces
`integration-test/src/generated`. Inspect generated changes before committing
them.

Run one test while diagnosing:

```bash
pnpm --dir integration-test vitest run \
  test/wow/cart/cartSnapshotQueryClient.test.ts
```

## Optional LLM environment

- `FETCHER_LLM_BASE_URL`
- `FETCHER_LLM_API_KEY`
- `FETCHER_LLM_MODEL`

These tests contact the configured provider and may incur cost. Keep them out of
ordinary local runs unless live integration evidence is required.

## Failure diagnosis

1. Build first; unresolved workspace imports usually mean stale or missing
   package output.
2. Check `http://localhost:8080/actuator/health` before generator or Wow tests.
3. Regenerate after a server-contract change.
4. Separate network/provider failures from package unit regressions.
5. Remove temporary containers and unset credential environment variables when
   finished.

[中文](./README.zh-CN.md)
