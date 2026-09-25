# AGENTS.md — @ahoo-wang/wow-generator

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/wow-generator build

# Run tests
pnpm --filter @ahoo-wang/wow-generator test

# Run a single test file
pnpm --filter @ahoo-wang/wow-generator exec vitest run test/index.test.ts

# Accept a change to the public surface
pnpm --filter @ahoo-wang/wow-generator exec vitest run test/publicSurface.test.ts -u

# Lint
pnpm --filter @ahoo-wang/wow-generator lint

# Clean
pnpm --filter @ahoo-wang/wow-generator clean

# Run CLI (after build)
node dist/cli.js generate -i <openapi-spec> -o <output-dir> -t tsconfig.json
```

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` in a `test/` directory at the package root (mirroring `src/`)
- `--testTimeout=15000` (longer timeout for code generation tests)
- Test spec fixture: `test/demo.spec.json`

## Public surface

`src/index.ts` is the only code entry: the programmatic API (`CodeGenerator`, its options and result, the
loggers, `GeneratorError` and `EXIT_CODES`). Its exports are listed name by name in `test/surface/root.txt`,
which `test/publicSurface.test.ts` writes from the source; a new export, or a removed one, changes the list,
and the change is made on purpose with `-u` and called out in the PR. The build runs
`scripts/verify-package.mjs`, which holds the built ES module and CommonJS entries to the same list, checks
the `bin` files exist, and that no declaration map ships. Anything else under `src/` is internal: do not
export it from `src/index.ts`.

## Project Structure

```
src/
  index.ts                    — CodeGenerator main class
  cli.ts                      — CLI entry point (commander-based)
  generateContext.ts           — Generation context/state
  types.ts                    — Shared type definitions
  client/
    apiClientGenerator.ts     — API client class generator
    clientGenerator.ts        — Base client generator
    commandClientGenerator.ts — Wow command client generator
    queryClientGenerator.ts   — Wow query client generator
    decorators.ts             — Decorator code generation helpers
    utils.ts                  — Client generation utilities
  model/
    modelGenerator.ts         — TypeScript interface/enum generator
    modelInfo.ts              — Model metadata extraction
    typeGenerator.ts          — Type mapping from OpenAPI to TypeScript
    wowTypeMapping.ts         — Wow-specific type mappings
  aggregate/
    aggregate.ts              — Aggregate definition generator
    aggregateResolver.ts      — Aggregate resolution from OpenAPI spec
    types.ts                  — Aggregate type definitions
    utils.ts                  — Aggregate utilities
  utils/
    clis.ts                   — CLI utility functions
    components.ts             — Component helpers
    logger.ts                 — Logging utility
    naming.ts                 — Name transformation utilities
    operations.ts             — OpenAPI operation processing
    parsers.ts                — OpenAPI spec parsing
    references.ts             — $ref resolution
    resources.ts              — Resource grouping
    responses.ts              — Response type processing
    schemas.ts                — Schema processing
    sourceFiles.ts            — ts-morph source file management
  stories/                    — Storybook stories
```

### Key Concepts

- **CodeGenerator**: Main class that orchestrates code generation from OpenAPI specs
- **OpenAPI 3.x**: Reads JSON/YAML/URL specs and generates TypeScript code
- **ts-morph**: Used for TypeScript AST manipulation and code generation
- **commander**: CLI framework for the `wow-generator` command (`fetcher-generator` stays as an alias until v10)
- **Generation modes**: API client classes, Wow CQRS clients (command/query), model types
- **Decorator output**: Generates `@api`, `@get`, `@post` etc. decorated classes

## Dependencies

- `@ahoo-wang/fetcher` — core HTTP client types
- `@ahoo-wang/fetcher-eventstream` — event stream types
- `@ahoo-wang/fetcher-decorator` — decorator type references
- `@ahoo-wang/fetcher-openapi` — OpenAPI type definitions
- `@ahoo-wang/wow-client` — Wow CQRS types
- `ts-morph` — TypeScript AST manipulation
- `commander` — CLI framework
- `yaml` — YAML parsing

## Goldens And Timing

Generated code is a public surface, so a change that is meant to keep it must keep every byte. These goldens hold it:

| Golden                                               | Held by                     | Covers                                                                                                                                    | Accept an intentional change                                        |
| ---------------------------------------------------- | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `expected/demo-spec/`, `expected/compensation-spec/` | `test/e2e.test.ts`          | The two Wow documents, file by file                                                                                                       | `UPDATE_SNAPSHOTS=true pnpm --filter @ahoo-wang/wow-generator test` |
| `expected/openai-spec/.wow-generator.json`           | `test/openaiGolden.test.ts` | `test/openai.spec.yml` (873 schemas, not Wow): the SHA-256 of every file, in the manifest's format; a failure names the files that differ | `pnpm --filter @ahoo-wang/wow-generator test:large -u`              |
| `expected/warnings/*.txt`                            | both suites                 | The warnings of each of the three documents, word for word                                                                                | `vitest run -u` on the suite, or `test:large -u`                    |

The OpenAI golden takes minutes to generate until the emit layer writes each file once (refactor batch B4 in `docs/design/refactor-2026-09.md`), so it runs only with `WOW_GENERATOR_LARGE=1` (`pnpm test:large`), not in the default `test` or in CI. Run it before merging any change to `src/`. A pull request that changes a golden on purpose lists the files that changed.

`pnpm --filter @ahoo-wang/wow-generator bench [spec ...]` (after `build`) times a generation phase by phase, with CPU and peak memory; without specs it times the demo and the OpenAI documents. It is not run in CI. The baseline is in section 7 of the refactor plan.

## Code Style

- TypeScript strict mode
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width
- Generated code should follow the same style conventions

## Git Workflow

- Conventional commits: `feat(generator):`, `fix(generator):`, `test(generator):`
- Version follows `version` in the repository root `gradle.properties`

## Boundaries

- ✅ Adding new code generation templates
- ✅ Adding new OpenAPI schema type support
- ✅ Adding new CLI options
- ✅ Writing new tests
- ⚠️ Changing generated code format — may break consumer codebases
- ⚠️ Modifying ts-morph usage — complex, test thoroughly
- ⚠️ Changing Wow CQRS generation — affects Wow microservice consumers
- 🚫 Breaking CLI command interface without major version bump
- 🚫 Removing existing generator output formats
- 🚫 Changing the decorator output format (must match @ahoo-wang/fetcher-decorator)
