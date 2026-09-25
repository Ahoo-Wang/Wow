# AGENTS.md — @ahoo-wang/wow-generator

<!-- This file provides coding agents with context about this package. -->

## Build & Run Commands

```bash
# Build this package
pnpm --filter @ahoo-wang/wow-generator build

# Run tests
pnpm --filter @ahoo-wang/wow-generator test

# Type-check src and every test file (`test` runs it after vitest)
pnpm --filter @ahoo-wang/wow-generator test:type

# Run a single test file
pnpm --filter @ahoo-wang/wow-generator exec vitest run --maxWorkers=2 test/pipeline/codeGenerator.test.ts

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

- Vitest with `globals: true` and `@vitest/coverage-v8`; `--testTimeout=15000`. Always pass `--maxWorkers=2`
  locally. Coverage thresholds (statements / branches / functions / lines): 97 / 92.5 / 98.5 / 98.
- Tests live in `test/`, grouped by the behaviour they hold, not by the class that implements it:

| Directory                     | Holds                                                                                                   |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- |
| `test/goldens/`               | Generated output byte for byte: the Wow documents, the OpenAI document, determinism across locales      |
| `test/probes/`                | A small document through the CLI into a cold directory, then `tsc --strict` or a real run               |
| `test/models/`                | What a generated model's types admit, checked by type-checking assignments against them                 |
| `test/analysis/`              | The `GenerationModel` a document yields: names, files, parameter order, body and return kinds, warnings |
| `test/emitters/`              | The code each emitter writes for a document                                                             |
| `test/output/`                | `OutputStore`, and regenerating over earlier output on disk                                             |
| `test/cli/`, `test/pipeline/` | The CLI (options, exit codes, Ctrl-C) and `CodeGenerator` end to end                                    |
| the rest                      | The leaf modules they are named after (`openapi/`, `naming/`, `input/`, `types/`, `wow/`, `emit/`, …)   |

- `test/support/` builds documents (`specs.ts`), runs the analysis and the emitters in memory
  (`emission.ts`), writes and type-checks models (`models.ts`), and generates through the CLI (`generation.ts`,
  `probes.ts`).
- Coverage instruments TypeScript's own compiler as it runs, about five times slower. A new ts-morph project
  or `ts.createProgram` parses `lib.dom.d.ts` and the `node_modules` declarations first, so a test that builds
  one per check spends nearly all its time parsing: `models.ts` keeps one in-memory project per test file,
  `generateCold` generates into one shared project, and `typeCheck` caches library declarations. Keep new
  tests on these helpers, and keep a file at a few seconds so the files spread over the workers.

## Public surface

`src/index.ts` is the only code entry, and it only re-exports: the programmatic API (`CodeGenerator`, its
options and result, the loggers, `GeneratorError` and `EXIT_CODES`). The public types live in `src/api/`,
which imports nothing else from the package; `CodeGenerator` lives in `src/pipeline/codeGenerator.ts`. Its
exports are listed name by name in `test/surface/root.txt`, which `test/publicSurface.test.ts` writes from
the source; a new export, or a removed one, changes the list, and the change is made on purpose with `-u`
and called out in the PR. The build runs `scripts/verify-package.mjs`, which holds the built ES module and
CommonJS entries to the same list, checks the `bin` files exist, that the declarations reachable from the
entry import neither `ts-morph` nor `@ahoo-wang/fetcher-openapi`, that no built file loads an `@ahoo-wang`
package, and that no declaration map ships. Anything else under `src/` is internal: do not export it from
`src/index.ts`.

- `CodeGenerator` takes only its options. The package hands it more under symbols it does not export, the
  `Seams` of `src/pipeline/seams.ts`: `PROJECT_SEAM`, a ts-morph project to write into (tests use
  `createCodeGenerator(options, project)` from `test/support/generation.ts`), and `SIGNAL_SEAM`, the
  `AbortSignal` the CLI aborts on Ctrl-C.
- A `Logger` has four required methods, `debug`, `info`, `warn` and `error`. Details go to `debug`, the
  outcome of a run to `info`. Only the pipeline logs warnings: every stage returns its warnings as lines,
  and `CodeGenerator` logs each one as it arrives and counts them for `GenerationResult.warnings`.
- A failure the user can act on is a `GeneratorError` of kind `input` (exit 2), `configuration` (3, an
  unreadable tsconfig too), `specification` (4) or `output` (5); a plain `Error` means a defect of the
  generator (exit 1). An interrupted run exits 130.

## Project Structure

The design, `docs/design/architecture.md`, explains the layers and why; this is the layout.

```
src/
  index.ts                    — Public re-exports only
  cli.ts                      — CLI entry point: runs cli/program.ts on the process's arguments
  api/                        — Public types and values; imports nothing else from the package
    options.ts                — GeneratorOptions, GenerationResult, SchemaDocs
    configuration.ts          — GeneratorConfiguration, ApiClientConfiguration, DEFAULT_CONFIG_PATH
    logger.ts                 — Logger, ConsoleLogger, SilentLogger, LogLevel
    errors.ts                 — GeneratorError, GeneratorErrorKind, EXIT_CODES
  cli/
    program.ts                — setupCLI: the commander program; runCLI: a usage error exits 2, --help/--version 0
    runGenerate.ts            — runGenerate: options, exit codes; generateAction: Ctrl-C aborts the run
  pipeline/
    codeGenerator.ts          — CodeGenerator: configuration, document, Wow model, analysis, emit, finish, commit
    seams.ts                  — PROJECT_SEAM, SIGNAL_SEAM, Seams, SeamOptions
  input/                      — Loading: file and http resources, JSON/YAML, the configuration (warnings returned)
  openapi/                    — Reading the document: OpenApiDocument (endpoints listed once), components, references, operations, responses, schemas
  naming/                     — Identifiers and cases (naming.ts); the en-US order of names (order.ts); ModelInfo; file layout and combinePaths (paths.ts)
  wow/                        — Wow conventions (conventions.ts); the Wow model (model.ts); resolveWowModel, a pure function (resolveWowModel.ts)
  analysis/                   — Document + Wow model + configuration → GenerationModel, as plain data; no ts-morph
    model.ts                  — GenerationModel and its parts: contexts, models, aggregates, API clients
    analyze.ts                — analyze(): the model and its warnings
    models.ts                 — Models and bounded contexts: which schemas, which files, name collisions
    aggregates.ts             — Command and query clients of each aggregate
    apiClients.ts             — API clients: tags, method names, parameters, bodies, returns
    modelInfo.ts              — How a component key names a model
    clientNames.ts            — Client, method and parameter names
  types/
    typeResolver.ts           — Schema → type text and the imports it needs, as pure functions (no module writes)
  emit/                       — The writing kit: ModuleBuilder, ImportRegistry, import specifiers, JSDoc text
  emitters/                   — GenerationModel → module statements: models, bounded contexts, query, command and API clients, index files
  finalize/
    finalize.ts               — finalizeSourceFiles: format, organize and type imports, verify, add the header
    typeOnlyImports.ts        — Rewrites imports of types to `import type`
    verification.ts           — Checks the output compiles before anything is written
  output/
    outputStore.ts            — OutputStore: one run's output — manifest, ownership, path guard, writing, removing stale files
```

Dependencies point one way. `eslint.config.js` holds it: `import-x/no-cycle` rejects a value import that
closes a loop, `import-x/no-restricted-paths` keeps each layer from importing anything above it, types
included (`api/` → `naming/` → `openapi/` → `wow/` → `analysis/`; `emit/` → `types/` → `emitters/`, which
read `analysis/`), and only the entries reach `pipeline/` or `cli/`. `no-restricted-imports` keeps ts-morph
out of `api/`, `input/`, `naming/`, `openapi/`, `wow/` and `analysis/`. There are no barrel files: import
the module itself. Sort names with `compareNames` from `naming/order.ts`, never `localeCompare`, whose order
follows the machine's locale.

A run decides everything first, then writes: `analyze()` returns the `GenerationModel` (every name, file,
parameter, body and return kind, as data), and `emitGeneration()` describes each file to its `ModuleBuilder`
in the order the files have always been written, resolving schemas to types as it goes, so the imports of a
module and the aliases a clash gives them come out the same. Emitters never change a ts-morph `SourceFile`
while they work: ts-morph re-parses the whole file on every change, which made a large document quadratic.
They add declarations as ts-morph structures (members and `docs` included) and imports to the module's
`ImportRegistry`; the pipeline writes every module once (`ModuleSet.build`) before the index files and
finishing. `test/emit/moduleBuilder.test.ts` holds that writing a module once gives the same text as adding
its statements one at a time.

### Key Concepts

- **CodeGenerator**: Main class that orchestrates code generation from OpenAPI specs
- **GenerationModel**: What a document generates, decided before anything is written (`analysis/model.ts`)
- **OutputStore**: One run's output directory: manifest `.wow-generator.json`, owned files, stale files
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

| Golden                                               | Held by                               | Covers                                                                                                                                                                                               | Accept an intentional change                                                                    |
| ---------------------------------------------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `expected/demo-spec/`, `expected/compensation-spec/` | `test/goldens/wowDocuments.test.ts`   | The two Wow documents, file by file                                                                                                                                                                  | `UPDATE_SNAPSHOTS=true pnpm --filter @ahoo-wang/wow-generator test`                             |
| `expected/openai-spec/.wow-generator.json`           | `test/goldens/openaiDocument.test.ts` | `test/openai.spec.yml` (873 schemas, not Wow): the SHA-256 of every file, in the manifest's format; a failure names the files that differ                                                            | `pnpm --filter @ahoo-wang/wow-generator exec vitest run test/goldens/openaiDocument.test.ts -u` |
| `expected/warnings/*.txt`                            | both suites                           | The warnings of each of the three documents, word for word                                                                                                                                           | `vitest run -u` on the suite                                                                    |
| `expected/type-resolver.json`                        | `test/types/typeResolver.test.ts`     | The text and imports `types/typeResolver.ts` gives each case of `test/types/typeResolverCases.ts`                                                                                                    | `vitest run test/types/typeResolver.test.ts -u`                                                 |
| `expected/wow-model/*.json`                          | `test/wow/wowModelContract.test.ts`   | The Wow model of the two Wow documents (aggregates, commands, events, state, fields, resource names, lent doc comments, warnings), and the hash of each file they generate with `schemaDocs: 'full'` | `vitest run test/wow/wowModelContract.test.ts -u`                                               |

`test/goldens/determinism.test.ts` runs the built CLI (build first) under `tr_TR.UTF-8` and `en_US.UTF-8`, from
two working directories, and holds the output to `expected/demo-spec/` and to itself: the output must not depend
on the locale or the working directory.

All the goldens run in the default `test`, in CI too: the OpenAI document generates in about a second. A pull
request that changes a golden on purpose lists the files that changed.

`pnpm --filter @ahoo-wang/wow-generator bench [spec ...]` (after `build`) times a generation phase by phase, with
CPU and peak memory; without specs it times the demo and the OpenAI documents. It is not run in CI. The baseline
and the numbers after each refactor batch are in section 5 of `docs/design/architecture.md`.

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
