#7228 ## Breaking
None.

#7227 ## Breaking
None.

#7225 ## Breaking
None.

#7222 ## Breaking
None.

#7220 ## Breaking
None. Such an expression failed its command (a `NullPointerException`); it is now a problem of the definition, which the catalog answers as it answers any expression it cannot read.

#7219 ## Breaking
An aggregation on MongoDB with a metric aliased `_id` was answered, its rows not what the query asked, and is now refused `400 wow.query.invalid` (`storage-unsupported`). Alias the metric otherwise. The other stores, and a group aliased `_id`, are unchanged.

#7218 ## Breaking
- **PostgreSQL:** an ignore-case `contains`, `starts-with` or `ends-with` whose folding pattern is past 32,764 bytes, about 8,000 ASCII letters, and short of where PostgreSQL finds it too complex (more than 32,000 letters passed on the container, 64,000 failed), was answered and is now refused `400 wow.query.invalid` (`storage-unsupported`). Shorten the operand, or match case-sensitively, a `LIKE` with no such bound.
- **A lone surrogate:** a query with a text operand, or a system field's value, holding an unpaired surrogate was answered on PostgreSQL and ClickHouse, with rows that matched a `?` it never held (and on a store that compares it as it is, with no row), and is now refused `value-mismatch` on every store. Such a text names nothing a store holds: a client that sends one splits a surrogate pair or reads a malformed input, and mends it there.
- **ClickHouse:** past its field limit a query was a storage failure (500) and is now `400`: no answer is lost.

#7217 ## Breaking
None.

#7216 ## Breaking
A caller the service's read authorizers refuse was answered a model's or a dataset's descriptor, and is now refused with the authorizer's problem (`403 wow.auth.forbidden` unless it names another code). An application whose authorizer allows some operations by `ReadAction.operation` is asked about `DESCRIBE` too, and decides it.

#7215 ## Breaking
A body sent without `Content-Type` to a command, a query, a service route that takes a body or an operator's route was read as JSON, and is now answered `415`: a client that relied on it (a script's `curl --data-binary @file` without `-H 'Content-Type: application/json'`, a fetch of a `Blob` with no type) sends the header. A request with no body is unchanged.

#7210 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A service with `wow.cosec.authorizer.enabled` and a `CallerResolver` of its own that maps CoSec's principal to another subject or tenant, or answers anonymous callers | Every command and service route call of such a caller allowed, Wow's operational commands included | `403 wow.auth.forbidden`, naming the action |
| Application code that sends a command as another caller (`CommandClient.of(caller)`, the default anonymous in-process client) while it handles a request CoSec permitted, with the switch on | Allowed | `403 wow.auth.forbidden` |
| A service with `wow.cosec.authorizer.enabled: true` behind a CoSec gateway, with CoSec off, or without wow-cosec and CoSec's WebFlux module | Started, the switch doing nothing | Refuses to start, naming the key and why |

#7209 ## Breaking
None.

#7207 ## Breaking
`OpenApiOptions`' seven-parameter constructor, and the synthetic one a Kotlin call with default arguments compiles to, leave the `wow-openapi` ABI: code compiled against the old constructor fails with `NoSuchMethodError` until it is recompiled. A Kotlin caller that names its arguments, as the class's KDoc asks, compiles unchanged; a Java caller of the full constructor passes the three new arguments. No overload keeps the old signature.

- `OpenApiOptions`' constructor takes three parameters more, after the others, each with a default.
- No document changes unless the host configures request facts, has an integration's request id header, or runs CoSec.

#7208 ## Breaking
None.

#7206 ## Breaking
None.

#7205 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A client that tells a malformed `Wow-Scope` from other refusals by the code | `wow.command.malformed` on a command, `wow.query.malformed` on a state read, a query, a peer read or a dataset route, `wow.route.malformed` on a service route | `wow.request.malformed` on every route, still 400 |

#7202 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A client whose request body pauses for longer than 30 s between two reads, on either port (a slow or stalled upload, headers sent and the body never) | Waited for without a bound | Answered `408` naming `wow.http.read-timeout`, and its connection closed (over HTTP/2, its stream reset). The key raises the bound |
| A client that sends its body after an early answer (a 401, a 404) and pauses it as long | The connection stayed open, the body read on without a bound | The connection closes |
| An application's own route on the public port that reads its body as a stream and takes none of it for longer than 30 s (subscribes to it late, or pauses between pieces) | Its client waited while it sent | Answered `408` and cut while the client is still sending. Raise `wow.http.read-timeout`, or read the body without pausing |

#7203 ## Breaking
| Entry | Old | New |
| --- | --- | --- |
| root | `ViewEngineOptions.resolveOptions`, `ViewEngine.resolveOptions` | `optionSource` |
| root | `AnalysisColumnView.ongoing` | `AnalysisColumnView.unfinished` |
| root | `MetricPeriod.partial` | `MetricPeriod.unfinished` |
| root | `DashboardRuntime.destinationBoard` | `DashboardRuntime.destinationDashboard` |
| root | `ViewEngine.addToDashboard(boardId, …)` | `addToDashboard(dashboardId, …)` (a parameter name; no caller changes) |
| `/react` | `ViewListState.reload`, `ViewListReloadOptions` | `ViewListState.refresh`, `ViewListRefreshOptions` |
| `/react` | `DashboardController.destinationBoard` | `DashboardController.destinationDashboard` |
| `/react` | `WorkbenchController.toBoard` | `WorkbenchController.toDashboard` |
| `/react` | `SaveCommands.boards` | `SaveCommands.dashboards` |
| `/react` | `SaveCommands.addToDashboard(boardId)` | `addToDashboard(dashboardId)` (a parameter name) |
| `/ui` | `RecordViewProps` | `RecordViewOptions` |
| `/ui` | `ExportWindowProps` | `ExportWindowOptions` |
| `/ui` | `BUILT_IN_PRESETS`, `BuiltInPreset` | `BUILTIN_PRESETS`, `BuiltinPreset` |
| `/ui` | `DashboardGridProps.header(board)` | `header(dashboard)` (a parameter name) |

No wire or stored name changes, and no message key changes.

#7201 ## Breaking
None.

#7200 ## Breaking
None.

#7199 ## Breaking
None.

#7198 ## Breaking
None.

#7197 ## Breaking
| What | Who is affected | What to do |
| --- | --- | --- |
| A `config` key that names a key the chart sets itself in another spelling (`WOW_WAIT_MAX_TIMEOUT`, `server_port`) fails the render; it was rendered and shadowed or ignored. | Values files that set such a key under `config`. | Remove it, or set the chart's own value (`shutdown.*`, `ports.*`), as the message says. |

#7195 ## Breaking
| What | Who is affected | What to do |
| --- | --- | --- |
| Under `spring.main.lazy-initialization=true`, a node whose `wow.health` configuration is refused (`parked-degraded-at` past `wow.node.max-parked`, a stall timeout not longer than the reconcile timeout, an option that does not read) fails its start; it started before and failed its first health probe. | Deployments that run lazy initialization with such a configuration. | Fix the configuration the refusal names: its message says what to set. |

#7194 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A consumer of another context's state topic (a Wow subscription, a Kafka reader of the envelope) that reads `remote_ip` or `user_agent` from a record's `headers` | Present when the source service recorded them | Absent. The source service's log, and its own stores, still have them |

#7189 ## Breaking
- A build or CI that runs `generateWowManifests` runs `generateWowKubernetes`.
- `WowPlugin`'s companion constants but `ID`, `API_ID` and `SERVICE_ID` are `internal`; `MANIFESTS_TASK` is gone.

#7193 ## Breaking
None.

#7191 ## Breaking
| What | Who is affected | What to do |
| --- | --- | --- |
| A service's call to the internal port whose path has matrix parameters (`c-1;x=1`, `c-1;`) is answered 400 `wow.request.malformed`; it was routed without them. | A caller that is no Wow client and sends a `;` unencoded in a call's path. The Kotlin client percent-encodes `;`, so a Wow service's call is not affected. | Percent-encode `;` in an aggregate id; send no matrix parameters, which no route of Wow reads. |

#7192 ## Breaking
None.

#7190 ## Breaking
None.

#7183 ## Breaking
| What | Who is affected | What to do |
| --- | --- | --- |
| A subscription of another context's state topic parks an aggregate at a version the topic did not deliver, where it used to handle the record past it. | Services that read a peer's published stream from which a version can be missing: after an operator skipped a commit the publishing service's transport sink had parked, or a record its broker refused for good; or a topic written before #7172, whose retired-only and schedule-only commits have no record. | Recreate a state topic a 10.0 pre-release wrote (D491 ③). For a version known to be gone, retry the parked aggregate by hand: the retry accepts the gap and goes on from the next version present. |

#7185 ## Breaking
- The cluster channel's forward and reply name their format `format`, where they named it `v` (`ForwardCommand.format`, `ForwardReply.format`, `FORMAT`, all `@InternalWowApi`). Who: a tool that speaks the internal port's forward protocol itself. Migrate: write and read `format`.
- The internal port refuses a forward, a wait signal or a `Wow-Call` of a format it does not read, where it read it as format 1. Who: nothing Wow writes today, every structure being format 1.
- A body the forward route cannot read is answered 200 `refused: "malformed"`, where it was answered 400. Who: a tool that posts forwards itself and reads the status.
- `Wow-Call` carries `format`; a target refuses a call of another format with 400 `wow.command.malformed`.

#7186 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A client that sends a request id starting with `wow:` (`Wow-Request-Id`, `CoSec-Request-Id`, or `CommandRequest.requestId` in process) | Taken | `400 wow.command.malformed`, and the command does not run |
| A saga entry or a timer slot handled again across the upgrade | Re-derived the same id, a duplicate | Derives the `wow:` id: a command an old node already sent is not recognized as its duplicate |
| A test that pins `ExecutionSagaContext.derivedId` or `scheduleId`, a compensation's `compensationId`, `DecidingCommand.requestIdOf` or `Timer.requestId` values | The old values | The same values with `wow:` in front |
| A reader of the ops ledger's request ids | `wow.ledger:…` | `wow:ledger:…` |

#7187 ## Breaking
None.

#7179 ## Breaking
- `wow-runtime`: `ClusterOptions.maxInFlightPerPeer`, `SnapshotOptions.interval`, `batch`, `pages` (and `DEFAULT_INTERVAL`, `DEFAULT_BATCH`, `DEFAULT_PAGES`), `AccessStampOptions.batch` (and `DEFAULT_BATCH`), `TimerOptions.claimTimeout`, `SourceTopics.partitionRefresh` (and `DEFAULT_PARTITION_REFRESH`, and `SourceTopics.of`'s parameter) take the names above. Who is affected: code that reads these options or their defaults, and a transport backend (`@WowSpi`) that passes `partitionRefresh =` to `SourceTopics.of`. The options' constructors are `@InternalWowApi` (Wow builds them from the host's configuration), so no application builds them; the configuration keys are unchanged here (#7118 renamed them).
- `wow-cosid`: `CosIdProperties` is `WowCosIdProperties`; an application that injects it changes the type.

#7184 ## Breaking
None.

#7182 ## Breaking
None.

#7180 ## Breaking
| What | Before | After |
| --- | --- | --- |
| The `title` of a `wow.storage.unavailable` problem on the ops parked routes (`/wow/ops/parked/…`) and on the internal port's peer reads | `Service Unavailable` | `Storage unavailable`, the catalog's title, as every other occurrence of the code already had |

A client keys on the code, which is unchanged, as are the status, the detail and `Retry-After`.

#7181 ## Breaking
None.

#7178 ## Breaking
- `wow_catalog` stores `ownership`, a sensitive `level` and `access` by their wire names (`owner`/`tenant`, `display`/`confidential`, `policy`/`default`). Who: a tool that reads `wow_catalog` itself. Migrate: read the lower-case names; a pre-release row reads fail-closed until the Sink appends it again.
- A sensitivity level the Sink's version does not know is written to `wow_catalog` as the Manifest names it, and ranks above every known level, where it was dropped and the path read as not sensitive (#7175). Who: a reader of `wow_catalog` that meets such a level; Wow's own read it as `CONFIDENTIAL`.
- Under `ddl: none`, a table that lacks something the Sink writes, a column or an element of a named tuple, now refuses the insert where ClickHouse dropped the value without a word. Who: a pre-release layout kept under `ddl: none`, or a table an operator altered. Migrate: recreate the tables a pre-release wrote (drop them and raise the Sink's generation); the subscription parks the aggregate until then.
- The query path no longer fails on a sensitivity level it does not know: it masks it as `CONFIDENTIAL`.
- The wait-signal sender no longer switches an origin to single signals after a refused batch.
- A parked entry's reason for a ClickHouse server's failure names its code after the class name (`…ServerException: clickhouse server error code 117`). Who: a tool that matches the reason as a whole; the class name, the text before the first `: `, is unchanged.

#7177 ## Breaking
None.

#7176 ## Breaking
| Entry | Removed |
| --- | --- |
| root (29) | `AbsoluteDateTimeValue`, `ArrangeStep`, `ArrayFilterValue`, `BooleanFilterValue`, `CHART_TYPES`, `ChartFamily`, `ConfigOfKind`, `ContentPanelKind`, `DateTimeFilterValue`, `DEFAULT_MISSING_KEY`, `DetailSection`, `DurationFilterValue`, `EnumFilterValue`, `FieldMetricType`, `fitChartSlots`, `InstantRange`, `isAnalysisRuntime`, `isDateTimeFilterValue`, `NumberFilterValue`, `NumberRange`, `PresetDateTimeValue`, `ReferenceFilterValue`, `ReferenceItem`, `RelativeDateTimeValue`, `resolveDateTimeRange`, `StringFilterValue`, `TextWords`, `ValueMetric`, `ViewErrorContext` |
| `/react` (24) | `HandedRemovalOptions`, `InstanceSyncOptions`, `PanelFollowUpsOptions`, `RecordDetailController`, `ReleaseDeletedOptions`, `Reshape`, `ReshapeProposal`, `SearchBoxController`, `UnavailableController`, `useAutoRefresh`, `useHandedConditions`, `useHandedRemoval`, `useHandOver`, `useInstanceSync`, `usePanelFollowUps`, `useRecordDetail`, `useReferenceLabels`, `useRefreshCountdown`, `useReleaseDeleted`, `useSearchBox`, `useUnavailable`, `useValueCandidates`, `ValueCandidatesController`, `ValueCandidatesOptions` |
| `/ui` (103) | `AnalysisHost`, `AnalysisParts`, `AnalysisPartsProps`, `AUDIENCE_ICON`, `ColumnSettings`, `ColumnSettingsProps`, `ColumnSettingsTable`, `ContentPanel`, `ContentPanelProps`, `ControlAria`, `ControlChromeProps`, `DashboardPanelCard`, `DashboardPanelCardProps`, `DashboardTabs`, `DashboardTabsProps`, `dedupeIssues`, `DetachedDialog`, `DetachedPopover`, `DialogHandle`, `EditingCommands`, `EditorBandToggle`, `EditorBandToggleProps`, `EditorFold`, `EditorFoldProps`, `ErrorStrip`, `ErrorStripProps`, `ExportButton`, `ExportDialog`, `ExportDialogProps`, `fileName`, `FilterValueEditor`, `FilterValueEditorProps`, `formatIssues`, `HandleMove`, `IssueStripProps`, `keptKey`, `KIND_ICON`, `kindIssue`, `kindWord`, `LineAlert`, `LineAlertFrame`, `LineAlertProps`, `NewViewCommand`, `NewViewControl`, `NewViewItem`, `NoteStrip`, `NumberInput`, `PanelCommands`, `PanelGridItem`, `PanelGridItemProps`, `PanelHandle`, `PanelHandleProps`, `PanelOrder`, `PanelOrderProps`, `PanelPress`, `PanelResizeHandle`, `PanelResizeHandleProps`, `PopoverHandle`, `PresentationDialog`, `PresentationDialogProps`, `QueryStrip`, `QueryStripProps`, `RecordPanelHost`, `RecordParts`, `RecordPartsProps`, `RefreshControl`, `RefreshControlProps`, `RenderBoundary`, `RenderBoundaryProps`, `RenderParts`, `RenderSlot`, `RevertDialog`, `RevertDialogProps`, `SaveAsCommands`, `SaveAsDialog`, `SaveAsDialogProps`, `SharedSaveConfirm`, `SortOwner`, `SortSettings`, `SortSettingsProps`, `SurfaceKind`, `SYSTEM_ICON`, `tableTime`, `TONE_ICON`, `UnsavedMark`, `useDashboardEditExtensions`, `useKindIssue`, `useKindWord`, `useSaidText`, `ViewExpandExit`, `ViewExpandToggle`, `ViewExpandToggleProps`, `ViewIcon`, `ViewingCommands`, `ViewList`, `ViewListProps`, `ViewRouteOf` (the root's stays), `ViewSwitcher`, `ViewSwitcherProps`, `WarningStrip`, `WorkbenchParts`, `WriteOutcome`, `WriteOutcomeProps` |

Added, not breaking: `isReferenceLabelTimeoutError` (root).

#7116 ## Breaking
None.

#7174 ## Breaking
None.

#7173 ## Breaking
None.

#7171 ## Breaking
A snapshot table registry a pre-release wrote is recreated, in every snapshot store: its `fill` is `SUBSCRIPTION`/`BACKFILL` there and `subscription`/`backfill` now, and one of the old spelling is refused, naming it. An Elasticsearch store a pre-release wrote is recreated: its registry is mapped `dynamic: strict` (an existing index keeps its mapping), and a document without `accessVersion` (with access attributes) or `stateRevision` is refused where it was read with a default. Who: a pre-release deployment with snapshots, or with Elasticsearch events. Migrate: recreate the stores a pre-release wrote with the upgrade (the snapshots fill again from the log, and the events sink's new generation refills its index).

#7172 ## Breaking
| What | Who is affected | What to do |
| --- | --- | --- |
| A state topic gains a record for each commit whose events the aggregate all retired (`events: []`) and for each commit that only sets or cancels schedules (its `wow.schedule.*` events). | An outside consumer of a state topic that assumed `events` is never empty, or that never sees a schedule-only commit. Wow subscriptions pass the first over and already met schedule events in mixed commits. | Accept an empty `events`: the record stands for its version. Treat a record of only `wow.schedule.*` events as a commit that changed no domain state. |
| A state topic written by a 10.0 pre-release is to be created again before a 10.0 release publishes to it (D491 ③). | Deployments that kept a pre-release's state topics. | Create the topics again, as every pre-release store is (D414). |

#7170 ## Breaking
| Entry | Old | New |
| --- | --- | --- |
| root | `compileAnalysis(def, config, kinds, context, limits?)` | `compileAnalysis(def, config, { kinds, context, limits? })` |
| root | `compileRecord(def, config, kinds, context, page)` | `compileRecord(def, config, { kinds, context, page })` |
| root | `projectRecord(def, config, page, pageIndex?, limits?)` | `projectRecord(def, config, page, { pageIndex?, limits? })` |
| root | `projectAnalysis(def, config, result, totals?, kinds?, context?, limits?)` | `projectAnalysis(def, config, result, { totals?, kinds?, context?, limits? })` |
| root | `Validate{Record,Analysis,Definition,Dashboard}Options.limits: RuntimeLimits`; `ValidateFilterOptions.limits: Pick<…>` | `Partial<RuntimeLimits>` |
| root | `ValidateDashboardOptions.definitions` | `lookup?: DefinitionLookup` |
| `/testing` | `ActionHarnessOptions.now: Date` | `now: () => Date` |
| root | `RecordViewRuntime.fetchRecord(key, signal?)`; `ValueCandidateSource.search(query, signal?)` | `(key, options?: ReadOptions)`; `(query, options?: ReadOptions)` |
| root, `/react` | `refusedScope`, `scopeIssues`, `removeFixedScope()`, `SummaryRow.scope`, `RecordExportScope(s)` | `refusedScopeFilter`, `scopeFilterIssues`, `removeFixed()`, `SummaryRow.over`, `RecordExportExtent(s)` |
| root, `/react`, `/ui` | `BoardOrigin`, `BoardValueSource`, `DestinationBoard`, `BoardChoice`, `BoardFilterModes` | `DashboardOrigin`, `DashboardValueSource`, `DestinationDashboard`, `DashboardChoice`, `DashboardFilterModes` |
| `/react` | `WorkbenchController.boardOrigin`, `HeldView.boardOrigin` | `dashboardOrigin`. This replaces #7143's `boardOrigin`, which shipped in no release. |
| `/ui` | `ViewBindingOptions.reading`; `DashboardWorkbench` `initialFilters`, `initialTab` | `detail`; `defaultFilters`, `defaultTab` |
| root | `isRecordRuntime(runtime: ViewRuntime<DataViewConfig>)` | `isRecordRuntime(runtime: AnyViewRuntime \| null \| undefined)` |
| root, `/react` | `DatasetResourcesInit`; `DatasetsReading` | `DatasetResourcesOptions`; `UseDatasetsOptions` |
| root | `RuntimeLimits.exportMax`, `RuntimeLimits.listedViews` | `maxExportRows`, `maxListedViews` |
| root | `builtinFieldKinds` | `builtinFieldKindRegistry` |

Nothing persisted changes: these are code names, not stored config members. The skill `skills/wow-migrate/references/http-and-clients.md` still names `builtinFieldKinds` and `actionHarness(…, { now: new Date(ms) })`. That Skill is not this stream's to edit.

#7169 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A client or a proxy that appends a query parameter to a command's URL (a cache-buster, a signature, a tracking tag) | Dropped; the command ran | `400 wow.request.malformed` naming it; the command does not run |
| A peer calling a command on the internal port with a query parameter | Dropped | The same 400 |
| A caller of a service route with a query parameter the route does not declare, a misspelled one among them | Dropped | `400 wow.route.malformed` naming it |
| A caller of a service route with a declared query parameter given twice | The first value read, the others dropped | `400 wow.route.malformed` naming it |

#7168 ## Breaking
None.

#7167 ## Breaking
The ClickHouse Sink's tables name an event's data `body`: `_state_events.events` is `Array(Tuple(name, revision, body))`, `_events` has `body`, and the catalog's paths are `events.body.<path>` and `body.<path>`. Who: a deployment with the ClickHouse Sink, and every query or dataset that reads `payload` there. Migrate: recreate the tables a pre-release wrote (drop them and raise the Sink's generation); read `body`.

#7166 ## Breaking
None.

#7165 ## Breaking
None.

#7164 ## Breaking
None.

#7162 ## Breaking
| Who | Before | After |
| --- | --- | --- |
| A domain whose rejection or service route refusal has an extension named `detail`, `code`, `violations`, `result`, `type`, `title` or `status`, on a problem without that member | Written as that member | Left out |
| A domain whose rejection or service route refusal has an extension named `requestId`, `traceId` or `errors` | Written | Left out |
| A reader of `Problem.extensions` of a rejection's problem | The rejection's whole map | Without those names |

The command's result (`result`, `ErrorInfo.extensions`) still carries the rejection's extensions as the domain wrote them.

#7163 ## Breaking
A published state event's events name their data `body` where they said `payload`: `events[i].body` in the record an envelope carries. Not renamed: the envelope's own `payload` member, the record itself (`{format, kind, id, type, payload, transport}`), and the Kotlin names (`StateEventPayload`, its `payload` property, `PublishedEvent.payload`). Who: a consumer of a Wow service's published state topics, a Wow service of another version or another program. Migrate: recreate the published state topics a pre-release wrote with the upgrade; another program reads an event's `body`.

#7161 ## Breaking
A pre-release MongoDB store is recreated: the log documents (`events[].body`), the timer, reservation, ledger and member collections and their keys, format 1; `wow_snapshot_table` gets a format record. Renamed: `owner` (timers, reservations) to `aggregate`; `wow_lease_member.subscription_group` to `service`; the ledger's `aggregate_name` to `aggregate` (its `last_retry_at` came with #7150); stored enumerations to their lower-kebab names. Added: `wow_lease_member.wow_version`. Who: a pre-release deployment on MongoDB. Migrate: start from an empty database.

#7158 ## Breaking
None.

#7156 ## Breaking
None.

#7152 ## Breaking
None.

#7160 ## Breaking
None.

#7154 [no Breaking]
#7153 ## Breaking
None.

#7150 ## Breaking
- `wow-spi`: `ParkedAggregate.retryAt` and `ParkedAmendment.Restate.retryAt` are `lastRetryAt` (with the parameters of `of` and the constructors).
- `wow-api`: `LedgerEntry.retryAt` is `lastRetryAt`.
- Stored formats, in place at format 1: the failure ledger's MongoDB field `retry_at` is `last_retry_at` (PostgreSQL's column since #7138).
- Wire: the ops publication, the ops context's events and state, and the operator routes' entries say `lastRetryAt`.

#7148 ## Breaking
None.

#7149 ## Breaking
None.

#7147 ## Breaking
None.

#7138 ## Breaking
A pre-release PostgreSQL store is recreated: the log rows (`events[].body`), the timer tables, the reservation tables, the failure ledger, `wow_lease_member` and `wow_snapshot_table` changed in place, format 1. Renamed: `owner_tenant_id`/`owner_id` (timers, reservations) to `aggregate_tenant_id`/`aggregate_id`; the timer's `scope` to `owner_id`/`space_id`; its `operator` to `operator_id`/`operator_tenant_id`/`operator_acted_elsewhere`; `wow_lease_member.subscription_group` to `service`; the ledger's `aggregate_name` to `aggregate` and `retry_at` to `last_retry_at`; stored enumerations to their lower-kebab names. Added: `wow_lease_member.wow_version`, a format record for `wow_snapshot_table`. Who: a pre-release deployment on PostgreSQL. Migrate: start from an empty store.

#7146 ## Breaking
None.

#7145 ## Breaking
None.

#7144 ## Breaking
- `wow-runtime`: `ParkedNotice.entry`, `spec` and `of` are `@WowSpi` (a caller outside Wow opts in to `WowSpi`); `ParkedNotice.subscription` is the subscription's name, a `String`, where it was the `@InternalWowApi` `SubscriptionId`.

#7143 ## Breaking
| Entry | Old | New |
| --- | --- | --- |
| root (persisted) | `RecordSummary.fn` | `RecordSummary.function` |
| root (persisted) | `PanelBinding.globalField` | `PanelBinding.dashboardField` |
| root | `ViewErrorContexts['query'].errorCode`; the `runtime.query.forbidden` issue's `params.errorCode` | `.code`; `params.code` |
| root | `ViewEngineOptions.newId` | `ViewEngineOptions.newRequestId` |
| root | `CreateInput.scope` | `CreateInput.audience` |
| `/react` | `ViewListOptions` (`kinds`) | `UseViewListOptions` (`viewKinds`) |
| `/react` | `WorkbenchOptions.kinds`, `WorkbenchController.kinds`, `useHandOver`'s `kinds` | `viewKinds` |
| `/react` | `WorkbenchController.board`, `HeldView.board` | `boardOrigin`. D486 ⑤ said `origin`, but `HeldView.origin` already names the view a drill came from, and `WorkbenchController.held.origin` would sit beside it. The coordinator amended the name. |
| `/react` | `DashboardController.kinds: FieldKindRegistry \| null` | `FieldKindRegistry \| undefined` |
| `/ui` | `DashboardExtensionsOptions.board` | `DashboardExtensionsOptions.runtime` |
| `/ui` | `en`: each sentence a literal type; `zhCN: Readonly<Record<string, string>>` | `Readonly<Record<MessageKey, string>>` for both |

A stored view config written with `fn` or `globalField` is **not migrated** (D306). Its summaries and bindings are refused as invalid where the engine validates them, and dropped where it reads a stored config defensively.

#7139 ## Breaking
| Who | What changes | Migration |
| --- | --- | --- |
| A dataset definition whose `…IgnoreCase` test compares two expressions (a column with a column, a call) | Refused when drafted or revised (`ReviseDataset`), naming the call. A published one is marked as needing revision at its next recompilation (the next commit of a source it reads, `DatasetRecompilation`), its published version serving on while its view still gives its columns | Compare with a literal text, `containsIgnoreCase(o.note, 'text')`; for a value of a row, use the case-sensitive form over `lower(…)` of both sides, knowing that ClickHouse's `lower` is not the query protocol's folding |
| A published dataset whose ignore-case test compares with a literal text | Its next recompilation compiles the folded match, a different view, and publishes it as the next version by itself (same columns) | None |
| Code that imported `me.ahoo.wow.query.backend.CaseFolding` (`@InternalWowApi`, Wow's own modules) | Moved to `me.ahoo.wow.api.query.CaseFolding` | Change the import |

#7142 ## Breaking
None.

#7140 ## Breaking
- `wow-query`: `QueryEntry.UNSPECIFIED` and `QueryCaller.UNSPECIFIED` are removed; a caller's entry defaults to `IN_PROCESS`. `QueryEntryPolicy` is `@InternalWowApi`, without `requireExplicitEntry`, `requireAuthenticatedScope` and `admit`. `QueryViolation.ExplicitEntryRequired` is removed. `QueryModelProfile.requiredScope` (`@InternalWowApi`) is removed.
- `wow-query-api`: `QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED` is removed.
- Configuration: `wow.query.require-explicit-entry` and `wow.query.require-authenticated-scope` are removed and refused at startup.
- TypeScript: `QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED` (`wow-client`) and the view engine's message `runtime.query.failed.explicit-entry-required` are removed.

#7137 ## Breaking
None.

#7141 ## Breaking
None.

#7135 ## Breaking
None.

#7136 ## Breaking
None.

#7134 ## Breaking
- **A service with CoSec on that relied on the device id being recorded** now records it only if it lists `CoSec-Device-Id` in `wow.http.request-facts`.
- **A service with CoSec off** no longer takes a client's `CoSec-Request-Id` as the request id of a command without `Wow-Request-Id`. It never documented doing so.

#7128 ## Breaking
A client that reads `GET /wow/manifest` from a service's public port gets 404 unless the service sets `wow.http.manifest.enabled: true`: a peer of `contract: manifest` read in user mode (the analytics catalog with `wow.peer.mode: user`), a `wow-client` `CommandClient` given no Manifest, the generator pointed at the service's URL, a probe or a script. Migrate: give the client the generated Manifest (or generate from `manifest/baseline.json`), read it as a service from the internal port (`mode: service`), or set the key on the service that must publish it.

#7132 ## Breaking
- `wow-client`: `ClientMode`, `HttpCommandClient`, `HttpQueryApi` are internal.
- `wow-test`: `AggregateSpec(model, ref, start)`, `ProcessorSpec(handlers, kind)`, `ScheduleSpec(handlers, start, partitions)` are internal; use `aggregateSpec`, `sagaSpec`, `scheduleSpec`.
- `wow-kernel`: `JobState`'s setters are internal.
- `wow-tck`: the suites' fixture constants are gone from the API; `TAIL_TIMEOUT_MS` is `tailTimeout`.
- `wow-runtime`: `ServerCommandRequest.builder` and `Builder` are gone: `ServerCommandRequest.of(..., caller = c, wait = w)`. `CommandWire`, `CallerWire`, `ReplyTo` are `@InternalWowApi`.
- `wow-spi`: `EnvelopeCodec` is `@InternalWowApi`.
- `wow-openapi`: `WowHttpContract` is `@InternalWowApi`.
- `wow-tck-containers`: the image tags are fields, no longer compile-time constants (a `const val` of a caller that reads one no longer compiles).

#7133 ## Breaking
None.

#7130 ## Breaking
| Old | New |
| --- | --- |
| A claim (`changeAudience(…, 'personal')`) of a shared view a stored system dashboard shows is accepted by the service | refused: `409 view-store.referenced-by-shared-dashboard`, the port's `INVALID` with `boards`, as for a shared dashboard |
| `MemoryViewStore` refuses a claim of a view any system board shows, a configured one included | only a stored system board (or a shared one) holds it, as on the service; a configured system board does not |

Not breaking: no API report, surface list, wire shape or Manifest changes. The fake is test code.

#7131 ## Breaking
None.

#7111 ## Breaking
None.

#7127 ## Breaking
None. A `ddl: validate` or `none` timer store whose account may only read `wow_timer_aggregate` starts serving a collection already recorded, which it refused since #6792.

#7117 ## Breaking
None.

#7112 ## Breaking
- Binary, for anything outside Wow's own modules: the declarations listed above as `@InternalWowApi` leave the ABI dumps and need `@OptIn(InternalWowApi::class)`, which only Wow's modules may give. In particular `QueryGateway`, `HttpQueries`, `KernelOptions`, `ManifestMerger`/`ManifestFragment`, `MongoQueryOptions`/`PostgresQueryOptions`, `ContractDirectory`'s constructor (use `ContractDirectory.of`).
- Source, as a warning only: the declarations marked `@WowSpi` ask for `@OptIn(WowSpi::class)`; the `:tck` and `:query-tck` suites too.
- `@WowSpi` has `BINARY` retention; reflection no longer sees it.

#7126 ## Breaking
| Old | New |
| --- | --- |
| `SHARED_SCOPE`, `SYSTEM_SCOPE` (exported) | not exported |
| `WowViewStoreOptions { fetcher; permissions?; pageSize? }` | `WowViewStoreOptions extends WowClientOptions { readonly fetcher; readonly permissions?; readonly pageSize? }` |
| `WowDatasetWordingsOptions { fetcher }` | `WowDatasetWordingsOptions extends WowClientOptions { readonly fetcher }` |
| `WowViewStore.create/save/rename/changeAudience/delete/setPreferences(…, context)`, `WowDatasetWordings.set/clear(…, context)`, `list(input?)` | the parameter is `options` (a name only: no call changes) |
| `WowDatasetWordings.set`/`clear` with a revision never issued: sent, `If-Match: "NaN"` | `INVALID`, nothing sent |

#7125 ## Breaking
| Old | New |
| --- | --- |
| `CommandRequest.id` | `CommandRequest.aggregateId` |
| `CreateCommandOptions.id` (a generated creation method's `{ id }` option) | `CreateCommandOptions.aggregateId` (`{ aggregateId }`) |

A generated client must be regenerated with the matching `@ahoo-wang/wow-generator`: the clients it wrote before send `id`, which the new `CommandRequest` does not have.

#7124 ## Breaking
A node whose only development profile was `test` or `demo` is a deployment now: without a durable backend, a `CallerResolver` bean or a transport it needs, it refuses to start, as any deployment does. Who: a team that runs Wow for development under such a profile. Migrate: list it, `wow.dev-profiles: dev, local, test`, or use `dev`.

#7123 ## Breaking
| Old | New |
| --- | --- |
| `wow-generator generate -t, --ts-config-file-path <file>` | removed: the CLI refuses it as an unknown option |
| `GeneratorOptions.tsConfigFilePath` | removed |
| `@ahoo-wang/wow-client` a required peer of `@ahoo-wang/wow-generator` | optional |

Not breaking: the hashes of `.wow-generator.json` are unchanged for the `\n` files the generator writes, so an existing manifest reads as before.

#7122 [no Breaking]
#7121 ## Breaking
Generated code changes; regenerate every client with this version.

| Old | New |
| --- | --- |
| `<Aggregate>CommandClient.client` | `<Aggregate>CommandClient.commands` |
| `<context>Wait` (`eshopCatalogWait.committed()`) | `<Context>Contract.Wait` (`EshopCatalogContract.Wait.committed()`) |
| `<Aggregate>Aggregate.path` | removed |
| `textNumbers.commands.<Command>` | `textNumbers.commands['<context>.<aggregate>.<Command>']` |
| A lifecycle body generated from `$defs` (`WowLifecycleTransferOwner`) | wow-client's `TransferOwner`, `TransferSpace` |
| A service route method by the Manifest's name (`'export-orders'(…)`) | camel-cased as a command's (`exportOrders(…)`) |
| An empty command body `Record<string, unknown>` | `Record<string, never>` |
| A state path whose name is no segment, or whose constant another took | not in `<Aggregate>StateField` |
| An unknown parameter location, `ownership`, `idSource`, access `type` or `creation` read as a guess | what it belongs to is left out, with a warning |

#7120 ## Breaking
Five starts that worked are refused now, each a setting that did nothing: a `wow.http.errors` code under `wow.` the framework does not answer; `app_id`, `user_agent` or `remote_ip` in `wow.http.request-facts`; a `wow.ops.publish.context` that is no context name; an adaptive `floor` above its store's ceiling; a Kafka `min-insync-replicas` above the `replication-factor` the topics are created with. Migrate: correct the code; remove the fact (`wow.http.client-facts.enabled` switches the address and the user agent); name the ops context as its service does (`ops` by default); lower the floor or raise the ceiling; lower `min-insync-replicas` or raise the replication factor.

#7119 ## Breaking
None in the API reports or the surface lists. A command stream whose `end` gives a reason other than `complete` now fails with `wow.command.unknown`; no server sends one today. The `@experimental` declarations keep their signatures. They no longer have the freeze's promise: a 10.x minor or patch release may change them.

#7118 ## Breaking
Every row above is a renamed key or value, or a changed meaning:
- `wow.wait.unknown-contexts: reject` is refused as an unknown value; write `refuse`.
- `wow.timers.claim-timeout`, `wow.kafka.topics.partition-refresh` and `wow.subscription.read-retry.error-after` are unknown keys, which the host refuses to start with; write `claim-ttl`, `partition-refresh-interval`, `error-after-attempts`. The environment variables follow (`WOW_KAFKA_TOPICS_PARTITION_REFRESH_INTERVAL`).
- `reindex-requests-per-second: -1` is refused; write `0` for no limit.
- A store's or transport's duration written `1m 30s` or `1.5s` is refused; write `90s` or `1500ms`.
- `0` on the five retry and timer keys above starts and turns the bound off, where it was refused.

Who: a deployment that sets any of these. Code that names `UnknownContexts.REJECT`.

#7115 ## Breaking
Every name below changes the root, `/dsl` or `./routes` report, or a surface list:

| Old | New |
| --- | --- |
| `wait.accepted()`, `wait.committed()`, `wait.snapshot()`, `wait.queryable()`, `wait.handled()` | `Hop.accepted()` and the rest |
| `StreamResumeOptions.maxRetries` (default 4) | `maxAttempts` (default 5, the first request included) |
| `StreamResumeOptions.initialDelayMs`, `maxDelayMs` | `backoffMs`, `maxRetryAfterMs` |
| `DEFAULT_RETRY` (root) | not exported |
| `CommandClientOptions.bodyNumbers` | `textNumbers` |
| `QueryModelClient.numbers`, `DatasetClient.dataset(…, numbers)` | `textNumbers` |
| `textNumberOr` | `textNumbersOr` |
| `readonly client: WowClientOptions` on `CommandClient`, `QueryModelClient`, `StateClient`, `DatasetClient`, `ServiceRoutes` | private; the parameter is `options` |
| `CountQuery`, `CountResult` from `@ahoo-wang/wow-client` | from `@ahoo-wang/wow-client/dsl` |
| `StateClient` protected `readAt`, `getAt`, `findAt`, `traceAt` | removed (module helpers) |
| `StateReadOptions` with optional `version`, `at`, `minVersion` together | a union, one of them at most |
| `QueryViolation` (mutable, its own shape) | `QueryViolation extends Violation` (readonly) |
| `checkManifest`, `queryPath` (root) | not exported |
| `Retry` | `RetryAdvice` |

Generated code changes with the generator: regenerate every client with this version.

#7114 ## Breaking
One start that worked is refused now: a blank `wow.tenant.default` with a `CallerResolver` bean of the application's, which started and failed every service call on the internal port. Every other setting here refused the start before; it now says which key. Migrate: name a tenant, or leave the key unset for `(0)`.

#7113 ## Breaking
- **`QueryHookReturn.problem`: `WowProblemError | undefined` → `Problem | undefined`.** Read the code, status, detail and violations from it as before. Read the retry hint (`retryAfterMs`, `retry`) from `error` when `error instanceof WowProblemError`.
- **`StreamExecutor<R, Q>` → `StreamExecutor<Q, R>`.** A host that wrote the type with both arguments swaps them.
- **A change of `query` or `requestOptions` aborts the request in flight with `autoExecute: false` too.**
- **An `AbortError` the run's own signal did not cause is a failure** (`status: 'error'`, `error` set), where the hook went `idle`.
- **`useCursorQuery`'s `hasMore` is false while its controlled query is `undefined`.**

#7110 ## Breaking
A start that worked is refused now: a node whose `wow.aggregates` names a type it does not serve under settings that were not checked (`query.events.unpinned`, `clickhouse.history.*`). Who: a deployment with a mistyped type there, or one sharing a file of settings across services with entries of other services' types. Migrate: correct the type, or keep each service's entries in its own file.

#7108 ## Breaking
None. The root entry gains one export, `isActionInputError` (a `+` line in `test/api/root.api.md` and `test/surface/root.txt`); a form refused with an `ActionInputError` from another copy of the package comes back, where it closed.

#7106 ## Breaking
A start that worked is refused now: a node whose `wow.node.address` has no port, port 0, or a port past 65535. Who: a deployment that named only a host there; its other nodes could not reach it at that address anyway. Migrate: add the internal port, `host:9090` by default.

#7107 ## Breaking
None.

#7105 ## Breaking
- `SortDsl.nested(...)` and `ProjectionDsl.nested(...)` are removed: write `"state".path { "price".asc() }` and `"state".path { include("name") }`.
- `FilterDsl.beforeNow` and `afterNow` take a `kotlin.time.Duration`, not a `java.time.Duration`: `afterNow((-30).minutes)`, or `javaDuration.toKotlinDuration()`.
- `@QueryReference(aggregateName, contextName)` is `@QueryReference(aggregate, context)`; a positional `@QueryReference("member")` compiles as before. The Manifest's `x-wow-reference` members are `aggregate` and `context`: regenerate the Manifest (the build does).
- SPI (`@WowSpi`): `query.store.SnapshotQueries` and `EventQueries` are `SnapshotStoreQueries` and `EventStoreQueries`; `StoreQueries.events()` returns `EventStoreQueries?`.
- `QueryBindingError` is removed; `QueryError.errorMsg` and `bindingErrors` are `message` and `violations`.
- A refused read's problem no longer lists a `READ_DENIED` violation.

#7104 ## Breaking
None.

#7099 ## Breaking
- **A Manifest whose names collide in one context is refused** with exit 4 (`specification`), where it failed with exit 1 as the generator's bug.
- **A name with an upper-case run is spelled as the Kotlin contract spells it**: an aggregate `orderID` gives `OrderID…` classes and member (was `OrderId…`), a command `CreateURLShortcut` the method `createURLShortcut` (was `createUrlshortcut`). No committed Manifest has such a name.
- **Every generated descriptor holds `idSource`**: `typeof <Aggregate>Aggregate` gains `idSource: 'assigned'` for an assigned aggregate. Code that compared descriptors by their keys sees one more.

No API report or surface list line changes (the generator's surface lists only its programmatic API).

#7102 ## Breaking
A start that worked is refused now: a PostgreSQL store whose `operation-timeout` is zero or below, or past about 24.8 days, and an Elasticsearch store whose `operation-timeout` is zero or below. Who: a deployment that set `wow.postgres.operation-timeout: 0` to turn the bound off (it bounded nothing, the server bounds included), or a negative value (which failed every call). Migrate: set a positive duration; 30 seconds is the default.

#7086 ## Breaking
None.

#7092 [no Breaking]
#7091 [no Breaking]
#7101 ## Breaking
- **`CommandRequest`**: `id` → `aggregateId` (builder `aggregateId(...)`); `targetTenant` and `targetScope: Scope?` → `targetScope: TargetScope?` (builder: `targetScope(TargetScope.of(tenant, scope))`, or `targetTenant(t)` alone); `CommandRequest.of`'s parameters follow. Callers that pass both an id and a ref to `toCommandRequest`, `send`, `wait`, `stream`, `call` or `schedule` no longer compile (they failed at run time).
- **A tenant-only target through the in-process client** (`InProcessCommandClient`, the node's own `CommandClient`) now acts in the caller's own scope, where it acted in none; to act in no scope there, name it: `TargetScope.of(tenant, Scope.NONE)`.
- **`TargetScope.scope`** is nullable (null keeps the sender's own); `TargetScope.of(tenant)` keeps it, where it meant `Scope.NONE`. `ScopeHeader.format` takes the sender's own scope.
- **`ServerCommandRequest.target`, `QueryCaller.target`** → `targetScope`.
- **`me.ahoo.wow.api.problem.Retry`** → `RetryAdvice` (Kotlin type only).
- **`Problem.retryAfter`** is a `Duration?` (it was seconds as `Long?`).
- **`CommandTarget.of(type, body, aggregateId, headers)`**, **`Origin(service, node, address)`**: positional callers reorder.
- **`Caller.toOperator(actedElsewhere)`**, **`derivedId(…, target, …)`**: named-argument callers rename.
- **`EventEnvelope`, `PeerCall`**: `aggregateId`/`tenantId` → `ref`.

#7100 ## Breaking
None. A topic holding a record of no value stopped every tail of the topic on the node; now it stops its own partition's, as the page says.

#7094 ## Breaking
None. A call that stands from a `Mono`, `Flux` or `@Blocking` decide function now takes the derived request id where it took a new one: a target answers it as the repeat it is.

#7096 ## Breaking
A start that worked is refused now: a PostgreSQL store whose tables lack a unique key of Wow's schema, under `ddl: auto` or `validate`, and one whose tables lack a named index, under `validate`. Who: a deployment whose schema was applied by hand without every `PRIMARY KEY` and `UNIQUE` of Wow's statements, or whose keys were dropped. Migrate: restore the key the refusal names (a unique index over its columns, any name), or, under `validate`, create the index it names, or run once with `auto`.

#7095 ## Breaking
- **A query whose numeric operand passes those digits** was taken and is now refused: 400 `wow.query.invalid`, binding error `storage-unsupported` naming the field. Who is affected: a client that sends such numbers, which no store compares exactly; send the value the column holds instead.
- **On a ClickHouse dataset's float column,** a number operand past a double's range, or one that rounds to a subnormal double or zero, is now refused instead of being bound as its full text (and compared as ClickHouse parsed it).

#7093 ## Breaking
- **`WowViewStore` and `MemoryViewStore` refuse a revision or a list cursor they never issued as `INVALID`** (`WowViewStore` answered `CONFLICT`; `MemoryViewStore` read a cursor it could not read as the first page). A host retrying such a write as a conflict, or relying on a bad cursor to restart a list, sees `INVALID`.
- **`WowViewStore`'s first list page holds at most `pageSize` views**; the system views beyond it come on the next pages.
- **`MemoryViewStore` refuses what the service refuses** and spends no revision on a write that changes nothing; a test relying on its leniency (a malformed config created through it, a revision moved by a same-title rename) changes. **`memorySource`** refuses a cursor it never issued and answers `total: 'none'` with `null`.

No API report or surface list line changes.

#7090 [no Breaking]
#7089 ## Breaking
None.

#7088 ## Breaking
None. A build that refused a newer peer's contract now takes it with a warning; nothing that was read before reads otherwise. (`ManifestJson.contract` gained a parameter; `wow-manifest-tools` is run by the plugin of the same version and has no ABI dump.)

#7087 ## Breaking
- **A query stream that cannot be resumed past its retries rejects with a `WowProblemError`** (`wow.peer.unavailable`) where it rejected with a `TypeError` (cut off) or the network's own error (lost). Code matching the error by class or message changes: match `code === 'wow.peer.unavailable'`, the network's error is its `cause`.
- **A `terms` key over an `int64`, `bigint` or `decimal` field arrives as a string** (its exact text), where it was a rounded `number`.
- **`problemOf` writes `retry` and the catalog's title; `problemResponse` writes `Retry-After`**: a test asserting the old title (`Conflict`) or the absence of `retry` changes.

No API report or surface list line changes.

#7081 ## Breaking
- **A request with a plan now states its timeout** (`@ahoo-wang/wow-client`). A command sent with `wait` but without `within()` or `timeout` (`wait: WaitPlan.COMMITTED`), and a `send` without `timeout`, went with no `Wow-Wait-Timeout`: the service waited its `wow.wait.default-timeout` and the client bounded the answer by `defaultWaitMs` (a minute) and 5 s. They now state 10 s, as the Kotlin client always has, so a service whose default wait is longer answers `504 wow.wait.timeout` after 10 s, and the client gives up at 15 s with `wow.command.unknown`. Migrate: give the plan the wait it needs (`.within(30_000)`, or `timeout`), or send without a plan to keep the service's default.
- **A request's `headers` naming one of the client's own headers is refused** with a `TypeError` before it is sent; it was sent, replacing the client's. Migrate: set the request's field (`requestId`, `wait`, `timeout`, `scope`, `space`, `expectedVersion`, `ifNoneMatch`, `resume.lastEventId`).
- **`parseWaitPlan` refuses a value past 4,096 characters or 32 hops**, which it read before; the service refuses those already.
- **`parseRetryAfter` answers `undefined`** for a value that is neither delay seconds nor an HTTP date (it answered a time, often 0), and **`retryOf`** the catalog's for a `retry` value it does not know (it answered the value). A caller reading `retryAfterMs` gets the catalog's wait instead of none.

No API report or surface list line changes.

#7079 ## Breaking
- **`DecideScope.callId()` is renamed `attemptKey()`.** A decide function that keys an undone act with `scope.callId()` writes `scope.attemptKey()`. Source and binary.
- **An after hook's refusal rejects the command.** An after hook that calls `reject(...)`, or whose `require`/`check` fails, used to fail the command (`wow.command.failed`, 500, logged as an error); it is now answered as that rejection (its code, or `wow.command.invalid`/`wow.command.refused`), nothing written, compensations run.
- **A saga that names a command's target by an id from `context.ids`** (in the same handling) now fails the handler unrecoverable and parks the aggregate, where it used to send another request to another aggregate on every redelivery. Name the target by an id computed from the commit, or derive the creation without an id and send what follows from the `@OnCommitted` handler of its receipt.

#7080 ## Breaking
None. A value that was refused (an unknown stage from another node) is now read; nothing that was read before changes.

#7084 ## Breaking
None.

#7082 ## Breaking
None.

#7023 ## Breaking
Wow's own API, configuration and formats do not change, and the ABI dumps are unchanged. CoSec 8.0.0-beta.9's breaks that reach a Wow deployment, listed in `operate/upgrades`:

- **Rollout order.** The control-plane hosts first; then the token issuer (all replicas) and every Wow service in gateway mode; then the CoSec gateway image and every host that verifies tokens itself (a Wow service in `enforce` mode included). A beta.9 gateway writes `auth.kind`, which a beta.8 service answers with 400.
- **Patterns.** `[ ] ( ) | ! ^ $ \` are reserved in every pattern; run `cosec-migrate check-patterns` against each store before upgrading it.
- **`Identity.Credential`, `TokenSubject`, `VerifiedToken` take a required `kind`.** Code of the application's own that builds them does not compile until it chooses. Wow builds none.

#7049 ## Breaking
None.

#7025 ## Breaking
None.

#7016 ## Breaking
None.

#7013 ## Breaking
None.

#7017 ### Breaking pull requests the page already covered
- **Covered by their own `## Upgrades paragraph`:** #6949, #6986, #6988, #6994, #6996, #7002, #7005, #7008. They are placed above.
- **Edited the page themselves, before the paragraph rule; checked against their `## Breaking`:** #6820, #6826, #6862, #6867, #6873, #6878, #6938, #6939, #6940, #6941.
- **Covered by the page's existing advice to start this version on empty stores:** #6799, the Elasticsearch alias layout.
- **`## Breaking` is "None.":** #6753, #6766, #6769, #6775, #6777, #6784, #6788, #6800, #6801, #6804, #6805. They give the page nothing to say.

That makes 38: 8 with their own paragraph, 10 that edited the page, #6799, 11 with "None.", and the 8 gaps above.

#7015 ## Breaking
None.

#7012 ## Breaking
None.

#7011 ## Breaking
None.

#7010 ## Breaking
None.

#7008 ## Breaking
`ProcessorBinding.saga`, `.project` and `.react` take the context the handler is bound in, before `eventType`, as `.schedule` takes it:

```kotlin
// Before
fun <S> saga(processor: String, function: String, stateType: Class<S & Any>?, blocking: Boolean = false, startFrom: StartFrom? = null,
    rejections: List<RejectionBinding<*>> = emptyList(), aggregates: List<String> = emptyList(), nullableState: Boolean = stateType == null,
    retry: HandlerRetry? = null, commits: List<CommittedBinding<*>> = emptyList(), derives: List<Class<*>> = emptyList(),
    schedules: List<Class<*>> = emptyList(), calls: List<Class<*>> = emptyList(), eventType: Class<*>? = null,
    handle: suspend (StateEvent<S>, SagaContext) -> List<CommandRequest<*>>): ProcessorBinding<S>
fun <S> project(processor: String, function: String, stateType: Class<S & Any>?, blocking: Boolean = false, startFrom: StartFrom? = null,
    aggregates: List<String> = emptyList(), nullableState: Boolean = stateType == null, retry: HandlerRetry? = null,
    eventType: Class<*>? = null, handle: suspend (StateEvent<S>, SubscriptionContext) -> Unit): ProcessorBinding<S>
// react: as project

// Now: each takes `context: String? = null` right before `eventType`
fun <S> saga(…, calls: List<Class<*>> = emptyList(), context: String? = null, eventType: Class<*>? = null, handle: …)
fun <S> project(…, retry: HandlerRetry? = null, context: String? = null, eventType: Class<*>? = null, handle: …)
fun <S> react(…, retry: HandlerRetry? = null, context: String? = null, eventType: Class<*>? = null, handle: …)
```

Their JVM signatures change in `kernel.api`, and no overload keeps the old ones.
- **Generated bindings** are rebuilt: the compiler writes the new calls, with the module's context.
- **A binding written by hand** compiles unchanged when it names its arguments. Without `context` it runs in the commit's context in a spec, as before. A node binds it in its own context, as before.

#7007 ## Breaking
On the memory reference (a `memoryQueries` spec, a node on the memory store), two queries that were answered are now refused, as PostgreSQL and MongoDB refuse them:

- **A comparison, sort or grouping by a date string**, such as `state.placedAt > "2026-01-01T00:00:00Z"`, a sort by an `Instant`, or a date histogram over one. It now gets `unsupported-capability`. Compare, sort or group by an epoch field instead, a number that supports every capability, or by the system time fields.
- **A sort by two independent array fields.** It now gets `parallel-array-sort`. Sort by one of them.

A spec that relied on either passed where its node failed; it now fails as the node does.

#7005 ## Breaking
Refused at start where it used to run:

- **A node started with a loopback `wow.internal.host` or `wow.node.address` and no profile of `wow.dev-profiles`.** Without a durable backend it refuses (`No durable backend`). Without a `CallerResolver` it refuses (`no profile of wow.dev-profiles`). With `wow.transport=memory` it refuses. With `wow.transport=auto` and no transport it has no topics, and refuses where it needs them. Its stores are told it is a deployment: MongoDB's replica-set check runs.
  - *What to do:* a local run activates `dev` (or another profile of `wow.dev-profiles`). A deployment gives the node its database, its `CallerResolver` (or CoSec), and Kafka where it needs topics.
- **A `wowServer` test without a development profile.** It refuses what a deployment refuses: no durable backend, no `CallerResolver`, `topics = …`.
  - *What to do:* a test of memory wiring states `"dev"` (`wowServer<App>("dev")`). A test of production wiring gives what production needs: `wow.backend=memory` named, a `CallerResolver`.

#7002 ## Breaking
- **TypeScript.** An aggregate named `wait` is the contract's member `WaitAggregate`, where it was `Wait`: generated output that compiled changes, and a client that reads `<Context>Contract.Wait` of such an aggregate no longer compiles.
- **Kotlin.** An aggregate named `errors` in a context with no shared error codes is `<Context>Contract.ErrorsAggregate`, where it was `<Context>Contract.Errors`, which compiled. An aggregate `wait`, and `errors` beside shared codes, did not compile before.

#6999 ## Breaking
None.

#7004 ## Breaking
The memory log, which domain, processor and service tests run on, and a host's memory store now refuse what PostgreSQL and MongoDB refuse:

- **A group with a gap or of two contexts.** A test that appended such a group in one `appendAll` got the first streams committed and a conflict after them. It now gets the `IllegalArgumentException` the durable logs' `appendAll` throws before writing anything: `…'s streams in a group come in consecutive versions: 3 after 1.` or `A group commit appends streams of ….`
- **Another context's stream on a host's memory store.** A test that appended one to the log `openLog(context)` gave got it committed. It now gets the `IllegalArgumentException` the durable logs throw: `… is not in context ….`

Either test would have met that exception in production. The fix is the caller's: one context per log, and consecutive versions per aggregate in a group.

#7003 ## Breaking
None.

#7000 ## Breaking
None.

#6998 ## Breaking
None.

#6996 ## Breaking
`aggregateSpec` and `aggregateSpecTests` take the aggregate's access policy, last before the block:

```kotlin
// Before
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>, id: String = "aggregate-1", caller: Caller? = null, fixedTenant: String? = null,
    tenantId: String = fixedTenant ?: caller?.tenantId ?: DEFAULT_TENANT, start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
)

// Now
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>, id: String = "aggregate-1", caller: Caller? = null, fixedTenant: String? = null,
    tenantId: String = fixedTenant ?: caller?.tenantId ?: DEFAULT_TENANT, start: Instant = DEFAULT_START,
    policy: AccessPolicy<S>? = null,
    block: AggregateSpec<S>.() -> Unit,
)
```

`aggregateSpecTests` changes alike. Kotlin callers that pass the block as a trailing lambda, or name their arguments, compile unchanged. Their JVM signatures change, so tests compiled against the earlier version are rebuilt. No overload keeps the old shape.

#6995 ## Breaking
None.

#6994 ## Breaking
`fakePeer(context: String): FakePeer` is `fakePeer(context: String, callers: Map<String, List<String>> = emptyMap()): FakePeer`.

- Kotlin source that calls `fakePeer("x")` compiles unchanged.
- Code compiled against the old signature (`fakePeer(Ljava/lang/String;)`) does not link until recompiled, and a Java caller now passes the map.
- A test that relied on the fake taking a system command, a malformed body, a duplicated or unknown property, or a non-name `Wow-Service` now gets the real peer's refusal (see Behaviour changes).

#6992 ## Breaking
None.

#6991 ## Breaking
None.

#6989 ## Breaking
None.

#6990 ## Breaking
None.

#6988 ## Breaking
A user without `view-store.shared-writer` who shares a personal view (`POST /view-store/view/{id}/share-view` in the user's own scope, which `WowViewStore.changeAudience(id, 'shared')` sends) is refused `403 wow.auth.forbidden`, where the share was taken and the view published to the tenant's shared list.

To keep a user sharing their views, grant them `view-store.shared-writer`, the role that already writes shared views and claims them. Then answer the front end's `permissions.changeAudience` by the same role, as the README says, so the button is off for a user the service refuses.

#6981 ## Breaking
None.

#6952 ## Breaking
None.

#6987 ## Breaking
None.

#6986 ## Breaking
`compare` and `export` exit with `4` when they find differences, where they exited with `3`, which is now the JVM's exit on an `OutOfMemoryError`. A script that reads `3` as "differences found" misses them now, and a Job manifest copied from the earlier `deploy/migrate-data/job/job.yaml` (`FailJob` on `1`, `2`, `3`) retries a comparison that found differences instead of failing at once, while it fails an out-of-memory run at once. The manifest's `FailJob` list goes with the image: `1`, `2`, `4`.

#6985 ## Breaking
None.

#6978 [no Breaking]
#6982 ## Breaking
None.

#6979 ## Breaking
None.

#6954 ## Breaking
None.

#6977 ## Breaking
None.

#6980 ## Breaking
None.

#6950 ## Breaking
None.

#6953 [no Breaking]
#6942 ## Breaking
None.

#6955 ## Breaking
None.

#6951 ## Breaking
None.

#6949 ## Breaking
A values file that sets `autoscaling.keda.minReplicas: 0` is refused by the chart's schema: set 1 or more. A service KEDA scaled to zero did not come back on its own. The paragraph for Operate › Upgrades is under `## Upgrades paragraph`.

#6938 ## Breaking
A request the catalog used to answer is now refused.

- **What is refused:** `POST …/datasets/{dataset}/rows/{field}/{id}/{command}` for an internal command of the referred aggregate is `400 wow.command.unhandled`, where the catalog sent it to the owning service's internal port and answered that service's result.
- **What a client changes:** a console or script that sent an internal command through the catalog sends it from the service that owns it, or the owning service makes the command public.
- `GET …/reference-actions` lists no internal command any more.

#6948 [no Breaking]
#6947 ## Breaking
None.

#6944 ## Breaking
None.

#6946 [no Breaking]
#6941 ## Breaking
The `wow-service` chart refuses values it rendered before:

- **A key the chart sets itself in `settings` or `env`**, in any spelling Spring binds: the shutdown's bounds (set `shutdown.*`), the ports (set `ports.*`), the node's identity and address. In `env` alone, also `POD_NAME`, `POD_IP` and `SPRING_APPLICATION_JSON`. Move the value to the chart's own key, or remove it.
- **With `networkPolicy.public.onlyGateway` (the default)**, an entry of `networkPolicy.public.from` that names no pods by their labels: an empty or no `podSelector`, or an `ipBlock`. Name the gateway's pods, or set `onlyGateway: false`.

The generated values name the code's peers `service.contractPeers` instead of `service.peers`. Regenerate them (`generateWowHelmValues`, which `assemble` runs). A values file generated by an earlier build still renders, its peers read as the deployment's.

Operate › Upgrades (en, zh) says so under "From a 10.0.0 pre-release".

#6943 [no Breaking]
#6940 ## Breaking
A node that started with either value refuses to start on this version, naming the key:

- **`wow.health.liveness.stall-timeout`** must be longer than `wow.cluster.reconcile-timeout`, which is `1m` by default. A pre-release started with a shorter or equal one. At the default reconcile timeout, `60s` gives: *wow.health.liveness.stall-timeout is 1m, not longer than wow.cluster.reconcile-timeout. Set it longer than wow.cluster.reconcile-timeout (1m), so a stuck node leaves the members before its liveness fails.*
- **`wow.query.dataset.max-concurrent`** must be `0` (no bound) or more. A pre-release ran a negative value as no bound. `-1` gives: *wow.query.dataset.max-concurrent is -1, below 0. Set 0 for no bound, or more.*

Operate › Upgrades, *From a 10.0.0 pre-release* (en, zh), says so for each key. Both messages are pinned by their tests (`HealthAutoConfigurationTest`, `QueryStoresTest`). #6854 changes only the metadata's and the reference's spelling of a default.

#6939 ## Breaking
Code written against the old output of these Manifests stops compiling:

- **Error constants:**
  - a code whose words split otherwise (`INVALID_URLVALUE` is `INVALID_URL_VALUE`, `ITEM2COUNT` is `ITEM2_COUNT`);
  - a code with a dot after its aggregate's name, which moves to the context's `Errors` under a `__` name;
  - a code that does not start with its context's name: the analytics catalog's twelve, `ANALYTICS_CATALOG_*` to `ANALYTICS__CATALOG__*`.

  A code whose segments are lower-case words with `-` (every eShop code, the ops service's, the view store's) keeps its name. Code that compares a problem's `code` with the text or with `<Context>ErrorCode` changes nothing.
- **A domain command `Gateway`** is sent by `gateway`, where it was `gatewayCommand`.
- **A command of an owner-id aggregate whose `…Own` method another command already took** (a command `PayOwn` listed before `Pay`) is now renamed as a whole (`payCommand`, `payCommandOwn`). It was `pay` with `payOwnCommand`.

Migration: generate the clients again and rename what the code refers to. `operate/upgrades.md` says so under "From a 10.0.0 pre-release".

#6924 ## Breaking
None.

#6883 ## Breaking
None.

#6923 ## Breaking
None.

#6922 ## Breaking
None.

#6886 ## Breaking
What a user sees change, per backend. The table runs the same texts as the options comment on #6773, ✓ for a match, · for none, before → after:

| Text, filter ignoring case | Reference | PostgreSQL | MongoDB | Elasticsearch | ClickHouse |
| --- | --- | --- | --- | --- | --- |
| `ſ`, contains `s` | · → ✓ | · → ✓ | ✓ | · → ✓ | · → ✓ |
| `ΟΔΟΣ`, contains `σ` | · → ✓ | ✓ | ✓ | ✓ | ✓ |
| `λόγος`, contains `σ` | · → ✓ | · → ✓ | ✓ | · → ✓ | · → ✓ |
| `ΟΔΟΣ`, ends-with `ος` | ✓ | · → ✓ | ✓ | · → ✓ | ✓ |
| `5µm`, contains `μ` | · → ✓ | · → ✓ | ✓ | · → ✓ | · → ✓ |
| `İstanbul`, starts-with `is` | · | ✓ → · | · | ✓ → · | · |

- **MongoDB changes least:**
  - `ﬅ`/`ﬆ`, `ΐ`/`ΐ` and `ΰ`/`ΰ` now meet;
  - its longest ignore-case `value` is shorter, since each letter takes its class (see the cost below);
  - a `value` past 32,764 bytes of pattern is answered `storage-unsupported` instead of the server's error.
- **PostgreSQL on a `C`-locale database:** an ignore-case match folded ASCII only, so `Ärger` did not start with `äR`. On main, the new `C`-locale run fails 10 cells, every case pair outside ASCII. A database of a Turkish locale no longer folds `I` with `ı`.
- **The test DSL's `memoryQueries`** follows the reference.

No API, wire format or stored data changes. The `query-tck` dump gains 6 lines and loses none: the probes' names are constants of `SemanticProbe`. `query.api` is unchanged, since `CaseFolding` is `@InternalWowApi`.

#6885 ## Breaking
None.

#6884 ## Breaking
None.

#6878 ## Breaking
A command's event stream (`Accept: text/event-stream`) has one more event after its last result: `end`, with data `{"reason":"complete"}` and no `id`. A reader that takes every event as a `CommandResult` must skip `end`: an event named `end` is never a result. A reader should treat a stream that ends without `end`, however cleanly, as an unknown outcome and send the command again with the same `Wow-Request-Id`. Wow's Kotlin and TypeScript clients of this version do both.

Neither mixed pairing is safe:

- **A client of an earlier version against a service of this one** reads `end` as a result. The Kotlin client fails the stream as `wow.command.unknown`; the TypeScript one emits `end`'s data as a last result.
- **A client of this version against a service of an earlier one** gets no `end`, and ends every stream as `wow.command.unknown`, its command's outcome reported unknown though the wait ended.

So a service and the readers of its command streams go up together: its peers' JVM clients, its web clients, and any reader of its own.

#6877 ## Breaking
None.

#6876 ## Breaking
None.

#6881 ## Breaking
None.

#6875 ## Breaking
None.

#6873 ## Breaking
**Who:** a service with `wow.cosec.authorizer.enabled: true` where CoSec decides the requests itself (`cosec.pipeline.mode` `enforce`). Not a service behind a CoSec gateway (`wow.cosec.mode: gateway`), which has no CoSec checker, nor one with the switch off.

**What changes:** until now CoSec's authorizers were absent there. The ops service and the analytics catalog decided their operators by role, admission made no second decision, and the operational commands were refused on the public port. From this version CoSec's policies decide, as the request's principal:
- every command and service route call at admission, whichever way it reached the command gateway;
- Wow's operational commands;
- the platform services' operators.

So two requests that were answered before are now refused (403 `wow.auth.forbidden`):
- an operator allowed by role whom no policy grants;
- a command or call whose action no policy grants, even where another path let it through.

**Before upgrading,** grant in CoSec's documents the actions these need, as Operate › Security's [Action names](https://wow.ahoo.me/operate/security#action-names) lists them:
- a command's `<context>:<command>` on `<aggregate>/<id>`;
- a service route's `<context>:<route>` on `route/<route>`;
- a scheduled job's `<context>:wow.schedule.EnableJob` (and `RescheduleJob`, `DisableJob`) on `schedule/<job>`;
- the snapshot rebuild's `<context>:wow.snapshot.RebuildSnapshot` on `snapshot/<aggregate>`;
- the ops service's `ops:Retry`, `ops:Skip`, `ops:MarkRecoverable` and `ops:ApplyRetrySpec` on `parked/<id>`, with the reads of its query routes ([Ops Console](https://wow.ahoo.me/operate/ops-console)).

Operate › Upgrades, *From a 10.0.0 pre-release*, says the same.

#6874 ## Breaking
None.

#6871 ## Breaking
None.

#6870 ## Breaking
None.

#6862 ## Breaking
**The upgrade is the hazard the issue describes, once.**
- Until this version, a state record went where its key's hash put it among the topic's partitions. From it, it goes to its aggregate's partition in the log.
- After the upgrade, an aggregate's next record may land in another partition than its earlier ones. A subscriber may then read it before an earlier record it has not read yet. It may also handle it past an aggregate it holds parked: such an entry stays in the partition it was parked in, since `partitionOf(parked)` falls back to the key's partition for a hash-placed record, and that partition holds back only the records placed there. So caught up is not enough: nothing may be parked either.
- What an operator does, for each publishing service whose topics are kept. Operate › Upgrades has it as [Published streams on Kafka](https://github.com/Ahoo-Wang/Wow/blob/main/documentation/docs/en/operate/upgrades.md#published-streams-on-kafka) (en, zh), and `operate/kafka.md` links it from the hash-placed bullet.
  1. Stop the commands to the publishing service, or its nodes.
  2. Wait until every subscription that reads its topics, in every consuming service, has caught up and has nothing parked. For each partition of the topic, `wow.subscription.backlog` reads 0 and `wow.subscription.progress` reads 1, and `wow.subscription.parked.aggregates` reads 0; the ops console shows the same. Retry or skip each parked entry first.
  3. Give each topic with fewer partitions than `wow.log.partitions` that many (`kafka-topics.sh --alter --topic <topic> --partitions <count>`). Nothing is published meanwhile, so no record moves. Done before step 1, the earlier publisher would place its records among the added partitions.
  4. Upgrade the publishers.
- Nothing in Wow makes it safe without that. The subscriber cannot know that an earlier record of an aggregate is still unread in another partition: a gap in versions is no evidence, since a commit whose events are all retired has no record (the round-2 question in #6786). Pre-releases carry no compatibility promise; this is the procedure.

**Also:**
- **A smaller topic refuses the start.** A published topic with fewer partitions than `wow.log.partitions` now refuses the publishing node's start, under every `wow.kafka.ddl`. Under `none`, the node now reads those counts at start, so Kafka must be reachable then, as it already must be for a node that takes commands, or under `auto` and `validate`. Give such a topic at least the log's count, at step 3 above.
- **Wire:** a state record carries the `wow-partitions` header. A reader that does not know a header ignores it.
- **API:** `PublishedSinks.topics(manifest, producer, masks, partitions, topicPrefix, processor)` takes the log's partition count. Its two lines in `runtime.api` (the function and its `$default`) are replaced, so the labeller marks the PR breaking. A host assembling the transport sink by hand passes the count. `EnvelopeHeaders.PARTITIONS` and `PartitionedSource.readAt` are added (`spi.api`, `+` only), and Kafka's override (`kafka.api`, `+`). The TCK's `TopicTransportSpec` gains `deleteBefore` and two cases (`tck.api`, `+`). `TopicRequirement` and its `Count` are `@InternalWowApi`.

#6866 ## Breaking
None.

#6868 ## Breaking
None.

#6867 ## Breaking
A command's event stream answered by status what it now answers as its last event. This is the same kind of change as #6826's refusals.

- **A wait that times out before the first hop its plan names**, the default plan's `committed` included, was `504 wow.wait.timeout`. On `main` the head went out with the first result, and a timeout at ACCEPTED was taken for a refusal.
  - It is now `200`, then the stream's last event: FAILED with `wow.wait.timeout` at the last hop reached (`accepted` when the command had not settled).
- **An execution that fails after the acceptance** was a `503` or `500` problem before the first result, and a cut connection after it.
  - It is now `200`, then a last event FAILED at `committed`, with `wow.storage.unavailable` or `wow.command.unknown`.
  - A retry with the same `Wow-Request-Id` is safe, as the catalog says of both.
- **What a client changes:** a client that took a status other than `200` as the stream's outcome reads its last event's result instead. Every refusal before the acceptance is the problem it was, with its status and `Retry-After`.
- **The `: keep-alive` comment** is new: once at the start, then every 15 s. A reader of server-sent events skips it. A client that parses the stream by hand skips lines that start with `:`, as the specification says.

**Docs, en and zh:** Operate › Upgrades › "From a 10.0.0 pre-release" has one paragraph with the above.

#6865 ## Breaking
None.

#6864 ## Breaking
None.

#6861 ## Breaking
None.

#6847 ## Breaking
None.

#6846 ## Breaking
None.

#6827 ## Breaking
None.

#6826 ## Breaking
A request to a service route that its handler used to answer is now refused before the handler runs. This is the same kind of change as the trailing `/` that #6729 listed as breaking.

- **A body sent as another type than JSON**, such as `text/plain` or a form, is 415.
  - The client sends it as `application/json` or `application/<name>+json`.
  - A client that names no `Content-Type` is taken as before.
- **A body with a property the route's request type does not have, or with a property given twice**, is 400 `wow.route.malformed`. Before, it was read: the last value was kept, and the unknown property ignored.
  - The client sends the request's properties alone, each once, or the application adds the property to the request type.
- **An `Accept` without a JSON type**, such as `text/html` alone, is 406.
  - The client accepts `application/json`. A wildcard is answered JSON, as before.

The note for an application is in Operate › Upgrades, "From a 10.0.0 pre-release". The ABI is unchanged.

#6824 ## Breaking
None.

#6820 ## Breaking
A saga, `@OnRejected` compensations included, that sends several commands of one type to one target from one handling (from one call, for a handler of each event) without a `CommandRequest.requestId` on each now fails that handling as unrecoverable. Its aggregate parks with the message above, which names the handler, the command type and the target. Before the upgrade, run the domain's `sagaSpec` and `scheduleSpec` tests on the new version. A spec that covers a handling sending several fails the same way, with the same message (held by `ProcessorDslTest`). A spec that does not cover one shows nothing, and whether a handling sends several may hang on the data: the eShop's delivery job sends two receipts to one stock only on a tick whose feed lists two deliveries of one product. The fix is a business key for each request: the item, the line, the delivery it is about. A handler that `call`s several such commands made its first call before the second was refused. Once keys are added, that first command runs again under its key, so fix such a saga before the upgrade. The Upgrades page, "From a 10.0.0 pre-release", says the same.

A handler of each event derives its commands under new ids, keyed or not: each now carries the index of its event in the stream. A stream handled before the upgrade and redelivered after it, after a failure or a rebalance, sends its commands under the new ids. The kernel does not see them as the commands it decided before, so they run again, once. To avoid that, let the subscriptions of sagas with per-event handlers catch up before the upgrade: stop the publishers, wait until every such subscription has handled through the last commit, then upgrade. Handlers of the whole stream, and a single command per handling, keep their ids.

`ExecutionSagaContext.derivedId` no longer takes an occurrence. A test that computes the id of a command of a handler of each event passes its event's index, `derivedId(…, eventIndex = typed.eventIndex)`, as the eShop e2e does; without it, it computes the id of a handler of the whole stream, which such a command no longer takes. See ABI.

#6822 ## Breaking
None.

#6823 ## Breaking
None.

#6802 ## Breaking
None.

#6821 ## Breaking
None.

#6805 ## Breaking
None. The ABI is unchanged.

#6804 ## Breaking
None. `LogProgress` gains a parameter, but it is `@InternalWowApi` and outside the ABI dumps; `SnapshotBackfills` is `internal`.

#6803 ## Breaking
None.

#6799 ## Breaking
Who: a service that ran an earlier 10.0 pre-release with an Elasticsearch store or the event search sink. An index that version created is a concrete index of the alias's own name. Under `auto`, Elasticsearch cannot add the alias over it, and `require_alias` refuses it, so the store refuses it, naming it: `The Elasticsearch index [<name>] is an index, not the alias Wow writes through…`.

What to do, per context (the format number stays 1: pre-GA, the layout changes in place):

1. Stop the service's nodes. Delete the pre-release indices. With an `index-prefix`, each name starts with `<prefix>_`:
   ```
   curl -X DELETE "$ES/wow_events__<context>__<aggregate>"
   curl -X DELETE "$ES/wow_snapshot__<context>__<aggregate>"
   curl -X DELETE "$ES/wow_snapshot_table"
   ```
2. Fill the events index again from the beginning: delete its sink's checkpoints in the log's store, for each aggregate that `query.events.store` sends to Elasticsearch:
   ```
   -- PostgreSQL
   DELETE FROM wow_checkpoint WHERE subscription = 'wow.elasticsearch-sink.<aggregate>';
   // MongoDB
   db.wow_checkpoint.deleteMany({ subscription: "wow.elasticsearch-sink.<aggregate>" })
   ```
   Add the `subscription_group` when several services share the database.
3. Start the nodes. `ddl: auto` creates each pair: the events sink refills from the beginning. The snapshots are written again as their aggregates commit. Rebuild the others as [Rebuild and Replay](https://github.com/Ahoo-Wang/Wow/blob/main/documentation/docs/en/operate/replay.md) says: raise `stateRevision` for a whole type, or `wow.snapshot.rebuild-snapshot` for some aggregates.

A node under `validate` or `none` needs the pairs created first, by one run under `auto` or by hand (the operate page).

#6801 ## Breaking
None. The texts of these problems change: the failure's class in place of its `toString`. No page quotes them.

#6800 ## Breaking
None. `InheritedWaits`, `Subscription`'s constructor and `messages`, `WaitHops` and `CommandIntake`'s callback are `internal`; no ABI dump changes.

#6798 ## Breaking
None.

#6792 ## Breaking
**Who is affected**: an operator running MongoDB stores under `ddl: validate` or `ddl: none` whose account has no `insert` on `wow_log_aggregate` or `wow_timer_aggregate`. Under `ddl: auto` nothing changes.

**What they see**: the first append or timer of an aggregate type fails with `The registry wow_log_aggregate has no record of the log collection <name>, and this store could not write it (<the server's message>): its tails would not read the collection. Let the store's account insert into wow_log_aggregate, or insert {_id: "<name>", context: "<context>", aggregate: "<aggregate>"} into it.`. The timers say the same of `wow_timer_aggregate`. Before, it passed, and a collection without its record was read by no other node's tail and fired no timer.

**How to migrate**: grant the store's account `insert` and `find` on both registries, or insert each document the message names, once per collection. A database whose registries list every collection needs nothing more than the grant.

#6791 ## Breaking
None.

#6790 ## Breaking
None.

#6788 ## Breaking
None. `WaitState`, `Waiting` and the coordinator's relay branch are `internal`; no ABI dump changes.

#6789 ## Breaking
None.

#6784 ## Breaking
None. `PortFailureException` and `GuardedIds` are internal; `ThrownRejections` is internal; the ABI dumps are unchanged.

#6781 ## Breaking
None.

#6780 ## Breaking
None.

#6779 ## Breaking
What a user sees: the order of some answers changes where text mixes a character of U+E000..U+FFFF with one above U+FFFF. Text of other characters orders as before.

- **The in-memory reference and the test DSL's `memoryQueries`:** a list or a page sorted by such a text, a range over it (`gt`, `gte`, `lt`, `lte`, `between`), and an aggregation sorted by a text metric (FIRST, LAST, ANY, MIN, MAX of a text) now answer in code point order, as PostgreSQL and MongoDB do. A spec that asserted the old order, `😀` before `Ａ` ascending, now gets `Ａ` first.
- **Elasticsearch:** a top-N by a text metric (`sort` on a FIRST, LAST or ANY of a text, with `limit`), which the core computes, now keeps the groups PostgreSQL and MongoDB keep. Before, of `Ａ` and `😀` descending it kept `Ａ`'s group.

No API, storage, configuration or wire format changes. `compareCodePoints` is `@InternalWowApi`, and the `:query` dump is unchanged.

#6778 ## Breaking
**Who is affected**: an operator whose MongoDB schema has an index named as one of Wow's (`version_key`, `request_key`, `lease_key`, `member_key`, `checkpoint_key`, `ledger_key`, `timer_key`, `timer_due`, `timer_parked`, `owner_ix`, `space_ix`) with another definition. That is an index made by hand or by a tool: other keys or key order, without `unique`, sparse, another partial filter, or a collation. The same applies to one holding Wow's keys under another name. A schema `ddl: auto` created is not affected.

**What they see**: the node's start fails under `ddl: validate` and under `ddl: auto` alike, with `The index <name> of <collection> is not the one this version of Wow creates: <each difference>. Drop it and start once with ddl auto, which creates it, or create it as <specification>.` Before, it started, and a unique key that was not unique let duplicates commit.

**How to migrate**: drop the index and start once with `ddl: auto`, or create it as the message's specification gives it. Before dropping a unique key that was not unique, check the collection for duplicates it let in (for the log, two streams of one aggregate at one version).

#6777 ## Breaking
None. `CancelledDecisionException` is internal, so the ABI dumps are unchanged.

#6775 ## Breaking
None. `WaitState`, `Waiting` and `InheritedWaits` are `internal`; no ABI dump changes.

#6771 ## Breaking
None.

#6770 ## Breaking
None.

#6766 ## Breaking
None of its own: the pull request below holds the code and its `## Breaking`.

#6769 ## Breaking
None. The changed declarations are `internal`; no ABI dump changes.

#6765 ## Breaking
- **For a user, by default, nothing changes:** a Wow call from a decide function still takes the id derived from the command, and a decision decided again still sends the same requests.
- **Action:** a decide function that undoes a Wow call in `onAppendFailure` (or voids a third-party act keyed on `scope.command.requestId`) must key that act with `scope.callId()`: `CommandRequest.requestId = scope.callId()`, or the third party's idempotency key. Without it, the next attempt or a caller's retry is answered with the act that was undone.
- **Action:** a decide function whose call that stands asks something else when decided again, on a newer state (another target, command type or body at the same place), now fails with `wow.command.failed` instead of being answered with the first call's commit. Undo such a call and take `callId()`, or give it a business request id.
- **Internal:** `CoroutineContext.requestIdOf(given: String?)` becomes `requestIdOf(request: CommandRequest<*>)`, and `DecidingCommand.nextRequestId()` becomes `nextRequestId(call)` with a new `nextAttempt()`. All are `@InternalWowApi` and left out of the dumps.
- **ABI:** one `+` line in `api/api/api/api.api` (`DecideScope.callId`). `DecideScope` is not implemented by users (`@SubclassOptInRequired(InternalWowApi::class)`).

#6764 ## Breaking
Who is affected: deployments on PostgreSQL that run with `wow.ddl` (or `wow.stores.<name>.ddl`) set to `validate` or `none`.

- **On `none`:** after the upgrade, the operator sees a row appear in `wow_log_aggregate` (or `wow_timer_aggregate`) for each log (or timer) table that exists without one, written at that table's first use by a node of this version (an append, a read, a timer write or read). From then on, the tails read its entries and its timers come due. A partition whose tails were waiting at such a table's first entry goes on from there. An account without `INSERT` on the registry now has that first use refused, naming the row to insert.
- **On `validate`:** the same record is written at start (the host prepares each type's tables). An account that may not insert into the registry, with the record missing, is refused at start, naming the table, the registry and the row: `('<context>', '<aggregate>', '<table>')`. Before, it started and lost delivery silently.
- **How to migrate:**
  - Grant `INSERT` on `wow_log_aggregate` and `wow_timer_aggregate` to the account the service runs with.
  - Or insert the records the refusal names.
  - A schema created by `auto` already has its records: nothing to do.
- **`PostgresEventLogOptions`** (`@InternalWowApi`, not in the dump) gains `stallWarning`, last, with its default.
- **No change:** storage format, configuration key, public API or ABI dump. `:postgres:checkKotlinAbi` is unchanged.

#6762 ## Breaking
None.

#6753 ## Breaking
None. A state that relied on the aliasing (an event's payload recorded as later events changed it) relied on no promise.

