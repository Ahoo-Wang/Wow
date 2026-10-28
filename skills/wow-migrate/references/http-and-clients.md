# HTTP and TypeScript clients, 9.x to V10

The job: the applications that call a 9.x service keep working against its V10 service. V10 changes the routes, headers, errors and the client packages' API (decision D266): there is no compatibility layer, so every caller moves with the service. The order is fixed: the service's source migration produces its V10 Manifest first; the TypeScript clients are then regenerated from that Manifest, and the application code is fixed by what the type check reports. Verify every V10 name against the pinned packages (`node_modules/@ahoo-wang/wow-client/dist/*.d.ts`) and the service's Manifest; the 9.x names below are for recognition only.

## Recognize the 9.x side

`@ahoo-wang/fetcher-wow`, `@ahoo-wang/fetcher-generator` (or the script `fetcher-generator`), a 9.x `@ahoo-wang/wow-client` or `@ahoo-wang/wow-generator` (9.x versions), `@ahoo-wang/fetcher-react`'s Wow hooks, `@ahoo-wang/fetcher-viewer`; in code `CommandClient.send<C>(request)`, `commandHeaders`, `waitStrategy`, `CommandStage`, `QueryClientFactory`, `pagedState`, `singleState`, `getStateById`, `EventStreamQueryClient`, `toWowError`, `ErrorCodes`, `bindingErrors`, `@ahoo-wang/wow-client/legacy` (`Condition`), and routes with `/tenant/{tenantId}/`, `/owner/{ownerId}/` or `snapshot/paged`.

## Packages

| 9.x | V10 |
| --- | --- |
| `@ahoo-wang/fetcher-wow`, or a 9.x `@ahoo-wang/wow-client` | `@ahoo-wang/wow-client` of the V10 version (peers `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventstream`; no `fetcher-decorator`) |
| `@ahoo-wang/fetcher-generator`, a 9.x `@ahoo-wang/wow-generator` reading the service's OpenAPI (`/v3/api-docs`, the `x-wow-*` extensions) | `@ahoo-wang/wow-generator` of the V10 version reading the service's **Manifest** (`GET /wow/manifest` or its committed `manifest/baseline.json`). The OpenAPI-to-Wow path, the `fetcher-generator` alias and `fetcher-generator.config.json` are gone; plain OpenAPI-to-TS generation remains for non-Wow APIs |
| `@ahoo-wang/fetcher-react` Wow hooks, or a 9.x `@ahoo-wang/wow-react` | `@ahoo-wang/wow-react` of the V10 version: `use*Query({ query, execute })` over a generated client's method; no hook takes a route |
| `@ahoo-wang/fetcher-viewer` | `@ahoo-wang/wow-view-engine` with a definition and a host, rebuilt, not renamed ($wow-view-definition, $wow-view-host) |
| The 9.x view store (`/view-store/tenant/{tenantId}/owner/{ownerId}/…`) | `@ahoo-wang/wow-view-store`'s `WowViewStore` on the V10 view store service ($wow-view-host) |

Keep every Wow package on one V10 minor (`~` or exact).

## Routes and headers

| 9.x | V10 |
| --- | --- |
| `POST /{context}/[tenant/{tenantId}/][owner/{ownerId}/]{aggregate}/{id}/{command}` | `POST /{context}/{aggregate}/{id}/{command}` (kebab command); tenant and owner come from the caller, never the path |
| `…/snapshot/{single,list,paged,cursor,count,aggregation}` and their `/state` forms; `…/event/…` | `POST /{context}/{aggregate}/{snapshot\|events}/query` with `kind` in the body (`single`, `list`, `paged`, `cursor`, `count`, `aggregate`) |
| `GET …/snapshot/schema`, `…/event/schema` | `GET` on the same query route (`ETag` = version) |
| The aggregate-management routes: delete, recover, snapshot regeneration | The system commands each aggregate lists in the Manifest, with their routes: `DELETE /{context}/{aggregate}/{id}`, `POST /{context}/{aggregate}/{id}/wow.lifecycle.<kebab>`, `POST /{context}/{aggregate}/wow.snapshot.rebuild` (`{"ids": [...]}`), and a context's jobs `POST /{context}/wow.schedule/{id}/{enable\|reschedule\|disable}`; a client sends each by its Manifest route, never a path it derives |
| A state by id | `GET /{context}/{aggregate}/{id}/state` (`?version=`, `?at=`, `?minVersion=`, `…/state/trace`) |
| `/dataset/…` | `GET /wow/dataset`, `/wow/dataset/{context}/{dataset}/query` |
| `Command-Wait-Stage`, `-Context`, `-Processor`, `-Function`, `Command-Wait-Tail-*`, `Command-Wait-Timeout` | `Wow-Wait` (a plan: `accepted`, `committed`, `snapshot`, `handled([ctx/]Processor.function)`, chained with `>`), `Wow-Wait-Timeout` |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match` |
| `Command-Tenant-Id`, `Command-Owner-Id`, `Command-Aggregate-Id` | From the caller and the route; an allowed caller acts elsewhere with `Wow-Scope`, `Wow-Space` |
| `Command-Local-First`, `Command-Type`, `Command-Header-*` | Gone; request facts the service lists in `wow.http.request-facts` |

Gateways, CoSec policies and API docs keyed on the 9.x paths move too: list every rule that names a 9.x path and its V10 replacement. With `wow.cosec.authorizer.enabled`, CoSec evaluates each command, whatever its entry, as its V10 route in the Manifest (a routeless system command as `POST /{context}/{aggregate}/{id}/{full type name}`), and a policy matching the command by attribute reads `wow.command`, the command's full type name (`shop.cart.AddItem`, a system command qualified by its aggregate type: `shop.cart.wow.lifecycle.DeleteAggregate`).

## Client code

| 9.x | V10 |
| --- | --- |
| `new CommandClient(…).send<C>({ … headers: commandHeaders({ … }), … })` with `waitStrategy({ stage: CommandStage.SNAPSHOT })` | The generated `<Aggregate>CommandClient.<command>(body, { id, wait: WaitPlan.SNAPSHOT })`, or `CommandClient.wait({ command: '<context>.<aggregate>.<Command>', body, id, wait })` |
| `sendAndWaitStream` | The generated `…Stream` method, or `CommandClient.stream` |
| `QueryClientFactory`, `cartQueryClientFactory` | The generated `<Aggregate>SnapshotQueryClient`, `<Aggregate>EventQueryClient`, `<Aggregate>StateClient` |
| `pagedState`, `listState`, `singleState` (state alone) | `paged`, `list`, `single` answer whole records; read `record.state`, or project with a literal `include` |
| `getStateById(id)` | `<Aggregate>StateClient.get(id)` (`read(id, { minVersion })` after a command) |
| `EventStreamQueryClient`: records of `body[]` with `bodyType` (a class name) | `EventQueryClient`: records are commits with `events[]` of `{name, revision, body}`; `name` is `<context>.<aggregate>.<Event>` |
| `@ahoo-wang/wow-client/legacy` `Condition` | Gone: `filter.*` from `@ahoo-wang/wow-client/dsl` |
| `DatasetDirectoryClient`, `DatasetQueryClient` | `DatasetClient` (`list()`, `dataset(context, name)`) |
| `abort` argument, `urlParams.path.{ tenantId, ownerId }`, `contextAlias` | `RequestOptions.signal`; nothing (the caller); `WowClientOptions.basePath` for a gateway prefix |
| `toWowError(error)`, `ErrorCodes`, `errorCode`, `errorMsg`, `bindingErrors` | `error instanceof WowProblemError`: `code`, `status`, `detail`, `violations` (`path`, `code`, `message`), `retry`; `FrameworkErrorCodes` and the generated `<Context>Contract.<Aggregate>.Errors` |
| `useFetcher*Query({ url, fetcher })` (fetcher-react, 9.x wow-react) | The `use*Query` hook with `execute: (query, options) => client.<kind>(query, options)` on the generated query client, created once outside the component; a dataset's client is `new DatasetClient(options).dataset(context, name)` |
| `useListStreamQuery`, a stream executor over `stream` | `useStreamQuery` with `execute: (query, options) => client.streamRows(query, options)`; it also returns `position`, which `requestOptions: { resume: { lastEventId } }` resumes after |
| A cursor page through `useSingleQuery` and `setQuery({ ...query, cursor })`; `client.aggregate` in an effect | `useCursorQuery` (`items`, `hasMore`, `fetchNext()`; its query has no `cursor`) and `useAggregateQuery` |
| A hook's error type argument `E` (`useSingleQuery<R, F, MyError>`) | Gone: `error` is an `Error`, and `problem` the `WowProblemError` when the service answered with one |
| A failed command resolving with an error code | Every command that did not succeed rejects: a rejected one with a `CommandRejectedError` (`code`, `result`), anything else with a `WowProblemError` |

### `@ahoo-wang/wow-view-engine` type names (V10 pre-release)

An application written against a V10 pre-release of the view engine renames these types; the values a config stores do not change. A type that holds a wow-client enum's values is named `<enum>Name`:

| Before | After |
| --- | --- |
| `AnalysisGroupType` | `AggregationGroupTypeName` |
| `AnalysisFunction` | `AggregationFunctionName` |
| `AnalysisDateUnit` | `AggregationDateUnitName` |
| `AnalysisDatePart` | `AggregationDatePartName` |
| `AnalysisDateDiffUnit` | `DateDiffUnitName` |
| `AnalysisExpressionOperator` | `AggregationExpressionOperatorName` |
| `AnalysisWindowFunction` | `WindowFunctionName` |
| `DurationComparison` | `ComparisonOperatorName` |
| `SortDirection` (the engine's type) | `SortDirectionName`; wow-client's `SortDirection` enum is unchanged |
| `PagingMode` (the engine's `'paged' \| 'cursor'`) | `RecordPagingMode`; wow-client's `PagingMode` enum is unchanged |

### The view engine's ports (V10 pre-release)

A host written against a V10 pre-release of the view engine, wow-view-store or wow-client moves to these shapes; stored views and configs are unchanged.

| Before | After |
| --- | --- |
| `viewSourceOf(client)` (wow-view-store) | the client itself: a generated `…SnapshotQueryClient`, an `…EventQueryClient` or `DatasetClient.dataset(…)` is a `ViewSource` as it is |
| `ViewSource.paged(query, attributes?, abortController?)` → `PagedList`, and `cursor` / `aggregate` / `describe` the same | `paged(query, { signal })` → wow-client's `PagedResult` (`total: number \| null`, `exact`); `cursor(query, options?)`, `aggregate(query, options?)`, `describe?(version?, options?)` → `DescriptorResult`; rows are `object` |
| `QueryDescriptorResult` (wow-view-engine) | `DescriptorResult` from `@ahoo-wang/wow-client/dsl` |
| `import { PagedQuery, PagedResult, TotalMode, DescriptorResult } from '@ahoo-wang/wow-client'` | import from `'@ahoo-wang/wow-client/dsl'`: the query language's types live in the DSL entry only, never in both |
| `ViewStore.list(definitionId, signal?)` → `ViewInstanceSummary[]` | `list(definitionId, { cursor?, signal? })` → `ViewListPage { items, nextCursor }`, a page at a time |
| `ViewStore.get(id, signal?)`, `getPreferences(id, signal?)` | `get(id, { signal? })`, `getPreferences(id, { signal? })` (`ReadOptions`) |
| `WriteContext` | `WriteOptions` (`{ requestId, signal? }`) |
| `ViewStore.listLimit`, `MemoryViewStoreOptions.listLimit` | gone: the engine pages to `RuntimeLimits.listedViews` (1000); `MemoryViewStoreOptions.pageSize` sets the memory store's page |
| `ViewEngine.list(id)`, `preferences(id)`, `open(id, options)` | `list(id, { signal })`, `preferences(id, { signal })`, `open(id, { …, signal })` |
| `new DatasetResources(engine, catalogs, onError?, onWordingError?, options?)`, `DatasetResourcesOptions` | `new DatasetResources(engine, { catalogs, onError?, onWordingError?, onDuplicate?, onCatalogError? })` (`DatasetResourcesInit`); `refresh({ signal })` |
| `DatasetCatalog.list()`, `DatasetWordingStore.list(signal?)` | `list({ signal })` (`ReadOptions`) |
| `DatasetDirectoryEntry { contextName, name, title: string \| null, version: string \| null }` | `{ context, dataset, title?, version? }`; a directory lists only what it serves, so no `null` version |
| `SHARED_OWNER_ID`, `SYSTEM_OWNER_ID`, `SYSTEM_TENANT_ID` (wow-view-store) | gone from the entry: name an audience by `SHARED_SCOPE` / `SYSTEM_SCOPE` (`Wow-Scope`); no path carries an owner |
| `typeof WowViewStoreErrorCodes[keyof …]` written by hand | `WowViewStoreErrorCode` |
| `ViewEngine extends EngineResources`; `EngineResources`, `ResourceHost` | `ViewEngine` extends nothing, and neither type is exported; `engine.resolveSource(key)` is removed |
| `register({ definition, source?, wording? })`, `DatasetRegistrar.definitions: ReadonlyMap<string, unknown>` | `register(resource: ViewResource)` (`ViewResource.wording?`); `ReadonlyMap<string, ViewDefinition>` |
| `saveAs` / `saveOwnedView` / `copyPanelView` with `{ title, scope }`; `SaveTargetInput` (`/react`) | `SaveAsInput` (root); `saveOwnedView` / `copyPanelView(dashboard: DashboardRuntime, …)` |
| `useAnalysisEditor(runtime: ViewRuntime \| null)` | `useAnalysisEditor(runtime: AnalysisViewRuntime \| null)`: narrow with `runtime?.kind === 'analysis'` or `isAnalysisRuntime` |
| `ViewRouter`, `ViewLocation`, `ViewDestination`, … from `/ui` only | also from the root; `/react-router` exports `ViewRouter`, `ViewLocation`; `/testing` exports `ViewDestination`, `ViewRoute` |
| `memorySource(rows, { now: () => ms })`, `matches(doc, filter, { now: () => ms })` | `{ now: () => new Date(ms) }`: a clock is `() => Date` |
| `actionHarness(list, rows, { now: ms })`, `ActionContext.now: number`, `RecordActionsController.now: number` | `{ now: new Date(ms) }`; `now: Date` (compare with `now.getTime()`) |
| `RecordAction.changesAt(row, ctx) => ms \| null`, `ActionHarness.changesAt(key?): number \| null` | `=> Date \| null` (e.g. `new Date(ms)`); a number is no longer read |
| `RefreshController.now()` | `refreshNow()` |
| `useRecordActions` read the system clock | it reads `options.environment.now()` (`useActionSurface` passes the engine's), so a pinned `RuntimeEnvironment.now` drives the rules |
| `ViewErrorContext.operation: string`, `ViewErrorKind` | `ViewErrorEvent` is a union on `kind`, each with its typed `operation` (`QueryOperation`, `StoreOperation`, `ExportOperation`, `ChartStage`; an action's id); read `context.remote` / `requestId` / `boundary` / `recordKey` after narrowing |
| `FilterSummaryItem.text` spelling operators raw (`Amount in 1, 7`, `… gt 48 hour`) | English words, never a wire value (`Amount is any of 1, 7`, `… > 48 hours`); a host that parsed it reads the item's parts instead |
| a stored board without `columns`, with `filter`/`filterMode`, or without `derivesTime`, rewritten on read | read as stored: one without `columns` is refused (`dashboard.grid.unsupported`); `filter`/`filterMode` are no longer read; `derivesTime` is gone and a board's time wires are always derived |
| `theme="light\|dark\|system"` on `ViewSurface`, the workbenches and the embeds; `ViewTheme` | `colorMode="…"`; `ColorMode` |
| `ViewHost theme="host"`; `useSurfaceTheme()` | `tokenSource="host"`; `useSurfaceColorMode()` |
| `KIND_ICON`, `AUDIENCE_ICON`, `SYSTEM_ICON`, `TONE_ICON` typed by lucide; `PopoverHandle`/`DialogHandle` as Base UI handles | `ViewIcon` (any SVG component); opaque engine handles a host passes along and does not open itself |
| `useViewEngine(options)` (`/react`); `useEngine` (`/ui`) | `useCreateViewEngine(options)`; `useEngine` from `/react` |
| `DashboardPanel` component, `DashboardPanelProps` (`/ui`) | `DashboardPanelCard`, `DashboardPanelCardProps` (the model's `DashboardPanel` type is unchanged) |
| `const served = await resources.refresh()`; `resources.duplicates` | `const { datasets, duplicates } = await resources.refresh()` (`DatasetRefresh`), or `onDuplicate` |
| `useDatasets(resources, key)`, `useDatasets(resources, null)` | `useDatasets(resources, { key })`, `useDatasets(resources, { paused: true })` (`DatasetsReading`) |
| `datasetBindings(served, { route, options })` | `datasetBindings(served, { route, binding })`; the dataset APIs are no longer `@beta` |
| `loading` on `OpenViewState`, `ViewListState`, `DatasetsState`, `RecordTableController`, `RefreshController`, `DashboardController`, `RecordDetailController` | `status: QueryStatus` and `error: Issue \| null` on every async hook (the hooks reference's "Async state"); `DatasetsState.error` is an `Issue`, not the caught value |
| `ViewListState.preferences` / `preferencesError` / `preferencesSettled` | `preferences: { value, status, error }` (`ViewListPreferences`) |
| `useValueCandidates(source, query, active)`, its `reason`, the key `label.filter.values-failed` | `useValueCandidates(source, { query, active })`, `error: Issue` (code `filter.values.failed`) |
| `useOpenView(engine, id, scopeFilter, opening)`, `useViewManager(engine, id, list)`, `useReleaseDeleted(openId, chosen, opened, setChosen)`, `useHandedRemoval(filter, handed, runtime)` | `useOpenView(engine, id, { scopeFilter, opening })`, `useViewManager(engine, id, { list })`, `useReleaseDeleted({ openId, chosen, opened, setChosen })`, `useHandedRemoval(filter, { handed, runtime })` |
| `useAnalysisResult(runtime, analysis, workbench)`, `usePanelFollowUps(runtime, navigate, handOver)`, `useRecordExport(runtime, table, { deliver })` | `useAnalysisResult(runtime, { analysis, workbench })`, `usePanelFollowUps(runtime, { navigate, handOver })`, `useRecordExport(runtime, { table, deliver })` |
| `<DatasetWorkbench datasets={list} loading onRefresh={…}>` | `<DatasetWorkbench datasets={useDatasets(…)} />`: it draws the read's state and its failure itself |
| `QuestionEditing = ReturnType<typeof questionEditing>`, `QuestionEditingInput` | an explicit `QuestionEditing` interface; `QuestionEditingInput` is not exported |
| `ColumnSettings` / `RecordPagination` `table: RecordTableController` | `ColumnSettingsTable` / `RecordPaginationTable`, the members each reads (a whole controller still fits) |
| `CartesianSeries.smooth: true`; root `isSmooth`, `withSmooth`; key `label.chart.smooth` | `line: 'smooth'`, or `line: 'step'` (each value holds until the next point; drawn as ECharts `step: 'end'`); unwritten is straight, and a stored `smooth` is not read; the helpers are gone; `label.chart.line` and its `.straight` / `.smooth` / `.step` / `.step.hint` |
| `/testing` `memorySource`: TERMS over an array keyed by the whole array (`[]` a non-null key) | one group per distinct non-null value, each record counted once in each; an empty, absent or all-null array in the null group (or `missingKey`); several groups give their cross product — as every Wow backend groups |
| a field under a descriptor's `dynamic` pattern never sorts or aggregates | it sorts and aggregates where the pattern grants it (`sort`, `aggregate`); `defineView` sorts it by default and offers it in analysis |
| `RecordAction.run(row, input)` | `run(row, input, { signal })`: aborted when the engine stops waiting |
| the record runtime's `PagedPaging.total` read as a count | `total` plus `exact: false` for a lower bound; the pager says "At least N records" |
| `OptionSource.search(input, signal?)`, `resolve(ids, signal?)` | `search(input, { signal })`, `resolve(ids, { signal })` (`SourceOptions`). `resolve` now also reads the ids a row holds as names: the engine asks once per page per remote, at most `RuntimeLimits.referenceBatch` (200) ids at a time, and shows an id the answer leaves out as itself |
| a `reference` field with `remote` sorted by `defineView` wherever the descriptor sorts | not sortable unless the spec says `sortable: true`, which sorts by the stored id and is marked "by id"; a saved sort on one that is not sortable is refused (`record.sort.reference-label`), and one in a definition's search fields is refused (`definition.search.reference-label`): denormalize the name into the read model (e.g. `customerName`) and sort or search that field |

### The view engine's root entry (V10 pre-release)

The root entry now exports what a host holds: the engine and its stores, one kernel entry per kind and stage (`validateRecord`, `compileRecord`, `projectRecord`, `validateAnalysis`, `compileAnalysis`, `projectAnalysis`, `validateFilter`, `compileFilter`, `validateDashboard`, `validateDefinition`), the definition helpers, and the errors with their guards. Nothing outside the package imported the names below. A host that did should go through the engine, the hooks or the kernels instead. None of them has a replacement export.

| Area: what went | Names no longer exported from `@ahoo-wang/wow-view-engine` |
| --- | --- |
| model constants and readers: field, analysis, dashboard, chart, instance, definition and record helpers (store helpers such as `toSummary`, `titleProblem`, `configBytes`, `VIEW_SCOPES` included) | `ANALYSIS_AUTO_RUN_MEMBERS`, `ANALYSIS_DATE_DIFF_UNITS`, `ANALYSIS_DATE_PARTS`, `ANALYSIS_DATE_UNITS`, `ANALYSIS_PRESENTATION_MEMBERS`, `BUILTIN_FIELD_KIND_IDS`, `CHART_COLOR_SLOTS`, `CHART_FAMILY`, `CODE_REVISION`, `CURRENCY_CODE_PATTERN`, `DASHBOARD_FILTER_KINDS`, `DASHBOARD_FILTER_TYPES`, `DASHBOARD_PRESENTATION_MEMBERS`, `DASHBOARD_WIDTHS`, `DATE_PART_DOMAINS`, `DATE_SUMMARY_FUNCTIONS`, `DEFAULT_APPROXIMATE_METRICS`, `DEFAULT_SEARCH_MODE`, `DEFAULT_STRING_COMPARISON`, `DEFAULT_TEMPORAL`, `DERIVED_FORMAT_STYLES`, `EPOCH_TIME_UNITS`, `FIELDLESS_FIELD_KIND_IDS`, `FIELD_CELL_IDS`, `FIELD_TONES`, `FILTER_GROUP_OPERATORS`, `MAX_CHART_LEVELS`, `MAX_DASHBOARD_FILTERS`, `MAX_DASHBOARD_TABS`, `MAX_DERIVED_DECIMALS`, `MAX_HEADING_LENGTH`, `MAX_KANBAN_LANES`, `MAX_MARKDOWN_LENGTH`, `MAX_MOVING_WINDOW`, `MAX_NUMERIC_SCALE`, `MAX_PANEL_LINKS`, `MAX_TIMER_DELAY_MS`, `METADATA_FIELD_KIND_IDS`, `METRIC_AGAINST`, `METRIC_HEADLINES`, `NUMERIC_TYPES`, `PANEL_PRESENTATION_MEMBERS`, `RECORD_PRESENTATION_MEMBERS`, `SEARCH_MODES`, `STRING_COMPARISONS`, `SUMMARY_FUNCTIONS`, `SYSTEM_INSTANCE_ID_PREFIX`, `SYSTEM_INSTANCE_ID_SEPARATOR`, `TEMPORAL_FIELD_KIND_IDS`, `TEMPORAL_TYPES`, `TIME_PRECISIONS`, `VIEW_AUDIENCES`, `VIEW_KINDS`, `VIEW_SCOPES`, `aggregationFunctionsOf`, `approximateMetrics`, `audienceOf`, `autoRunMembers`, `boardWidth`, `columnHidden`, `columnPinned`, `configBytes`, `currencyPathOf`, `dateDiffUnitsOf`, `datePartsOf`, `epochUnitOf`, `expressionFieldsOf`, `fieldAliasSegment`, `filterTypeOf`, `groupFieldsOf`, `isApproximate`, `isDateCell`, `isFieldName`, `isFieldlessKind`, `isFilterGroupOperator`, `isSingleStringField`, `isSystemScope`, `isValueMetric`, `nearestPageSize`, `numberFormatOf`, `parseSystemInstanceId`, `presentationMembers`, `sameFilterType`, `say`, `summaryFunctionsOf`, `temporalOf`, `titleProblem`, `toSummary` |
| the filter-tree editing algebra, the individual field kinds and the presence helpers (hosts extend kinds with `withFieldKinds(builtinFieldKinds, …)`) | `DATE_TIME_PRESETS`, `DELETION_STATES`, `MAX_RELATIVE_DATE_AMOUNT`, `METADATA_FIELD_KINDS`, `PRESENCE_OPERATORS`, `RELATIVE_DATE_UNITS`, `arrayFieldKind`, `booleanFieldKind`, `clearFilter`, `compilePresence`, `conditionOf`, `conditions`, `countLeaves`, `dateFieldKind`, `dateTimeFieldKind`, `deletionFieldKind`, `describeFilter`, `describePresence`, `describePresenceParts`, `durationFrom`, `elementFields`, `elementMatchFieldKind`, `emptyFilter`, `enumFieldKind`, `fieldGroups`, `filterFields`, `filterIndexes`, `groupJoinWord`, `impliedDeletion`, `insertAt`, `isBlankFilter`, `isBlankLeafValue`, `isDeletionState`, `isDurationFilterValue`, `isEmptyFilter`, `isExecutableFilter`, `isFilterGroup`, `isFilterLeaf`, `isFilterNode`, `isGroupItem`, `isKnownEditorInput`, `isNegation`, `isNumberRange`, `isOrderedRange`, `isPresenceOperator`, `isReferenceFilterValue`, `isRootFilterIssue`, `isSimpleTree`, `isValidTimeZone`, `mergeFilters`, `negateAt`, `nodeAt`, `numberFieldKind`, `operatorsOf`, `periodOf`, `readInstant`, `readValue`, `referenceFieldKind`, `removeAt`, `removeConditionAt`, `resolveDateTimeBound`, `rootSearch`, `sameFilterNode`, `sameFilterTree`, `searchFieldKind`, `searchFieldOf`, `stringFieldKind`, `unmarkedErrors`, `updateAt`, `validateDataConfigBase`, `validateViewConfigBase`, `variantGroups`, `walkFilter`, `walkFilterShape`, `withRootSearch`, `writeValue` |
| record helpers: kanban lanes, paging, projection and compilation beside `compileRecord` / `projectRecord` | `CSV_BOM`, `FIRST_PAGE`, `OTHER_LANE_ID`, `appendLane`, `cardField`, `clampPage`, `compileKanbanCounts`, `compileSummaries`, `cursorPaging`, `defaultKanbanSpec`, `detailSections`, `kanbanLanes`, `laneConfig`, `laneFields`, `lastPageInWindow`, `maxSortFields`, `pageAfterShrink`, `pageSummaries`, `pageWindow`, `pagedPaging`, `projectKanban`, `projectSummaries`, `recordCapabilityOf`, `recordProjection`, `recordValue`, `summaryAlias` |
| analysis editing: chart options and families, capabilities, formulas, defaults, candidates, metric formats, drill, fitting, and the helpers beside the kernel entries | `CHART_FAMILIES`, `CHART_PICKER_ORDER`, `DEFAULT_PERCENTILE`, `EXPRESSION_OPERATORS`, `HAVING_OPERATORS`, `OPERATOR_SIGN`, `PEAKS_ONLY_FROM`, `VALUE_CANDIDATE_LIMIT`, `VALUE_CANDIDATE_OPERATORS`, `aliasOf`, `analysisProbeLimit`, `analysisScope`, `asksForWhole`, `bucketChange`, `bucketRange`, `chartMarks`, `chartPickerGroups`, `chartUnfit`, `comboAxis`, `comboMark`, `compileAnalysisTotals`, `derivedMetric`, `derivedText`, `drillConditions`, `drillFilter`, `drillGroups`, `durationMetric`, `elementFilterFields`, `expressionText`, `familyOf`, `firstMetric`, `fitCharts`, `focusOn`, `formulaFormat`, `formulaMetric`, `freeAlias`, `groupFacts`, `groupFor`, `groupKeyText`, `groupOfType`, `groupableFields`, `innermostElement`, `isAdditiveMetric`, `isChartColor`, `isDuration`, `isFormula`, `isPercentStacked`, `isStacked`, `leadMetric`, `levelLabel`, `limitBounds`, `measureColumns`, `metricCondition`, `metricFieldOf`, `metricFormat`, `metricFunctionOf`, `metricMeasure`, `metricMeasures`, `metricOfSummary`, `metricReferenceText`, `metricWithCondition`, `momentColumns`, `momentMetrics`, `narrowValueCandidates`, `narrowsTo`, `nextExpansion`, `nextLevel`, `offersPercentStack`, `offersStacking`, `optionTabs`, `outOfScopeNames`, `peaksOnlyLabels`, `periodRollover`, `qualify`, `rangeSpan`, `readValueCandidates`, `readsAsItsField`, `recommendDateUnit`, `relativeFields`, `relativeName`, `relativeTree`, `resultSpan`, `rowCurrency`, `scopePrefix`, `seriesMark`, `shapeChart`, `splitBy`, `stacks`, `stageValues`, `summaryChoices`, `summaryOf`, `switchChartType`, `unknownOrOutside`, `validateChart`, `valueCandidateField`, `valueCandidateLimit`, `valueCandidateNarrowing`, `valueCandidatesConfig`, `valueLabelsOn`, `wallClockAt`, `withElements`, `withHavingRows`, `withLevel`, `withMovedTo`, `withOutOfScope`, `withPercentStack`, `withSlot`, `withStacked`, `withStageOrder`, `withStagesFrom`, `withoutLevelsFrom`, `wordReferences` |
| dashboard editing: layout, filters, panels, edits, clicks, wiring, tabs, merge and the validators beside `validateDashboard` | `CLICK_GO_KINDS`, `FILTER_TYPE_OPERATOR`, `ONE_DAY_PRESETS`, `STACKED_COLUMNS`, `addFilter`, `addPanel`, `addTab`, `admitFilters`, `arrangePanel`, `autoBindings`, `bindPanel`, `bindingsOf`, `boardCondition`, `boardFilterChoices`, `boardValueChoices`, `boardValueSources`, `bottomOf`, `clickDraftGaps`, `clickDraftOf`, `clickOf`, `clicksFilter`, `compactLayout`, `compactTab`, `coversScope`, `crossFilterChoices`, `defaultFilters`, `defaultPanelSize`, `draftedClick`, `duplicatePanel`, `editContent`, `fillUrl`, `filterCondition`, `filterControlValue`, `filterEditor`, `filterOperatorOf`, `filterReach`, `filterStoredValue`, `filterValueIssues`, `filtersOf`, `filtersOnTab`, `fitsGrid`, `freeSpot`, `grownLayout`, `isBlankFilterValue`, `isContentPanel`, `isOneDayValue`, `isOwnedPanel`, `isPresentationMember`, `isSafeContentUrl`, `isViewPanel`, `mapGlobalFilter`, `mappedValues`, `mergeGlobalFilter`, `moveFilter`, `movePanelToTab`, `moveTab`, `overlaps`, `panelFilterTree`, `panelTab`, `panelsOf`, `placePanel`, `placePanelIn`, `presentationMembersOf`, `pressableGroups`, `readingOrder`, `referToSaved`, `referencedInstance`, `removeFilter`, `removeFixedScope`, `removePanel`, `removeTab`, `renameFilter`, `renamePanel`, `renameTab`, `reorderPanel`, `reorderPanelIn`, `replacePanelView`, `retypeFilter`, `setBoardWidth`, `setFilterDefault`, `setFilterMultiple`, `setFilterOneDay`, `setFilterOptions`, `setFilterRequired`, `setPanelClick`, `setPresentation`, `setTimeGrouping`, `stackedLayout`, `tabsOf`, `takesGroup`, `unbindPanels`, `urlFillable`, `urlPlaceholders`, `validateBoardClick`, `validateFilterFields`, `validateLayout`, `validatePanelClick`, `validateTabs`, `validateTimeGrouping`, `valueSourceKey`, `wireableFields`, `wiredOptions`, `withBoard`, `withLayouts` |
| the always-visible host stand-in (`defaultRuntimeEnvironment()` already sees a page that never hides) | `ALWAYS_VISIBLE` |
| types only those values named | `AnalysisScope`, `AnalysisScopeElement`, `BoardClick`, `BucketChange`, `BucketRange`, `ChartFamilyTraits`, `ChartPickerGroups`, `ChartShape`, `ClickChoice`, `ClickDraft`, `ClickGoKind`, `DataPanelSource`, `DateInstant`, `DatePartOffer`, `DrillContext`, `DrilledGroup`, `FieldGroup`, `GroupCondition`, `GroupFacts`, `KanbanLane`, `LanePage`, `MalformedVisit`, `MappingSources`, `MetricFacts`, `OptionsTab`, `OrderStep`, `PanelFields`, `PlacedPanel`, `PressableGroup`, `RangeEdge`, `Rescoped`, `SeriesMark`, `ShapeFacts`, `SummaryChoice`, `SummarySource`, `TreeVisit` |

## Behaviour that changes without a type error

- **Error codes:** every code changed: framework codes are `wow.*` (`wow.aggregate.not-found`, `wow.command.busy`, …), a domain rejection is `<context>.<aggregate>.<code>`. Grep the application for every 9.x code string and map each.
- **Exact numbers:** a `Long`, `BigInteger` or `BigDecimal` field is a `string` in the V10 types and read exactly; code that did arithmetic on a `number` now concatenates strings unless it was fixed. Find each such field in the regenerated types and every use.
- **Time zones:** a relative-time filter without `zoneId` reads calendar days in UTC in V10 (9.x used the server's zone): pass `zoneId` wherever the application meant a local day.
- **Event names:** `bodyType` class names become `name` values; saved filters, analytics and screens keyed on the old names change.
- **Snapshot fields:** 9.x `eventId` and `snapshotTime` are gone; `firstEventTime`, `lastEventTime`, `operator`, `firstOperator`, `tags` remain.
- **React hooks:** a change of `requestOptions` (scope, space, headers) by content runs the query again; a header value rebuilt on every render reruns it on every render.
- **Waits:** 9.x stages map by meaning: `SENT` → `accepted`, `PROCESSED` → `committed`, `SNAPSHOT` → `snapshot` (the V10 default), `PROJECTED`, `EVENT_HANDLED`, `SAGA_HANDLED` → `handled('Processor.function')` naming the subscription, which the generated `<context>Wait` builders list.

## Steps and gates

1. The service's V10 Manifest exists and passes its own compatibility baseline (the source migration).
2. Replace the packages, regenerate from the Manifest into the same output directory (commit `.wow-generator.json`), delete the 9.x generator configuration and scripts.
3. Run `tsc`: fix each error by the tables above, never with a cast or `any`. Then lint and the application's tests.
4. Search for what the type check cannot see: 9.x error code strings, `bodyType`, hard-coded 9.x paths in fetches, gateway rules, CoSec policies, `zoneId`-less relative times, arithmetic on exact-number fields.
5. Run the application against the V10 service in development or staging: a command with its wait, a state read, a query, a refusal shown to the user. Report each gate with its command and result; what did not run is `MISSING EVIDENCE`.
