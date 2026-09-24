# TypeScript Packages - Agent Instructions

These rules apply under `typescript/` and to the pnpm workspace files at the repository root. The root `AGENTS.md` still governs Kotlin, Gradle and everything else.

## Migration In Progress

The packages that move here from [fetcher](https://github.com/Ahoo-Wang/fetcher) (`wow-client`, `wow-react`, `wow-view-engine`, `wow-generator`, `storybook`, `integration-test`) arrive in steps. Read [MIGRATION.md](MIGRATION.md) first: it holds the plan, what has landed, the pull requests in flight and the next step. Update its "进度" section whenever a migration pull request merges.

## Packages

| Directory           | Package                                                                               | Published                                  | Moved from fetcher                                                       |
| ------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------ |
| `wow-client/`       | `@ahoo-wang/wow-client`                                                               | yes                                        | `@ahoo-wang/fetcher-wow`                                                 |
| `wow-react/`        | `@ahoo-wang/wow-react`                                                                | yes                                        | Wow hooks of `@ahoo-wang/fetcher-react`                                  |
| `wow-generator/`    | `@ahoo-wang/wow-generator` (CLI `wow-generator`, alias `fetcher-generator` until v10) | yes                                        | `@ahoo-wang/fetcher-generator`                                           |
| `wow-view-engine/`  | `@ahoo-wang/wow-view-engine`                                                          | not yet (`HELD_BACK` in `publish-npm.mjs`) | `@ahoo-wang/fetcher-view-engine`                                         |
| `storybook/`        | `wow-storybook`                                                                       | never                                      | fetcher's `.storybook` and the view-engine, shared and Wow query stories |
| `integration-test/` | `wow-integration-test`                                                                | never                                      | wow cases of fetcher's `integration-test`                                |

Each package keeps its own `AGENTS.md` with package-specific rules. Generated code imports `@ahoo-wang/wow-client`.

## Workspace

The pnpm workspace root is the repository root:

| File                                                 | Role                                                                                           |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `package.json`                                       | Private root; scripts and shared dev tooling only                                              |
| `pnpm-workspace.yaml`                                | Members (`typescript/*`, `compensation/dashboard`, `documentation`) and the dependency catalog |
| `pnpm-lock.yaml`                                     | The only JavaScript lockfile in the repository                                                 |
| `tsconfig.base.json`                                 | Compiler options that package `tsconfig.json` files extend                                     |
| `eslint.config.js`, `.prettierrc`, `.prettierignore` | Lint and format rules for this toolchain                                                       |

```bash
pnpm install
pnpm build:typescript     # Build the packages; they import each other through dist
pnpm lint                 # Root scripts, then ESLint inside each typescript/* package
pnpm typecheck            # tsc --noEmit in every typescript/* package (build first)
pnpm test                 # Unit tests of every package except integration-test (build first)
pnpm test:ci-scripts      # node --test for .github/scripts
pnpm --filter <package>... build
pnpm --filter <package> exec vitest run --maxWorkers=3 <file>
```

- Add external dependencies to the `catalog` in `pnpm-workspace.yaml` and reference them with `catalog:`. Reference workspace packages with `workspace:`.
- Never add another lockfile, a nested `pnpm-workspace.yaml`, or `shamefully-hoist`. A package that needs a dependency declares it.
- Prettier covers only the paths `.prettierignore` re-includes. The dashboard and the documentation site keep their own styles.

## Dependency Direction

- Dependencies point one way: Wow → fetcher. Every `@ahoo-wang/fetcher*` package comes from npm through the catalog and is a peer dependency of the publishable packages. Nothing in fetcher may depend on `@ahoo-wang/wow-*`.
- Workspace packages depend on each other through peer dependencies with `workspace:~`, which becomes `~x.y.z` on publish.

## Versions And Releases

- The single version source is `version` in the root `gradle.properties`. Maven and npm release together under the same tag.
- Breaking changes ship only in an `x.Y.0` release, for Kotlin and TypeScript alike. Mark them with `!` in the conventional commit. Release admission (`.github/scripts/release-admission.mjs`) enforces this.
- `pnpm set-version <version>` rewrites `gradle.properties` and every workspace `package.json`; `pnpm check:versions` (run by `quality`) fails when they disagree.
- Compatibility kept until v10 is listed in `docs/compat-debt.md`. Mark it with `@deprecated … Removed in v10.` or `// compat(wow<9): <reason>` / `// compat(fetcher): <reason>`; the ledger check in `quality` pairs markers with entries.
- A new public package must be added to `PUBLISHED` or `HELD_BACK` in `.github/scripts/publish-npm.mjs`; view-engine and view-store start in `HELD_BACK`.

## Code

- Strict TypeScript, ES modules, type-only imports (`consistent-type-imports`), and the Apache 2.0 header used across the repository.
- Before touching shadcn/Base UI components, load the repository skill `.claude/skills/shadcn`. Prefer existing shadcn and Base UI components and tokens over hand-written ones.

## CI

`.github/workflows/typescript.yml` runs on every pull request. Its `scope` job (`.github/scripts/ci-scope.mjs`) decides which jobs run: Kotlin, Gradle, dashboard and prose paths skip it, and unknown paths run everything. `typescript-gate` is the merge signal. When you add a directory, classify it in `ci-scope.mjs` and cover it in `ci-scope.test.mjs`. The compensation dashboard keeps its own `dashboard-test.yml`.
