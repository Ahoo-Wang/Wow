---
title: Regenerate in CI
description: Keep generated Wow clients in step with the services they call, by committing the OpenAPI document and failing CI when regeneration changes the output.
---

# Regenerate in CI

This page answers: **how do I make sure the committed generated clients match the service contract, without a developer remembering to regenerate?**

Treat the generated code like a lockfile: it is committed, a tool produces it from an input, and CI fails when the committed output is not what the tool produces now. The Wow repository checks its own generated clients this way against the example service, on every change to the server or the generator.

## 1. Decide where the document comes from

| Source | Suits | Trade-off |
|---|---|---|
| A document committed in the application's repository (`openapi/orders.json`) | Most projects | The build needs no running service. Someone updates the file when the service changes — by hand, by a scheduled job, or by a pull request from the service's pipeline |
| The running service (`-i https://orders.internal/v3/api-docs`) | A staging environment that always runs the version you release against | CI depends on that environment being up and on the right version; pass credentials with `-H` and a `--timeout` |

Either way, generate from exactly one document per service, and let the output directory belong to the generator alone.

## 2. One script

```json
{
  "scripts": {
    "generate": "wow-generator generate -i openapi/orders.json -o src/generated/orders -t tsconfig.json --strict",
    "generate:check": "pnpm generate && git diff --exit-code -- src/generated"
  }
}
```

- `--strict` exits with code 4 when the run logged a warning, such as an operation it skipped for lack of an operationId, so a contract that generates less than it should fails too.
- The run records its files in `src/generated/orders/.wow-generator.json`. Commit it: it is how the next run deletes a file it no longer generates.
- `git diff --exit-code` also sees deleted files. To catch added ones, stage the directory first or check `git status --porcelain`, as below.

Several services go to several output directories, one command each; the manifest of each keeps their files apart.

## 3. The CI job

```yaml
name: Generated clients
on:
  pull_request:
  push:
    branches: [main]
jobs:
  generated-clients:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: pnpm/action-setup@v4
      - uses: actions/setup-node@v5
        with:
          node-version: 22
          cache: pnpm
      - run: pnpm install --frozen-lockfile
      - name: Regenerate
        run: pnpm generate
      - name: Require the committed clients to match
        run: |
          if [ -n "$(git status --porcelain --untracked-files=all -- src/generated)" ]; then
            git --no-pager diff -- src/generated
            echo "::error::src/generated is out of date. Run 'pnpm generate' and commit the result."
            exit 1
          fi
      - name: Type-check
        run: pnpm exec tsc --noEmit
```

Pin the actions to the versions or commit SHAs your organization uses. To generate from a running service instead, replace the input with the URL and pass its token from a secret: `-i "$SPEC_URL" -H "Authorization: Bearer $SPEC_TOKEN" --timeout 60000`.

## 4. Read the exit code

| Exit code | Meaning | What to do |
|---|---|---|
| 0 | Generated | Compare the output, as above |
| 1 | Internal error | Rerun with `--verbose` for the stack trace and report it |
| 2 | Input: the document cannot be read or fetched, is not JSON or YAML, is not OpenAPI 3.x (Swagger 2.0 is refused), or an option is invalid | Check the path or URL, the network and the credentials |
| 3 | Configuration: `wow-generator.config.json` (or the file named with `-c`) cannot be read, parsed or validated | Fix the file; a missing default file is not an error |
| 4 | Specification: the document describes code the generator cannot produce, or `--strict` and the run logged a warning | Read the one-line message; it names the operation or schema |
| 130 | Interrupted | — |

The [CLI reference](../../reference/typescript/wow-generator/cli.md#failures-and-exit-codes) lists every case.

## 5. Review a regeneration

When the check fails because the service changed, regenerate locally and read the diff like a contract change: a removed command method or a renamed field breaks callers at compile time, which is the point. Upgrading `@ahoo-wang/wow-generator` itself can change the output too; do it in its own pull request, with the regenerated code, so that the diff shows only what the generator changed. Never edit generated files by hand: the next run replaces them.

## Where to read more

- [Quick Start](./quick-start.md): the first generation.
- [Generator CLI](../../reference/typescript/wow-generator/cli.md), [configuration](../../reference/typescript/wow-generator/configuration.md) and [generated output and regeneration](../../reference/typescript/wow-generator/generated-output.md).
- [Compatibility and Versions](./compatibility.md): which generator version to use with which service.
