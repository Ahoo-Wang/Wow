# Fetcher View Engine

View Engine owns further data-view development. `@ahoo-wang/fetcher-viewer` is deprecated and in maintenance mode, with no new features; use this package for new projects. The packages use different models and APIs, so migration requires adaptation.

[Task guides](../../wiki/guides/view-engine/index.md) · [API reference](../../wiki/reference/view-engine/index.md) · [Shared runnable example](../../wiki/examples/view-engine.md)

Independent `@ahoo-wang/fetcher-view-engine` package with headless Wow filter compilation and validation, a complete `FilterPanel`, structured value editors, and shadcn/Base UI controls. It also provides a headless ViewEngine and a complete RecordView page with host-managed definitions, instances and persistence. Record table/card views and analysis tables/charts share the same engine; dashboards are outside this scope.

## Module responsibilities

The public `ViewEngine` composes internal services; applications use its public commands and snapshots. The core runtime imports no React, DOM or table component library.

| Module                                                     | Owns                                                                                                                     |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `engine/SessionStore`, `sessionState`                      | Immutable snapshots, subscriptions, saved/editing baselines and derived dirty/pending state.                             |
| `EngineScope`, `InstanceWork`                              | Lifetime/navigation versions, cancellable selection, write/reload exclusion and uncertain create receipts.               |
| `RecordEdits`                                              | Validated draft/config changes and the record/summary queries each change requires.                                      |
| `RecordQueries`, `RecordSummaries`                         | Independent record and aggregate requests, cancellation, result validation and failure recovery.                         |
| `ViewLoader`, `ViewReload`                                 | Definition/instance loading, navigation and reload reconciliation without discarding edits.                              |
| `ViewPersistence`, `ViewManagement`, `instancePermissions` | Save/save-as, names/deletion/user order, permissions and response reconciliation.                                        |
| `record/validation`                                        | Definition, instance/list and record trust boundaries.                                                                   |
| `filter`                                                   | Operator metadata, protocol construction/compilation, editor lifetimes and focused panel/value components.               |
| `record/page`, `record/table`                              | Page ownership/navigation/actions and table state/header/body/cell/summary composition. Layout calculations remain pure. |

Tests and Storybook interactions are grouped by behavior, including controlled editor lifetimes, configuration persistence and query boundaries.

## Runnable public-package examples

From the repository root, using the existing workspace dependencies:

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine... build
node packages/view-engine/examples/core.mjs
node packages/view-engine/scripts/verify-package.mjs
pnpm exec vite packages/view-engine/examples/react --host 127.0.0.1 --port 4175
```

`examples/core.mjs` runs the headless public API, including JSON save and restoration by a new engine for unset controls and opaque component props. The standalone `examples/react/sales-order/OrderWorkbench.tsx` composes a page and all five extension types using public imports only. Open `http://127.0.0.1:4175`, or **View Engine → 开始体验 → 全链路体验 / 开发接入 → 最小接入** in Storybook, for actions, custom filtering/cells, error recovery and a narrow dark view.

After `pnpm install`, `pnpm storybook` and `pnpm build-storybook` explicitly build View Engine and its workspace dependencies before starting or building Storybook. Both use the public `dist` entries, including CSS. Restart the command after editing package source to rebuild it; no source aliases replace the package during production acceptance.

`examples/react/FilterPersistenceExample.tsx` saves the selected status ID and an independently edited display name. Open `http://127.0.0.1:4175/?example=persistence`, or **View Engine → 专项场景 → 视图与运行时 → 配置与恢复 → 公共包 · 组件配置 JSON 保存与重新打开**, to add an unset control, save it without querying, and reopen the JSON in a new engine. Changing the display name can also be saved directly; valid changed status values can be saved before Query.

`verify-package.mjs` creates a temporary archive, checks its exports/CSS and exact distribution content, then runs and type-checks consumers against the extracted package. It performs no installation or publication. These are library integration examples backed by a strict local simulated service; production authentication, authorization, persistence and backend query behavior still require host-system verification.

### Library delivery acceptance

Run `pnpm verify:view-engine` from the repository root after building the workspace. It checks the public package and local/HTTP recovery using an isolated development Storybook, then builds and serves a fresh production Storybook for browser acceptance and performance measurements. Success, failure and interruption clean up its owned processes; your existing port 6006 server is not used.

```bash
pnpm build
pnpm exec playwright install chromium firefox webkit
VITEST_MAX_WORKERS=4 pnpm test:unit
pnpm lint:view-engine
pnpm test:storybook
VIEW_ENGINE_BROWSERS=chromium,firefox,webkit VIEW_ENGINE_ARTIFACTS=/tmp/view-engine-acceptance pnpm verify:view-engine
```

The default is Playwright Chromium; `VIEW_ENGINE_BROWSER_CHANNEL=chrome` selects installed Chrome. If the default browser cache is not writable, set the same writable `PLAYWRIGHT_BROWSERS_PATH` during installation and execution. `VIEW_ENGINE_BROWSERS` selects only the UX/scale checks; service recovery uses Chromium. CI installs three engines and runs this entry point, uploading per-stage logs, measurement JSON and screenshots on failure.

The fixture uses 100 loaded rows, 30 data columns and 100 filter candidates at 1440px/390px in light/dark themes. It checks keyboard query/selection, retry and repeated disposal. Refresh, selection and field-picker warm interactions each record 10 samples with a 1250ms p95 regression ceiling, including automation transport and paint settlement; this is not a business-network latency SLA. Larger workloads need consumer measurements; virtual scrolling or arbitrary scale is not promised.

Raw axe findings are retained. WebKit's hidden Base UI focus-sentinel naming diagnostic is recorded as [upstream expected behavior](https://github.com/mui/base-ui/issues/5237), with actual keyboard entry, Tab exit and Escape restoration checked separately. Other violations fail acceptance. This does not replace real VoiceOver/mobile-device testing.

## Shared record and analysis lifecycle

```tsx
import type { ViewHost } from '@ahoo-wang/fetcher-view-engine';
import { useViewEngine, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function OrderPage({
  host,
  scopeKey,
}: {
  host: ViewHost;
  scopeKey: string;
}) {
  const binding = useViewEngine({ scopeKey, definitionId: 'orders', host });
  return <ViewPage {...binding} selectable />;
}
```

`useViewEngine(options)` owns creation, loading and disposal, including React StrictMode. Its required `scopeKey` and `definitionId` identify the lifetime; changing either replaces the engine. Optional local `definition`/`instances`, paired compiler/editor registrations in `extensions`, `limits` and `onDiagnostic` initialize that lifetime. Same-scope host updates preserve edits. Change the React key to explicitly reinitialize other inputs. The hook returns `ViewEngineBinding`: `{ engine: ViewEngine | null, extensions?, error? }`.

`ViewPage` is pure UI: pass the binding, or a caller-owned engine. It never loads or disposes that engine. `ViewPageContent` requires a non-null engine. Both compose navigation, shared writes and the selected `RecordView` or `AnalysisView`. `RecordView` and `AnalysisView` render only their own kind. A headless caller creates `new ViewEngine({ definitionId, host, definition?, instances?, filterCompilers?, analysisCompilers?, limits?, onDiagnostic? })`, calls `load()`, then `dispose()` when its scope ends.

### Definitions and saved instances

`ViewDefinition` has `id`, `title`, `sourceId`, shared `fields`, optional `timeZone`, `allowedOperators` and `filterEditors`. Declare at least one capability:

- `record: { rowKey, allowedLayouts, defaultPresentation?, recordActions? }`. `RecordViewDefinition` makes this capability required. Row keys are own-property paths; `allowedLayouts` is a nonempty unique list of `table`/`card`.
- `analysis: AnalysisCapability` authorizes COUNT, field grouping, numeric functions, date units and bounds. An aggregate-only source does not need a record row key or paging functions.

`ViewInstance` is the discriminated union `RecordViewInstance | AnalysisViewInstance`. Both require nonblank `id`, `definitionId`, `title`, `revision` and a `scope`. `kind: 'record'` uses `RecordViewConfig` (`filters`, `sort`, `pagination`, `presentation`); `kind: 'analysis'` uses `AnalysisViewConfig` described below. Scope is personal or public/system/shared; it is not permission. Create input omits only ID and revision; the service returns both.

`ViewInstanceList` contains the visible instances and `defaultInstanceId: string | null`. Default preference and current selection are independent. The list may mix kinds. Structurally valid but currently unexecutable configurations remain editable with `session.validation`; a broken instance does not prevent healthy siblings from being used.

### Working content, applied results and saves

Snapshots are immutable. `ViewEngineState.version` increases on publication and each session has an `editVersion` for working edits. `RecordSession.queryAttempt` captures the in-flight query; `RecordSession.result` binds successful rows to its config/filter/page/cursor and receivedAt. `AnalysisSession.pendingQuery` captures the in-flight plan. Render provenance and business actions must use the applicable result/attempt, never infer them from working edits. `ViewSession` is discriminated by `kind`; narrow it before accessing record-only or analysis-only state. Shared fields include `baseline`, current working `instance`, `dirty`, `validation`, `writeStatus`, `writeError`, `requiresReload` and optional `conflict`.

Record sessions retain `filterDraft`, `filterBaseline`, `appliedFilter`, `filterPending`, page/cursor, rows, summaries and selection. Editing working filters does not change the applied query or records. `filterPending` means the working filter differs from the applied scope; it does not by itself disable Save. Analysis sessions keep an independent successful `result` with query/schema provenance; later edits or failed runs do not relabel those rows as a new result.

`save(id?)` validates and persists current working content without running a query. Invalid raw input blocks saving, but valid unqueried edits can be saved. A receipt advances the baseline while preserving edits made after submission. `saveAs({ title, scope }, id?)` returns `Promise<string | undefined>`: a created ID when known; creation recovery can outlive the originating selection. Runtime rows, selection, errors and countdowns are never persisted as configuration.

### Commands and result ownership

`engine.analysis(id)` binds `edit(updater)`, `run()`, `refresh()`, `clearSort()`, `setFilterValidity(valid)` and `restore()` to one analysis instance. `edit`/`clearSort`/`restore` do not query; `run` compiles and validates the complete result before publishing. `engine.record(id)` binds `edit(updater)`, `refresh()`, `setPage(page)`, `setPageSize(size)`, `applyFilter()` and `restore()`. Bound commands become invalid after the instance lifetime is replaced. Editing callbacks must be pure and may not reenter engine commands.

`engine.analysis(id).refresh()` requests a safe automatic refresh: it runs only when the current query still matches the successful result, editor input is valid, writes are idle, and no conflict or reload requirement exists. It skips ineligible states without submitting drafts; `run()` remains the explicit execution/retry operation. `AnalysisSession.queryValid` is derived from query compilation, editor validity and resource limits; presentation-only errors do not make the query invalid, though they still block saving.

Both session kinds expose `editorEpoch`. Accepting a reviewed remote version advances it and discards local editor buffers; ordinary reload/restore retain their documented non-destructive input behavior. Custom mounted editors should bind commands and reset their local buffers when `(instance.id, editorEpoch)` changes, as the built-in views do. Old validity callbacks are ignored after this reset; old analysis edits and record draft edits are rejected rather than overwriting the accepted remote configuration. Published active and pending-create sessions use the same final validation path. Record admission always checks pagination/layout discriminants and nested presentation structure; missing field or capability references remain recoverable semantic errors. Cancelling an analysis refresh retains the successful result status, allowing subsequent automatic refresh.

All record operations are on `engine.record(id)`: `setFilterDraft(configuration, valid?)`, `setFilterValidity(valid)`, `setFilterMode(mode)`, `applyFilter()`, `setSort(sort)`, `setColumns(columns)`, `setLayout(layout)`, `setCardConfig(card)`, `setPage(index)`, `setPageSize(size)`, `nextPage()`, `setSelection(keys)`, `refresh({ background? }?)`, `retryQuery()` and `refreshSummary()`. The facade no longer exposes direct record commands. Shared operations are `setTitle`, `save`, `saveAs`, `restore`, `reloadInstance`, `renameInstance`, `deleteInstance`, `setDefaultInstance` and `reorderInstances`. Record restore restores the baseline and queries; analysis restore restores its working configuration without running.

### Conflicts, unknown writes and runtime bounds

A real divergence retains the old baseline, local edits and latest remote document in `session.conflict`. Normal Save cannot silently put old content on a newer revision. The UI offers use latest, save a copy, and overwrite when permitted. `useRemoteInstance(review, id?)` and `overwriteInstance(review, id?)` require the exact reviewed conflict snapshot. New local edits or a new remote revision invalidate an old confirmation. Overwrite still uses the reviewed remote revision as CAS. Remote metadata and current permissions remain authoritative.

Unknown write outcomes are separate: after a dispatched timeout, network failure or `UNKNOWN_OUTCOME`, preserve the original operation and reconcile using `reloadInstance`. An uncertain create reuses its original `requestId` and submitted body; changing the request ID can create duplicates. Unknown delete recovery retains its original identity and revision. Default-view preferences, delete receipts and cross-tab transactions remain host responsibilities. The provided memory/browser hosts are reference adapters, not a production authorization boundary.

Default `limits` are load 15,000 ms, query/write 30,000 ms, 4 concurrent queries, 5 retained result sets and 262,144 configuration bytes. Result eviction does not evict working drafts or recovery state. Late reads cannot overwrite a newer request or result scope; cancellation is not a user-facing query failure. Optional `onDiagnostic` receives operation identity, kind, phase, elapsed time and optional error code, without query/row payloads; callback failures are isolated. Verify the host/backend contract and browser flows for your deployment; these APIs alone do not establish production readiness.

## View service contract and runtime boundary

Acceptance follows **service JSON → ViewHost → fresh ViewEngine → frontend registry → recovered components/actions**. MemoryViewHost is the executable service fixture; the internal HTTP adapter exercises a provisional protocol. The service owns definitions, instances, visibility, revisions, create receipts and user ordering. The frontend owns components, callbacks, filter compilation and business-query clients. Business record writes never rewrite view configuration.

### Local service fixture

```tsx
import { IndexedDBViewHost } from '@ahoo-wang/fetcher-view-engine/react';

const host = new IndexedDBViewHost({
  serviceKey: 'development-tenant',
  scopeKey: 'alice',
  definition: orderDefinition,
  instances: orderViews,
  resolveSource,
});
```

`serviceKey` is a trusted tenant/service namespace; `scopeKey` is the trusted user within it. Storage uses `fve:views:${JSON.stringify([serviceKey, definition.id])}`. Public views are shared within this namespace; personal views and display order are isolated by user. Private ownership is service-owned and is not accepted from write bodies. Set useViewEngine's scopeKey to include tenant and user; omit its local definition/instances inputs so loading goes through the host.

IndexedDBViewHost commits read, authorization, revision checks and writes in one IndexedDB readwrite transaction. MemoryViewHost uses a native Map for in-process service state; a shared Map is supplied explicitly. The two hosts share only domain rules, and all browser persistence uses IndexedDB. Seed instances get service-issued revisions on initialization. System views are read-only. Optional `instancePermissions`, `canReorder` and `permissionsRevision` supply a trusted policy; increment the monotonic policy revision whenever grants change. Writes always recheck the current policy inside the transaction.

`permission.load(definitionId, signal?)` reads authority snapshots. `permission.refresh()` notifies `permission.subscribe`; ViewEngine subscribes/unsubscribes with its host lifetime, so permission changes update controls without destroying drafts. Permissions remain synchronous reads during rendering. `await reset()` is an administrative fixture reset for the whole service/definition (including users and create receipts), not a REST operation. Malformed storage is reported rather than silently reset. The raw local-storage document is internal service state, not a ViewInstanceList DTO.

### Development-only HTTP experiment

HTTP classes and the status-code mapping live under `dev/http`; they are neither
public exports nor included in the package. See [the experiment](dev/README.md).
The copyable order example accepts a `createViewHost` callback; HTTP wiring is
confined to `dev/HttpOrderExample.tsx`.

`ViewHost.instance.create(input, {requestId, signal?})` requires one ID per logical create. Same user/key and canonical body replay the stored receipt; changed content returns CONFLICT. The view and receipt are committed in the same transaction. ViewEngine retains the ID and original snapshot until the result is verified, including denied retries and a full reload. Unknown creation results are confirmed by replaying the same create request, never by matching list content. An explicitly returned created ID can be inspected directly. Other writes wait for confirmation; the original request remains retryable and newer local edits are retained. It never treats a transport failure as proof that a write did not happen. Direct clients must retain their request ID when retrying, including after reconstructing a client. Fixture receipts live until the administrative reset.

Personal ordering is a complete replacement: the last successful replacement for the same user wins. The current visible ID set must match, and no other user's order is modified. Instance writes use revision CAS. These are separate, explicit concurrency semantics.

### Reproducible verification

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm storybook
# In another terminal:
node packages/view-engine/scripts/verify-view-host.mjs
node packages/view-engine/scripts/verify-http-view-host.mjs
# Keep the HTTP fixture running for manual Storybook use:
node packages/view-engine/scripts/verify-http-view-host.mjs --serve
```

The HTTP script starts an isolated local server with MemoryViewHost with a shared Map and server-bound identities; the browser uses HttpViewHost and the real UI. It covers shared/private visibility, private ordering, response loss/idempotency, permission revoke/restore, timeout/retry and engine cancellation. It separately runs native IndexedDB transaction races and queued cancellation across two tabs. `httpViewHost.test.ts` also tests stale permission-response ordering, invalid sessions, ownership forgery and concurrent HTTP writers. Both compiled and uncompiled component tests cover missing/replaced extensions and conflict recovery. Business records remain a separate service; no real production backend or authentication provider is claimed by this fixture.

## Page and all-record summaries

Only fields declared as `type: 'number'` support column summaries. Set
`summary: ['SUM', 'AVG', 'MIN', 'MAX']` to select several metrics for one column.
Column settings use a multi-select; deselecting every option disables summaries.
Other field types have no summary controls. `field.summaryFunctions` can restrict the list, with `[]`
disabling summaries. Column functions are saved with the instance; COUNT is not
supported.

The footer displays page and all-record summaries together in two aligned rows,
listing each selected metric in SUM/AVG/MIN/MAX order. Scope labels appear once
on the left; metric labels and right-aligned numbers stay on one line. Loading
uses a centered Spinner for records and one Spinner beside each pending scope
label. Pending metric values stay blank, and pagination does not repeat loading
text.
Page values use the loaded records; all-record values use the host's Wow `aggregate`
with the applied filter, without fetching every record. Unapplied filter edits and
row selection do not change the scope. Aggregate loading/failure leaves page values
and records available, with a separate retry. Changing a function recalculates both
summaries without reloading the list.

Each failed scope shows one error icon beside its label. Click it to open the
cause; the all-record scope also offers a retry that only requests aggregation.
Failed metrics keep “—” placeholders. Errors stay in their summary row, without a
full-width alert below the table.

Numeric fields can set `numberFormat`, using `Intl.NumberFormatOptions` plus an
optional `locale` (default `zh-CN`). For money, use
`numberFormat: { style: 'currency', currency: 'CNY' }`; for integers, use
`{ maximumFractionDigits: 0 }`. Decimal formatting defaults to at most two
fraction digits unless digit options are supplied; other styles use Intl defaults.
Default record cells and summaries share this format. Custom cells can reuse
`formatRecordNumber(value, field)`. Formatting does not change records or aggregate
values. Focus or hover a summary value to see its raw precision in a tooltip.

Numeric functions skip null/missing values. Empty inputs return null (“—”), distinct
from zero. Headless consumers can use `calculateRecordSummary`,
`createRecordSummaryQuery`, `readRecordSummaryResult`, or the engine's
`refreshSummary` method and session `pageSummary` / `allSummary`. Result values
are keyed by column ID and function, for example `values.amount.SUM`. Across all
columns, at most 64 metrics are allowed.

### Automatic refresh and expansion

The top global toolbar provides combined manual/automatic refresh and page expansion.
Automatic refresh defaults to off, with 30 second / 1 minute / 5 minute intervals. Background reads retain the current records
until success and do not overlap. Automatic refresh pauses for pending filters,
selected records, active writes, errors, hidden documents or focused editors and
popups; later cursor pages also pause. It waits for all-record summaries before
reading again. Failed reads retain records for manual retry. Pass
`autoRefreshPaused` to `ViewPage`, `ViewPageContent` or `RecordView` to pause during
host-owned business operations. Switching instances resets the interval.

The refresh button shows the selected interval and a `mm:ss` countdown computed
from the next deadline. It shows Paused while blocked and Refreshing during a
read; resuming, changing the interval or finishing a refresh starts a full new
interval. Countdown changes do not trigger per-second screen-reader announcements.

Headless consumers can call `engine.record(id).refresh({background: true})` and observe
`session.refreshing`. The engine enforces the query, selection and write guards;
the React controls additionally manage timers, document visibility and focus.

Expand fills the page viewport and preserves browser tabs, filters, selection
and pagination. Escape closes the active popup first, then collapses the view;
the toolbar also provides Collapse. Within an iframe expansion stays in that
frame. These display preferences are not saved to the instance. Storybook's
**Automatic refresh and page expansion** example demonstrates both controls.

## FilterPanel

```tsx
import { FilterOperator, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  type FilterFieldDefinition,
} from '@ahoo-wang/fetcher-view-engine';
import { FilterPanel } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

const fields: FilterFieldDefinition[] = [
  { field: 'amount', label: 'Amount', type: 'number' },
  { field: 'status', label: 'Status', type: 'string' },
];

export function OrderFilters({
  onQuery,
}: {
  onQuery: (expression: FilterExpression) => void;
}) {
  return (
    <FilterPanel
      fields={fields}
      defaultValue={createFilterConfiguration(
        newFilterNode(FilterOperator.MATCH_ALL),
      )}
      onApply={({ expression }) => onQuery(expression)}
    />
  );
}
```

Simple mode uses an implicit AND at the root and within each ELEMENT_MATCH scope, including nested arrays; advanced mode structurally edits all 50 Wow operators including AND / OR / NOR / ELEMENT_MATCH. Editing, clearing, undo and mode switches make no requests. Query calls `onApply` with `{configuration, expression}`. The host executes the request and supplies `querying` / `queryError`. Use `onPendingChange` to show unapplied query edits; use configuration validity to guard saving.

Simple mode allows one condition per field within each scope, including unset values. Element predicates show “same element satisfies” with child filters and no logical-group selector. Empty element scopes remain editable but block Query until a child condition is added. Advanced AND/OR/NOR groups allow multiple conditions on the same field, including inside element scopes. Repeated bindings are valid for compilation and instance persistence; they keep the editor in advanced mode until each field occurs once per scope and the tree is otherwise simple.

Add filter opens an anchored Popover with grouped checkboxes, keeping the table and query toolbar in place. Its height is capped and its field area scrolls internally. It stays open for continuous additions; Done or Escape closes it and returns focus to Add filter. Clicking outside dismisses it. Set `group` on field definitions to group choices in definition order. Ungrouped fields appear under Other fields when mixed with named groups. Checking a field adds its condition; unchecking removes that field's direct conditions in the current group. Checkbox state follows the draft even when a value is unset. Advanced mode shows each selected field’s condition count and an adjacent Append condition action for additional same-field predicates in any logical group; advanced mode places AND/OR/NOR in the adjacent icon dropdown instead of the field picker. Root-level operators remain add actions. Logical menu choices respect the definition allowlist. Changes apply only on Query.

Fully unset values keep their controls but produce no predicate; partial values, invalid data and missing extensions block Query. Fields bind when added and remain in their original group and scope. `extensions.filters` supplies local custom editors. `value` / `onChange` lets the host own the configuration per instance; `defaultValue` instead gives the panel local ownership. `appliedValue` supplies an accepted configuration baseline. These snapshots share the same component tree; mode lives in configuration.mode. Custom components publish serializable `props` through `onChange(props)`; saveable UI state such as selected IDs and display labels belongs there. Serialize recoverable raw buffers in component props; local-only React state does not survive unmounts.

Simple mode has no condition action menu or ordering controls. Advanced mode supports adding, deleting and editing groups without moving conditions between groups. Built-in scalar rows omit Clear and Special value buttons; deleting input text leaves an unset value. Use null/empty-string operators for those predicates. Existing datetime edits retain their original offset in repeated DST hours, while dates in other seasons use their actual offset. Without an applicable offset hint, repeated local times choose the earlier occurrence independently of the system timezone; nonexistent DST times remain invalid.

For composition, `FilterPanel.renderToolbar` replaces the default heading and receives
`FilterPanelToolbarProps`: `panelId`, `mode`, `options`, `pending`, `disabled` and
`onModeChange`. Use its guarded callback and disabled options; incomplete conditions, repeated fields within a scope, OR/NOR and nested logical groups
cannot switch to simple. Supported element scopes switch without changing the tree. `collapsed` hides only the body and
retains editors. `RecordView.toolbarStart` accepts leading global-toolbar content;
`ViewPage` supplies saving, title and instance navigation there.

Each filter component definition registers its `component`, pure `compile(props, context)` and optional `clear(props, context)` together in `extensions.filters`. This is the only filter registration entry for `ViewPage`; it derives the engine capabilities from those definitions, keeping rendering and compilation consistent for the scope. `FilterCompiler` is the minimal React-independent capability contract used by a directly constructed `ViewEngine` through `filterCompilers`, not a second registration for React applications. Builtin-compatible editors can reuse `compileBuiltinFilter` and `clearBuiltinFilterProps`. Complete components receive `onClear` only when clear semantics exist.

Custom editors can use any React component. Register `render: 'value'` (the default) for the value area, or `render: 'filter'` with `FilterComponentProps` to own the complete non-container UI. The panel continues to validate bindings and capabilities, display errors, and apply only on Query.

## Individual controls

```tsx
import { useState } from 'react';
import {
  FieldFilter,
  InputGroupInput,
} from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function AmountFilter() {
  const [operator, setOperator] = useState('GTE');
  return (
    <FieldFilter
      field={{ field: 'state.amount', label: 'Amount' }}
      operator={operator}
      operators={[
        { value: 'EQ', label: 'Equals' },
        { value: 'GTE', label: 'At least' },
      ]}
      onOperatorChange={setOperator}
    >
      <InputGroupInput
        aria-label="Amount value"
        type="number"
        defaultValue="1000"
      />
    </FieldFilter>
  );
}
```

`FieldFilter` fixes the field label and delegates the value editor to its children. Operator selection only calls `onOperatorChange`; the host owns validation, pending edits, applying a query and saving a view. `FilterSelect` also supports controlled enum values, field-add pickers and logical group choices. Pass `onClear={() => setValue(null)}` to offer Clear selection in its popup; omit it for required operator selectors.

`FilterSearchSelect` provides a select popup with built-in candidate search using Base UI Combobox. It shares `FilterSelect` props and adds `searchPlaceholder` / `emptyText`; typing filters local candidates, while selection and clearing update only the editor. See the searchable customer extension in Storybook.

## Entries and theme

- The core entry exports field/configuration contracts and filter compilation helpers without React, DOM or CSS.
- `/react` exports `ViewTheme`, `ViewThemeStyle`, `FilterPanel`, `FilterValueEditor`, individual filter controls and composition primitives.
- `/styles.css` contains compiled, prefixed component styles and the default Neutral appearance. `/themes/{neutral,blue,violet,green,orange,shadcn}.css` adds selectable themes. Consumers do not need Tailwind. React 19 is required when using `/react`.

Importing a theme only makes it available; select it with `data-fve-theme` or `ViewTheme.theme`. Import order does not select a theme. Theme names are open strings, so custom CSS uses the same contract:

```tsx
import { ViewTheme } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import '@ahoo-wang/fetcher-view-engine/themes/blue.css';

<ViewTheme theme="blue" appearance="system" density="compact">
  <ViewPage {...props} />
</ViewTheme>;
```

`appearance` accepts `light`, `dark` or `system`; `density` accepts `comfortable` or `compact`. Omitted values inherit. `ViewThemeStyle` accepts normal React CSS properties plus typed `--fve-*` variables. Plain CSS remains the base interface: use `.fve-root[data-fve-theme='brand']` for an arbitrary custom theme. An unknown or unloaded name does not throw and falls back through CSS inheritance/defaults; that fallback does not prove the theme file was imported.

Use `px` or `rem` for `--fve-font-size`; `em` and `%` compound through the semantic text scale. Other public size variables may use `em` relative to the effective font. When `--fve-line-height` is unset, the root uses `1.5` and semantic text styles retain their upstream ratios; setting it overrides those ratios.

The `shadcn` theme reads complete CSS colors such as `oklch(...)`, `hsl(...)` or `#hex` from the host's semantic variables. It does not parse legacy bare HSL channels. Missing tokens use library fallbacks, while present invalid or cyclic values follow normal CSS invalid-value behavior. The host still owns global preference, persistence and `.dark`; a local light marker cannot reconstruct light tokens when the host only defines dark values.

Select menus, dropdown menus and Popover panels use a body portal so clipping ancestors do not hide them. Each opening, including a controlled `open` change, copies the control's current theme tokens, explicit theme marker, color scheme and typography to the portal. Dark variants use native CSS container style queries to respect the nearest explicit theme, including light sections inside a dark page and dark sections inside light sections. A `data-theme` value takes priority over `.dark` on the same element. This requires modern browsers with container style query support; no legacy compatibility layer is included. While open, ancestor theme class, data-theme and inline style changes are reflected in the popup; closed popups do not observe ancestors. `SelectContent.container` is available for hosts that explicitly choose another Select portal target.

Variable themes propagate to library portals. Structural selectors such as `.brand [data-slot=...]` do not cross a body portal, and arbitrary CSSOM stylesheet replacement without an attribute/class/inline-style change is not observed. Third-party portals must use that component's own theme-container support. Define aliases and derived values at the target theme boundary: CSS custom-property references are resolved before inheritance, so a child cannot be assumed to recompute an inherited derived value after changing its inputs. Change paired colors such as `--fve-primary` and `--fve-primary-foreground` together. The complete public variable table and region callback contracts are in the [API reference](../../skills/fetcher-view-engine/references/api.md).

`ViewPage`, `ViewPageContent` and `RecordView` accept `renderToolbar` and `renderPagination`. Each callback receives readonly state, the default region node and instance-bound controlled operations. Return the default node to preserve it, wrap it to compose UI, or return `null` to hide it. Return a component when the extension needs Hooks or local state.

`FilterDatePicker` uses the shadcn Calendar with a Chinese locale and a controlled `Date | undefined`. `FilterTimeInput` combines a text input with hour/minute/second Select controls; it preserves incomplete input and accepts `HH:mm` or `HH:mm:ss`, with whole-second precision at most. Restored fractional clock values are truncated to seconds when displayed or edited. Both accept `inline` for composition inside `FieldFilter`. The host owns timezone conversion and applying the query. Unset values are valid: keep the editor visible and omit its value-dependent predicate on Query. If no predicates remain, apply `filter.matchAll()`. A date/time pair is unset only when both parts are empty; partial values require completion. Clock selectors preserve the other typed segments during partial input. Malformed nonempty input remains invalid; value-free operators and explicit null/zero/false literals retain their Wow semantics.

## Development

React Compiler is enabled for the library build through the existing Vite `reactCompilerPreset`. It targets the React 19 runtime exposed as `react/compiler-runtime`; consumers do not install the compiler. Pure derived values, table JSX and action callbacks rely on compiler memoization. Two explicit caches remain for Effect dependency stability: the controlled filter draft clone and portal theme capture. Permissions and host capabilities are observed through `getCapabilitiesSnapshot` and `subscribe`; no component needs a `use no memo` escape hatch. For caller-owned engines, call `updateHost(nextHost)` when callbacks or policy change within the same scope. Replace the engine when the user/tenant/access scope changes. Host policies must be pure; mutating a closure without replacing the host or notifying permission.subscribe does not notify the engine. Commands recheck permissions at execution time.

`test` runs the same suite without compilation and in compiler mode, then checks types. Storybook runs against compiled public exports. Packed verification checks the compiler runtime import in `/react` and rejects React imports in the core entry. The record-cell regression verifies that typing an unapplied filter adds no cell renders in the compiled mode; this is a regression bound, not a claim that every screen becomes faster.

```bash
pnpm --filter @ahoo-wang/fetcher-view-engine build
pnpm --filter @ahoo-wang/fetcher-view-engine test
pnpm --filter @ahoo-wang/fetcher-view-engine test:compiled
pnpm storybook
```

Start at **View Engine → 专项场景 → 查询与筛选 → 组合筛选** in Storybook for business filters, nested element scopes, custom-editor validation, query retry, dark mode and the 50-operator gallery. **View Engine → 专项场景 → 组件与主题 → 日期时间** covers individual date/time controls. The examples cover a combined field with manual query, a calendar, a time value at second precision, incomplete input, unset values and the dark theme. The **View Engine → 专项场景 → 组件与主题 → Select** example covers selecting, clearing and selecting again. Controls expose appearance and disabled states. The combined example uses the browser's local timezone and an epoch-millisecond Wow expression without contacting a service. Rebuild the package after changing its source; these stories consume its public built exports.

See [the API reference](../../skills/fetcher-view-engine/references/api.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

### React and Compiler lint

Run `pnpm lint:view-engine` from the repository root for read-only package and
Storybook checks, or `pnpm --filter @ahoo-wang/fetcher-view-engine lint:check` for
this package. The existing package `lint` command applies ESLint fixes.

Both entry points share the stable official `eslint-plugin-react-hooks` recommended
rules, including Compiler diagnostics. Exhaustive dependencies, incompatible
libraries and unsupported syntax are errors; unused disable directives are errors
as well. No separate legacy compiler plugin is required. The root CI `pnpm lint`
also checks `stories/view-engine`. Explicit parser roots avoid workspace-root inference errors when invoking ESLint
from the repository, package or a worktree.

Regression tests lint invalid Hooks/Compiler examples through all three entry paths
and retain a valid manual-memoization example. See the
[official rule reference](https://react.dev/reference/eslint-plugin-react-hooks).

### Built-in filter components

The React entry exports `FilterMultiSelect`, `FilterRemoteSelect`, `FilterTextValues`, and `FilterDateTimeRange`. Configured fields can use `select`, `multi-select`, `remote-select`, `remote-multi-select`, `text-values`, or `datetime-range` without registering their own components. Explicit custom registrations take precedence consistently for render, compile and clear; unknown names still fail.

Selection IDs are strings or finite numbers; `1` and `'1'` remain distinct. Grouped options use `group`. Single selection uses EQ/NE, multiple selection and text collections use IN/NOT_IN, and date/datetime ranges use BETWEEN. Empty selection contributes no predicate. An incomplete or reversed range is invalid; timestamps follow the global view timezone and existing DST rules.

`FilterDateTimeRange` and the default date/datetime BETWEEN editor use a Date Range Picker. Date-only mode is the default, displaying two adjacent months (stacked with internal scrolling on narrow screens). Datetime fields query complete natural days, including the final selected day and actual DST boundaries. Component properties retain the selected dates; query compilation derives timestamp bounds without replacing those properties.

Set `editor: { name: 'datetime-range', options: { showTime: true } }` to enable time editing through seconds. Start and end values share one compact trigger; dates and times are edited together in the popup. Confirm publishes the draft; Cancel/Escape preserve the existing condition. The default scalar datetime editor also accepts `editor: { name: 'builtin', options: { showTime: true } }`. Time mode accepts `HH:mm` and `HH:mm:ss` and retains valid offset hints. Restored fractional-second strings and numeric timestamps are floored to the start of their second for filtering; selecting or confirming publishes values at second precision. Queries still use epoch milliseconds. Date-only ranges still end one millisecond before the next day, including the entire final second. Table cell data and lower-level Wow protocol values retain their original precision.

Configure timezone once with `ViewDefinition.timeZone`; filters, applied summaries and date/time cells share it. Omit it to use the local runtime timezone. Standalone panels and direct date controls accept `timeZone`; fields have no timezone override. Relative-time predicates also use this global setting.

```ts
// Global view setting; omission uses the local runtime timezone.
const definition = { ...orderDefinition, timeZone: 'Asia/Shanghai' };
// Default date precision:
const dateEditor = { name: 'datetime-range' };
// Explicit date + time through seconds:
const dateTimeEditor = { name: 'datetime-range', options: { showTime: true } };
```

Remote candidates are supplied through `extensions.optionSources`, outside ViewHost. The source object should remain stable during a session; replace it when its data scope changes. `useViewEngine` scopeKey isolates access scopes; standalone panels should use a React `key` when the user/tenant changes.

```tsx
import type { FilterOptionSource } from '@ahoo-wang/fetcher-view-engine';
import { useViewEngine, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';

// sources.users implements search(query, signal) and resolve(ids, signal).
// search returns the existing Wow CursorPage: { list, nextCursor }.
// resolve returns { list, missing }, explicitly accounting for every requested ID.
const sources: Record<string, FilterOptionSource> = { users: userOptionSource };

function RemoteOptionsPage() {
  const binding = useViewEngine({
    definitionId: 'orders',
    scopeKey: 'tenant:user:access',
    host,
    extensions: { optionSources: sources },
  });
  return <ViewPage {...binding} />;
}
// Field: editor: { name: 'remote-multi-select', options: { source: 'users', pageSize: 20, debounceMs: 300 } }
```

The remote component reuses `@ahoo-wang/fetcher-react/core` for asynchronous execution and debounce; candidate responses reuse `CursorPage`. It supports manual load-more, cancellation, stale-response suppression, page deduplication, and independent candidate/label retries. IME composition does not start a search. Search Enter confirms a candidate without submitting the record query.

Saved props contain `value` or `values` plus `selectedOptions` label snapshots. Label hydration is runtime-only: it does not update props, dirty state or record queries. Explicitly missing IDs retain their saved identity; failed hydration is not treated as deletion. Clearing removes selected values and snapshots while keeping the configured filter node.

Text collections split pasted input on newlines, commas and semicolons, trim and deduplicate, and preserve spaces inside identifiers, case and leading zeros. Enter commits a pending token first. This is not a CSV parser. Date-time ranges retain `lowerBound` and `upperBound`; they never automatically expand the end to the end of the day.

Open **View Engine → 专项场景 → 查询与筛选 → 内置筛选器** in Storybook, or the standalone example with `?example=builtin-filters`. `BuiltinFiltersExample.tsx` uses Fetcher with deterministic data-URL fixtures and IndexedDBViewHost to demonstrate selected-label recovery and JSON persistence. Only this data-URL fixture removes URL-template resolution; real HTTP clients keep their usual URL/authentication interceptors.

`getFieldOperators(field)` derives capabilities only from field type and explicit `field.operators`. Field `editor` and definition `filterEditors` are defaults for new nodes; an existing node's `component` is authoritative. Its registration supplies component-specific compatibility checks, so changing a field editor default does not restrict or replace saved components.

### Built-in table cells

`TextCell`, `TagsCell`, `StatusCell`, `LinkCell`, `DateTimeCell`, and `NumberCell` are exported from `/react`. Use them independently or reference their built-in names from `field.cellRenderer` / `column.renderer`; no `extensions.cells` registration is required. Explicit own-property custom registrations override the built-ins. Column references take precedence over field references.

| Renderer    | JSON options                                   | Behavior                                                                          |
| ----------- | ---------------------------------------------- | --------------------------------------------------------------------------------- |
| `text`      | `ellipsis`, `copyable` (both false by default) | Enum display labels, accessible full-text tooltip, copy the original value        |
| `tags`      | `maxVisible` (positive integer, default 2)     | Scalar/array enum labels, typed deduplication, keyboard-accessible overflow popup |
| `status`    | `tones: [{ value, tone }]`                     | Enum label plus `neutral`, `success`, `warning`, `danger` or `info` tone          |
| `link`      | `hrefField`, `newTab` (default false)          | Address from the value or another record field; text remains the bound field      |
| `date-time` | `locale`, `dateStyle`, `timeStyle`             | Field type/timezone; styles are `full`, `long`, `medium` (default), `short`       |
| `number`    | None; use `field.numberFormat`                 | The same numeric/currency/percent format as summaries                             |

```tsx
import { NumberCell, TextCell } from '@ahoo-wang/fetcher-view-engine/react';

<TextCell value="ORDER-0001" ellipsis copyable />;
<NumberCell
  value={0.125}
  format={{ style: 'percent', maximumFractionDigits: 1 }}
/>; // 12.5%
// Field: { field: 'amount', label: 'Amount', type: 'number',
//   numberFormat: { style: 'currency', currency: 'CNY' },
//   cellRenderer: { name: 'number' } }
```

Empty values render `—`; zero and false remain values. NumberCell accepts finite numbers, not formatted strings. Date-only `YYYY-MM-DD` stays a calendar date without timezone shifting; epoch zero is valid. Date/time text accepts YYYY-MM-DD with an optional T/t or whitespace separator and HH:mm[:ss[.fraction]], followed optionally by Z/z or a numeric offset. Other formats are rejected. Timestamp-less local date/time strings use the global view timezone and the existing DST validation; fractional seconds beyond three digits are rejected instead of falling back to the machine timezone. Invalid dates/numbers render a placeholder. Invalid component options are configuration errors, isolated by the table's existing renderer boundary.

LinkCell allows HTTP(S), mailto, tel and relative URLs after URL parsing; unsafe addresses render as text. New tabs always include `noopener noreferrer`. Application routing remains a custom component. Copy uses the raw value, not the enum label or ellipsis; clipboard rejection/unavailability shows a local retry message. TextCell's optional `text` only changes display. Nothing in these interactions mutates the view or triggers a query.

Status theme tokens are `--fve-success`, `--fve-warning`, `--fve-info` and the existing `--fve-destructive`; labels remain visible without relying on color. Popups inherit the active theme. See **View Engine → 专项场景 → 组件与主题 → 内置单元格** and `examples/react/BuiltinCellsExample.tsx` for standalone usage, dark/narrow layout, invalid data and IndexedDBViewHost reload recovery.

Creation does not modify the default-instance preference. `MemoryViewHost` preserves explicit `defaultInstanceId: null`; only a previously selected default that is no longer visible falls back to an available instance. Deleting an absent instance in the current access scope succeeds without affecting another user's private view; existing visible instances still require permission and the correct revision. Pending creation state belongs to the current engine lifetime; callers of the service keep their own request ID across client reconstruction.

If the source of an unconfirmed creation disappears from a full list response, `getSnapshot().pendingCreates` retains its editor context separately from visible views. Use `reloadInstance(sourceId)` to reconcile the original request; `ViewPageContent` provides the recovery action. These entries contain no business rows and cannot be queried or saved as ordinary instances. Reload preserves local title/configuration and filter drafts, while taking the authoritative returned `scope`.

Remote label snapshots change for an ID when that ID is added or reselected, using the selected candidate. Hydration and changes to other IDs do not overwrite its saved label. Text collection paste honors the current caret/selection before splitting values. Invalid range endpoint structures report validation errors instead of becoming an unset predicate.

Record views support table and card layouts with `record.defaultPresentation` presets. Use `resolveRecordPresentation` to construct a presentation, and `setLayout` / `setCardConfig` to edit it. Switching back preserves the previous configuration. See [table and card guide](https://fetcher.ahoo.me/guides/view-engine/table-and-runtime).

Use `renderCard(context)` for custom card content while the library retains grid, selection and paging. Layout switching uses a current-mode dropdown in the global toolbar at every width.

`ViewDefinition.record.allowedLayouts` is required when record capability is declared and must be nonempty and unique: `['table']`, `['card']`, or both. A single allowed layout hides the toolbar switch. The engine and instance loading reject disallowed active layouts. Switching preserves each layout's configuration. Cards use a top-right selection button (`aria-pressed`) without a separate row; custom content should leave this corner clear. Global controls use icons with hints; menus retain text.

## Pure analysis compilation

The core entry exports `compileAnalysis(config, context)`, `validateAnalysisResult(rows, plan)` and `analysisRowKey(row, dimensions)`, together with the analysis model types. These functions do not render React or make requests. The following example compiles COUNT + SUM and validates a supplied response; it does not depend on analysis page or engine integration.

```ts
import { AggregationFunction, FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  compileAnalysis,
  createFilterConfiguration,
  newFilterNode,
  validateAnalysisResult,
  type AnalysisCompileContext,
  type AnalysisViewConfig,
} from '@ahoo-wang/fetcher-view-engine';

const context: AnalysisCompileContext = {
  fields: [{ field: 'amount', label: 'Amount', type: 'number' }],
  capability: {
    count: true,
    fields: [
      { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
    ],
  },
};
const config: AnalysisViewConfig = {
  filters: createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
  dimensions: [],
  metrics: [
    {
      id: 'count',
      component: { name: 'count' },
      alias: 'orders',
      title: 'Orders',
      props: {},
    },
    {
      id: 'sum',
      component: { name: 'numeric' },
      field: 'amount',
      alias: 'revenue',
      title: 'Revenue',
      props: { function: AggregationFunction.SUM },
    },
  ],
  sort: [],
  limit: 100,
  presentation: {
    layout: 'table',
    columns: [{ alias: 'orders' }, { alias: 'revenue' }],
  },
};
const compiled = compileAnalysis(config, context);
if (!compiled.plan)
  throw new Error(compiled.errors.map(error => error.message).join('; '));
const result = validateAnalysisResult(
  [{ orders: 2, revenue: 125 }],
  compiled.plan,
);
if (!result.rows)
  throw new Error(result.errors.map(error => error.message).join('; '));
console.log(compiled.plan.query, result.rows);
```

`AnalysisViewConfig` keeps `filters`, `dimensions`, `metrics`, alias-based `sort`, `limit` and table `presentation.columns`. Each component has a stable `id`, persisted `component` reference, optional `field`, output `alias`, display `title` and raw JSON `props`. Incomplete text can remain in configuration, but compilation returns errors and no executable plan.

`AnalysisCompileContext` supplies filter `fields`, explicit `capability`, optional `timeZone`, `allowedOperators`, `filterCompilers` and custom analysis `compilers`. Capabilities separately authorize COUNT, field grouping, numeric functions and date units. Built-ins are `terms`, `histogram` (`props.interval`), `date-histogram` (`props.unit`), `count`, `numeric` (`props.function`) and `any` (an authorized scalar representative value). Date grouping requires an explicit timezone. Custom `AnalysisCompiler` registrations require a nonempty, unique `roles` array (`['dimension']`, `['metric']` or both). `AnalysisComponentCompileContext` extends `AnalysisCompileContext` with the actual readonly `role`; custom compilers and editors can branch on it. Unsupported roles are rejected before compilation. `compile` returns one group or metric, which core validates against its field, alias and capability. The engine and React adapter snapshot and freeze role metadata for each lifecycle.

`compileAnalysis` returns `{ plan?, errors }`; a successful `AnalysisPlan` contains the Wow `query`, matching `schema` and optional `timeZone`. Group aliases omitted from sort are appended in ascending order for deterministic ordering. Default ceilings are 32 groups, 64 metrics, 32 effective sort items and 10,000 rows; capability limits may tighten them. At least one metric is required. Ungrouped analysis has no sort and admits at most one result row.

`validateAnalysisResult` returns `{ rows?, errors }`, rejecting the whole result on missing aliases, invalid types, excess rows or duplicate typed dimension tuples. It reads aliases as literal own keys, retains only schema columns, permits null only for nullable columns, and requires COUNT to be a nonnegative safe integer. Date bucket values are epoch milliseconds. Call `analysisRowKey(row, plan.schema.filter(column => column.role === 'dimension'))` on a validated row for its typed dimension identity.

### Text value buffers

`FilterTextValues` accepts `value?: readonly string[]`, optional controlled `rawText?: string`, `onRawTextChange?(text)` for raw typing, and `onValueChange(values, rawText)`. Without `rawText` it owns a local input buffer. Enter/paste commits the new value collection with an empty buffer in one callback; removing a chip returns the remaining values with the current buffer. A controlled caller must update both values and rawText from that callback. IME confirmation does not submit a token or query.

The registered `text-values` editor serializes every raw edit in `props.rawText`, so unconfirmed input survives instance navigation. Nonempty trimmed rawText prevents pure compilation; a non-string rawText is invalid. Confirmation removes rawText while retaining confirmed values; clearing removes both values and rawText. Whitespace-only input contributes no unconfirmed value. Standalone `onValidityChange(valid, message?)` remains optional; registered validity comes from compilation.

`AnalysisPlan.schema` preserves query output order and semantic metadata. `projectAnalysis(plan, rows, presentation).plan` applies `presentation.columns` alias order and optional positive CSS-pixel width, then appends unspecified outputs without hiding columns. Query compilation does not depend on presentation compatibility. `analysisRowKey` canonicalizes dimension order by alias, so presentation reordering preserves row identity. Histogram `props.interval` accepts complete positive numeric text while keeping the original editor string; the compiled interval is a number.

## TypeScript consumption boundary

Validated with TypeScript 6.0.3 in both ESNext + Bundler and NodeNext modes, with strict checking and `skipLibCheck: false`. Packed public exports preserve the negative assertions for invalid aggregation metrics and missing engine scope. Relative module references throughout the Wow/React dependency chain use explicit `.js` paths, including `/index.js` for directory exports.

## Analysis charts and Wow API integration

`AnalysisPresentation.layout` supports `table`, `metric`, `bar`, `line`, `area` and `pie`. A bar can use `orientation: 'horizontal'`; `donut: true` renders a ring. `x`, `series` and `metrics` reference output aliases. The same verified result can be displayed in different ways without querying again. Saving persists configuration; **Run analysis** refreshes data. While a query draft differs, the previous result retains its executed scope and presentation.

Switching to the data table retains the chart axes, series, metric selection and display preferences for the return trip. Deleting an output removes its display references; deleting the last selected metric restores metric defaults, while an explicitly empty user selection remains editable. Selecting an available metric also removes stale aliases. Positive and negative SUM values stack separately around zero in both bar and area charts; null/missing combinations report incompatibility while the data table remains available. Changing the layout never truncates a valid metric selection or replaces an explicitly empty selection. A multi-metric pie request shows its compatibility message until the user explicitly selects one supported metric; returning to the previous layout retains the selection. Optional defaults are resolved when rendering rather than written into the draft, so a layout round trip does not create an otherwise unchanged dirty view.

`projectAnalysis` is a pure projection with no transport or persistence. It preserves typed grouping identity and null values, never averages averages or invents zero buckets. Charts are limited to 500 returned groups and 12 series. Incompatible units, additional unrepresented dimensions, numeric ANY values or unsupported chart shapes explain the problem and show a table. Pie and stacked charts require additive COUNT/SUM metrics; null stacking and all-zero pies also fall back. The data table pages already returned rows locally.

`AnalysisView` loads shadcn Chart/Recharts on demand. Query configuration uses an accessible right-side Sheet at every viewport size; visualization settings fold independently on the left. Results show executed root/element filters, timezone and returned limits. Date axes have compact ticks; tooltips and data tables retain complete values. `formatAnalysisValue(value, column, timeZone)` handles enum labels and display-only numeric formatting. `capability.fields[].numberFormat` accepts Intl number options plus `locale`; COUNT keeps exact integer formatting. Chart colors use scoped `--fve-chart-1` through `--fve-chart-5` tokens.

`adaptWowAnalysisSchema(schema, { labels?, units? })` maps a current Wow Schema into filter fields and aggregation capabilities. Masked, unknown/union values, scalar arrays and unsupported temporal encodings are not advertised. Valid homogeneous `enumValues` become typed field options. Authorized object-array `ELEMENT_SCOPE` paths become predefined `capability.scopes`; `config.scope` selects one and supplies a filter for each element layer. Root filters remain root-relative; each element filter and final aggregation field is relative to its selected element.

When `capability.expressions` permits it, a numeric metric can carry a bounded FIELD/CONSTANT/BINARY `expression` (depth 8, at most 256 nodes). ANY is a representative scalar shown in the table, never a stable group/series identity or numeric chart metric. `AnalysisEditor` accepts filter extensions/context and reports combined element-filter validity; the full `AnalysisView` combines root and element editing validity before run/save.

Reuse `SnapshotQueryClient` or `EventStreamQueryClient` with the application's existing authenticated `Fetcher` as the host source. See the [compensation dev example](examples/react/compensation/README.md) for Schema discovery, real read-only queries, cancellation, local view persistence and opt-in integration tests. Root event-stream COUNT counts batches; COUNT after expanding the `body` scope counts event entries. Storybook **View Engine → 专项场景 → 分析图表** supplies offline chart scenarios; **补偿 API 分析** connects only after explicit selection.

Production acceptance is reproducible with `VIEW_ENGINE_BROWSER_CHANNEL=chrome pnpm verify:view-engine` (or an installed Playwright browser). The existing verifier builds and serves static Storybook, checks host and accessibility contracts, then enforces analysis input P95 ≤300ms and cached-instance switching P95 ≤350ms at 100 fields/20 filters/100 rows. It also checks input retention and cancellation with 10,000 rows ×21 columns. Set `VIEW_ENGINE_ARTIFACTS` to retain raw samples and environment metadata; development-mode timing is not production acceptance.

Analysis uses three regions: a right-side query Sheet, central results, and a left visualization panel collapsed by default. The query Sheet keeps its editor mounted across closing and resizing; closing preserves drafts and neither saves nor runs. Select a chart type before configuring visualization fields. Unconfigured analysis defaults to the data table; saved charts retain their presentation. The bottom Analysis / Table modes are always available, even without a chart or result, and switching does not query or save. Chart incompatibility stays in Analysis mode with an explicit Table action. Dimensions and metrics keep their summary chips, retained popover editors and keyboard ordering.

Analysis configuration keeps a single mounted editor subtree across desktop/dialog placement and dialog closure, preserving extension-local drafts and validity. `DialogContent.keepMounted` (default `false`) retains hidden dialog content when needed. Pie/donut charts include persistent per-group values and shares of returned groups; values remain unchanged and ratios are normalized before summation to avoid overflow.

View-kind icons are consistent in the sidebar, instance selector and view manager, with accessible type descriptions. Chart and metric results use centered bottom Analysis/Data table tabs. Switching tabs is local, does not query or save, and retains the returned-table page after its first opening. Unsupported chart configurations show their reason in Analysis mode and offer an explicit action to view the data table; neither mode is disabled or selected implicitly.
