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
pnpm typecheck            # tsc --noEmit in every typescript/* package, integration-test with its tests (build first)
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
- Public packages declare `engines.node` equal to the root's (`>=22.12.0`) and take every external peer range from the named catalog `catalog:peers`, never the default catalog: peer ranges are a published contract that Renovate only widens. Raise a lower bound by hand, and only in an `x.Y.0` release.
- `node .github/scripts/package-check.mjs` (the `package` job, and the release preflight) checks the packed tarballs: publint, types under node16, nodenext and bundler resolution, and a fresh npm project that imports, requires and runs them. Run it after `pnpm build:typescript` when you change a `package.json`, an entry point or the build. With `--registry` it runs the same consumer checks on the versions npm serves; the release workflow's `npm-smoke` job runs it after `npm-deploy`.
- Releasing, including the first npm release and rolling a broken release back, follows [RELEASING.md](RELEASING.md). Release notes follow [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md).

### Size ceilings

Size is not a target; function and experience come first. The ceilings only catch an accidental blow-up, such as a dependency bundled by mistake or a chunk that stopped being lazy. Each package's `scripts/verify-package.mjs`, which its `build` runs in CI and in the release preflight, weighs its entries through `.github/scripts/size-budget.mjs`. An entry's weight is the entry module plus every module of the package it imports statically, gzipped at level 9. External packages are not counted, and a dynamic `import()` is weighed on its own. Each weight is held under a ceiling in the package's `scripts/size-budget.json`: `wow-client`'s root, `/dsl` and `/legacy`; `wow-react`'s root; and `wow-view-engine`'s `.`, `./react`, `./ui`, the lazily loaded chart chunk, `styles.css` and `themes.css`. A ceiling is set about 15–20% above what it measured. A failure names the entry, the measured size and the ceiling. If the growth is intended, raise the ceiling in the same pull request, again to about 15–20% above the new size, update its `reason`, and say so in the pull request.

## Code

- Strict TypeScript, ES modules, type-only imports (`consistent-type-imports`), and the Apache 2.0 header used across the repository.
- Before touching shadcn/Base UI components, load the repository skill `.claude/skills/shadcn`. Prefer existing shadcn and Base UI components and tokens over hand-written ones.

## Flaky Tests

A test that fails and then passes at the same commit is a defect, in the test or in the product. [#3339](https://github.com/Ahoo-Wang/Wow/pull/3339) is the worked example.

- **No retries.** Do not add `retry` to Vitest or the Storybook runner, and do not raise a timeout to make a failure go away. A re-run in CI is for confirming a flake, not for merging past one.
- **Fix the root cause**, then say in the pull request what the cause was (a race in the product, or a test waiting on the wrong signal) and what evidence showed it.
- **Or quarantine it**, when the fix cannot land right away: skip only that test (`it.skip`, or `tags: ['!test']` on a story) with a comment that links an open issue labelled `area: typescript` and names a deadline, at most two weeks out: `// Quarantined: <issue URL>, fix by YYYY-MM-DD.` At the deadline the test is fixed, or deleted with its coverage replaced; it is never left skipped.
- **Reproduce under load before fixing, and again after.** CI runners are slower than a laptop, so make the local run slow:
  - one package, one file, `--maxWorkers=2` (or `--maxWorkers=1 --no-file-parallelism`), in a loop of 20 runs;
  - at low priority: `nice -n 19` anywhere, `taskpolicy -b` on macOS, or next to a full suite of another package;
  - with the suspected timing injected: hold a promise on a deferred, delay a response or a store answer, or reorder the step the test assumes has finished (a popup's close before the next focus). A fix counts when the injected timing fails without it and passes with it, and the loop of 20 passes.

## CI

`.github/workflows/typescript.yml` runs on every pull request. Its `scope` job (`.github/scripts/ci-scope.mjs`) decides which jobs run: Kotlin, Gradle, dashboard and prose paths skip it, and unknown paths run everything. `typescript-gate` is the merge signal; it also covers `workflow-lint`, which runs actionlint whenever any workflow changes. When you add a directory, classify it in `ci-scope.mjs` and cover it in `ci-scope.test.mjs`. The compensation dashboard keeps its own `dashboard-test.yml`.
