# AGENTS.md — fetcher-generator

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter fetcher-generator build

# Run tests
pnpm --filter fetcher-generator test

# Run a single test file
pnpm --filter fetcher-generator vitest run src/index.test.ts

# Lint
pnpm --filter fetcher-generator lint

# Clean
pnpm --filter fetcher-generator clean

# Run CLI (after build)
node dist/cli.js generate -i <openapi-spec> -o <output-dir> -t tsconfig.json
```

## Testing

- Vitest with `globals: true` and `@vitest/coverage-v8`
- Test files: `*.test.ts` alongside source files
- `--testTimeout=15000` (longer timeout for code generation tests)
- Test spec fixture: `test/demo.spec.json`

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
- **commander**: CLI framework for the `fetcher-generator` command
- **Generation modes**: API client classes, Wow CQRS clients (command/query), model types
- **Decorator output**: Generates `@api`, `@get`, `@post` etc. decorated classes

## Dependencies

- `@ahoo-wang/fetcher` — core HTTP client types
- `@ahoo-wang/fetcher-eventstream` — event stream types
- `@ahoo-wang/fetcher-decorator` — decorator type references
- `@ahoo-wang/fetcher-openapi` — OpenAPI type definitions
- `@ahoo-wang/fetcher-wow` — Wow CQRS types
- `ts-morph` — TypeScript AST manipulation
- `commander` — CLI framework
- `yaml` — YAML parsing

## Code Style

- TypeScript strict mode
- Apache 2.0 license headers
- Prettier: single quotes, trailing commas, semicolons, 80 char width
- Generated code should follow the same style conventions

## Git Workflow

- Conventional commits: `feat(generator):`, `fix(generator):`, `test(generator):`
- Version synced via `pnpm update-version`

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
