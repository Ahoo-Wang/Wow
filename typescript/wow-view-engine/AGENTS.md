# AGENTS.md — @ahoo-wang/wow-view-engine

<!-- This file provides coding agents with context about this package. -->

Configurable data view engine for Wow-based applications: definitions in code, view configs as data, rendered as records, analyses and dashboards.

## Status — active development, no compatibility obligation

This package is being rebuilt from an empty tree against `docs/design/`, and it **does not owe anyone backward compatibility**. Treat every export as changeable.

- **Change the shape; do not add a compatibility layer.** No shims, no `*V2` names, no aliases re-exported "just in case", no `@deprecated` markers, no migration guides. The wow package carries a deprecated Condition API for external reasons — this package must never grow one.
  - **One exception, for stored data rather than API**: dashboard configs saved in older forms are migrated on read, because they are users' saved work, not code anyone can update — approved by the user on 2026-09-23: the 12-column grid onto the 24-column one (D22; marked by `columns: 24`), and a board condition written before batch C into its filters' defaults where a filter could hold it, the rest into the board's fixed scope (D23 Q16, D26 Q31; marked by the `fixed` member being there at all, so an author's later change to the filters never migrates again), and the board's own `filter` and `filterMode`, which a board no longer has, taken out of the config (D27; marked by those members being there at all — a condition one still holds is ANDed into `fixed`, never dropped). The steps live in `migrateDashboardConfig` in the kernel and run at one read boundary, `readStored` in `runtime/storedViews.ts`, which every view out of the store passes; nothing past it migrates, and a board declared in code is code. Keep such migrations there, marked by the config itself, never by guessing.
- **Renaming, narrowing or deleting an export is a normal change.** Update the callers in this repo and move on. The only consumers that matter are in this monorepo.
- **When a design gets clearer, rewrite the old shape out of existence** rather than layering onto it. A clean architecture outranks a stable surface here.
- **`docs/design/` is the source of truth** for the model, boundaries and contracts, and it is written before the code. It is one page per layer — start at `docs/design/README.md`, which indexes the rest. When behaviour it describes changes, change the page in the same PR. Where this file and the design doc disagree, the design doc wins.
- **Before changing behaviour, check `docs/design/todo.md`** for an entry that already covers it, and continue that one rather than opening a second front. Entries are deleted when done — never ticked. Product questions still undecided live in `docs/design/decisions.md`, not there.
- **A rule worth having is a test, not a paragraph** (`docs/design/README.md`). Do not add an invariants index; add the test.

## Build & Run Commands

`@ahoo-wang/wow-client` resolves to its `dist/`, so the workspace dependencies must be built first — otherwise vitest fails with `Failed to resolve import "@ahoo-wang/wow-client"`, and even this package's own `build` cannot stand alone, because its `test:package` step imports the built entry. Use the **trailing `...`** filter (`pnpm --filter @ahoo-wang/wow-view-engine... build`); it builds `wow-client` before this package. `pnpm build:typescript` from the repo root does the same for every package. The fetcher packages come from npm and need no build.

```bash
# Build, workspace dependencies included (also verifies the built package)
pnpm --filter @ahoo-wang/wow-view-engine... build

# Run tests (coverage + three tsc projects)
pnpm --filter @ahoo-wang/wow-view-engine test

# Faster loop, no coverage
pnpm --filter @ahoo-wang/wow-view-engine test:no-coverage

# Run a single test file (`exec` — there is no `vitest` script)
pnpm --filter @ahoo-wang/wow-view-engine exec vitest run test/filter.test.ts

# Type-check only: tsconfig.json, tsconfig.headless.json, tsconfig.test.json
pnpm --filter @ahoo-wang/wow-view-engine test:type

# Verify the built artifact (needs a build first)
pnpm --filter @ahoo-wang/wow-view-engine test:package

# Lint (--fix) and the CI gate (--max-warnings 0)
pnpm --filter @ahoo-wang/wow-view-engine lint
pnpm --filter @ahoo-wang/wow-view-engine lint:check
```

## Testing

- Vitest in the **jsdom** environment, with `clearMocks` and `restoreMocks`
- **No `globals: true`** — unlike the other packages here, import `describe`, `it`, `expect`, `vi` from `vitest` explicitly
- Test files live in `test/` at the package root, named by subject rather than mirroring `src/` one-to-one. Fixtures shared by several suites sit beside them: `test/fixtures.ts` for definitions, configs and sources, and the waits — `deferred`, a promise settled by hand, and `nextTask`, one turn of the event loop (no suite keeps a `flush` or a timed sleep of its own; a wait that is the behaviour under test says so) — `test/fixtures/ui.tsx` for what the UI suites open and `settle`, `nextTask` inside `act` — every `RecordTableController` a suite renders is built from its `recordTableController` / `twoColumnTable` with only what that suite varies passed in, so no suite re-declares the forty members and none reaches for `as unknown as`, `test/fixtures/manager.tsx` for the view-manager harness two suites share, `test/fixtures/workbench.ts` for the gestures the workbench suites share (the filter fold's handle and its field picker, the analysis tray's handle and `openTray`), `test/fixtures/dashboard.ts` for the dashboard the grid and the workbench suites both open, `test/fixtures/writes.ts` for `tracked`/`landed` (a suite waits for the write a gesture caused, then reads the store once — never polls it), `test/fixtures/exports.ts` for what an entry exports, read off the source (the entry lists and the READMEs' entry table both read it), `test/fixtures/themeTokens.ts` for the theme's tokens as each preset, mode and change convention resolve them off the shipped `styles.css` and `themes.css`, with the WCAG arithmetic the contrast suites share (`presetContrast`, `paletteInk`, `paletteDistance`), `test/fixtures/{analysis,columns,filter,hooks}.ts` for the rest
- **The public surface is a list, name by name** (A-16, D29): `test/surface/{root,react,ui}.txt` hold every name each code entry exports and whether it is a type or a value, `test/publicSurface.test.ts` compares the source entries with them, and `scripts/verify-package.mjs` the built JavaScript entries. A name added or removed there is a change to the public surface — make it on purpose (`pnpm exec vitest run test/publicSurface.test.ts -u`) and say so in the PR. `src/runtime/index.ts` exports by name: the engine, the runtime contracts and every type their signatures name, never a part the runtime is built from; `react` and `ui` import those parts from their own modules, not through the index
- `@` resolves to `src/`
- **Coverage thresholds are enforced**: statements 95, branches 91, functions 97, lines 96. `src/ui/components/**`, `src/ui/lib/**` and `src/styles.ts` are excluded — they are vendored from the shadcn registry and are upstream's to test
- **A jsdom suite asserts what a class _means_, not how it is spelled** (A-09). A `className` assertion proves nothing about the screen — jsdom loads no stylesheet and lays nothing out — and it turns red wholesale the moment a colour or a recipe moves into a `cva`. So state is said **on the element** and read back from there: `data-pin` / `data-pin-edge` / `data-pin-index` / `data-sticky` / `data-overflowing` for the table's sticky chrome, `data-tone` for a toned badge, alert or destructive answer, `aria-current`, `aria-pressed`, `data-default`, `data-released`, `data-scrolls`, `data-invalid`, a role, an accessible name, a `title`, or an inline style jsdom really computes. Where a component writes no such attribute and the class is the only witness, **add the attribute** rather than keep the assertion. Three files are the deliberate homes of the remaining class assertions, because in each the class string _is_ the contract: `test/pinnedColumns.test.tsx` ("the sticky chrome recipe") for `ui/record/sticky.ts`, `test/variants.test.tsx` for the cva wrappers of D16-8, and `test/popups.test.tsx` for our copy of each popup's registry markup. Elsewhere a surviving assertion is marked **surviving class assertion** with its reason — a pure declaration with no state behind it (a length, a grid template, a border model, `sr-only`, a `:hover` fill), whose pixels a browser story measures instead
- `test/architecture.test.ts` enforces the dependency rules below on the TypeScript AST, so multi-line, type-only, re-exported and **statically resolvable** dynamic imports are all seen — an `import()` whose argument is a string literal or a substitution-free template. One built from a variable is not recorded, and would slip past these assertions. It reads the wow **sources** off disk, so it is the one suite that runs without any build — every test that imports `@ahoo-wang/wow-client` needs the dependency chain built first
- `tsconfig.headless.json` type-checks the headless layers **without the DOM lib**, which is what keeps them free of browser globals
- `scripts/verify-package.mjs` checks the built artifact: every entry resolves and imports and exports at run time exactly the values its surface list names, the root entry's types need no DOM lib, and no JavaScript entry pulls in the stylesheet, and the built stylesheet paints nothing outside `.fve-root` — no rule sits outside the root, Tailwind's `:root` theme variables included — and the built `themes.css` holds only preset blocks (`:where([data-fve-preset='…'])`), each assigning the same required set of `--fve-*` variables the token blocks read — bar `pin-shadow`, `text-ui` and `rise` / `fall`, which no preset owns — and each optional group (the chart colours, the shadows, the font stack; D35 Q62) whole or not at all, with `neutral` assigning everything as `initial`, and the built `shadcn-bridge.css` is one `:where(:root:not([data-fve-preset]))` rule pointing each required variable at the shadcn token of its name (`--fve-dark-primary: var(--primary)`), bar `input`, `ring`, the status colours and the derived `row-hover` / `quiet-foreground` (D30 Q46), plus `--fve-font-sans: var(--font-sans)`; it prints the three stylesheets' gzipped sizes and holds `themes.css` under 8 KB. `scripts/scope-utilities.mjs` (`postcss-prefix-selector`) makes that true at build time by pinning every rule, preflight and utilities included, to `:where(.fve-root, .fve-root *)`; Storybook runs the same plugin on the theme file

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
- Third-party landing spots are fixed by `HEADLESS_DEPENDENCIES` in `test/architecture.test.ts`, and a dependency the manifest carries but that list does not name is **UI-only**: `@ahoo-wang/wow-client` only at the root entry and in `model`, `filter`, `record`, `analysis`, `runtime` (not `dashboard`, not `store`); `dayjs` in `filter`, `record`, `analysis`, `runtime`, `ui`; `dequal` in `runtime` alone; `culori` in `analysis` and `ui`. UI-only is therefore all the rest — `@base-ui/react`, `@dnd-kit/dom`, `@dnd-kit/react`, `class-variance-authority`, `cn`, `lucide-react`, `react-day-picker`, `react-error-boundary`, `react-grid-layout`, `react-markdown`, `echarts` — while `react` / `react-dom` are optional peers and reach `react` and `ui`. There is no table library: D16-1 declined `@tanstack/react-table`. A new React dependency cannot reach a headless layer without being listed explicitly in the test
- **Deprecated Wow APIs are banned.** The test derives the deprecated export set from the wow sources themselves and fails on any import of it. Use `FilterExpression` and the `Filter*Query` family — never `Condition`, `PagedQuery`, `ListQuery` or `SingleQuery`
- Wow must be imported from its root entry, by name, so every binding can be checked

Package entries:

| Entry                        | Contents                                                                                    |
| ---------------------------- | ------------------------------------------------------------------------------------------- |
| `@ahoo-wang/wow-view-engine` | `model`, the four kernels, `runtime`'s public face, the `ViewStore` port, `MemoryViewStore` |
| `/react`                     | Hooks and headless controllers                                                              |
| `/ui`                        | Default components, views and workbenches                                                   |
| `/styles.css`                | Theme, imported explicitly, customised through `--fve-*` / `--fve-dark-*` on the host       |
| `/themes.css`                | Presets, optional: only `--fve-*` assignments on `:where([data-fve-preset='…'])`            |
| `/shadcn-bridge.css`         | Optional: a host's shadcn tokens read into `--fve-*` while no preset is named               |

## Project Structure

Every source file is listed; `test/agentsStructure.test.ts` fails when one is added without a line here (vendored `ui/components/` and `ui/lib/` are summarised).

```
src/
  index.ts                    — Root entry — model, four kernels, runtime, store port
  styles.ts                   — Build entry that carries styles.css into dist
  styles.css                  — Theme; consumers import it explicitly
  themes.css                  — The presets: `--fve-*` values keyed by `data-fve-preset`, the colours always and the optional groups (chart colours, shadows, font stack) whole or not at all, an optional entry copied into `dist` as written
  shadcn-bridge.css           — A host's shadcn tokens as the `--fve-*` values (`input`, `ring`, status and chart colours and shadows excepted, the font stack included), only while no preset is named, an optional entry copied into `dist` as written
  model/                      — Types and constants only; imports nothing
    analysis.ts               — Wow aggregation enums as stored literals, the date units coarsest first (`ANALYSIS_DATE_UNITS`); which metric types measure one field (`FIELD_METRIC_TYPES`)
    chart.ts                  — ChartSpec — one sub-object per chart family (a waterfall's and a treemap's among them), every reference a group or metric alias; `CHART_TYPES`, `CHART_FAMILY`, `CHART_COLOR_SLOTS` (the palette's size, which a pie folds at); what a cartesian chart draws over its marks — reference lines at a number or a statistic (`REFERENCE_STATISTICS`), target bands, extremes, derived series (`DERIVED_KINDS`, `MAX_MOVING_WINDOW`)
    config.ts                 — ViewConfig — what each view kind stores, and which of its members only draw the result (`presentationMembers`)
    dashboard.ts              — Dashboard config: the 24-column grid it says it is in, the width it is laid out at (`DashboardWidth`, `boardWidth`: none is full; D31), its fixed scope (`fixed`), tabs, the filters (five types, `filterTypeOf`; default, required, multiple, a list) and the time grouping, what they hold (`DashboardFilters`, never saved), bindings (auto or by hand), panels on a saved view or one the board owns (`OwnedView`), how a panel looks at its view (`PanelPresentation`) and what a press on one of its groups does (`PanelClick`), content panels heading included
    definition.ts             — ViewDefinition, FieldDefinition, capabilities
    field.ts                  — FieldKindId; what a cell reads as, which fields hold one string, and how a time field is stored (`temporalOf`, epoch milliseconds unless declared)
    filter.ts                 — FilterOperator as stored in a config; the three group operators and the reading of one
    instance.ts               — ViewInstance: authorship, scope, permissions
    issue.ts                  — Issue — how every kernel reports a problem
    json.ts                   — JsonValue — configs are plain JSON; `LiteralEnums`, a Wow type with its enums as the strings stored; `without` and `overlaid`, a member unset by taking it out, never by leaving `undefined` behind; `sameJson`, two JSON values equal whatever their key order
    limits.ts                 — RuntimeLimits: admission and scheduling budgets
    record.ts                 — RecordData, RecordColumn, and how an untrusted `pinned` / `hidden` reads
    storeError.ts             — ViewStoreError
    index.ts                  — Types and constants only: definitions are code and configs are data, so this layer describes both and depends on nothing else
  filter/                     — Filter kernel — imports model only
    compile.ts                — compileFilter → FilterExpression; `FilterCompileContext` is the moment and zone a relative condition resolves against
    configBase.ts             — The config part every view kind shares
    describe.ts               — describeFilter — the applied-condition summary; a field's `GTE`＋`LT` under "all of" read as one segment
    fieldGroups.ts            — Fields as a picker lists them: ungrouped first, then each declared group
    fieldKind.ts              — FieldKind contract and registry (extension point)
    issuePath.ts              — How an issue path reads against a tree: whose tree it is, and which node
    marks.ts                  — unmarkedErrors — the errors no condition pill can carry
    time.ts                   — Relative and preset values resolved at compile time; `periodOf`, the one calendar period a range is exactly
    tree.ts                   — Tree node predicates, and what a node says as a condition (`conditionOf`, `conditions`, `removeConditionAt`: the negation wrapper is known here and nowhere else); trees arrive untrusted
    search.ts                 — The view's search on the tree's root: `rootSearch`, `withRootSearch` (blank takes it out; an `or` top is narrowed, not joined), `searchFieldOf`
    validate.ts               — validateFilter — shape, budgets and kind rules over an untrusted tree; `isBlankFilter`, `isExecutableFilter`
    values.ts                 — Value shapes of the built-in kinds
    index.ts                  — The filter kernel: pure functions over a stored tree, plus the `FieldKind` registry that makes field types the one axis an application extends
    kinds/                    — Built-in FieldKinds
      array.ts                — A field holding many values: `CONTAINS_ALL` and `IS_EMPTY` beside `IN`, closed by `options` or searched through `remote`
      boolean.ts
      dateTime.ts             — The one instant a single-bound operator compares against, as a phrase, and every bound written the way the field stores time; `readInstant`, the kind's own reading of a value, shared by the cells and the record kernel
      deletion.ts             — The soft-delete dimension as a declared field kind (D17-2); `impliedDeletion`
      elementMatch.ts         — The fields of one element, named as a condition names them
      enum.ts                 — A closed set of values declared by the definition
      metadata.ts             — The kinds backed by Wow's metadata filters
      number.ts
      options.ts              — The tiers (`EmbedInteraction`: static, interactive — neither writes, D36), `EmbedSize`, and `EmbedBaseProps` — what both embeds take, `expandable` among them
      presence.ts             — Operators that ask about presence rather than about a value
      reference.ts            — Points at rows of another dataset
      search.ts               — Full-text search: the box at the top of a list page
      string.ts
      index.ts
  record/                     — Record kernel — imports model and filter
    compile.ts                — compileRecord → FilterPagedQuery / CursorQuery, carrying `recordProjection` (the fields a page asks for: what the view shows, plus `rowFields`), and compileSummaries → AggregationQuery; `FIRST_PAGE`, `summaryAlias`
    defaults.ts               — defaultRecordConfig — a complete starting config, since `create` takes one rather than inventing it; `recordCapabilityOf`
    detail.ts                 — `detailSections`: a record's detail laid out by the definition's field groups, the ungrouped fields after, value-less kinds left out
    export.ts                 — `serializeCsv`: rows as a CSV, values read the UI's way; `writeCsv`, the one writer every export goes through, formulas neutralized unless `neutralizeFormulas: false` (D37)
    path.ts                   — `readPath`: the value at a dot path in a row, how a row's key is read
    paging.ts                 — What the pager reads, from what ran: `pagedPaging` (the size that ran, the pages reachable inside the source's `maxWindow`, `hasNext`, `reachable` when the window cuts the total short), `cursorPaging`, `lastPageInWindow`, `clampPage`
    project.ts                — projectRecord — the columns a table draws, the edge each is held against (`ColumnEdge`), the card layout (`RecordCardView`), the rows and their paging
    validate.ts               — validateRecord — columns, sort, card and summaries against the definition
    index.ts                  — The record kernel: a definition and a config in, a Wow query or a rendered view out
  analysis/                   — Analysis kernel — imports model and filter
    budget.ts                 — Depth and node budgets of a walked tree
    bucketChange.ts           — `bucketChange`: how a time axis's bucket moved from the one right before it (the tooltip's 「较上一期」, D33 Q59) — only on consecutive buckets (`CartesianData.timeline`), only between two numbers the rows hold
    capability.ts             — The three scopes the element chain makes: the root's, each element's, the innermost one's; and the renaming a scope implies
    candidates.ts             — A field's values as the data counts them (value candidates): `valueCandidateField` (grouped by `TERMS`, counted, one string, no `options`/`remote`), `valueCandidatesConfig` (an ordinary analysis — the top `valueCandidateLimit` by count, narrowed by `CONTAINS`/`STARTS_WITH` where the field offers one), `readValueCandidates` (whole or not, off the probe row), `narrowValueCandidates`
    chart.ts                  — Chart-shaped projection for the renderers, one family's shaping each (`shapeChart`): a pie folded before the palette runs out, a heatmap's time rows and columns without holes; `ChartData`, the union every renderer switches on
    cartesian.ts              — `shapeCartesian`: a bar, line, area or combo's points — a time axis always earliest first and without holes (marked `timeline` when its buckets follow one another), a split's missing combination 0 where it is known empty and the metric adds, every value filled in rather than measured named so (`filled`) — and what it draws over them: the reference lines placed, the derived lines, the extremes and what could not be drawn and why (`gaps`)
    funnel.ts                 — `shapeFunnel`: a funnel's stages, its own rows' numbers unless asked to accumulate, each with its conversion
    derived.ts                — Lines computed from one drawn metric along the time axis (D33 batch B): `derivedGap` — why a trend, a moving average or a running total cannot be drawn over this result (a split, no time axis, shares, 「只保留」, rows cut short, a hole nobody can fill, not additive, too few points; Q53) — `derivedValues`, `movingWindow` (a week of days by default), `deriveLines`, `derivedKey`
    references.ts             — Reference lines placed over the marks (`placeLines`: a statistic — average, median — of one metric's measured values, a filled-in 0 left out), and each series' highest and lowest measured point (`seriesExtremes`)
    waterfall.ts              — `shapeWaterfall`: a waterfall's steps in reading order (time forward), each with the running total it starts and ends at, and the closing total unless the spec asks none (`WaterfallData`)
    treemap.ts                — `shapeTreemap`: a treemap's tiles, largest first, nested in one block per outer value when there are two dimensions; a row with no area (not above zero) counted as `omitted` rather than drawn (`TreemapData`)
    chartRows.ts              — What every chart family reads its rows by: `seriesKey` (a group value as a key that keeps apart what the query kept apart), `num`, `absenceReader` (whether a group the rows lack is known to be empty), and `groupKeyText` — a group value as a colour key, the one spelling every chart family reads
    metricCard.ts             — The metric card's projection: one row's number, or over a trend its last period (the last bucket over when the question was asked, the one still under way left out and named) with the change from the period right before, or the whole (`MetricTrend.headline`); compare and target read the same span; `periodRollover`, how long until the period under way ends and the card should be asked again
    timeAxis.ts               — A time axis for the chart projection: `forwardInTime` (earliest first, the missing-value sentinel last), `withoutHoles` (every bucket between the first and the last, stepped by `bucketRange` in the histogram's zone; nothing filled it cannot place) and `bucketSpan` (where a bucket ends, and whether it had by a given moment, on the engine's clock)
    chartSlots.ts             — `fitChartSlots`: the family sub-object of the current chart type, filled from the groups and metrics in force; `switchChartType`: a type switch carries the lead metric (`leadMetric`) into the new family; `comboMark`: a combo draws its first metric as bars, the others as lines; `comboAxis`: one that measures something else than the first goes to the other axis
    compile.ts                — compileAnalysis → AggregationQuery
    fitCharts.ts              — Which chart types can draw a result of this shape and which it reads best as (K3, Q6): the capability says which exist, this says which are greyed and why; `chartUnfit`, why a config's own chart cannot draw its shape — then it is drawn as the table (D20); `chartPickerGroups`, the picker's 「适合这个结果」 and 「其他图型」 (D33 Q54)
    formula.ts                — Formulas and derived metrics (D20 屏 B): their first shapes, `expressionText`／`derivedText` as the author would say them, `isFormula`
    chartFamilies.ts          — What a chart family is, one row each: its options pages, legend and value labels, and the shapes it can draw — the forward reading of `validateChart`, held to it by a test over every shape
    chartOptions.ts           — The rules behind the visualization panel's second level: which pages a type has, a slot swap, one-choice stacking (bars and areas only, `stacks`), 100% stacking (`offersPercentStack`) and smoothing, and a funnel's stage order from the rows (D20 屏 J)
    drill.ts                  — One result row back into the conditions that select its records: `bucketRange` (the inverse of date bucketing, K1), `drillConditions` and the same by dimension (`drillGroups`), whether a filter still narrows to them (`narrowsTo`), and the two follow-ups' patches, `focusOn` and `splitBy`; hands out conditions and config patches only (K6)
    expand.ts                 — The config re-scoped to an expansion chain (D20 屏 G): `withElements` keeps what still names the new unit's fields and starts the metrics again otherwise; `withLevel`, `withoutLevelsFrom`, `nextExpansion`
    granularity.ts            — The granularity a new time dimension starts at (K4): `recommendDateUnit` from the applied range's span (`rangeSpan`) or the result's buckets (`resultSpan`)
    having.ts                 — 「只保留」 as rows of one comparison each: `havingRows` reads a conjunction, `withHavingRows` writes it, any other shape is declined rather than flattened
    defaults.ts               — defaultAnalysisConfig — the first metric the capability can express; the one builder of a dimension (`groupOfType` over `GroupFacts`) and of a metric (`metricOfSummary`, `summaryChoices`, `summaryOf`), `groupableFields`, `aliasOf`, `DEFAULT_MISSING_KEY`, `DEFAULT_PERCENTILE`; `limitBounds`, the range 「前 N 组」 takes and what a blank stands for
    expressions.ts            — Aggregate and derived expression walks
    metricCondition.ts        — `metricCondition`: a metric's own condition as its name reads it (D20 显示名) — the whole condition, and the one value of one field that names it (「金额的合计 · 已发运」) when there is one
    metricFormat.ts           — `metricFormat`/`metricFunctionOf`: how an aggregate's number prints, which is not how its field's values print; `readsAsItsField` (MIN/MAX/PERCENTILE/ANY read as the field does) and `momentMetrics` (those over a date: read, never measured by a mark); `metricMeasure`/`metricMeasures`: what a metric is a quantity of, from its function and its field's declared unit
    project.ts                — projectAnalysis — table columns and rows
    queryFilter.ts            — A filter in metric or element position, and the scope it may name
    validate.ts               — validateAnalysis — the one entry every per-rule file below is read through
    validateAliases.ts        — Alias syntax, the reserved prefix, duplicates
    validateChart.ts          — Chart rules; groups must all be consumed
    validateReferences.ts     — What a cartesian chart draws over its marks, checked for shape: reference lines (a number or a statistic of a metric drawn on its axis), target bands (from below to), derived series (a known kind, a metric drawn, a window of 2 to `MAX_MOVING_WINDOW`)
    chartRefs.ts              — What every family's chart rules read (`ChartContext`) and the checks a slot is made of: a group, a metric, a quantity, every group consumed (internal)
    validateElements.ts       — The expansion chain, walked level by level, and each gate filter
    validateGroups.ts         — Group kinds, date units, dense, missing-value keys
    validateHaving.ts         — Having: declared, grouped, over known metrics
    validateLimits.ts         — Declared limits under Wow's own ceilings; the row limit against `limitBounds`, one finding for every way out of range
    validateMetrics.ts        — One rule set per metric type, filters included
    validateShape.ts          — The skeleton every other rule reads through
    validateSort.ts           — Sort and table columns, over known aliases
    index.ts                  — The analysis kernel
  dashboard/                  — Dashboard kernel — imports model and filter
    defaults.ts               — emptyDashboardConfig
    edit.ts                   — Building a board as pure edits (D22 A–E): `addPanel` (at `freeSpot`, sized by `defaultPanelSize`), remove, duplicate, rename, replace the view, `referToSaved`, `setPresentation`, `editContent`, `movePanelToTab`, `compactTab`, `setBoardWidth`
    click.ts                  — What a press on a panel's group does (D22 H, I), as configs say it: `urlPlaceholders`, `fillUrl` (each `{{field}}` encoded, only a URL a board may open), `takesGroup` and `crossFilterChoices` (the filters a press can set: wired through a field the panel groups by), `pressableGroups`, `validatePanelClick` (warnings only — a click that cannot do what it says falls back to the follow-up menu), `validateBoardClick`, `boardValueChoices` and `boardFilterChoices` (another board's filters, each mapped by hand to a dimension or a filter of this board's that fits it, judged against the board once read — D23 Q17), `setPanelClick`
    clickDraft.ts             — 「点击时…」 as a draft (`ClickDraft`): what the form opens on (`clickDraftOf`), the click it makes or `undefined` while it lacks something (`draftedClick`), what a destination still lacks (`clickDraftGaps`), another board picked (`withBoard`), a URL a board may open whatever it holds (`urlFillable`), and a board's mapping — the sources each of its filters can take (`boardValueSources`, `valueSourceKey`) and what still holds (`mappedValues`)
    filterEdit.ts             — Setting up the board's filters as pure edits (D22 G): add, rename, retype, remove, default, required, multiple, a list of its own, move; the time grouping; the fixed scope taken out whole (`removeFixedScope`, D23 Q16)
    filters.ts                — A filter's value (D22 F): the one condition it stands for (`FILTER_TYPE_OPERATOR`, `filterOperatorOf`, `filterCondition`), what the filters start at (`defaultFilters`) and take (`admitFilters`: required never blank, the panel a value was pressed on kept while its click sets it), the condition one panel runs under (`panelFilterTree`, unwired filters left out), the control that edits it and its value's two shapes (`filterEditor`, `filterControlValue`, `filterStoredValue`)
    layout.ts                 — Where panels may go on the 24-column grid: `fitsGrid`, `placePanel` (covered panels make way, then the tab floats up — only a hand compacts), `compactLayout`, `arrangePanel` (one keyboard command on a compacted board), `freeSpot`, `readingOrder` and `stackedLayout` (the one-column reading a narrow screen shows), `reorderPanel`／`reorderPanelIn` (a step along that column written back onto the grid)
    merge.ts                  — mergeGlobalFilter onto one panel's fields; `boardCondition`, the fixed scope ANDed with `filter`
    migrate.ts                — `migrateDashboardConfig`: a board stored without `columns` read from 12 columns into 24, every `x` and `w` doubled; a pre-C board condition read into its filters' defaults where one could hold it and the rest into `fixed`, the mark that it was read (`intoDefaults`, by `FILTER_TYPE_OPERATOR`)
    panels.ts                 — A stored board's panels and tabs read as untrusted, the one reading every layer imports (`panelsOf`, `tabsOf`); which panel a stored one is: `isViewPanel`, `isContentPanel`, `isOwnedPanel`, `referencedInstance`, `panelTab`, `freshId`, `bindingsOf`, `clickOf` / `clicksFilter` (a panel's click); the members its own look sets (`presentationMembersOf`, `isPresentationMember`); and `isSafeContentUrl` for what a content panel points at
    tabs.ts                   — A board's tabs: `validateTabs`, and add (the first time, two), rename, reorder, remove with their panels
    validate.ts               — validateDashboard — grid, width, tabs, panels (saved or owned views, overrides of how they look), bindings, content
    validateFilters.ts        — The board's filters as declared (type, default, required, multiple, a list) and its time grouping
    wiring.ts                 — Which field of which panel a filter narrows (D22 G), over what of a panel says which view it shows (`DataPanelSource`: all a panel being added has) and that view's fields (`PanelFields`): `wireableFields`, `bindPanel` (by hand, then auto-connect: same name, same type, any tab, any data), `unbindPanels`, `autoBindings` for a panel being added, the list the wired fields declare (`wiredOptions`), and what reaches a panel (`filterReach`) or a tab (`filtersOnTab`)
    index.ts                  — The dashboard kernel: admission, panel binding resolution and the global filter merge
  runtime/                    — Stateful layer; never imports react or ui
    dashboardRuntime.ts       — `DashboardViewRuntime`: the parts in `dashboard/` assembled over one `RuntimeStore` and one clock — admission, the state and the timer wired, and `sync`, which lines the references, the child runtimes and what the filters hold up with the config; only the tab on screen runs (`showTab`)
    definitions.ts            — The definition registry: judged once, refused at the point of use
    environment.ts            — `RuntimeEnvironment` and the `VisibilitySource` port; `ALWAYS_VISIBLE`, `defaultRuntimeEnvironment`
    execute.ts                — The two execution kinds a runtime drives
    exportRows.ts             — Fetching every row the conditions match, page by page, under `exportMax` and the source's paging window (`exportPlan`)
    abort.ts                  — `abortWith`: the controller a source call takes, following the caller's signal (internal, not exported)
    fetchRecord.ts            — One record, whole, by its row key within the injected scope — not the page's conditions, no projection
    issues.ts                 — `toIssue`: one Issue for whatever a command threw
    listeners.ts              — `listenerSet`: the subscribe / notify half every store in this package has
    navigation.ts             — `ViewNavigation`: where a way off a board or an embed goes, for the host's route; `HandOver`, the two parts a view takes off a board (what the page holds as its scope, the reader's values as its own conditions) and the board to go back to (`BoardOrigin`); `GroupNaming`; `withHandedFilter`
    openRuntimes.ts           — The views one engine has open, and who holds an instance
    panelViews.ts             — `PanelViews`: a dashboard panel's view saved as a view of its own and the panel pointed at it — the analysis it owns (`saveOwnedView`) or the saved view it shows, copied for another audience (`copyPanelView`)
    pending.ts                — `comparePending`: what the draft says that the applied config does not (D17-6), presentation members excepted
    permissions.ts            — What a command is allowed to do; `instanceAbilities`, the one reading of "a system view is read-only", which the manager's buttons ask as well
    preferences.ts            — The preference cache, the list order and the default view
    refreshTimer.ts           — The one auto-refresh timer both runtimes arm; `refreshIntervalOf`, `refreshDelayOf`; `MomentTimer`, one refresh at a moment (a metric card's period ending)
    autoApply.ts              — 「改了就跑」: whether the draft is due to run on its own (`autoApplyDue`) and the delay that merges a burst of edits into one query
    requestRunner.ts          — Scheduling; a newer request supersedes a key
    recordRuntime.ts          — `RecordDataViewRuntime`: the page, the selection, the export and the record read whole — a Record view's alone; `dataViewRuntime` builds the one a config's kind runs on, `isRecordRuntime` tells them apart
    runtimeFactory.ts         — How one runtime is assembled, `open` and `create` alike
    runtimeStore.ts           — The store both runtimes are made of: the state, its listeners, the refresh timer's bookkeeping and the rollover's (`expiresAt`), dirty-against-saved; `hasError`
    sourceReason.ts           — What a source said went wrong, in its own words: a Wow error body's `errorMsg`, else the HTTP status, never the URL
    savedConditions.ts        — `conditionsDrifted`: whether the conditions in force are other than the ones the view was saved with
    scope.ts                  — What an injected scope does to admission: the merge, and what it alone is refused for
    source.ts                 — resolveSource — three QueryApi methods
    storedViews.ts            — The one read boundary for stored views: `readingStore` wraps the host's store so every view it hands back, a conflict's included, passes `readStored` (a dashboard through `migrateDashboardConfig`) once on its way in
    tabMemory.ts              — `TabMemory`: where each reader last read each dashboard (`ViewPreferences.lastTabs`), the tab a board opens on, and a burst of switches written as its last, never rejecting
    summaries.ts              — The instance-summary cache: noted on listing and on a confirmed write, dropped on delete, read before the store
    validateDefinition.ts     — Definition admission; needs all three kernels
    valueCandidates.ts        — `ValueCandidateSources`: one `ValueCandidateSource` per offered field, compiled through the analysis kernel under the injected scope, answers kept for the life of the view and narrowed in hand where the source has nothing to add
    viewChanges.ts            — The change notifications a list of views subscribes to (D15)
    viewEngine.ts             — ViewEngine — the command surface: admission, then one dispatch
    viewRuntime.ts            — `DataViewRuntime`: one open data view over one `RuntimeStore` — admission, ask, run, land; what only a Record view has comes in through four hooks (`startOver`, `pageNow`, `settle`, `holds`)
    viewRuntimeTypes.ts       — The runtime contract: `ViewRuntime`, `RecordViewRuntime`, `ManagedViewRuntime`, the state and result shapes; `hasResult`, the one reading of "a result ever arrived", and `hasAsked`, of "a question was sent"
    write.ts                  — Write bodies, retry and overwrite replay
    writeLedger.ts            — The write ledger: outcomes by requestId, retry, conflicts
    index.ts                  — Transient state: what is open, what is in flight, what came back; the runtime's public face, named export by export (D29)
    dashboard/                — What the dashboard runtime is made of
      contract.ts             — `DashboardRuntime`, its state (the tab on screen, what the filters hold, whether it is being built and the reader's own refresh interval among it) and its options: what the dashboard runtime is to the rest of the package; `handOver`, what a panel's view takes off the board
      commands.ts             — `BoardRules`: the rules that span the board's parts, each named in one place — an edit taken only while the board is built (`building`), building holding the timer, whose refresh interval is in force, a filter's default also what it holds, a filter the host holds setting aside the clicks that set it, what a board opens on (`opensOn`); `BoardCommands`: every other command forwarded to one part or one patch of the snapshot, deciding nothing
      filterCandidates.ts     — `FilterCandidates`: what a text filter offers, the values of every field it is wired to counted across the board (`ValueCandidateSources`)
      filterValues.ts         — `FilterValues`: what the filters hold — admitted (one reader's change all or nothing, a host's address partly), shown at once, run a moment later (「改了就跑」)
      grouping.ts             — `regrouped`: the board's time grouping on one panel's time dimension, where its definition allows the unit (`PanelGrouping`)
      panelRun.ts             — `panelRun`: what one data panel runs on the board — its look, its time grouping, the conditions that reach it (`panelScope`, which a text filter's values are counted under too), and the same in the two parts a view takes off the board (`panelHandOver`); `panelReach`; `boardPanels`, every panel's state as the board stands (the tab on screen runs, the others held, a board error stopping them all); `boardHandOver`, a panel's two parts and the board to go back to
      children.ts             — PanelChildren: one child runtime per data panel — a saved view or one the board owns (`PanelView`, `panelView`); a new config for the same view is an edit and a run, not a new child — an edit alone when only how it is drawn changed; a panel on a tab not shown is held as it is, and one that missed a refresh runs when its tab is shown
      editing.ts              — `DashboardEditing`, `DashboardFilterEditing` and `boardEditing`: building the board and its filters, each edit a kernel function applied to the draft and the screen alike and one step of the history (`undo`, `redo`); a data panel added comes wired (`autoBindings`)
      history.ts              — `EditHistory`: one step per edit command, the members it changed before and after on the draft and the screen, a burst of one naming or setting on one thing one step; `outsideHistory`, a plain `edit` kept off the members those commands own; `rewound`
      panels.ts               — What the runtime knows of its panels without holding any: `DashboardPanelState`, the click in force and the finding that set a click aside (`clickInForce`), which panel a finding is about (`panelOf`); `blocksBoard`, the errors that stop the whole board; `boardFindings`, what the board says above its panels — the one reading the workbench, an embed and a host share; `stopsSave`, what stops a save of each kind; a child's findings addressed to its panel (`panelIssues`, `atPanel`, `reissued`), `samePanels`; `shownTab`, the tab on screen
      presentation.ts         — `presentedConfig`: a panel's override of how it looks laid over its view's config, dropped with a note when it no longer fits
      press.ts                — `PanelPresses`: a press on a panel's group worked out (D22 H, I), the click read off the panel state and never judged again (A-11) — the group's value in a board filter's shape set from the panel (`crossFilter`, a second press clears), whether a group is the one pressed (`pressed`), and where a custom destination goes carrying it (`destination`: a filled URL, a saved view taking what the panel takes off the board (`handOver`) and the group's conditions, on the fields its data has too, or another board with its mapped filters set and the rest at their defaults — a click set aside, or a mapping the board read at the press finds stale, falls back to the follow-up menu); `board`, another board read only when asked
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
    recordSelection.ts        — Shift ranges over the rows on screen: `toggledSelection` (the range goes the way the pressed row goes), the anchor and the page of which question it stands on (`RowsMark`, `standingAnchor`)
    useAnalysisEditor.ts      — Analysis controller
    useAnalysisResult.ts      — The analysis result as a host draws it: the rows that ran, the question before any have (`question`, its `columns`), the chart over them, the picker's fits, and the follow-ups on a pressed group — the group by dimension, and what each follow-up opens
    analysisEditing.ts        — The edits to the question as plain functions over the draft (`questionEditing`): dimensions, metrics, conditions, copies, formulas, derived metrics, having
    useAutoRefresh.ts         — `RefreshController`: refresh now, the cadence ladder cut to the limits, the countdown
    useBulkCommand.ts         — A host's command for one record run over a selection: a few at a time, progress, stop, each refusal's reason, the unfinished rows left selected
    useSearchBox.ts           — The view's search kept on hand: the definition's search field, the draft's and the applied text, set / submit / clear
    useDashboard.ts           — Dashboard panels, geometry and state; the board's edit commands, its tabs, `preload` for a view about to be added, and a press on a panel's group (`crossFilter`, `pressed`, `destination`, `destinationBoard`)
    usePanelFollowUps.ts      — The follow-up menu on a dashboard panel (D22 H): `useAnalysisResult`'s workbench half routed to the host (`ViewNavigation` of kind `unsaved`, the board's filters folded into the view's own), and `ownedNavigation` for a board's own analysis
    useFilterEditor.ts        — Filter tree editor controller
    useRecordExport.ts        — The export run: scope, progress, the ceiling, delivery
    useRecordDetail.ts        — One record's detail: open, read whole (`fetchRecord`) and read again when the view's result lands; the page's row until then
    useRecordTable.ts         — Record controller
    useSaveCommands.ts        — Save, save-as, revert, rename, delete
    useValueCandidates.ts     — A condition's values offered from the data: asked when the list opens, a typed fragment once typing pauses, aborted on change and on close; loading, the source's reason on failure, retry
    useViewEngine.ts          — Creates and disposes one engine
    useViewList.ts            — View summaries in the user's order
    useViewManager.ts         — Rename, delete, reorder, default; outcomes per row
    useWorkbench.ts           — One workbench's shell: list, open, leave, the header's outcomes, a view nobody saved a host hands it (`unsaved`)
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
      handOver.ts             — `useHandOver`: a view a host hands the workbench (D26 Q30) — a saved one opened by its id, one nobody saved held as `handed` so the shell opens it folded — once per object through the leave guard, under what the page holds as its scope; `useHandedConditions`, the reader's board values put onto a handed saved view's own conditions
  ui/                         — Default look; may import every layer
    AnalysisChart.tsx         — Dispatches by chart family; nothing else but saying whether the follow-up menu is open over the chart (`ChartMenuOpen`)
    AnalysisTable.tsx         — The aggregation as a table: groups first, then metrics, with the totals row from its own ungrouped query rather than from summing what is on screen, and its scope said under 「合计」; read with the record table's recipes — numbers on the right, ids in monospace, `SortableHeader`, held widths and the filler
    Announcer.tsx             — `useAnnouncer`: one live region per surface, handed back rather than rendered by the caller, the same words twice said twice; `SurfaceAnnouncer`／`useSurfaceAnnouncer`: a surface's one voice handed to the parts drawn inside it (a board's grid, tabs, filters, building)
    AppliedBar.tsx            — The conditions the rows on screen were fetched under; a page's scope worn read-only. Record and analysis views only: a dashboard draws none (D27)
    BulkStatus.tsx            — `BulkStatus`: a host's bulk command as one line above the rows — how far it has come with a Stop, then what it came to and why
    CardSettings.tsx          — The card layout's settings behind the column settings' button (D18 VI)
    ColumnSettings.tsx        — Which columns show, in which order, pinned or not, summarised how — one sortable group per area (D19)
    ConflictConfirm.tsx       — The same choice, put once more with both configs on the table
    CopyButton.tsx            — A value's own copy button: the clipboard, the tick, and the two words a press comes back with
    DashboardArrange.tsx      — Placing a panel without a pointer: one handle a pointer drags and a keyboard arranges with (`PanelHandle`: Enter to start, arrows move, Shift+arrows resize, Esc puts it back), the corner named after its panel (`PanelGridItem` tells it which, and hands the board's voice down), and 上移／下移 in the one-column reading (`PanelOrder`)
    DashboardGrid.tsx         — The panels, placed — measured before the first paint, at the fixed width and centred or full width (D31), in reading order, one column below `md`, the tab on screen alone when a tab bar says which; the edit bar over them and the first things to add on an empty board, where the board is built; each panel's commands (`panelCommands`) and its press (`panelPress`)
    DashboardPanel.tsx        — One framed panel: its title (a heading panel is that and nothing else), 「不受『〈筛选〉』影响」, 「点击筛选「〈筛选〉」」, its 「⋯」 menu, the wiring strip under it, the one arrange handle while the board is built, the body; what a panel is called (`panelName`, and `panelNames` numbering the names the board makes up); chart findings named by column
    DashboardPanels.tsx       — The static panels: a heading, a note (its headings under the panel's own), a picture, a list of links
    DashboardWorkbench.tsx    — Default Dashboard workbench: no 「正在显示」 band (D27), the board's fixed scope handed to the filter bar, a finding about a filter or a panel said by its name
    DataWorkbench.tsx         — The data workbench: one list of record and analysis views; `useWorkbench` + both parts + `WorkbenchShell`, joined (D18-1, D20)
    DeleteDialog.tsx          — What a delete costs, said before it happens — a dashboard's, that the analyses made inside it go with it
    DragHandle.tsx            — The handle a sortable row is carried by, and the arrow keys that move it; the three lists share it
    EditorBand.tsx            — The fold a view's editor lives in
    EmbeddedDashboard.tsx     — A saved board on a business page (D22), read and never written (D36): a tier (static, interactive), each filter editable, locked or hidden (`filterModes`) — the locked and hidden values the page's own and followed (`pageValues`), the reader's the host's address (`initialFilters`/`onFiltersChange`, never a held one), every filter read as what it holds in the static tier — the filter bar, tabs and grid (`ReadBoard`), the title, panel-title, export and fill-the-screen switches
    EmbeddedView.tsx          — A saved record or analysis view on a business page (D22), never written (D36): a tier (static, interactive), the page's narrowing ANDed onto the view's own (`scopeFilter`), and the title, search, export, auto-refresh, fill-the-screen and 在工作台中打开 switches; a dashboard is `EmbeddedDashboard`'s
    ExportDialog.tsx          — The export window: scope, name, progress and outcome in one journey (D14), controlled so a panel's menu can open it, what the file holds said in the surface's own unit where its rows are an analysis's groups (`holds`, D25 Q28); `ExportButton`, the toolbar's own trigger for it
    ExportSteps.tsx           — The export window's steps, one per phase (`phaseOf`, `said`): scope and what the file will hold, progress, the outcome, and the buttons each phase ends in
    FieldMenu.tsx             — A picker's entries by catalogue group; shared by the field pickers
    FilterPanel.tsx           — Condition builder root: mode, focus boundary, actions row
    FilterValueEditor.tsx     — The switch over `EditorDescriptor.input`; the only place that knows the union
    HandOffMenu.tsx           — A menu whose items may open a dialog: `HandOffMenu`, `HandOffMenuContent`, and `DialogMenuItem`, which keeps the menu closing after it from taking the keyboard back to its trigger
    IconButton.tsx            — An icon-only control and the tooltip saying its name; the one place the two are paired — and `BadgeTooltip`, a badge whose note is a tooltip focus and a tap open too
    LeaveGuard.tsx            — `LeaveDialog`: draws the headless guard's question
    MessagesProvider.tsx      — `MessagesProvider`: the wording every default component reads, each provider merging over the one above it
    OutcomeActions.tsx        — One outcome as a line and its buttons, shared by the two above and the manager
    PanelUnavailable.tsx      — A dashboard panel that cannot show anything: the finding mapped to why, in the reader's words, and who can bring it back — or, while the board is built, the buttons that do
    PendingDot.tsx            — The "changed, not applied" dot pinned to a pill or a group
    RecordCards.tsx           — The same result as cards, drawn from the card half of the saved config (D18 V); a value reads as a card reads it, a note on its own three lines
    RecordPagination.tsx      — How many rows there are and how to reach the next of them
    RecordTable.tsx           — The record view as a table: the columns and rows of the result that ran, never of the draft; its rows are `TableDataRow`s and a value reads as a table reads it, one line each
    RefreshControl.tsx        — Refresh now, and the auto-refresh cadence menu, as one split button
    RenameInput.tsx           — A name typed in place — a panel's title, a tab's, a view's in the manager (Q-10): focused and selected; Enter or leaving keeps, Escape puts back; trimmed, an unchanged name no rename, a blank one refused where asked; ✓ and ✕ in the field where asked
    RenderBoundary.tsx        — The boundary each part of a view renders behind, so one failing leaves the rest standing
    ResultToolbar.tsx         — Selection, bulk slot, layout, columns, refresh
    RowActions.tsx            — The wrapper a host's per-row actions land in
    RowItem.tsx               — One row of a list over the registry's `Item`; the five lists share it
    SaveActions.tsx           — The split save button group: save in place, and the menu of the other ways to save
    SaveAsDialog.tsx          — What the create is: a copy of a saved view, the first save of one made from nothing, a board's own analysis promoted, or a personal view on a shared board copied for its readers (`share`, no audience asked)
    SortSettings.tsx          — The sort editor: entries in priority order, direction, drag to reorder
    SystemMark.tsx            — The lock a view that came with the definition wears in the sidebar and the switcher
    StatusStrip.tsx           — One-line findings: warning, error, failed query (+ `dedupeIssues`)
    ViewExpansion.tsx         — Filling the screen: `useViewExpansion`, `ViewExpandToggle`, the document's scroll lock
    ViewHeader.tsx            — Title bar: kind, audience, title, unsaved mark, save commands
    ViewList.tsx              — What this list is a list of — the definition's own title
    ViewManager.tsx           — Rename, delete, reorder and the default view, from the sidebar
    ViewManagerRow.tsx        — One managed view: drag handle, rename in place, default, delete
    ViewSurface.tsx           — The boundary every view renders inside: the theme, the wording, the locale and the zone a time reads on; `useSurfaceAttributes`, the mode, preset and change convention a popup copies
    ViewSwitcher.tsx          — The view list as one control, for when the sidebar is folded away
    WorkbenchShell.tsx        — The frame the workbenches share, over one `useWorkbench`: composes its columns and blocks from `workbench/` and holds the state they share; resolves the refresh, the query strip and the warnings itself
    WriteOutcome.tsx          — The open view's last write, and the three ways out of a conflict
    alerts.tsx                — `LineAlert`: one callout one line high, tone deciding colour, icon and role
    band.ts                   — `bandText`: a number histogram's key as the band it starts, 「¥0～500」, its bounds short when short is exact; `segmentText`, a `GTE`＋`LT` segment written the same way
    describeConfig.ts         — One config in a sentence, for a conflict's side-by-side
    display.ts                — A value as its field shows it: enum labels, dates, bucket keys, an array of objects by its elements' title or its count (`heldReading`), never JSON; `summaryFunctionKey` names a summary in its column's vocabulary, `columnTitle` composes an analysis header from its two parts, and a time dimension's with its granularity
    download.ts               — Hands a file to the browser; the whole of the DOM the export needs, and the name it is handed under
    dragAnnounce.ts           — What a screen reader hears while a row is dragged, in the shape the drag library takes; the four sortable lists share it
    dragWording.ts            — `dragWording`: one list's three drag sentences read out of the catalogue under that list's keys; each `drag.ts` names only its keys
    dragDrop.ts               — `dropped()`: what makes a finished drag a drop at all, before any list adds its own rule
    dragPlugins.ts            — The drag library's plugins, set once (Q-11): `announcedPlugins`, a sortable list's provider with the `Accessibility` plugin worded by the list; `withoutOptimisticSorting`, a sortable row's
    features.ts               — `WorkbenchFeatures`: which of the workbench's own controls exist (D18 XI)
    gridPlacement.ts          — `useGridPlacement`: a pointer drag or resize placed by the kernel's `placePanel`, preview and drop alike
    kinds.ts                  — The icon each kind and audience wears, shared by list and header; `SurfaceKind`, the one kind a surface has open, `kindWord`／`useKindWord`: a dashboard's chrome says 仪表盘 where another says 视图 (D26 Q34), and `kindIssue`／`useKindIssue`, the same for what the engine reports about the thing open, by the entry its code reads
    layout.ts                 — `TEXT_UI`, `SPACE`: the one small type size and the spacing ruler; `FIXED_BOARD_WIDTH`, what a fixed-width dashboard is held to (D31)
    messages.ts               — Wording, by key
    popups.tsx                — The popups this package renders, themed and on a layer of their own
    roving.ts                 — A group of peers as one Tab stop: who holds it, how it moves, where an arrow lands; the record header and the analysis result's rows share it
    summary.ts                — The applied-conditions bar in words: one `FilterSummaryItem` as a sentence
    toolbar.tsx               — Base UI's toolbar primitive: one tab stop with the arrow keys inside
    variants.tsx              — The colours, edges and shapes a vendored component does not ship, in one place (D16-8); `ChangeBadge`, a change in the colour the host's change convention picks (`styles.css`); `TableDataRow` holds a record row's three states; the dashboard's `PanelCard` (the warning edge on the card's ring), `ControlFrame` (the `--input` edge a filter chip's controls share), `ModeBar` and `FOCUS_INSET`
    index.ts                  — The `/ui` entry: the default look, built on shadcn/ui with Base UI primitives
    analysis/                 — What the analysis view is made of
      AnalysisToolbar.tsx     — The result's first row: the reading (dimensions · metrics) and how the result is looked at — table or chart as two icon segments, the chart one drawing the chart type in force, the visualization panel, and 「导出」 over the groups on screen (D25 Q28)
      chartIcons.ts           — `CHART_ICON`, one glyph per chart type and the table, shared by the picker's tiles and the toolbar's chart segment; `glyphType` falls back to the generic chart (the bar) for a type this package does not draw
      ChartPicker.tsx         — The visualization panel's first level: the chart types as tiles in the sidebar column, in two groups — 「适合这个结果」 with the table last, 「其他图型」 greyed with a reason (D33 Q54) — one radiogroup over both, the recommended one marked (D20 屏 I); under them one labelled button on to the chosen type's options
      CompositionOptions.tsx  — The options of the two charts that add their numbers up: a waterfall's steps and value and whether its total is drawn, a treemap's tiles, outer level and value (D33 Q55)
      ChartOptions.tsx        — The visualization panel's second level: the chosen type's options on the data, display and axes pages (D20 屏 J)
      ReferenceOptions.tsx    — The display page's marks over a cartesian chart (D33 batch B): reference lines at a number, the average or the median; target bands; the highest and lowest points; trend, moving average and running total, each greyed with why the result cannot carry it (Q53)
      VisualizationPanel.tsx  — The visualization panel's two levels as the workbench and a panel's own look both draw them (`visualizationPanel`), the keyboard following the level (`useVisualizationFocus`), and `ignore`, a command a host cannot take
      drag.ts                 — What the series list makes of a drag: `seriesDrop` takes only a drop between two series this chart draws, plus what a reader hears
      DataTab.tsx             — The options' data page: each family's slots, position slots listing dimensions and measure slots listing metrics; a funnel's stages reordered by hand, and a metric-staged one named by hand
      DisplayTab.tsx          — The options' display page: legend and value labels, then each family's own settings (a cartesian chart's marks over it in `ReferenceOptions`); the table's totals row
      AxesTab.tsx             — The options' axes page, cartesian only: title, bounds and number format of each numeric axis
      optionControls.tsx      — The few controls the options pages are built of: slot select, check, choice, number, text and name fields, and a titled section
      CompactSelect.tsx       — The one select a tray card carries: a named choice among a few words
      CardMenu.tsx            — A tray card's name and the way to rename it (`CardName`), and the card's own menu (`CardMenu`): display name, the sentinel bucket, filling empty periods
      DimensionCard.tsx       — The dimensions slot and its cards: field, and the control its type asks for (granularity, band width)
      ElementsSlot.tsx        — The expansion slot (D20 屏 G): the chain of arrays counted inside, one card a level with its own gate, 「展开：…」 along the declared chain, and the counting unit
      FormulaCard.tsx         — The controls of a formula metric and of a derived metric (D20 屏 B): two operands picked or typed, the operation between, the summary for a formula
      HavingRows.tsx          — 「只保留」: the groups kept, as rows of one comparison each under the result slot's first label; a stored having of another shape is shown and clearable
      DrillMenu.tsx           — The follow-up menu on one group of a result: the records behind it, split by another dimension, only this group (D20 追问); headed by the group as the result reads it (`groupText`: a date bucket as its column prints it), naming what it opens 「{what} · {group}」; as wide as its words, hung from the mark, point or cell pressed, the keyboard handed back to the row; on a dashboard panel the board's filters under its heading (`context`) and ↗ on each item (`away`)
      EmptyResult.tsx         — An aggregation that matched no group, one sentence for both layouts, and in the workbench the record view's one way out (`wayOutOf`) — none with no condition in force
      SkeletonResult.tsx      — The first answer on its way, in its shape: bars for the table's rows or one chart area, and the caption's bar (`CaptionSkeleton`)
      MetricCard.tsx          — The metrics slot and its cards: field and summary (the six ways Wow measures a field as one list), a percentile's number, the record count
      MetricCondition.tsx     — Conditions edited in place under a tray card (D20 屏 H): the funnel, the block of the range's own pills, the 「只算 …」 line at rest; a metric's and an expansion level's
      SeriesList.tsx          — The data page's 系列 slot: one row per metric drawn, carried by the shared handle into the order the analyst wants (`cartesian.series`, and nothing else about a series)
      RangeSlot.tsx           — The tray's first slot: the condition panel under a heading that holds the tree's simple/advanced switch
      ResultSlot.tsx          — The tray's 「结果」 step: which groups are kept (「只保留」), their order and 「前 N 组」, each under a visible label; the N — a blank is the starting N, text out of range stays in the box, marked, and never reaches the draft
      Tray.tsx                — The analysis view's editor: range → dimensions | metrics → result, one Apply for the whole draft (D20), quiet while auto-run leaves it nothing to do; the slots scroll, the footer stays
      editing.ts              — What the tray picks when a field is picked — its alias and the type or summary it starts as (`defaultGroup`, `defaultMetric`), the shapes being the kernel builders' — and what a metric or a dimension is called (`metricReference`, `groupReference`)
      issueNames.ts           — `chartIssueNamer`: a chart finding says each dimension and metric as its column is headed, never by alias, and why a chart shows as the table
      listFocus.ts            — Where the keyboard stands after the card it was on leaves the page: `useListFocus`, shared by every remove and move in the tray and the options panel (A2)
      exportOffer.ts          — The analysis's 「导出数据…」 (D25 Q28): `analysisFile`, the result as its table reads it — groups first, the first N, the totals row last, never the chart's padding — and `useAnalysisExportOffer`, what the export window is handed for it, no scope to pick
      headerSort.ts           — The result table's header sort: `headerSorted` (ascending, descending, back to the order the presses began from — an analysis's sort decides which groups the first N are) and `useHeaderSort`, which writes it through the editor's `sortNow` — run at once, as the record header does, unless the draft holds another edit waiting for Apply
      tableColumns.ts         — An analysis column in the record table's terms: its reading (`readingOf`, a plain metric is a number), one value as its cell reads (`analysisCellText`, the exported file's reading too), whether it is an id (`isIdentifier`), a width from the column alone and never from its values (`columnWidthOf`), and the `RecordColumnView` its `SortableHeader` takes
    charts/                   — One file per family, plus what they share
      Cartesian.tsx           — Bar, line, area and combo through `cartesianOption` (D21): the legend, the names fitted to the width, a pressed mark handed back as its group; the legend a row of switches for the series and the derived lines (`hidden`), the zoom kept for one result (`zoomFor`), and above the legend why a line asked for is not drawn (`chart-gap-note`, Q53)
      cartesianOption.ts      — `cartesianOption`: a cartesian chart as the library draws it — each series' mark, axes on the scales the plan owns and their titles, short numbers, value labels (inside a stacked segment in the ink that stands off it), stack totals, reference lines, emphasis toward the ink, room for the widest value label
      cartesianPlan.ts        — `cartesianPlan`: what a cartesian chart decides before its theme and size — which way it lies (`drawsHorizontal`: long category names lay bars down unless the analyst said), its series and stacks and their shares (`stackPlan`, the reading table's too), each axis's scale (`sharedScales`), each label's text
      cartesianFit.ts         — `cartesianFit`: what the plot's size changes — the category names (`categoryFit`: side by side, slanted, a time axis flat and thinned), the value labels flat, upright or none for the whole chart and a segment's only where it fits, an axis title cut to its side
      cartesianTooltip.ts     — `cartesianTooltip`: the axis tooltip — every drawn series at the category, a filled 0 said so, a 100% stack's share, and on a time axis each number's change from the bucket before with the footnote 「较上一期」
      cartesianZoom.ts        — `zoomOption`: a long upright axis (`ZOOM_FROM`) zoomed by a slider, and in a workbench by pinch or Ctrl + wheel, never a plain wheel (Q51: never saved); `LARGE_FROM`, past which bars and a scatter draw as one path
      cartesianMarks.ts       — What is drawn over the marks, every number the kernel's: reference lines and target bands (markLine, markArea) on one carrier per axis, the highest and lowest points (markPoint), the derived lines dashed in the foreground, in no colour slot
      markWords.ts            — The words those marks are written with (`markWords`, `derivedName`: 「7 期移动平均（算出的）」) and why a line asked for is not drawn (`gapNotes`, `gapReason`)
      scale.ts                — `sharedScales`: one or two value axes on nice steps of their own cut into one count, zero on one line, a whole axis stepped in whole numbers (`niceStep`)
      ChartLegend.tsx         — The legend as text beside the drawing: a dot per series, on top by default, one line with the rest counted (「还有 N 个」); given `onToggle`, each entry a switch (`aria-pressed`) for its series, a switched-off one a ring and struck through; a derived line a dash (`dashed`)
      ChartReading.tsx        — The chart's numbers as a table, for whoever cannot see the marks
      highlight.ts            — `faded`: every mark but the group pressed drawn faint, over any family's option (D22 I)
      EChart.tsx              — The thin binding to the library: create once sized, resize, a whole new option per change, dispose; the frame (`data-slot="chart"`), the named image and the theme read off the element; a plot hugging its legend (`hug`); `ChartMenuOpen`, a menu over the chart, which keeps the tooltip away until it closes; a zoom kept across redraws of one result and the categories refitted to it, a plain wheel kept from the library, patterns over the colours (`composed`)
      echarts.ts              — The chart chunk: the library's pieces registered on demand (markLine, markArea and markPoint among them), SVG renderer; imported by `load.ts` only
      load.ts                 — `loadCharts`: the chart chunk loaded on first use and kept
      measure.ts              — How wide a line of tick text is: a canvas where there is one, an estimate elsewhere
      theme.ts                — `readChartTheme`: the stylesheet's tokens read back off the chart's element as concrete colours, a derived one (`color-mix()`, `oklch(from …)`) through a hidden probe the browser resolves; `CHART_TOKENS` and `THEME_ATTRIBUTES`, what a chart reads and what `ViewSurface` watches for it; `mixColor`, `emphasized` (a hovered mark a step toward the ink) and `inkOn` (the ink a label on a mark wears, by contrast); the host's pin on patterns (`--fve-chart-patterns`)
      tooltip.ts              — The tooltip as the registry draws one, in HTML, every data text escaped; a row's quiet note and a footnote under the rows
      Waterfall.tsx           — A waterfall through `waterfallOption`: a pressed step handed back as its group, the total's basis said over rows cut short
      waterfallOption.ts      — `waterfallOption`: bars stacked on an unseen base so each step floats on the running total, a rise in the success colour and a fall in the destructive one, the total in the first slot, signed values past each bar's end; `drawnBars`
      Treemap.tsx             — A treemap through `treemapOption`: a pressed tile handed back as its group (both, when nested), the groups with no area and the share basis said over it
      treemapOption.ts        — `treemapOption`: tiles by area named on themselves, a slot a tile up to eight and one hue shaded by rank past that, inner tiles shaded from their block's colour, no zoom or breadcrumb; `drawnTiles`
      Funnel.tsx              — A funnel through `funnelOption`, the conversion's basis said over it
      funnelOption.ts         — `funnelOption`: a centred bar a stage in the order given — one length for one number, no trapezoids — each stage's name, value and conversion in one column beside them; `drawnStages`
      Heatmap.tsx             — A heatmap through `heatmapOption`; a pressed cell handed back as its row's and column's group
      heatmapOption.ts        — `heatmapOption`: cells filling the plot, a `visualMap` colour scale, a log scale shading by the log, values on the cells in the ink that stands off each; `heatmapLabelsFit`, every cell's number or none
      MetricCard.tsx          — The value under the span it covers (a period, 「范围内全部」), the change from the period before as a toned badge, the comparison signed, the target, and the trend through `sparklineOption`
      sparklineOption.ts      — `sparklineOption`: a metric card's trend as a line and a faint fill, no axes
      PieSlices.tsx           — A pie or a donut through `pieOption`: the legend beside it and the two centred together (`PIE_HUG`), led by the measured column (and, cut short, the share basis), each slice with its share
      pieOption.ts            — `pieOption`: slices with their shares outside, labels that give way, the remainder grey, a donut's whole in its hole when the measure adds up; `drawnSlices`
      ScatterPoints.tsx       — A scatter through `scatterOption`; a pressed point handed back as its group
      scatterOption.ts        — `scatterOption`: both axes titled by their columns and padded past the extremes, whole ticks where the values are, a third metric as size, a few points named
      axis.ts                 — Value format, whole axes (`allWhole`), a category name cut for its axis, which axis a series is on, a vertical axis's title (`sideTitle`: Chinese set flat at the axis's head) and what a value axis measures (`measuredTitle`)
      dateTicks.ts            — `useDateTicks`/`shortDateTicks`: a time axis's ticks written short (「9月1日」), the year only on the first and where it changes
      family.ts               — `FamilyProps`, the value labeller and the column titler
      legend.ts               — Where the legend beside the chart goes (`legendAt`), from the spec's `legend` and the family's own default
      motion.ts               — Whether a chart animates its marks: not when the reader asked for less motion (`useChartMotion`, read live)
      patterns.ts             — Patterns over the colours (decal, D33 Q57): `usePatterns` follows `prefers-contrast: more` live, `--fve-chart-patterns` pins it, `withPatterns` turns on the aria component's decal alone
      palette.ts              — The eight slot colours, the grey of a pie's "Other", and the spec's overrides
      reading.ts              — A chart as text: its name and the numbers it draws
    embed/                    — What the two embeds share, and the data view's bodies (D22)
      options.ts              — The tiers (`EmbedInteraction`, `DashboardEmbedInteraction`), `EmbedSize`, and `EmbedBaseProps` — what both embeds take
      EmbedFrame.tsx          — The surface both embeds draw on (`data-embed-size`), the moment it opens, said as well as drawn, what can go wrong opening one — unopenable, another kind, a refused narrowing — said by the one kind it draws (`SurfaceKind`), the auto-refresh switch, and one render boundary
      EmbedHead.tsx           — An embed's first row, only when it has something in it: the title at the host's heading level and the controls on the right; `OpenInWorkbench`; `EmbedExpand`, 「铺满屏幕」 on the embed's own surface (`useViewExpansion`)
      EmbeddedRecord.tsx      — A record view embedded: the rows, the read-only applied band and the search at its end, the export in the head; header sort and pages in the interactive tier
      EmbeddedAnalysis.tsx    — An analysis view embedded: its chart or table as saved; in the interactive tier the table｜chart switch, the header sort and the follow-up menu through the host's route
    dashboard/                — What building a dashboard is made of (D22 A–E)
      BoardFilters.tsx        — `useBoardFilters`: the board's filters as it draws them — the bar (in the one-column reading, `FilterSheet`), 「添加筛选」, each filter's settings, wiring and the toast whose 「只接刚选的面板」 unwires what auto-connect added; `FiltersRefused`, what the board left out of the address it opened on, said once over the bar, each filter by its name
      ClickSettings.tsx       — 「点击时…」 (D22 I): the follow-up menu, a board filter a press sets, or another view, board or page, one `RadioGroup`; a view picked with the `ViewPicker`, a URL checked at the field
      BoardDestination.tsx    — 「另一块仪表盘」 in 「点击时…」 (D23 Q17): the board picked and read (`useDestinationBoard`), its filters one row each — 「不带」, 「这一组的〈维度〉」 or 「这块板的〈筛选〉」 — and what no longer holds said and dropped (`mappedValues`)
      AddMenu.tsx             — 「＋ 添加 ▾」: 数据 (a saved view, a new analysis where one can be made) and 内容 (heading, text, image, links); the same first steps on an empty board
      BoardWidth.tsx          — `BoardWidthSwitch`: 固定宽度／全宽 on the edit bar (D31), one step of building
      Board.tsx               — `DashboardBoard`: the grid with the edit bar over it, the picker and the content form, every edit one `DashboardEditing` command; where a new panel goes (the first row on screen of the tab on screen) and where the keyboard goes after
      buildShell.tsx          — `useBuildShell`: the shell of building a board around `DashboardWorkbench`'s title bar — 「编辑」, the primary of a board read (D32), for whoever may save it, the keyboard back on it after 保存／取消, and the tab and filters told to the host; an embed never builds (D36)
      gridBlocks.ts           — `gridBlocks`: the board's cells while it is built, soft blocks as near a square as the 80px row allows (`blockHeight`, D34), one row of them as the mask `styles.css`'s `dashboard-grid-blocks` layer is painted through — the library's own sums (`calcGridItemPosition`) at the width the grid is drawn at
      building.tsx            — `useDashboardExtensions`: the default workbench's `DashboardEditExtensions` — a new owned analysis, a panel's own look and its reset, 另存为视图, 复制为共享视图并替换, the tab bar — and the four dialogs they open
      commands.ts             — `panelCommands`: what one panel's menu offers — 「看」 always (`ViewingCommands`) (导出数据… on a record panel with rows or an analysis panel with groups), 「改」 while the board is built (`EditingCommands`; 「恢复为视图的样子」 only over a look of its own, 「复制为共享视图并替换」 only over a personal view on a shared board), renaming and removing alone in the one-column reading, where the panel's 上移／下移 reorder it; `readerCommands`, a read-only board's export alone — and the builder the grid reaches through context
      ContentEditor.tsx       — The small form a note, a picture or a list of links is written in, what the kernel would refuse said at the field
      DashboardTabs.tsx       — The tab bar over the grid, two tabs or more: switch; while building, the edits — add, rename in place, move, delete (asked first when it holds panels) — and where the keyboard lands after each, drawn by `EditableTabs`; `tabTitle`
      EditableTabs.tsx        — The tab bar while the board is built (`EditableTabBar`): each tab carried by a handle or arrows, shown, renamed in place, moved or deleted from its menu, 「添加」 after them; `TabRemovalDialog`, the question a tab holding panels is asked first
      filterModes.ts          — How an embedding page offers each filter (`DashboardFilterMode`: editable, locked, hidden) and the time grouping; what the page holds (`heldOf`) and what is the reader's (`readersOf`); `staticModes`, the bar a static embed reads — every filter as what it holds
      FilterBar.tsx           — The filter bar (D22 F): a chip a filter with the condition editor's value controls, required ones starred and never empty, 按日｜周｜月, 「清空」, a filter reaching nothing on the tab drawn quieter and saying why, 「来自「〈面板〉」」 on a value a press set; on an embedding page a locked filter read as what it holds, with a lock, and a hidden one not at all; the board's fixed scope at the head of the row, read-only (D27) and removed whole while the board is built (D23 Q16)
      FilterReadings.tsx      — What the bar holds that the reader reads and does not change: the board's fixed scope (`FixedScope`, 「固定范围」, its author's ✕ while built), a filter the page locked (`LockedChip`, `LockedReading`), and the chip's own layout (`CHIP`)
      FilterSheet.tsx         — The filter bar below `md` (D26 Q38): one button, 「筛选（已设 n 个）」, opening every filter in a sheet from the bottom edge; the fixed scope and the locked filters read beside it
      findings.ts             — `filterNamer`: a kernel finding about one of the board's filters said by its name on the bar, never its key (X-03); `boardFindingNamer`: what the board says above its panels as the workbench and an embed say it — a panel after its name, a filter after its (Q-01)
      FilterOrder.tsx         — `useFilterOrder`: the bar's filters put in another order while the board is built — a `DragHandle` on each chip, ←/→ or a drag, the landing said in the board's voice, every move `moveFilter`; the places are the bar's, a hidden filter kept in order (`barMove`), the time grouping after them all
      FilterSettings.tsx      — 「添加筛选」 (`AddFilterMenu`) and one filter's settings popover: type, name, default, several values, required, where its values come from, 接线 and 移除
      FilterWiring.tsx        — Wiring a filter (D22 G): the context the grid reads, each panel's strip (same-type fields, 「没有可接的字段」, 「手动」), the wiring bar, the toasts in the board's own root
      landing.ts              — `useLanding`: where the keyboard goes when a press on the filter bar takes its own control away (U-02), and the lookups on the board it lands by
      EditBar.tsx             — The bar a board is built under: 正在编辑, 撤销／重做, 添加 and 添加筛选 beside it, 取消 (put back the saved board, asked first) and 保存 (the save, a shared board asked first, a new one named; D26 Q37); stuck to the top of what scrolls the board while it is built
      history.ts              — `useBoardHistory`: 撤销／重做 named after the step each takes, said when taken, ⌘Z／Ctrl+Z on the board and never in a field, and where the keyboard goes
      extensions.ts           — `DashboardEditExtensions`: the parts of building that live elsewhere (a new owned analysis, the presentation editor and its reset, 另存为视图, 复制为共享视图并替换 with whether it is offered, the tab bar), each entry there only while provided
      NewAnalysisDialog.tsx   — A new analysis made inside the dashboard: the data first, then `AnalysisParts` in a dialog (tray, result, visualization panel, 改了就跑), a title following the reading, 「放进仪表盘」
      PanelBodies.tsx         — What a data panel draws: the record table, the analysis drawn from its child's draft over the rows on hand (`useAnalysisResult`), a press on its groups (`PanelPress`: the follow-up menu through the host's route, the board's filter set with the group marked, a destination), a first answer on its way said busy, a failed query with its retry, and 「此处改为〈图型〉」 (`presentationMark`)
      PanelMenu.tsx           — 「⋯」 on a panel (从仪表盘移除 at once, 撤销 brings it back), and its title renamed in place (`RenameInput`)
      PanelMarks.tsx          — `PanelMarks`: what a panel's title row says besides its name (`panelMarks`) — the warning and the note before the title, and on a line under it 「不受『〈筛选〉』影响」, 「点击筛选「〈筛选〉」」 and a look of its own
      PanelExport.tsx         — 「导出数据…」: the export window over a panel's child view — a record panel's rows by `useExportOffer`, an analysis panel's groups by `useAnalysisExportOffer` — named after the panel
      PresentationDialog.tsx  — 「改这里的展示」: the chart picker and options writing one panel's look, beside the panel as it will look; Cancel puts back the look it opened with
      press.ts                — `PanelPress`: what the grid hands a panel for a press on its groups, `pressMode` (the menu and a destination only with a route), the board line the follow-up menu says, and what a cross-filter press says
      ViewPicker.tsx          — Choosing a saved view: grouped as the switcher groups them, searched, narrowed by kind and data, 「已在板上」 and 「只有你看得到」 said on the row; or, for a click, another board
    columns/
      ColumnRow.tsx           — One row of the column settings: checkbox, two-state pin toggle, summary, handle
      drag.ts                 — What the settings make of a drag: `columnDrop` refuses one across the areas, plus what a reader hears
      rows.ts                 — The column settings' model: rows, the two areas (D19), order
      sections.ts             — Rows of one area by catalogue group; the search over them
    components/               — 34 shadcn/ui primitives — vendored, see below
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
        suggested.tsx         — A text value typed or picked from the field's own values with their record counts: one value as the box itself, several as chips; free text stays a value
        shared.tsx            — Whether the condition holding this value is refused
        text.tsx              — Free text
        unsupported.tsx       — A value no control can hold: the stored value and why it is read-only (F-06)
    lib/utils.ts              — shadcn cn() helper — vendored
    manage/
      drag.ts                 — What the manager makes of a drag: which drop it will take, and what a screen reader hears while one is under way
    messages/                 — the catalogue, one file per prefix family
      analysis.ts             — the analysis editor and its charts, with the two kernels behind them
      building.ts             — building a board, batch B3: tabs, a new analysis in a dashboard, saving it as a view, a panel's own look
      bulk.ts                 — bulk outcome wording
      clicks.ts               — a press on a dashboard panel, batch D: the follow-up menu's board line, cross-filtering, 「点击时…」, and the click findings
      config.ts               — shared config — the part every view kind stores, so every kind reports it
      dashboard.ts            — the dashboard grid, its panels, and the dashboard kernel behind them
      definition.ts           — definition admission, worded for whoever wrote the release
      embed.ts                — an embedded view or dashboard: a filter the page locked
      filters.ts              — a board's filters, batch C2: the filter bar, a panel a filter does not reach, a filter's settings and wiring
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
      exportOffer.ts          — `useExportOffer`: what the export window is handed for one record view — the CSV of the columns drawn, read as the cells read, the conditions the rows came back under, the columns and the ceiling — from the runtime, the table, the filter and the title alone, the wording and the zone read off the surface; the workbench's toolbar and an embed's head alike
      emptyWayOut.ts          — Which way out an empty result offers: back to a saved view's conditions, change them, clear them, or add one; `wayOutOf`, the press that takes it, shared with the analysis
      Filler.tsx              — The aria-hidden last cell that lets rows fill the frame while columns keep their width (P-11)
      SkeletonCards.tsx       — The cards of a first query still on its way
      SkeletonRows.tsx        — The rows of a first query still on its way, one bar per column (`SKELETON_ROWS`, `barWidth`, the analysis's skeleton reuses both)
      openRows.ts             — Rows and cards that open their record: a press on the row's own ground, or Enter/Space in the rows' one Tab stop (`useOpenRows`)
      RowCheckbox.tsx         — One row's checkbox, table and cards alike: Shift+press or Shift+Space extends the selection (`{ range }`), and `RangeHint`, the one sentence per surface that says so
      RecordDetail.tsx        — The side panel a record opens in: every field under its group, long values whole, the row's commands in its header
      DetailStructure.tsx     — A structure in the detail read whole: an array of objects element by element (title, declared fields, what nothing declares), an object key by key, long text as a copyable block
      SortableHeader.tsx      — One column header: the sort button, its place in the sort, the resizer
      SummaryRows.tsx         — The table footer: one row per summary scope; `SummaryValue`
      cells.tsx               — `cellValue`/`cellText`: one value as its field reads it, for table, cards and CSV; `CellSurface` decides how many lines it may take, and nothing else
      columns.ts              — Which columns the table holds and what its cells wear otherwise: `tablePins`, `pinnedSlots`, `heldColumns`, `usePinnedOffsets` (the left offset chain, keyed on the pins), `HEAD_CELL`, `TABLE_CELLS`, `ACTION_CELL`; `ACTIONS_COLUMN` is the one column ever held on the right (D19)
      headerRoving.ts         — `useRovingHeader`: one Tab stop per header row, arrows between columns, Alt+arrows resize (P-02)
      overflow.ts             — `useOverflowing`: whether the table is wider than its port, said as `data-overflowing`; the held columns' edges answer to it (P-23)
      pinCap.ts               — The pin cap (D17-4): which pins to let go on a narrow port; `ReleasedPins`; its observer is keyed on the slots, not rebuilt every render
      queryAnnouncement.ts    — What a query says about itself to a screen reader
      roomBelowRows.ts        — `useRoomBelowRows`: the room the rows leave in a taller port, so the totals sit at its bottom beside the pagination
      sticky.ts               — The one home of the table's sticky chrome (A-09): `stickyCell`/`stickyHead` (the held cell's recipe plus `data-pin`/`data-pin-edge`/`data-pin-index`), `stickyBand` and `BAND`/`BAND_ROW` (the two bands, `data-sticky`), `OWN_LAYER`, `pinVar`; `LeftPin` carries the offset chain and `RightPin` is the one column against the edge itself (A9, D19); the boundary's edge is drawn through `in-data-[overflowing]:`, so it answers to the port's word from `overflow.ts` (P-23)
      useSummaries.ts         — The two summary scopes from the one the runtime executed; table and cards share it
    sort/
      drag.ts                 — What the sort editor makes of a drag: which entry a drop moves where, the order that comes out of it, and what a screen reader hears meanwhile
    workbench/                — The shell's private parts, and the parts each kind of view puts into it
      AnalysisParts.tsx       — What makes an analysis view an analysis view: its editor and its table or chart, handed to the shell as slots
      ConditionBlock.tsx      — The view's editor on a tray of its own: the fold's band when it folds, an open block when it does not, behind one boundary
      NewView.tsx             — The "new view" command drawn as a press or as a menu of the kinds, in the sidebar, the empty work area and the switcher (D20 Ⅱ)
      NoViews.tsx             — The work area when the definition has no view of these kinds yet
      OpeningSkeleton.tsx     — The shape of the page that is opening: title-bar and result-block skeletons, one status sentence (P-13)
      OriginBar.tsx           — The line under the title bar of a view opened from another's group: the way back, which names the origin once (D20)
      RecordParts.tsx         — What makes a record view a record view: the condition band, the toolbar, the rows and the paging, handed to the shell as slots
      ResultBlock.tsx         — The result and its caption on the one bordered frame (D12); `ShellResult` fills it with the toolbar, the strip and the result, each half behind its own boundary
      parts.ts                — `WorkbenchParts`, the slice of the shell's slots a kind fills, and the render prop it fills them through
      Sidebar.tsx             — The sidebar in its two forms: `SidebarColumn` (the visualization panel or the view list beside the view; the panel a drawer from the bottom on a narrow surface) and `FoldedSidebar` (the way back, the definition's name and the switcher in the title bar)
      StatusLine.tsx          — The status line under the title bar: the errors, then the warnings — the view's, its result's, the definition's and failed preferences (D12 Ⅰ′)
      SearchBox.tsx           — The search box at the applied band's end: typing edits the draft, Enter applies (not while an input method composes), ✕ clears and asks again
      TitleBar.tsx            — The title bar's ruled-off block: `ViewHeader` with the host's actions behind a boundary and the view-level controls — the editor's toggle, the refresh, filling the screen
      Unopenable.tsx          — The work area when the chosen view cannot be opened
      useEditorFold.ts        — The editor's fold, per opening; `filled`
      useSidebarFold.ts       — The sidebar's fold, following the surface's width until the user presses; `useNarrowSurface`, whether there is room for a column beside the view at all
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

- `@ahoo-wang/wow-client` — query protocol (`FilterExpression`, `FilterPagedQuery`, `CursorQuery`, `AggregationQuery`)
- `react` / `react-dom` — **optional peer dependencies**; the root entry works without React
- UI-only: `@base-ui/react`, `@dnd-kit/dom`, `@dnd-kit/react`, `echarts` (every chart, loaded on first use; D21), `react-grid-layout`, `react-markdown`, `react-day-picker`, `react-error-boundary`, `lucide-react`, `class-variance-authority`, `cn`
- Headless: `dayjs` (time), `dequal` (runtime equality), `culori` (colour syntax: in `analysis` a saved chart colour is validated, in `ui` the theme's colours are converted to `rgb()` for the chart library)

## Code Style

- TypeScript strict mode; Apache 2.0 license headers on every source file
- Prettier: single quotes, trailing commas, semicolons, 80 char width
- ESLint runs `react-hooks` with `exhaustive-deps`, `incompatible-library` and `unsupported-syntax` all set to **error**; CI gates on `lint:check` with `--max-warnings 0`
- `max-lines` is a tripwire, counting code only (`skipBlankLines`, `skipComments`): **500** for `src/**` (vendored `ui/components` / `ui/lib` and the `ui/messages/` catalogue are out of scope) and **1200** for `test/**` — a file that exceeds it is either split or given a per-file override in `eslint.config.js`, whose ceiling is **the measured code lines × 1.1, rounded up to a multiple of ten**, so a fix may add a few lines but the file cannot grow back meaningfully; **an override requires a matching entry in `docs/design/todo.md`** saying how it comes back under the line, and a round of splitting re-measures and re-tightens the remaining ceilings
- **Any work under `src/ui/**` starts with the shadcn skill — `Skill(shadcn)` (or `/shadcn`).** That is this repository's copy at `.claude/skills/shadcn` (from `npx skills add shadcn/ui -a claude-code --copy`). **Never install the skill globally as well**: Claude Code resolves a same-named skill to `~/.claude/skills/shadcn` first, the repository copy is then never loaded, and the global preamble runs `shadcn info` from the repository root and fails on a monorepo. The repository copy's preamble passes `-c "$(git rev-parse --show-toplevel)/typescript/wow-view-engine"`, which is the one workspace with a `components.json`. Read its `SKILL.md` and `rules/*.md` (styling, composition, forms, icons) before writing or reviewing UI; when running any other CLI command from it, pass the same `-c` (or `cd typescript/wow-view-engine` first). Its rules are this package's rules: semantic tokens only; `className` for layout, never a component's colours or typography; existing components before custom markup (`Badge`, `Separator`, `Empty`, `Alert`, `Skeleton`, `Field`/`FieldGroup` for forms, `ToggleGroup` for option sets); icons inside components carry `data-icon` and no size classes; Base UI triggers use `render`; add a missing component with `npx shadcn@latest add <name> -c typescript/wow-view-engine`, never by hand. Where a rule is knowingly set aside — a vendored colour overridden to fix a defect, a component declined for a reason — say so at the call site and in `docs/design/ui/`. A subagent brief for UI work repeats this paragraph.
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
