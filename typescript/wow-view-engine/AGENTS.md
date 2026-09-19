# AGENTS.md — @ahoo-wang/fetcher-view-engine

<!-- This file provides coding agents with context about this package. -->

Configurable data view engine for Wow-based applications: definitions in code, view configs as data, rendered as records, analyses and dashboards.

## Status — active development, no compatibility obligation

This package is being rebuilt from an empty tree against `docs/design/`, and it **does not owe anyone backward compatibility**. Treat every export as changeable.

- **Change the shape; do not add a compatibility layer.** No shims, no `*V2` names, no aliases re-exported "just in case", no `@deprecated` markers, no migration guides. The wow package carries a deprecated Condition API for external reasons — this package must never grow one.
- **Renaming, narrowing or deleting an export is a normal change.** Update the callers in this repo and move on. The only consumers that matter are in this monorepo.
- **When a design gets clearer, rewrite the old shape out of existence** rather than layering onto it. A clean architecture outranks a stable surface here.
- **`docs/design/` is the source of truth** for the model, boundaries and contracts, and it is written before the code. It is one page per layer — start at `docs/design/README.md`, which indexes the rest. When behaviour it describes changes, change the page in the same PR. Where this file and the design doc disagree, the design doc wins.
- **Before changing behaviour, check `docs/design/todo.md`** for an entry that already covers it, and continue that one rather than opening a second front. Entries are deleted when done — never ticked. Product questions still undecided live in `docs/design/decisions.md`, not there.
- **A rule worth having is a test, not a paragraph** (`docs/design/README.md`). Do not add an invariants index; add the test.

## Build & Run Commands

`@ahoo-wang/fetcher-wow` resolves to its `dist/`, so the workspace dependencies must be built first — otherwise vitest fails with `Failed to resolve import "@ahoo-wang/fetcher-wow"`, and even this package's own `build` cannot stand alone, because its `test:package` step imports the built entry. Use the **trailing `...`** filter, which the root `AGENTS.md` documents for exactly this; it selects `fetcher` → `eventstream` + `decorator` → `wow` → `view-engine`. `pnpm build` from the repo root does the same for everything.

```bash
# Build, workspace dependencies included (also verifies the built package)
pnpm --filter @ahoo-wang/fetcher-view-engine... build

# Run tests (coverage + three tsc projects)
pnpm --filter @ahoo-wang/fetcher-view-engine test

# Faster loop, no coverage
pnpm --filter @ahoo-wang/fetcher-view-engine test:no-coverage

# Run a single test file (`exec` — there is no `vitest` script)
pnpm --filter @ahoo-wang/fetcher-view-engine exec vitest run test/filter.test.ts

# Type-check only: tsconfig.json, tsconfig.headless.json, tsconfig.test.json
pnpm --filter @ahoo-wang/fetcher-view-engine test:type

# Verify the built artifact (needs a build first)
pnpm --filter @ahoo-wang/fetcher-view-engine test:package

# Lint (--fix) and the CI gate (--max-warnings 0)
pnpm --filter @ahoo-wang/fetcher-view-engine lint
pnpm --filter @ahoo-wang/fetcher-view-engine lint:check
```

## Testing

- Vitest in the **jsdom** environment, with `clearMocks` and `restoreMocks`
- **No `globals: true`** — unlike the other packages here, import `describe`, `it`, `expect`, `vi` from `vitest` explicitly
- Test files live in `test/` at the package root, named by subject rather than mirroring `src/` one-to-one (46 files). Fixtures shared by several suites sit beside them: `test/fixtures.ts` for definitions, configs and sources, `test/fixtures/ui.tsx` for what the UI suites open
- `@` resolves to `src/`
- **Coverage thresholds are enforced**: statements 95, branches 91, functions 97, lines 96. `src/ui/components/**`, `src/ui/lib/**` and `src/styles.ts` are excluded — they are vendored from the shadcn registry and are upstream's to test
- `test/architecture.test.ts` enforces the dependency rules below on the TypeScript AST, so multi-line, type-only, re-exported and **statically resolvable** dynamic imports are all seen — an `import()` whose argument is a string literal or a substitution-free template. One built from a variable is not recorded, and would slip past these assertions. It reads the wow **sources** off disk, so it is the one suite that runs without any build — every test that imports `@ahoo-wang/fetcher-wow` needs the dependency chain built first
- `tsconfig.headless.json` type-checks the headless layers **without the DOM lib**, which is what keeps them free of browser globals
- `scripts/verify-package.mjs` checks the built artifact: every entry resolves and imports, the root entry's types need no DOM lib, and no JavaScript entry pulls in the stylesheet, and the built stylesheet paints nothing outside `.fve-root` — no rule sits outside the root, Tailwind's `:root` theme variables included. `scripts/scope-utilities.mjs` (`postcss-prefix-selector`) makes that true at build time by pinning every rule, preflight and utilities included, to `:where(.fve-root, .fve-root *)`; Storybook runs the same plugin on the theme file

## Architecture — the six dependency rules

From `docs/design/README.md`, all enforced by `test/architecture.test.ts`:

1. `model` imports no other directory
2. `filter` imports `model` only
3. `record`, `analysis`, `dashboard` import `model` and `filter` only — **never each other**
4. `runtime` imports `model`, `filter`, `record`, `analysis`, `dashboard`, and the `store` port type only — never `react` or `ui`
5. `store` imports `model` only
6. `react` never imports `ui`; `ui` may import everything

Beyond the six:

- `model` through `store` contain no React, DOM, `window` or `document`
- `runtime` reaches `store` only as a **type-only import of `store/ViewStore`** — the port, never an implementation
- Third-party landing spots are fixed: `@ahoo-wang/fetcher-wow` only at the root entry and in `model`, `filter`, `record`, `analysis`, `runtime` (not `dashboard`, not `store`); `dayjs` in `filter`, `record`, `analysis`, `runtime`, `ui`; `dequal` in `runtime` alone; `culori` in `analysis` alone; everything else (`recharts`, `react-grid-layout`, `react-markdown`, `@base-ui/react`, `lucide-react`, …) is **UI-only**. A new React dependency cannot reach a headless layer without being listed explicitly in the test
- **Deprecated Wow APIs are banned.** The test derives the deprecated export set from the wow sources themselves and fails on any import of it. Use `FilterExpression` and the `Filter*Query` family — never `Condition`, `PagedQuery`, `ListQuery` or `SingleQuery`
- Wow must be imported from its root entry, by name, so every binding can be checked

Package entries:

| Entry                            | Contents                                                                              |
| -------------------------------- | ------------------------------------------------------------------------------------- |
| `@ahoo-wang/fetcher-view-engine` | `model`, the four kernels, `runtime`, the `ViewStore` port, `MemoryViewStore`         |
| `/react`                         | Hooks and headless controllers                                                        |
| `/ui`                            | Default components, views and workbenches                                             |
| `/styles.css`                    | Theme, imported explicitly, customised through `--fve-*` / `--fve-dark-*` on the host |

## Project Structure

```
src/
  index.ts                      — Root entry — model, four kernels, runtime, store port
  styles.ts                     — Build entry that carries styles.css into dist
  styles.css                    — Theme; consumers import it explicitly
  model/                        — Types and constants only; imports nothing
    definition.ts                 — ViewDefinition, FieldDefinition, capabilities
    config.ts                     — ViewConfig — what each view kind stores
    instance.ts                   — ViewInstance: authorship, scope, permissions
    field.ts                      — FieldKindId
    filter.ts                     — FilterOperator as stored in a config
    analysis.ts                   — Wow aggregation enums as stored literals
    chart.ts                      — Chart spec, stored per family
    dashboard.ts                  — Dashboard config, global fields, bindings
    record.ts                     — RecordData — one row from the source
    issue.ts                      — Issue — how every kernel reports a problem
    json.ts                       — JsonValue — configs are plain JSON
    limits.ts                     — RuntimeLimits: admission and scheduling budgets
    storeError.ts                 — ViewStoreError
    index.ts
  filter/                       — Filter kernel — imports model only
    fieldKind.ts                  — FieldKind contract and registry (extension point)
    validate.ts                   — validateFilter, depth and node budgets
    marks.ts                      — unmarkedErrors — the errors no condition pill can carry
    compile.ts                    — compileFilter → Wow FilterExpression
    tree.ts                       — Tree node predicates; trees arrive untrusted
    values.ts                     — Value shapes of the built-in kinds
    time.ts                       — Relative and preset values resolved at compile time
    describe.ts                   — describeFilter — the applied-condition summary
    configBase.ts                 — The config part every view kind shares
    index.ts
    kinds/                        — Built-in FieldKinds
      string.ts, number.ts, boolean.ts, dateTime.ts, enum.ts,
      array.ts, reference.ts, elementMatch.ts, search.ts,
      metadata.ts, presence.ts, index.ts
  record/                       — Record kernel — imports model and filter
    validate.ts                   — validateRecord
    compile.ts                    — compileRecord → FilterPagedQuery or CursorQuery
    project.ts                    — projectRecord — columns, rows, row keys
    defaults.ts                   — defaultRecordConfig
    index.ts
  analysis/                     — Analysis kernel — imports model and filter
    validate.ts                   — validateAnalysis — aliases, limits, capabilities
    validateChart.ts              — Chart rules; groups must all be consumed
    compile.ts                    — compileAnalysis → AggregationQuery
    project.ts                    — projectAnalysis — table columns and rows
    chart.ts                      — Chart-shaped projection for the renderers
    capability.ts                 — Reachable fields once element paths expand
    defaults.ts                   — defaultAnalysisConfig, alias derivation
    index.ts
  dashboard/                    — Dashboard kernel — imports model and filter
    validate.ts                   — validateDashboard — panels, bindings, content
    merge.ts                      — mergeGlobalFilter onto one panel's fields
    panels.ts                     — Panel kinds and their referenced instances
    defaults.ts                   — emptyDashboardConfig
    index.ts
  runtime/                      — Stateful layer; never imports react or ui
    viewEngine.ts                 — ViewEngine — registry, open/create/list, permissions
    viewRuntime.ts                — One open view; subscribe / getSnapshot store
    dashboardRuntime.ts           — Owns one child runtime per data panel
    requestRunner.ts              — Scheduling; a newer request supersedes a key
    execute.ts                    — The two execution kinds a runtime drives
    source.ts                     — resolveSource — three QueryApi methods
    environment.ts                — The only interface between runtime and host
    validateDefinition.ts         — Definition admission; needs all three kernels
    write.ts                      — Write bodies, retry and overwrite replay
    index.ts
  store/                        — Persistence port — imports model only
    ViewStore.ts                  — The only port a backend must satisfy
    MemoryViewStore.ts            — In-memory implementation for examples and tests
    index.ts
  react/                        — Headless hooks and controllers; never imports ui
    useViewEngine.ts              — Creates and disposes one engine
    useViewList.ts                — View summaries in the user's order
    useViewManager.ts             — Rename, delete, reorder, default; outcomes per row
    useRecordTable.ts             — Record controller
    useAnalysisEditor.ts          — Analysis controller
    useFilterEditor.ts            — Filter tree editor controller
    useDashboard.ts               — Dashboard panels, geometry and state
    useSaveCommands.ts            — Save, save-as, revert, rename, delete
    useWorkbench.ts               — One workbench's shell: list, open, leave, the header's outcomes
    actions.ts                    — The three action slots a host fills: global, bulk, row
    environment.ts                — Page visibility, so a hidden tab stops polling
    issues.ts                     — Turns a thrown command into one Issue
    index.ts
    workbench/                    — What `useWorkbench` composes
      leaveGuard.ts                 — Headless leave protection; `/ui` draws `LeaveDialog` from it
      releaseDeleted.ts             — Lets a workbench's pinned id go once the view is deleted
  ui/                           — Default look; may import every layer
    WorkbenchShell.tsx            — The frame the three workbenches share, over one `useWorkbench`
    RecordWorkbench.tsx           — Default Record workbench
    AnalysisWorkbench.tsx         — Default Analysis workbench
    DashboardWorkbench.tsx        — Default Dashboard workbench
    ViewHeader.tsx                — Title bar: kind, audience, title, unsaved mark, save commands
    SaveActions.tsx               — Split save button group; SaveAsDialog.tsx
    WriteOutcome.tsx              — The open view's last write; ConflictConfirm.tsx (mine/theirs)
    OutcomeActions.tsx            — One outcome as a line and its buttons, shared by the two above and the manager
    ViewManager.tsx               — Rename, delete, reorder and the default view, from the sidebar
    ViewManagerRow.tsx            — One managed view: rename in place, arrows, default, delete
    DeleteDialog.tsx              — What a delete costs, said before it happens
    LeaveGuard.tsx                — `LeaveDialog`: draws the headless guard's question
    EditorBand.tsx                — The fold a view's editor lives in
    StatusStrip.tsx               — One-line findings: warning, error, failed query (+ `dedupeIssues`)
    AppliedBar.tsx                — The conditions the rows on screen were fetched under
    ResultToolbar.tsx             — Selection, bulk slot, layout, columns, refresh
    RowActions.tsx, RecordPagination.tsx
    RecordTable.tsx, RecordCards.tsx
    AnalysisTable.tsx, AnalysisChart.tsx, AnalysisEditor.tsx
    DashboardGrid.tsx, DashboardPanels.tsx
    FilterPanel.tsx               — Condition builder root: mode, focus boundary, actions row
    FilterValueEditor.tsx         — The switch over `EditorDescriptor.input`; the only place that knows the union
    filter/                       — What the panel is made of
      GroupBlock.tsx                — One group as a framed block, and its strip of conditions
      ConditionPill.tsx             — One condition; the element-match block; `PendingDot`
      AddEntry.tsx                  — The field picker a group is added to from
      FilterActions.tsx             — Clear and Apply, with the blocked count
      inputs/                       — One file per `EditorDescriptor.input`, plus shared.tsx
    ViewList.tsx, ViewSurface.tsx, EmbeddedView.tsx
    kinds.ts                      — The icon each kind and audience wears, shared by list and header
    describeConfig.ts             — One config in a sentence, for a conflict's side-by-side
    messages.ts, MessagesProvider.tsx   — wording by key, overridable; `MessageKey` is the union
    messages/                     — the catalogue, one file per prefix family
      en.ts                         — the English catalogue: the files below, spread
      zh-CN.ts                      — `zhCN`, the same keys in 简体中文
      save.ts, header.ts, record.ts, filter.ts, config.ts, scope.ts,
      view.ts, manage.ts, analysis.ts, dashboard.ts, status.ts, definition.ts
    display.ts                    — A value as its field shows it: enum labels, dates, bucket keys
    index.ts
    components/                   — 24 shadcn/ui primitives — vendored, see below
    lib/utils.ts                  — shadcn cn() helper — vendored
```

`test/` (46 test files plus `fixtures.ts` and `fixtures/ui.tsx`), `examples/` (`FetcherViewStore.ts`, `PlainRecordWorkbench.tsx`, `quickstart.ts`) and `docs/design/` sit beside `src/`.

### Key Concepts

- **The one value chain**: `ViewDefinition + ViewConfig` —compile→ Wow query —execute→ result —project→ presentation, with save/open closing the loop
- **Definitions are code**: fields, kinds, operators and aggregation capabilities ship with the application. There is no definition service, no definition version, no reload protocol — a definition change is a release
- **Configs are data**: what a user saves is a way of looking, not a snapshot. The only persisted objects are `ViewInstance` and personal preferences; consistency is an optimistic version plus an idempotent `requestId`
- **Runtime state is transient**: drafts, results, paging and selection live only inside one opening. A `ViewRuntime` is a small `subscribe` / `getSnapshot` store and never persists
- **Four pure kernels**: `filter`, `record`, `analysis`, `dashboard` are synchronous pure functions, all shaped "definition + config in, result or `Issue` out". They never read the clock — relative times resolve against an injected `ctx.now`
- **`FieldKind` is the main extension point**: operators, validation, compilation and a data-only editor descriptor. Applications register custom kinds in `FieldKindRegistry`. Note that `EditorDescriptor.input` is a **closed union** and `FilterValueEditor` switches over it, so a custom kind picks one of the existing inputs — there is no renderer registry in `/ui` or `/react` today, and an unrecognised shape falls through to a plain text input. `docs/design/extension.md` describes a per-kind renderer registry as intended, not as built
- **Untrusted configs**: configs arrive from a store, so validation checks the node shape together with the depth and node budgets on one iterative walk before any kind rule runs, and a malformed node is an Issue at its path rather than a `TypeError`
- **A blank leaf is not an error**: a field chosen without a value yet is a normal editing state — it is not validated by kind and does not compile

## Dependencies

- `@ahoo-wang/fetcher-wow` — query protocol (`FilterExpression`, `FilterPagedQuery`, `CursorQuery`, `AggregationQuery`)
- `react` / `react-dom` — **optional peer dependencies**; the root entry works without React
- UI-only: `@base-ui/react`, `recharts`, `react-grid-layout`, `react-markdown`, `react-day-picker`, `lucide-react`, `class-variance-authority`, `cn`
- Headless: `dayjs` (time), `dequal` (runtime equality), `culori` (colour syntax, `analysis` only — a saved chart colour is validated before it reaches a `<style>` element)

## Code Style

- TypeScript strict mode; Apache 2.0 license headers on every source file
- Prettier: single quotes, trailing commas, semicolons, 80 char width
- ESLint runs `react-hooks` with `exhaustive-deps`, `incompatible-library` and `unsupported-syntax` all set to **error**; CI gates on `lint:check` with `--max-warnings 0`
- `src/ui/components/**` and `src/ui/lib/**` are vendored from the shadcn registry — update them with `shadcn add --diff` rather than editing by hand
- Bilingual READMEs (`README.md`, `README.zh-CN.md`); `docs/design/` is in Chinese

## Git Workflow

- Conventional commits: `feat(view-engine):`, `fix(view-engine):`, `refactor(view-engine):`
- Version synced via `pnpm update-version`

## Boundaries

- ✅ Changing, renaming or deleting any export — no compatibility obligation, just fix the callers
- ✅ Adding a `FieldKind`, a chart family, or a `ViewStore` implementation
- ✅ Rewriting a shape the design doc has outgrown, doc updated in the same PR
- ⚠️ Adding a third-party dependency — it is UI-only unless `test/architecture.test.ts` says otherwise
- ⚠️ Changing `ViewStore` — it is the port a business backend implements
- 🚫 Breaking any of the six dependency rules, or reaching `store` from `runtime` as anything but a type-only port import
- 🚫 Importing a deprecated Wow API, or React/DOM anything into `model` through `store`
- 🚫 Editing vendored shadcn files by hand, or lowering the coverage thresholds to make a change fit
