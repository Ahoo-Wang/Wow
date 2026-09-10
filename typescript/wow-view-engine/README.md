# Fetcher View Engine

View Engine owns further data-view development. `@ahoo-wang/fetcher-viewer` is deprecated and in maintenance mode, with no new features; use this package for new projects. The packages use different models and APIs, so migration requires adaptation.

[Task guides](../../wiki/guides/view-engine/index.md) · [API reference](../../wiki/reference/view-engine/index.md) · [Shared runnable example](../../wiki/examples/view-engine.md)

Independent `@ahoo-wang/fetcher-view-engine` package with headless Wow filter compilation and validation, a complete `FilterPanel`, structured value editors, and shadcn/Base UI controls. It also provides a headless ViewEngine and a complete RecordView page with host-managed definitions, instances and persistence. Cards, AnalysisView and DashboardView remain separate work.

## Module responsibilities

The public `ViewEngine` composes internal services; applications use its public commands and snapshots. The core runtime imports no React, DOM or table component library.

| Module                                                     | Owns                                                                                                                     |
| ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `record/engine/SessionStore`, `sessionState`               | Immutable snapshots, subscriptions, saved/editing baselines and derived dirty/pending state.                             |
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

`examples/react/FilterPersistenceExample.tsx` saves the selected status ID and an independently edited display name. Open `http://127.0.0.1:4175/?example=persistence`, or **View Engine → 专项场景 → 视图与运行时 → 配置与恢复 → 公共包 · 组件配置 JSON 保存与重新打开**, to add an unset control, save it without querying, and reopen the JSON in a new engine. Changing the display name can also be saved directly; changing the status requires Query before Save.

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

The fixture uses 100 loaded rows, 30 data columns and 100 filter candidates at 1440px/390px in light/dark themes. It checks keyboard query/selection, retry and repeated disposal. Refresh, selection and field-picker warm interactions each record 10 samples with a 1000ms p95 regression ceiling, including automation transport and paint settlement; this is not a business-network latency SLA. Larger workloads need consumer measurements; virtual scrolling or arbitrary scale is not promised.

Raw axe findings are retained. WebKit's hidden Base UI focus-sentinel naming diagnostic is recorded as [upstream expected behavior](https://github.com/mui/base-ui/issues/5237), with actual keyboard entry, Tab exit and Escape restoration checked separately. Other violations fail acceptance. This does not replace real VoiceOver/mobile-device testing.

## RecordView

```tsx
import type { ViewHost } from '@ahoo-wang/fetcher-view-engine';
import { ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';

export function OrderPage({
  host,
  scopeKey,
}: {
  host: ViewHost;
  scopeKey: string;
}) {
  return (
    <ViewPage
      scopeKey={scopeKey}
      definitionId="orders"
      host={host}
      selectable
    />
  );
}
```

`ViewHost` is a composition facade, not a REST API containing every operation.
`definition` (`ViewDefinitionService`) loads metadata; `instance`
(`ViewInstanceService`) lists, loads, creates, saves, renames and deletes saved
views; `preference` (`ViewPreferenceService`) saves the current user's order;
`permission` (`ViewPermissionService`) projects and refreshes grants.
Each service can be provided independently; omitted services/methods disable
that capability. `resolveSource` remains a local runtime bridge.

```ts
const host: ViewHost = {
  definition: definitionService,
  instance: instanceService,
  preference: preferenceService,
  permission: permissionService,
  resolveSource: id => businessSources[id],
};
```

The service contracts live in `src/record/ViewHost.ts`, separately from record
metadata. MemoryViewHost implements them with a shared storage transaction.
HTTP remains an internal development experiment, not part of the public package.
When replacing only one method, merge its service explicitly, for example
`instance: {...host.instance, save: customSave}`.

`ViewHost` loads the definition and complete instance list, resolves a configured
Wow query source, supplies permissions, and optionally saves/creates instances.
Instance lists require `defaultInstanceId: null` or an ID present in that list.
If supplied, `revision` must be a nonblank string. Invalid responses fail at the load boundary.
For local data, pass `definition` and `instances: {instances, defaultInstanceId}`
to the page. Required `scopeKey` identifies the user/tenant/access scope; change
it when that scope changes. The engine lives for `[scopeKey, definitionId]`.
Same-scope host callbacks and capabilities update without discarding drafts.
Local definition/list values initialize that lifetime; changing their object
references does not reload them. Change the React key to explicitly reinitialize.
`ViewPage` owns the engine lifecycle; use
`ViewPageContent` or `RecordView` with an existing engine when the host owns it.

Each `engine.load()` initializes permissions alongside metadata: it awaits
`permission.load(definitionId, signal)` when available, otherwise `permission.refresh(signal)`.
The provider owns the permission projection and must initialize its synchronous getters
before resolving. Initialization failure prevents ready state and record queries; retry
uses `load()`, and disposal/reload aborts the initialization signal. Providers with only
synchronous getters need no initialization. Same-scope `updateHost` expects a prepared
projection; subsequent asynchronous changes notify `permission.subscribe`.

A `RecordQuerySource` supplies `paged`, `cursor`, or both. `aggregate` remains optional.
Unsupported saved pagination modes fail explicitly before record/aggregate dispatch;
paged-only adapters do not need a throwing cursor stub.

Engine subscriber exceptions are reported through `console.error` and do not interrupt
writes or other subscribers. Filter compilation depends on draft/applied-filter/validity
changes; selection, query status, summaries and title-only edits do not recompile it.
All five extension registries resolve only explicitly registered own properties.
Persisted component names identify stable property/compile semantics. Use a new name
such as `order-status/v2` for an incompatible change and keep the old registration while
old configurations exist. Unknown registrations remain blocked; no automatic migration
framework is implied.

Instances persist `config.filters: {mode, root}`. Each component stores a stable
configuration ID, `{name, options?}` reference, operator, field binding, raw JSON
`props`, and any child components. The compiled query lives only in
`session.appliedFilter`; `null` means compilation has not succeeded and blocks
record and aggregate requests. Saved JSON never reconstructs components from a
compiled expression. Object properties with `undefined` are omitted; explicit
null, false, zero and empty strings survive JSON. Non-JSON values are rejected.

`setFilterDraft(configuration, id?, valid?)` compiles through the registered pure functions.
Valid edits producing the same applied query immediately update the accepted
configuration and editor baseline, enabling Save without a request. Adding an
unset control or changing its display label follows this path. Changed query
values and invalid local input set `filterPending` and block Save until Query
accepts the draft or Undo restores it. `setFilterMode` persists supported mode
changes when synchronized. `dirty` compares accepted configuration with saved JSON.
`sameFilterQuery` ignores object key order and redundant singleton AND/OR wrappers;
other expression differences still require Query.

Programmatic clients call `setFilterDraft(configuration)` before `applyFilter(id?)`.
Editing and accepted filter snapshots both use `FilterConfiguration`, including mode. `applyFilter` rejects invalid editor buffers
without changing the draft or applied query. `setFilterValidity(true)` cannot
make a changed or uncompiled query eligible for Save.

Core snapshots use `DeepReadonly` for definitions, instances, drafts and records.
Read these values directly, or pass them back to `setFilterDraft`,
`setSort` and `setColumns`; the engine copies accepted inputs. Build edits as new
objects. Host query/write requests receive independent, editable DTOs.

The table uses shadcn Table + TanStack Table with server sorting and paged or
forward cursor queries. Resize at table-header edges by dragging, with keyboard arrows as an accessible alternative. Reorder columns by dragging their handles within the same
fixed region, or focus a handle and use Up/Down keys; show/hide columns there too.
Dropping commits the order; cancelling leaves it unchanged. Presentation edits never
query. Only Query applies filter edits. The page retains drafts per instance and
guards save/save-as while filters are pending. Save-as supports personal/shared
instances; permissions and actual persistence remain with the host.

The compact workbench orders its global toolbar as title, current instance and Save split button, with Save As/Restore in the menu. Creation stays on the right.
The active instance has no separate edited badge; save enablement reflects the guarded draft state.
Successful saves briefly show a check and “已保存”, with an accessible announcement.
Personal and public views form two navigation groups; system views carry a System badge.
**Manage views**, beside the sidebar heading and inside the view switcher dropdown, combines inline
name editing, confirmed deletion and within-group drag ordering. Names display
as text until Edit is clicked. Save or Cancel returns to text; Escape cancels
only the current name edit, while failed saves keep the input for retry. System views
cannot be renamed or deleted; their personal display position can still change.
Implement `host.instance.rename(id, title, revision?)` / `instance.delete(id, revision?)`
and grant `rename` / `delete` through `permission.getInstance`. Renaming changes
only persisted metadata, retaining pending filters and unsaved column configuration.
The optional `preference.saveOrder(definitionId, ids)` stores ordering for the fixed
current user, including public views; it must not change other users' ordering.
Writes persist before updating the list and failures keep edits available for retry.
Full `engine.load()` rejects while save, rename, delete or preference ordering is in flight;
commands that read or edit sessions are rejected until loading finishes, including cancellation callbacks. Successful Save As and reconciliation complete
independently of the subsequent record request, whose failure stays in `queryError`.
After a dispatched save, rename or delete returns `UNKNOWN_OUTCOME`, `UNAVAILABLE`
or an unclassified exception, the engine blocks unrelated writes to that instance and retains
local edits. Save/rename require successful reload; an absent or inaccessible instance
retains its recovery error and edits. Unknown creates can replay their original request
ID, and unknown deletes can replay the same ID/revision. Subscribed UI controls read
`getCapabilitiesSnapshot().instances[id].retryDelete`. Hosts should use definitive
`ViewServiceError` codes for known rejections.
The headless engine exposes `renameInstance(title, id?)`, `deleteInstance(id?)`,
`canReorderInstances()` and `reorderInstances(ids)`. Save As and Restore remain
in the original split button; its standalone Delete entry is removed.

When saving the current instance is not permitted, Save As becomes the primary action.
Save As uses radio buttons with descriptions for Personal (visible only to you)
and Public (visible to users with access). Unavailable scopes remain visible but
disabled, and the initial selection uses an allowed scope.
The filter split button toggles the panel and selects simple/advanced mode without
an extra heading row. Add filter sits on the left; Undo, Clear and Query align right.
A separate table toolbar shows the selected count and Clear selection on the left,
with batch actions and column settings on the right. Clear selection
keeps the query and filter draft. Pending edits appear near Query when expanded
and in the filter toggle when collapsed; the current title/sidebar does not repeat
the notice, while other instances retain pending markers. Record counts and
pagination share the footer. Collapsing filters keeps editors, drafts and selection.
A compact applied-filter summary sits below the editor and above the table toolbar,
remaining visible when the editor is collapsed. Top-level AND conditions appear as
separate shadcn Badge tags; OR/NOR and element conditions remain complete groups.
Each tag's close button uses registered clear semantics to unset its values and immediately queries, retaining every
field, operator, group and editor ID. Value-free predicates and custom components without clear semantics have no clear button.
Clearing is disabled while a query is loading or unapplied edits remain; query or
undo those edits first. Enter in a single-line filter input applies the query;
IME composition, selectors, multiline inputs and popup interactions keep their
normal keyboard behavior.
Labels preserve exact thresholds and wrap long expressions; no conditions displays
all records. Pending edits do not replace the applied tags until Query is applied.
The global filter toggle only controls visibility and mode; it has no applied-filter tooltip.

Unpinned string columns without enum options or an explicit `width` share spare container width,
starting from 180px and growing up to 480px. Explicit widths, pinned columns and
other field types keep their configured/default sizes. Narrow tables scroll
horizontally. Dragging an automatic column persists its actual new width. Column settings have
no width input; definitions can omit width to opt into automatic sizing. Container
resizing never edits the instance or queries. Space that cannot be assigned stays
before right-pinned columns, keeping actions at the right edge. The responsive
workbench Storybook scene shows 15 records per page.
Numeric cells and headers align right and use tabular numerals, including cells
rendered by extensions unless the extension overrides their alignment.

When pinned regions leave less than 128px for business fields, the table uses a
temporary compact layout: row keys shrink with full values available in tooltips,
action columns use 64px popover triggers, and ordinary pinned fields scroll with
the center region. Saved widths and pinning return when space allows; key/action
resize handles are available in the regular layout. Extremely small containers or
many mandatory columns show an explicit space warning. This adaptation does not
persist presentation changes.

Query failures render in the record area, with an icon, cause and retry. They do not
show the empty-result icon, zero-record claims or pagination. `engine.retryQuery(id?)`
retries the current page/cursor, as does the record error action; explicit `refresh()`
still restarts cursor pagination at page one. Background failures
retain existing rows and label them as the previous result. Auto-refresh tooltips
explain pauses and what will resume the countdown.

Summary calculation/query helpers consume `RecordSummaryMetric[]` (`id`, `field`,
`function`) independently of table settings. Use
`getRecordSummaryMetrics(instance.config.presentation)` to adapt a table instance.
`ViewInstanceMetadata` holds common instance metadata. `RecordViewConfig` directly
contains `sort`, `pagination`, canonical `filters` and `presentation`;
`RecordTablePresentation` defines its table layout and columns.

The field bound to `definition.rowKey` always stays at the left edge, and action
columns at the right edge. Their sides cannot be changed by saved preferences or
column settings. An unpinned field can be pinned only when exactly one adjacent settings row is
pinned; it inherits that neighbor's side. Neither neighbor pinned, or both pinned,
disables pinning. Ordinary pinned fields can be unpinned; no side selector is shown.
Numeric summaries share the same settings row with visibility and pinning. The contract still accepts
`pinned: 'left' | 'right' | false` from the host, preserving configured sides until
edited. Pin choices persist with the instance. Headers, rows and summaries move together, with
resizing/visibility reflected in their offsets. Selection stays before the key
column. Pin/order changes do not query records or aggregates.

`extensions.cells`, `extensions.globalActions`, `extensions.toolbarActions`, `extensions.rowActions`, and
`extensions.filters` register local React components referenced by JSON names.
Extension inputs use exported `DeepReadonly<T>` snapshots. Copy the fields needed
into a component's own form state, then submit changes through host commands or
engine methods. Unsubmitted filter edits do not rerender the record-cell boundary.
Field definitions and table columns bind full dot paths relative to returned records, such as `customer.name`, `state.amount` and `items.0.name`. Default, built-in and custom cells share the same lookup and receive the resolved `value`. Only own properties are read; missing/null intermediates resolve to undefined and default to “—”, while zero and false are preserved. Bracket syntax, wildcards and automatic object-to-column expansion are not supported.
Action rendering errors recover when the renderer's inputs change, including
selection or query state; unrelated draft edits do not repeatedly retry a failure.
Definitions use `recordActions.global`, `.toolbar` and `.row` to choose their action
areas (for example, create, batch process and inspect record). Existing global
registrations retain their location; move batch components to `toolbarActions` explicitly.
Standalone `RecordTable` requires `appliedFilter` explicitly. Business actions receive
that runtime scope (`filter` is null until compilation succeeds), stable record keys and an
instance-bound refresh callback. Selection contains explicit current-page keys.
Records, definitions and instances must be JSON data; keys must be unique strings
or finite numbers, with no index fallback. The core import remains React-free.

See **View Engine → 专项场景 → 数据展示** in Storybook and the full
[host/renderer API contract](../../skills/fetcher-view-engine/references/api.md#record-views-and-host-contract).
The stories use an in-memory service to demonstrate request/response behavior.

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

`serviceKey` is a trusted tenant/service namespace; `scopeKey` is the trusted user within it. Storage uses `fve:views:${JSON.stringify([serviceKey, definition.id])}`. Public views are shared within this namespace; personal views and display order are isolated by user. Private ownership is service-owned and is not accepted from write bodies. Use an access-scoped ViewPage key that includes both tenant and user; omit ViewPage's local `definition`/`instances` props so loading goes through the host.

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

Headless consumers can call `engine.refresh(id, {background: true})` and observe
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

Simple mode uses an implicit AND at the root and within each ELEMENT_MATCH scope, including nested arrays; advanced mode structurally edits all 50 Wow operators including AND / OR / NOR / ELEMENT_MATCH. Editing, clearing, undo and mode switches make no requests. Query calls `onApply` with `{configuration, expression}`. The host executes the request and supplies `querying` / `queryError`. Use `onPendingChange` to guard view saving.

Simple mode allows one condition per field within each scope, including unset values. Element predicates show “same element satisfies” with child filters and no logical-group selector. Empty element scopes remain editable but block Query until a child condition is added. Advanced AND/OR/NOR groups allow multiple conditions on the same field, including inside element scopes. Repeated bindings are valid for compilation and instance persistence; they keep the editor in advanced mode until each field occurs once per scope and the tree is otherwise simple.

Add filter opens an anchored Popover with grouped checkboxes, keeping the table and query toolbar in place. Its height is capped and its field area scrolls internally. It stays open for continuous additions; Done or Escape closes it and returns focus to Add filter. Clicking outside dismisses it. Set `group` on field definitions to group choices in definition order. Ungrouped fields appear under Other fields when mixed with named groups. Checking a field adds its condition; unchecking removes that field's direct conditions in the current group. Checkbox state follows the draft even when a value is unset. Advanced mode shows each selected field’s condition count and an adjacent Append condition action for additional same-field predicates in any logical group; advanced mode places AND/OR/NOR in the adjacent icon dropdown instead of the field picker. Root-level operators remain add actions. Logical menu choices respect the definition allowlist. Changes apply only on Query.

Fully unset values keep their controls but produce no predicate; partial values, invalid data and missing extensions block Query. Fields bind when added and remain in their original group and scope. `extensions.filters` supplies local custom editors. `value` / `onChange` lets the host own the configuration per instance; `defaultValue` instead gives the panel local ownership. `appliedValue` supplies an accepted configuration baseline. These snapshots share the same component tree; mode lives in configuration.mode. Custom components publish serializable `props` through `onChange(props)`; saveable UI state such as selected IDs and display labels belongs there. Keep only unsaved temporary buffers in local React state.

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

Remote candidates are supplied through `extensions.optionSources`, outside ViewHost. The source object should remain stable during a session; replace it when its data scope changes. `ViewPage.scopeKey` isolates access scopes; standalone panels should use a React `key` when the user/tenant changes.

```tsx
import type { FilterOptionSource } from '@ahoo-wang/fetcher-view-engine';
import { ViewPage } from '@ahoo-wang/fetcher-view-engine/react';

// sources.users implements search(query, signal) and resolve(ids, signal).
// search returns the existing Wow CursorPage: { list, nextCursor }.
// resolve returns { list, missing }, explicitly accounting for every requested ID.
const sources: Record<string, FilterOptionSource> = { users: userOptionSource };

<ViewPage
  definitionId="orders"
  scopeKey="tenant:user:access"
  host={host}
  extensions={{ optionSources: sources }}
/>;
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

Record views support table and card layouts with definition-level `defaultPresentation` presets. Use `resolveRecordPresentation` to construct a presentation, and `setLayout` / `setCardConfig` to edit it. Switching back preserves the previous configuration. See [table and card guide](https://fetcher.ahoo.me/guides/view-engine/table-and-runtime).

Use `renderCard(context)` for custom card content while the library retains grid, selection and paging. Layout switching uses a current-mode dropdown in the global toolbar at every width.

`ViewDefinition.allowedLayouts` is required and must be nonempty and unique: `['table']`, `['card']`, or both. A single allowed layout hides the toolbar switch. The engine and instance loading reject disallowed active layouts. Switching preserves each layout's configuration. Cards use a top-right selection button (`aria-pressed`) without a separate row; custom content should leave this corner clear. Global controls use icons with hints; menus retain text.
