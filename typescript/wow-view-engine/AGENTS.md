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
- Test files live in `test/` at the package root, named by subject rather than mirroring `src/` one-to-one. Fixtures shared by several suites sit beside them: `test/fixtures.ts` for definitions, configs and sources, `test/fixtures/ui.tsx` for what the UI suites open — every `RecordTableController` a suite renders is built from its `recordTableController` / `twoColumnTable` with only what that suite varies passed in, so no suite re-declares the forty members and none reaches for `as unknown as`, `test/fixtures/manager.tsx` for the view-manager harness two suites share, `test/fixtures/workbench.ts` for the gestures the workbench suites share (the filter fold's handle and its field picker, the analysis tray's handle and `openTray`), `test/fixtures/dashboard.ts` for the dashboard the grid and the workbench suites both open, `test/fixtures/writes.ts` for `tracked`/`landed` (a suite waits for the write a gesture caused, then reads the store once — never polls it), `test/fixtures/{analysis,columns,filter,hooks}.ts` for the rest
- `@` resolves to `src/`
- **Coverage thresholds are enforced**: statements 95, branches 91, functions 97, lines 96. `src/ui/components/**`, `src/ui/lib/**` and `src/styles.ts` are excluded — they are vendored from the shadcn registry and are upstream's to test
- **A jsdom suite asserts what a class _means_, not how it is spelled** (A-09). A `className` assertion proves nothing about the screen — jsdom loads no stylesheet and lays nothing out — and it turns red wholesale the moment a colour or a recipe moves into a `cva`. So state is said **on the element** and read back from there: `data-pin` / `data-pin-edge` / `data-pin-index` / `data-sticky` / `data-overflowing` for the table's sticky chrome, `data-tone` for a toned badge, alert or destructive answer, `aria-current`, `aria-pressed`, `data-default`, `data-released`, `data-scrolls`, `data-invalid`, a role, an accessible name, a `title`, or an inline style jsdom really computes. Where a component writes no such attribute and the class is the only witness, **add the attribute** rather than keep the assertion. Three files are the deliberate homes of the remaining class assertions, because in each the class string _is_ the contract: `test/pinnedColumns.test.tsx` ("the sticky chrome recipe") for `ui/record/sticky.ts`, `test/variants.test.tsx` for the cva wrappers of D16-8, and `test/popups.test.tsx` for our copy of each popup's registry markup. Elsewhere a surviving assertion is marked **surviving class assertion** with its reason — a pure declaration with no state behind it (a length, a grid template, a border model, `sr-only`, a `:hover` fill), whose pixels a browser story measures instead
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
- Third-party landing spots are fixed by `HEADLESS_DEPENDENCIES` in `test/architecture.test.ts`, and a dependency the manifest carries but that list does not name is **UI-only**: `@ahoo-wang/fetcher-wow` only at the root entry and in `model`, `filter`, `record`, `analysis`, `runtime` (not `dashboard`, not `store`); `dayjs` in `filter`, `record`, `analysis`, `runtime`, `ui`; `dequal` in `runtime` alone; `culori` in `analysis` alone. UI-only is therefore all the rest — `@base-ui/react`, `@dnd-kit/dom`, `@dnd-kit/react`, `class-variance-authority`, `cn`, `lucide-react`, `react-day-picker`, `react-error-boundary`, `react-grid-layout`, `react-markdown`, `recharts` — while `react` / `react-dom` are optional peers and reach `react` and `ui`. There is no table library: D16-1 declined `@tanstack/react-table`. A new React dependency cannot reach a headless layer without being listed explicitly in the test
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

Every source file is listed; `test/agentsStructure.test.ts` fails when one is added without a line here (vendored `ui/components/` and `ui/lib/` are summarised).

```
src/
  index.ts                    — Root entry — model, four kernels, runtime, store port
  styles.ts                   — Build entry that carries styles.css into dist
  styles.css                  — Theme; consumers import it explicitly
  model/                      — Types and constants only; imports nothing
    analysis.ts               — Wow aggregation enums as stored literals
    chart.ts                  — ChartSpec — one sub-object per chart family, every reference a group or metric alias; `CHART_TYPES`, `CHART_FAMILY`
    config.ts                 — ViewConfig — what each view kind stores, and which of its members only draw the result (`presentationMembers`)
    dashboard.ts              — Dashboard config, global fields, bindings
    definition.ts             — ViewDefinition, FieldDefinition, capabilities
    field.ts                  — FieldKindId; what a cell reads as, and which fields hold one string
    filter.ts                 — FilterOperator as stored in a config; the three group operators and the reading of one
    instance.ts               — ViewInstance: authorship, scope, permissions
    issue.ts                  — Issue — how every kernel reports a problem
    json.ts                   — JsonValue — configs are plain JSON
    limits.ts                 — RuntimeLimits: admission and scheduling budgets
    record.ts                 — RecordData, RecordColumn, and how an untrusted `pinned` / `hidden` reads
    storeError.ts             — ViewStoreError
    index.ts                  — Types and constants only: definitions are code and configs are data, so this layer describes both and depends on nothing else
  filter/                     — Filter kernel — imports model only
    compile.ts                — compileFilter → FilterExpression; `FilterCompileContext` is the moment and zone a relative condition resolves against
    configBase.ts             — The config part every view kind shares
    describe.ts               — describeFilter — the applied-condition summary
    fieldGroups.ts            — Fields as a picker lists them: ungrouped first, then each declared group
    fieldKind.ts              — FieldKind contract and registry (extension point)
    issuePath.ts              — How an issue path reads against a tree: whose tree it is, and which node
    marks.ts                  — unmarkedErrors — the errors no condition pill can carry
    time.ts                   — Relative and preset values resolved at compile time
    tree.ts                   — Tree node predicates, and what a node says as a condition (`conditionOf`, `conditions`, `removeConditionAt`: the negation wrapper is known here and nowhere else); trees arrive untrusted
    validate.ts               — validateFilter — shape, budgets and kind rules over an untrusted tree; `isBlankFilter`, `isExecutableFilter`
    values.ts                 — Value shapes of the built-in kinds
    index.ts                  — The filter kernel: pure functions over a stored tree, plus the `FieldKind` registry that makes field types the one axis an application extends
    kinds/                    — Built-in FieldKinds
      array.ts                — A field holding many values: `CONTAINS_ALL` and `IS_EMPTY` beside `IN`, closed by `options` or searched through `remote`
      boolean.ts
      dateTime.ts             — The one instant a single-bound operator compares against, as a phrase; `readInstant`, the kind's own reading of a value, shared by the cells and the record kernel
      deletion.ts             — The soft-delete dimension as a declared field kind (D17-2); `impliedDeletion`
      elementMatch.ts         — The fields of one element, named as a condition names them
      enum.ts                 — A closed set of values declared by the definition
      metadata.ts             — The kinds backed by Wow's metadata filters
      number.ts
      options.ts              — What `enum` and `array` share
      presence.ts             — Operators that ask about presence rather than about a value
      reference.ts            — Points at rows of another dataset
      search.ts               — Full-text search: the box at the top of a list page
      string.ts
      index.ts
  record/                     — Record kernel — imports model and filter
    compile.ts                — compileRecord → FilterPagedQuery / CursorQuery and compileSummaries → AggregationQuery; `FIRST_PAGE`, `summaryAlias`
    defaults.ts               — defaultRecordConfig — a complete starting config, since `create` takes one rather than inventing it; `recordCapabilityOf`
    export.ts                 — `serializeCsv`: rows as a CSV, values read the UI's way
    project.ts                — projectRecord — the columns a table draws, the edge each is held against (`ColumnEdge`), the rows
    validate.ts               — validateRecord — columns, sort, card and summaries against the definition
    index.ts                  — The record kernel: a definition and a config in, a Wow query or a rendered view out
  analysis/                   — Analysis kernel — imports model and filter
    budget.ts                 — Depth and node budgets of a walked tree
    capability.ts             — The three scopes the element chain makes: the root's, each element's, the innermost one's; and the renaming a scope implies
    chart.ts                  — Chart-shaped projection for the renderers
    chartSlots.ts             — `fitChartSlots`: the family sub-object of the current chart type, filled from the groups and metrics in force
    compile.ts                — compileAnalysis → AggregationQuery
    fitCharts.ts              — Which chart types can draw a result of this shape and which it reads best as (K3, Q6): the capability says which exist, this says which are greyed and why
    formula.ts                — Formulas and derived metrics (D20 屏 B): their first shapes, `expressionText`／`derivedText` as the author would say them, `isFormula`
    chartOptions.ts           — The rules behind the visualization panel's second level: which pages a type has, a slot swap, one-choice stacking and smoothing, and a funnel's stage order from the rows (D20 屏 J)
    drill.ts                  — One result row back into the conditions that select its records: `bucketRange` (the inverse of date bucketing, K1), `drillConditions`, and the two follow-ups that stay in the view, `focusOn` and `splitBy`; hands out conditions and config patches only (K6)
    expand.ts                 — The config re-scoped to an expansion chain (D20 屏 G): `withElements` keeps what still names the new unit's fields and starts the metrics again otherwise; `expanded`, `collapsed`, `nextExpansion`
    granularity.ts            — The granularity a new time dimension starts at (K4): `recommendDateUnit` from the applied range's span (`rangeSpan`) or the result's buckets (`resultSpan`)
    having.ts                 — 「只保留」 as rows of one comparison each: `havingRows` reads a conjunction, `withHavingRows` writes it, any other shape is declined rather than flattened
    defaults.ts               — defaultAnalysisConfig — the first metric the capability can express; `aliasOf`, `termsGroup`, `DEFAULT_MISSING_KEY`
    expressions.ts            — Aggregate and derived expression walks
    metricFormat.ts           — `metricFormat`/`metricFunctionOf`: how an aggregate's number prints, which is not how its field's values print
    project.ts                — projectAnalysis — table columns and rows
    queryFilter.ts            — A filter in metric or element position, and the scope it may name
    validate.ts               — validateAnalysis — the one entry every per-rule file below is read through
    validateAliases.ts        — Alias syntax, the reserved prefix, duplicates
    validateChart.ts          — Chart rules; groups must all be consumed
    validateElements.ts       — The expansion chain, walked level by level, and each gate filter
    validateGroups.ts         — Group kinds, date units, dense, missing-value keys
    validateHaving.ts         — Having: declared, grouped, over known metrics
    validateLimits.ts         — Declared limits under Wow's own ceilings
    validateMetrics.ts        — One rule set per metric type, filters included
    validateShape.ts          — The skeleton every other rule reads through
    validateSort.ts           — Sort and table columns, over known aliases
    index.ts                  — The analysis kernel
  dashboard/                  — Dashboard kernel — imports model and filter
    defaults.ts               — emptyDashboardConfig
    merge.ts                  — mergeGlobalFilter onto one panel's fields
    panels.ts                 — Which panel a stored one is: `isViewPanel`, `isContentPanel`, and `isSafeContentUrl` for what a content panel points at
    validate.ts               — validateDashboard — panels, bindings, content
    index.ts                  — The dashboard kernel: admission, panel binding resolution and the global filter merge
  runtime/                    — Stateful layer; never imports react or ui
    dashboardRuntime.ts       — `DashboardViewRuntime`: N child runtimes and one global filter, on one clock, over one `RuntimeStore`
    definitions.ts            — The definition registry: judged once, refused at the point of use
    environment.ts            — `RuntimeEnvironment` and the `VisibilitySource` port; `ALWAYS_VISIBLE`, `defaultRuntimeEnvironment`
    execute.ts                — The two execution kinds a runtime drives
    exportRows.ts             — Fetching every row the conditions match, page by page, under `exportMax`
    issues.ts                 — `toIssue`: one Issue for whatever a command threw
    listeners.ts              — `listenerSet`: the subscribe / notify half every store in this package has
    openRuntimes.ts           — The views one engine has open, and who holds an instance
    pending.ts                — `comparePending`: what the draft says that the applied config does not (D17-6), presentation members excepted
    permissions.ts            — What a command is allowed to do; `instanceAbilities`, the one reading of "a system view is read-only", which the manager's buttons ask as well
    preferences.ts            — The preference cache, the list order and the default view
    refreshTimer.ts           — The one auto-refresh timer both runtimes arm; `refreshIntervalOf`, `refreshDelayOf`
    requestRunner.ts          — Scheduling; a newer request supersedes a key
    runtimeFactory.ts         — How one runtime is assembled, `open` and `create` alike
    runtimeStore.ts           — The store both runtimes are made of: the state, its listeners, the refresh timer's bookkeeping, dirty-against-saved; `hasError`
    scope.ts                  — What an injected scope does to admission: the merge, and what it alone is refused for
    source.ts                 — resolveSource — three QueryApi methods
    summaries.ts              — The instance-summary cache: noted on listing and on a confirmed write, dropped on delete, read before the store
    validateDefinition.ts     — Definition admission; needs all three kernels
    viewChanges.ts            — The change notifications a list of views subscribes to (D15)
    viewEngine.ts             — ViewEngine — the command surface: admission, then one dispatch
    viewRuntime.ts            — One open view over one `RuntimeStore`: the `ViewRuntime` contract, admission and execution; `hasResult`, the one reading of "a result ever arrived"
    write.ts                  — Write bodies, retry and overwrite replay
    writeLedger.ts            — The write ledger: outcomes by requestId, retry, conflicts
    index.ts                  — Transient state: what is open, what is in flight, what came back
    dashboard/                — What the dashboard runtime is made of
      children.ts             — PanelChildren: one child runtime per data panel
      panels.ts               — Panel helpers: reading, addressing, comparing
      references.ts           — PanelReferences: loading what panels point at
  store/                      — Persistence port — imports model only
    MemoryViewStore.ts        — In-memory implementation for examples and tests
    ViewStore.ts              — The only port a backend must satisfy
    index.ts                  — Persistence is one port with eight methods
  react/                      — Headless hooks and controllers; never imports ui
    actions.ts                — The three action slots a host fills: global, bulk, row
    environment.ts            — `documentVisibility` / `browserRuntimeEnvironment`: page visibility, so a hidden tab stops polling
    issues.ts                 — Turns a thrown command into one Issue
    recordColumns.ts          — `recordColumn`, the one builder of a stored column, and what the column commands write with it: `repinned`, `resized`, `shown`, `withColumnsShown`, `reordered`
    recordEdits.ts            — What a record command works out before it writes: the sort cycle, the page-size ladder, the spelling of "no summaries", and the repairs a patch carries
    recordDraft.ts            — The draft's lists as a control may read them; the controller is the boundary
    useAnalysisEditor.ts      — Analysis controller
    analysisEditing.ts        — The edits to the question as plain functions over the draft (`questionEditing`): dimensions, metrics, conditions, copies, formulas, derived metrics, having
    useAutoRefresh.ts         — `RefreshController`: refresh now, the cadence ladder cut to the limits, the countdown
    useBulkCommand.ts         — One business command over the selection: pending, outcome, dismiss
    useDashboard.ts           — Dashboard panels, geometry and state
    useFilterEditor.ts        — Filter tree editor controller
    useRecordExport.ts        — The export run: scope, progress, the ceiling, delivery
    useRecordTable.ts         — Record controller
    useSaveCommands.ts        — Save, save-as, revert, rename, delete
    useViewEngine.ts          — Creates and disposes one engine
    useViewList.ts            — View summaries in the user's order
    useViewManager.ts         — Rename, delete, reorder, default; outcomes per row
    useWorkbench.ts           — One workbench's shell: list, open, leave, the header's outcomes
    writes.ts                 — One write-outcome vocabulary, shared by the save commands and the manager
    index.ts                  — The `/react` entry: hooks and headless controllers over the runtime
    manager/                  — What `useViewManager` composes
      abilities.ts            — Which manager buttons exist, from `instanceAbilities` and the rows on screen
      order.ts                — Where a row goes when it moves, within its audience group
      outcomes.ts             — One outcome per row, and what a row's slot accepts
      queue.ts                — One write at a time, in click order, per set of inputs
      useCommandRunner.ts     — The protocol every manager command runs under
    workbench/                — What `useWorkbench` composes: instance sync, the leave guard, a new view, a deleted one let go
      instanceSync.ts         — Keeping the host's `instanceId` and the workbench's open view in agreement
      leaveGuard.ts           — Headless leave protection; `/ui` draws `LeaveDialog` from it
      newView.ts              — What `create` makes: the kind's default config and the audience it goes to
      releaseDeleted.ts       — Lets a workbench's pinned id go once the view is deleted
  ui/                         — Default look; may import every layer
    AnalysisChart.tsx         — Dispatches by chart family; nothing else
    AnalysisTable.tsx         — The aggregation as a table: groups first, then metrics, with the totals row from its own ungrouped query rather than from summing what is on screen
    Announcer.tsx             — `useAnnouncer`: one live region per surface, handed back rather than rendered by the caller
    AppliedBar.tsx            — The conditions the rows on screen were fetched under
    BulkOutcome.tsx           — `BulkOutcomeStrip`: what a host's bulk command did to the records it was handed
    CardSettings.tsx          — The card layout's settings behind the column settings' button (D18 VI)
    ColumnSettings.tsx        — Which columns show, in which order, pinned or not, summarised how — one sortable group per area (D19)
    ConflictConfirm.tsx       — The same choice, put once more with both configs on the table
    CopyButton.tsx            — A value's own copy button: the clipboard, the tick, and the two words a press comes back with
    DashboardArrange.tsx      — Placing a panel without a pointer: the two named handles, and the menu
    DashboardGrid.tsx         — The panels, placed
    DashboardPanels.tsx       — The static panels: a note, a picture, a list of links
    DashboardWorkbench.tsx    — Default Dashboard workbench
    DataWorkbench.tsx         — The data workbench: one list of record and analysis views; `useWorkbench` + both parts + `WorkbenchShell`, joined (D18-1, D20)
    DeleteDialog.tsx          — What a delete costs, said before it happens
    DragHandle.tsx            — The handle a sortable row is carried by, and the arrow keys that move it; the three lists share it
    EditorBand.tsx            — The fold a view's editor lives in
    EmbeddedView.tsx          — An outer condition ANDed onto the view's own, in the view's field names
    ExportDialog.tsx          — The export window: scope, name, progress and outcome in one journey (D14)
    FieldMenu.tsx             — A picker's entries by catalogue group; shared by the field pickers
    FilterPanel.tsx           — Condition builder root: mode, focus boundary, actions row
    FilterValueEditor.tsx     — The switch over `EditorDescriptor.input`; the only place that knows the union
    IconButton.tsx            — An icon-only control and the tooltip saying its name; the one place the two are paired
    LeaveGuard.tsx            — `LeaveDialog`: draws the headless guard's question
    MessagesProvider.tsx      — `MessagesProvider`: the wording every default component reads, each provider merging over the one above it
    OutcomeActions.tsx        — One outcome as a line and its buttons, shared by the two above and the manager
    PendingDot.tsx            — The "changed, not applied" dot pinned to a pill or a group
    RecordCards.tsx           — The same result as cards, drawn from the card half of the saved config (D18 V); a value reads as a card reads it, a note on its own three lines
    RecordPagination.tsx      — How many rows there are and how to reach the next of them
    RecordTable.tsx           — The record view as a table: the columns and rows of the result that ran, never of the draft; its rows are `TableDataRow`s and a value reads as a table reads it, one line each
    RefreshControl.tsx        — Refresh now, and the auto-refresh cadence menu, as one split button
    RenderBoundary.tsx        — The boundary each part of a view renders behind, so one failing leaves the rest standing
    ResultToolbar.tsx         — Selection, bulk slot, layout, columns, refresh
    RowActions.tsx            — The wrapper a host's per-row actions land in
    RowItem.tsx               — One row of a list over the registry's `Item`; the five lists share it
    SaveActions.tsx           — The split save button group: save in place, and the menu of the other ways to save
    SaveAsDialog.tsx          — What the create is: a copy of a saved view, or the first save of one made from nothing
    SortSettings.tsx          — The sort editor: entries in priority order, direction, drag to reorder
    StatusStrip.tsx           — One-line findings: warning, error, failed query (+ `dedupeIssues`)
    ViewExpansion.tsx         — Filling the screen: `useViewExpansion`, `ViewExpandToggle`, the document's scroll lock
    ViewHeader.tsx            — Title bar: kind, audience, title, unsaved mark, save commands
    ViewList.tsx              — What this list is a list of — the definition's own title
    ViewManager.tsx           — Rename, delete, reorder and the default view, from the sidebar
    ViewManagerRow.tsx        — One managed view: drag handle, rename in place, default, delete
    ViewSurface.tsx           — The boundary every view renders inside: the theme, the wording, the locale and the zone a time reads on
    ViewSwitcher.tsx          — The view list as one control, for when the sidebar is folded away
    WorkbenchShell.tsx        — The frame the workbenches share, over one `useWorkbench`; draws the refresh, the query strip and the warnings itself
    WriteOutcome.tsx          — The open view's last write, and the three ways out of a conflict
    alerts.tsx                — `LineAlert`: one callout one line high, tone deciding colour, icon and role
    describeConfig.ts         — One config in a sentence, for a conflict's side-by-side
    display.ts                — A value as its field shows it: enum labels, dates, bucket keys; `summaryFunctionKey` names a summary in its column's vocabulary, `columnTitle` composes an analysis header from its two parts
    download.ts               — Hands a file to the browser; the whole of the DOM the export needs, and the name it is handed under
    dragAnnounce.ts           — What a screen reader hears while a row is dragged; the column settings and the manager share it
    dragDrop.ts               — `dropped()`: what makes a finished drag a drop at all, before any list adds its own rule
    features.ts               — `WorkbenchFeatures`: which of the workbench's own controls exist (D18 XI)
    kinds.ts                  — The icon each kind and audience wears, shared by list and header
    layout.ts                 — `TEXT_UI`, `SPACE`: the one small type size and the spacing ruler
    messages.ts               — Wording, by key
    popups.tsx                — The popups this package renders, themed and on a layer of their own
    summary.ts                — The applied-conditions bar in words: one `FilterSummaryItem` as a sentence
    toolbar.tsx               — Base UI's toolbar primitive: one tab stop with the arrow keys inside
    variants.tsx              — The colours, edges and shapes a vendored component does not ship, in one place (D16-8); `TableDataRow` holds a record row's three states
    index.ts                  — The `/ui` entry: the default look, built on shadcn/ui with Base UI primitives
    analysis/                 — What the analysis view is made of
      AnalysisToolbar.tsx     — The result's first row: the reading (dimensions · metrics) and how the result is looked at — table or chart, chart type, totals row
      ChartPicker.tsx         — The visualization panel's first level: the chart types as tiles in the sidebar column, greyed with a reason, the recommended one marked, the table among them (D20 屏 I)
      ChartOptions.tsx        — The visualization panel's second level: the chosen type's options on the data, display and axes pages (D20 屏 J)
      DataTab.tsx             — The options' data page: each family's slots, position slots listing dimensions and measure slots listing metrics; a funnel's stages reordered by hand, and a metric-staged one named by hand
      DisplayTab.tsx          — The options' display page: legend and value labels, then each family's own settings; the table's totals row
      AxesTab.tsx             — The options' axes page, cartesian only: title, bounds and number format of each numeric axis
      optionControls.tsx      — The few controls the options pages are built of: slot select, check, choice, number, text and name fields, and a titled section
      CompactSelect.tsx       — The one select a tray card carries: a named choice among a few words
      CardMenu.tsx            — A tray card's name and the way to rename it (`CardName`), and the card's own menu (`CardMenu`): display name, the sentinel bucket, filling empty periods
      DimensionCard.tsx       — The dimensions slot and its cards: field, and the control its type asks for (granularity, band width)
      ElementsSlot.tsx        — The expansion slot (D20 屏 G): the chain of arrays counted inside, one card a level with its own gate, 「再展开」 along the declared chain, and the counting unit
      FormulaCard.tsx         — The controls of a formula metric and of a derived metric (D20 屏 B): two operands picked or typed, the operation between, the summary for a formula
      HavingRows.tsx          — 「只保留」: the groups kept, as rows of one comparison each under the metrics; a stored having of another shape is shown and clearable
      DrillMenu.tsx           — The follow-up menu on one group of a result: the records behind it, split by another dimension, only this group (D20 追问); anchored to the mark or row pressed
      EmptyResult.tsx         — An aggregation that matched no group, one sentence for both layouts
      MetricCard.tsx          — The metrics slot and its cards: field and summary (the six ways Wow measures a field as one list), a percentile's number, the record count
      MetricCondition.tsx     — Conditions edited in place under a tray card (D20 屏 H): the funnel, the block of the range's own pills, the 「只算 …」 line at rest; a metric's and an expansion level's
      SeriesList.tsx          — The data page's 系列 slot: one row per metric drawn, carried by the shared handle into the order the analyst wants (`cartesian.series`, and nothing else about a series)
      RangeSlot.tsx           — The tray's first slot: the condition panel under a heading that holds the tree's simple/advanced switch
      SortRow.tsx             — The bottom of the metrics slot: what the first N groups are the first N of
      Tray.tsx                — The analysis view's editor: range → dimensions | metrics, one Apply for the whole draft (D20)
      editing.ts              — What the tray builds when a field is picked: default dimension and metric, the summary choice a card shows and the metric it swaps in
    charts/                   — One file per family, plus what they share
      Cartesian.tsx           — Which axis carries the numbers
      ChartReading.tsx        — The chart's numbers as a table, for whoever cannot see the marks
      Funnel.tsx
      Heatmap.tsx             — A grid rather than a chart library: a heatmap is cells with a background, and every library's version of that costs more than it saves
      MetricCard.tsx          — The comparison, signed
      PieSlices.tsx
      ScatterPoints.tsx
      TooltipValue.tsx        — One measured value inside a tooltip, read as its column reads it
      asImage.ts              — What every chart family spreads onto its drawing: a named image
      axis.ts                 — Value format, axis domain and ticks
      family.ts               — `FamilyProps`, the value labeller and the column titler
      legend.ts               — Where a legend goes as the chart library takes it, from the spec's `legend` and the family's own default
      palette.ts              — Slot colours and the spec's overrides
      reading.ts              — A chart as text: its name and the numbers it draws
    columns/
      ColumnRow.tsx           — One row of the column settings: checkbox, two-state pin toggle, summary, handle
      drag.ts                 — What the settings make of a drag: `columnDrop` refuses one across the areas, plus what a reader hears
      rows.ts                 — The column settings' model: rows, the two areas (D19), order
      sections.ts             — Rows of one area by catalogue group; the search over them
    components/               — 33 shadcn/ui primitives — vendored, see below
    filter/                   — What the panel is made of
      AddEntry.tsx            — The field picker a group is added to from
      ConditionPill.tsx       — One condition; the element-match block; `PendingDot`
      FieldChecklist.tsx      — The field picker: a grid of checkboxes by catalogue group, with a search (user ruling 2026-09-21)
      FilterActions.tsx       — Clear and Apply, with the blocked count
      FilterModes.tsx         — Simple / advanced, and whether the other mode is reachable
      GroupBlock.tsx          — One group as a framed block, and its strip of conditions
      enter.ts                — `isPlainEnter`: whether a press of Enter is the editor's to act on
      groupOperators.ts       — The group operators' wording, shared by select and menu
      inputs/                 — One file per `EditorDescriptor.input`, plus shared.tsx
        calendar.tsx          — The registry's calendar, speaking the surface's language
        chips.tsx             — A list of typed values as chips (IN / NOT_IN)
        date.tsx              — A moment: off a calendar, a window from now, or a named period
        daterange.tsx         — A day or a span of days, with the time of day where the field carries one
        number.tsx            — A number, a range of two; Base UI `NumberField`
        relative.tsx          — Which side of now a relative window lies on
        candidates.ts         — `useCandidates`: a reference field's candidates searched from its `OptionSource` — debounced, aborted on change, paged, with a retry
        remote.tsx            — A value the host looks up: chips over a searchable `Combobox` (F-04), the host's whole list, or typed ids without either
        select.tsx            — One of a closed set, several of one, or yes/no/either
        shared.tsx            — Whether the condition holding this value is refused
        text.tsx              — Free text
        unsupported.tsx       — A value no control can hold: the stored value and why it is read-only (F-06)
    lib/utils.ts              — shadcn cn() helper — vendored
    manage/
      drag.ts                 — What the manager makes of a drag: which drop it will take, and what a screen reader hears while one is under way
    messages/                 — the catalogue, one file per prefix family
      analysis.ts             — the analysis editor and its charts, with the two kernels behind them
      bulk.ts                 — bulk outcome wording
      config.ts               — shared config — the part every view kind stores, so every kind reports it
      dashboard.ts            — the dashboard grid, its panels, and the dashboard kernel behind them
      definition.ts           — definition admission, worded for whoever wrote the release
      en.ts                   — the English catalogue: the files below, spread
      export.ts               — the export window
      filter.ts               — the condition builder, the applied-condition bar, and the filter kernel
      header.ts               — The title bar and the collapsible editor under it
      manage.ts               — The view manager
      record.ts               — the record view: its layouts, toolbar, paging, rows and summaries
      refresh.ts              — auto refresh
      render.ts               — render boundaries
      save.ts                 — The save commands, their dialogs, and what a write settled as
      scope.ts                — who a view is for: the audience picker's words and the list's two headings
      status.ts               — What the view says about itself: what failed, what is still on screen, and whether something is running right now
      view.ts                 — a view itself: which kind it is, the list it sits in, and the commands
      workbench.ts            — the frame around a view
      zh-CN.ts                — `zhCN`, the same keys in 简体中文
    record/                   — What the record view is made of
      CardSummaries.tsx       — The summary lines under the cards, both scopes (D18 V)
      ColumnResizer.tsx       — The handle a column is dragged wider by, and its keyboard
      EmptyResult.tsx         — A query that matched nothing, with one way out
      Filler.tsx              — The aria-hidden last cell that lets rows fill the frame while columns keep their width (P-11)
      SkeletonCards.tsx       — The cards of a first query still on its way
      SkeletonRows.tsx        — The rows of a first query still on its way, one bar per column
      SortableHeader.tsx      — One column header: the sort button, its place in the sort, the resizer
      SummaryRows.tsx         — The table footer: one row per summary scope; `SummaryValue`
      cells.tsx               — `cellValue`/`cellText`: one value as its field reads it, for table, cards and CSV; `CellSurface` decides how many lines it may take, and nothing else
      columns.ts              — Which columns the table holds and what its cells wear otherwise: `tablePins`, `pinnedSlots`, `heldColumns`, `usePinnedOffsets` (the left offset chain, keyed on the pins), `HEAD_CELL`, `TABLE_CELLS`, `ACTION_CELL`; `ACTIONS_COLUMN` is the one column ever held on the right (D19)
      headerRoving.ts         — `useRovingHeader`: one Tab stop per header row, arrows between columns, Alt+arrows resize (P-02)
      fitViewport.ts          — `useViewportFit`: the scroll port ends where the viewport does, so the summaries and the pagination row stay in view (P-22)
      overflow.ts             — `useOverflowing`: whether the table is wider than its port, said as `data-overflowing`; the held columns' edges answer to it (P-23)
      pinCap.ts               — The pin cap (D17-4): which pins to let go on a narrow port; `ReleasedPins`; its observer is keyed on the slots, not rebuilt every render
      queryAnnouncement.ts    — What a query says about itself to a screen reader
      sticky.ts               — The one home of the table's sticky chrome (A-09): `stickyCell`/`stickyHead` (the held cell's recipe plus `data-pin`/`data-pin-edge`/`data-pin-index`), `stickyBand` and `BAND`/`BAND_ROW` (the two bands, `data-sticky`), `OWN_LAYER`, `pinVar`; `LeftPin` carries the offset chain and `RightPin` is the one column against the edge itself (A9, D19); the boundary's edge is drawn through `in-data-[overflowing]:`, so it answers to the port's word from `overflow.ts` (P-23)
      useSummaries.ts         — The two summary scopes from the one the runtime executed; table and cards share it
    sort/
      drag.ts                 — What the sort editor makes of a drag: which entry a drop moves where, the order that comes out of it, and what a screen reader hears meanwhile
    workbench/                — The shell's private parts, and the parts each kind of view puts into it
      AnalysisParts.tsx       — What makes an analysis view an analysis view: its editor and its table or chart, handed to the shell as slots
      NewView.tsx             — The "new view" command drawn as a press or as a menu of the kinds, in the sidebar, the empty work area and the switcher (D20 Ⅱ)
      NoViews.tsx             — The work area when the definition has no view of these kinds yet
      OpeningSkeleton.tsx     — The shape of the page that is opening: title-bar and result-block skeletons, one status sentence (P-13)
      OriginBar.tsx           — The "from" line under the title bar of a drilled view: the way back, the origin's name and the conditions the drill added (D20)
      RecordParts.tsx         — What makes a record view a record view: the condition band, the toolbar, the rows and the paging, handed to the shell as slots
      ResultBlock.tsx         — The result and its caption on the one bordered frame (D12)
      parts.ts                — `WorkbenchParts`, the slice of the shell's slots a kind fills, and the render prop it fills them through
      Unopenable.tsx          — The work area when the chosen view cannot be opened
      useEditorFold.ts        — The editor's fold, per opening; `filled`
      useSidebarFold.ts       — The sidebar's fold, following the surface's width until the user presses
      useWorkbenchFolds.ts    — The shell's two folds as one hook: the list beside the view, the view filling the screen, and where a press sends focus
```

`test/` (one file per subject plus `fixtures.ts` and `fixtures/`), `examples/` (`FetcherViewStore.ts`, `PlainRecordWorkbench.tsx`, `quickstart.ts`) and `docs/design/` sit beside `src/`.

### Key Concepts

- **The one value chain**: `ViewDefinition + ViewConfig` —compile→ Wow query —execute→ result —project→ presentation, with save/open closing the loop
- **Definitions are code**: fields, kinds, operators and aggregation capabilities ship with the application. There is no definition service, no definition version, no reload protocol — a definition change is a release
- **Configs are data**: what a user saves is a way of looking, not a snapshot. The only persisted objects are `ViewInstance` and personal preferences; consistency is an optimistic version plus an idempotent `requestId`
- **Runtime state is transient**: drafts, results, paging and selection live only inside one opening. A `ViewRuntime` is a small `subscribe` / `getSnapshot` store and never persists
- **Four pure kernels**: `filter`, `record`, `analysis`, `dashboard` are synchronous pure functions, all shaped "definition + config in, result or `Issue` out". They never read the clock — relative times resolve against an injected `ctx.now`
- **`FieldKind` is the main extension point**: operators, validation, compilation and a data-only editor descriptor. Applications register custom kinds in `FieldKindRegistry`. Note that `EditorDescriptor.input` is a **closed union** — its members are listed as values in `EDITOR_INPUTS` (`filter/fieldKind.ts`) and `FilterValueEditor` switches over them — so a custom kind picks one of the existing inputs; there is no renderer registry in `/ui` or `/react`, and `docs/design/ui/README.md` records, under "FilterPanel 的布局", where one would be cut in. An unrecognised value shape is **refused rather than guessed at** (F-06): `validateFilter` reports `filter.kind.unregistered` for a kind the registry does not hold and `filter.kind.unknown-editor` for one asking an input the engine has no control for, so Apply is blocked; the switch's `default:` draws `ui/filter/inputs/unsupported.tsx` — the stored value, read-only, with the reason — and the field picker leaves such a field out of its list altogether
- **Untrusted configs**: configs arrive from a store, so validation checks the node shape together with the depth and node budgets on one iterative walk before any kind rule runs, and a malformed node is an Issue at its path rather than a `TypeError`
- **A blank leaf is not an error**: a field chosen without a value yet is a normal editing state — it is not validated by kind and does not compile

## Dependencies

- `@ahoo-wang/fetcher-wow` — query protocol (`FilterExpression`, `FilterPagedQuery`, `CursorQuery`, `AggregationQuery`)
- `react` / `react-dom` — **optional peer dependencies**; the root entry works without React
- UI-only: `@base-ui/react`, `@dnd-kit/dom`, `@dnd-kit/react`, `recharts`, `react-grid-layout`, `react-markdown`, `react-day-picker`, `react-error-boundary`, `lucide-react`, `class-variance-authority`, `cn`
- Headless: `dayjs` (time), `dequal` (runtime equality), `culori` (colour syntax, `analysis` only — a saved chart colour is validated before it reaches a `<style>` element)

## Code Style

- TypeScript strict mode; Apache 2.0 license headers on every source file
- Prettier: single quotes, trailing commas, semicolons, 80 char width
- ESLint runs `react-hooks` with `exhaustive-deps`, `incompatible-library` and `unsupported-syntax` all set to **error**; CI gates on `lint:check` with `--max-warnings 0`
- `max-lines` is a tripwire, counting code only (`skipBlankLines`, `skipComments`): **500** for `src/**` (vendored `ui/components` / `ui/lib` and the `ui/messages/` catalogue are out of scope) and **1200** for `test/**` — a file that exceeds it is either split or given a per-file override in `eslint.config.js`, whose ceiling is **the measured code lines × 1.1, rounded up to a multiple of ten**, so a fix may add a few lines but the file cannot grow back meaningfully; **an override requires a matching entry in `docs/design/todo.md`** saying how it comes back under the line, and a round of splitting re-measures and re-tightens the remaining ceilings
- **Any work under `src/ui/**` starts with the shadcn skill — `Skill(shadcn)` (or `/shadcn`).** That is this repository's copy at `.claude/skills/shadcn` (from `npx skills add shadcn/ui -a claude-code --copy`). **Never install the skill globally as well**: Claude Code resolves a same-named skill to `~/.claude/skills/shadcn` first, the repository copy is then never loaded, and the global preamble runs `shadcn info` from the repository root and fails on a monorepo. The repository copy's preamble passes `-c "$(git rev-parse --show-toplevel)/packages/view-engine"`, which is the one workspace with a `components.json`. Read its `SKILL.md` and `rules/*.md` (styling, composition, forms, icons) before writing or reviewing UI; when running any other CLI command from it, pass the same `-c` (or `cd packages/view-engine` first). Its rules are this package's rules: semantic tokens only; `className` for layout, never a component's colours or typography; existing components before custom markup (`Badge`, `Separator`, `Empty`, `Alert`, `Skeleton`, `Field`/`FieldGroup` for forms, `ToggleGroup` for option sets); icons inside components carry `data-icon` and no size classes; Base UI triggers use `render`; add a missing component with `npx shadcn@latest add <name> -c packages/view-engine`, never by hand. Where a rule is knowingly set aside — a vendored colour overridden to fix a defect, a component declined for a reason — say so at the call site and in `docs/design/ui/`. A subagent brief for UI work repeats this paragraph.
- `src/ui/components/**` and `src/ui/lib/**` are vendored from the shadcn registry — update them with `shadcn add --diff` rather than editing by hand; `src/ui/popups.tsx` holds a copy of each popup's Portal/positioner/popup markup, because the theme and the stacking level have to reach elements the vendored wrapper does not expose, so a registry update carries over to it as well — `test/popups.test.tsx` renders both and compares what comes out
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
