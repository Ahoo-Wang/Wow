
## #7079 (en 1 / zh 1)
A decide function that keys an act it undoes in `onAppendFailure` with `scope.callId()` now writes `scope.attemptKey()`: the member is renamed, its meaning unchanged. An after hook (`@AfterCommand`, `afterCommand`) that calls `reject(...)`, or whose `require` or `check` fails, now rejects the command with that code, writing nothing and running the attempt's compensations, where it failed the command with `wow.command.failed`. A saga handler that sends, schedules, cancels or calls a command whose target's id it drew from `context.ids` in the same handling now fails unrecoverable and parks the aggregate; name the target by an id computed from the commit (the source aggregate's id, a business key of the event), or derive the creation without an id and send the commands that follow it from the `@OnCommitted` handler of its receipt. An id drawn into a command's body is not affected.

## #7081 (en 1 / zh 1)
**The TypeScript client states every wait plan's timeout.** A command sent with a `wait` and no `within()` or `timeout`, and a `send` without `timeout`, now states 10 seconds as `Wow-Wait-Timeout`, as the Kotlin client always has, and is answered `504 wow.wait.timeout` past it; give such a plan the wait it needs, or send without a plan to keep the service's default. A request's `headers` may no longer carry a header the client writes itself (`Wow-Request-Id`, `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Scope`, `Wow-Space`, `If-Match`, `If-None-Match`, `Last-Event-ID`): set the request's own field instead.

## #7087 (en 1 / zh 1)
**TypeScript: a query stream lost past its resumes, a `terms` key, and `/testing`'s problems.** A streamed query cut off, or whose connection is lost, past its resumes now rejects with a `WowProblemError` `wow.peer.unavailable` (the network's error its `cause`), as the Kotlin client's does, where it rejected with a `TypeError` or the network's error. An aggregation's `terms` key over a field the generated types say `string` of (`int64`, `bigint`, `decimal`) arrives as that string, so two groups past 2^53 stay apart. `@ahoo-wang/wow-client/testing`'s `problemOf` and `problemResponse` build what a service writes: the catalog's `retry` and title, and `Retry-After`.

## #7093 (en 1 / zh 1)
**TypeScript view stores: `INVALID` for what a store never issued, the page bound, and fakes as the service.** `WowViewStore` and `MemoryViewStore` refuse a revision or a list cursor they never issued as `INVALID`, where `WowViewStore` answered `CONFLICT`. `WowViewStore` pages the system views, so no list page holds more than `pageSize`. `MemoryViewStore` and `memorySource` of `@ahoo-wang/wow-view-engine/testing` answer as the services do: `MemoryViewStore` refuses a config, a definition id or preferences the view store service refuses and spends no revision on a write that changes nothing; `memorySource` refuses a cursor it never issued (`wow.query.invalid`, `invalid-cursor`) and answers `total: 'none'` with no total. A test that relied on the fakes' leniency changes with them.

## #7094 (en 1 / zh 1)
A decide function that returns a `Mono` or a `Flux` and calls a Wow client inside `mono { … }` or `flux { … }`, or a `@Blocking` one that calls it inside `runBlocking { … }`, now sends each call that stands under the request id derived from the deciding request, as a suspending decide function does: a call decided again is answered as a repeat instead of running again, and a call decided again that asks something else fails the command (`wow.command.failed`). Undo a call that may change in `onAppendFailure` with `attemptKey()`, or give it a business request id.

## #7095 (en 1 / zh 1)
A query's numeric operand has at most 16,383 digits after the point and 131,072 before it, PostgreSQL `numeric`'s limits. A wider one (`1e-500000000`) is refused with 400 `wow.query.invalid` (`storage-unsupported`) before any store is asked. A number compared with a ClickHouse dataset's `Float32` or `Float64` column must be a double: past a double's range, or rounding to a subnormal or zero, it is refused alike. A projection that names a field twice counts it once.

## #7096 (en 1 / zh 1)
**PostgreSQL: the keys of Wow's tables are checked at start.** Under `wow.ddl: auto` and `validate`, a PostgreSQL store refuses a table that lacks a unique key of Wow's schema (a log table's version or request key, any table's primary key), naming the table and the key, and under `validate` a missing index of Wow's schema, naming it. A schema created by Wow passes; a schema applied by hand must keep every `PRIMARY KEY` and `UNIQUE` of Wow's statements.

## #7099 (en 1 / zh 1)
**wow-generator: names as the Kotlin contract's, collisions refused, `idSource` in every descriptor.** Regenerate the clients (`src/generated/`) with this version: every `<Aggregate>Aggregate` descriptor now writes `idSource` (`'assigned'` included). A Manifest name with an upper-case run is spelled as the Kotlin contract spells it (`orderID` is `OrderID`, a command `CreateURLShortcut`'s method `createURLShortcut`). A Manifest two of whose names give one generated name in a context (`order-item` and `order_item`, or a context `order-command` holding an aggregate `order`) is refused with exit 4, naming both: rename one with `@Name`.

## #7101 (en 1 / zh 1)
`CommandRequest.id` is `aggregateId` (the builder's `aggregateId(...)`, the `aggregateId` parameter of `toCommandRequest`, `send`, `wait`, `stream`, `call` and `schedule`), and a request names its target tenant and scope in one field, `targetScope: TargetScope?`: `targetScope(TargetScope.of(tenant, scope))`, or `targetTenant(tenant)` alone. A target that names only a tenant acts there in the caller's own scope on every path; the node's own command client acted in no scope, so name `Scope.NONE` where that is meant. An id and a ref are two overloads now, so a call naming both no longer compiles. `TargetScope.scope` may be null (the sender's own), `ServerCommandRequest.target` and `QueryCaller.target` are `targetScope`, the enum `me.ahoo.wow.api.problem.Retry` is `RetryAdvice` (its wire spellings unchanged), `Problem.retryAfter` is a `Duration`, `CommandTarget.of` takes the body before the aggregate id, `Origin` takes the service first, and the test DSL's `EventEnvelope` and the test server's `PeerCall` carry the aggregate's `ref`.

## #7102 (en 1 / zh 1)
**PostgreSQL and Elasticsearch: an operation timeout must bound something.** A store's `operation-timeout` (`wow.postgres.operation-timeout`, `wow.elasticsearch.operation-timeout`, `wow.stores.<name>.operation-timeout`) of zero or below now refuses the start, naming the key, as MongoDB's already did; on PostgreSQL so does one past about 24.8 days. A PostgreSQL timeout of zero left every call unbounded, and a negative one failed every call.

## #7105 (en 1 / zh 1)
The sort and projection DSLs name fields under a path with `"state".path { }`, as filters do; `nested(...)` is removed. `beforeNow` and `afterNow` take a `kotlin.time.Duration`. `@QueryReference`'s members are `aggregate` and `context`, and the Manifest's `x-wow-reference` carries `aggregate` and `context`: rebuild to regenerate it. A backend implementing the query SPI renames `SnapshotQueries` and `EventQueries` to `SnapshotStoreQueries` and `EventStoreQueries`, and a `QueryError` reads `message` and `violations`. A read a `ReadAuthorizer` refuses answers the authorizer's problem with no `violations`. The query codes only an aggregation query gives are experimental with it.

## #7106 (en 1 / zh 1)
**`wow.node.address` names its port.** An address without a port, with port 0 or past 65535 now refuses the start, naming the key: the other nodes reached such a node at port 80, or nowhere. Write `host:port`, an IPv6 host in brackets.

## #7110 (en 1 / zh 1)
**Every `wow.aggregates` entry names a served aggregate type.** An entry of `wow.aggregates.[<context>.<aggregate>]` that names no aggregate type the node serves now refuses the start, whichever of its settings it holds; before, its `query.events.unpinned` and its ClickHouse history were ignored without a word.

## #7111 NONE: None: release automation only.

## #7112 (en 1 / zh 1)
Every public declaration of `wow-spi` and `wow-query` now says its level. Most are `@WowSpi`: a backend's, which a minor release may change and which asks for `@OptIn(WowSpi::class)`, as do the TCK suites; the rest are `@InternalWowApi`, Wow's own, among them `QueryGateway`, `HttpQueries`, `KernelOptions`, `ManifestMerger` and `ManifestFragment`. Build a `ContractDirectory` of a Manifest with `ContractDirectory.of`. `WowJson`, `ReadAuthorizer`, `MaskStrategyResolver` and `QueryCountCache` stay application API.

## #7113 (en 1 / zh 1)
**wow-react: `problem` is the problem body, `StreamExecutor<Q, R>`, and what ends a run.** A hook's `problem` is the RFC 9457 body (`Problem`) the service answered with: its `code`, `status`, `detail` and `violations`. `error` is the `WowProblemError` itself, which still has the retry hint (`retryAfterMs`, `retry`). So code that read `problem.retryAfterMs` now reads `error.retryAfterMs` after checking `error instanceof WowProblemError`. `StreamExecutor` takes the query first, `StreamExecutor<Q, R>`. If the query or `requestOptions` changes while a request is in flight, that request is aborted, `autoExecute` or not. Only the run's own signal aborts a run: an `AbortError` from anywhere else, such as an executor's own timeout, makes the run fail. `useCursorQuery` gives `hasMore: false` while its controlled query is `undefined`.

## #7114 (en 1 / zh 1)
**A setting the host cannot start with names its key.** A value out of range under `wow.node`, `wow.internal`, `wow.health`, `wow.ops`, `wow.peer`, `wow.log`, `wow.http`, `wow.query` or a store's or transport's options now refuses the start with Wow's failure analysis, naming the key or its group, instead of a bean's stack; a blank `wow.tenant.default` refuses the start in every case.

## #7115 (en 1 / zh 1)
**wow-client: Kotlin's names for the hops, the resume options and the retry type, one name for the exact-number marks, and fewer exports.**
- Build a wait chain's hops with `Hop.committed()` (and `accepted`, `snapshot`, `queryable`, `handled`) where `wait.committed()` was.
- A stream's `resume` takes `maxAttempts` (the first request included, so `maxRetries: n` is `maxAttempts: n + 1`), `backoffMs` and `maxRetryAfterMs`.
- A hand-written `CommandClient` takes its marks as `textNumbers`, where it took `bodyNumbers`. A dataset's marks are `dataset(context, dataset, textNumbers)`. `textNumberOr` is `textNumbersOr`.
- Import `CountQuery` and `CountResult` from `@ahoo-wang/wow-client/dsl`.
- The type `Retry` is `RetryAdvice`.
- `StateReadOptions` takes one of `version`, `at` and `minVersion`.
- `QueryViolation` extends `Violation`.
- These are no longer exported: `DEFAULT_RETRY`, `checkManifest`, `queryPath`, the clients' `client` member, and `StateClient`'s protected readers.
- Regenerate the generated clients with this version: their hop builders and command options use the new names.

## #7116 NONE: None: the drill's own files.

## #7117 NONE: None: the benchmark harness.

Refs #7109

## #7118 (en 1 / zh 1)
**Renamed configuration keys and values.** `wow.timers.claim-timeout` is `wow.timers.claim-ttl`, `wow.kafka.topics.partition-refresh` is `wow.kafka.topics.partition-refresh-interval`, `wow.subscription.read-retry.error-after` is `wow.subscription.read-retry.error-after-attempts`, and `wow.wait.unknown-contexts: reject` is `refuse`; the former names refuse the start. `0` turns off every bound that can be turned off: `wow.timers.expires-after`, `wow.timers.max-fire-rate` and the retries' `timeout` take it, and `reindex-requests-per-second` takes `0` for no limit instead of `-1`. A store's or transport's duration reads as the host's do (`5S`, `500ms`, `PT5S`); `1m 30s` and `1.5s` are refused.

## #7120 (en 1 / zh 1)
**A setting that would do nothing refuses the start.** A `wow.http.errors` code under `wow.` that the framework does not answer, `app_id`, `user_agent` or `remote_ip` in `wow.http.request-facts`, a `wow.ops.publish.context` that is no context name, an adaptive query `floor` above its store's `ceiling`, and a Kafka `topics.min-insync-replicas` above `topics.replication-factor` now refuse the start, naming the key; before, each was ignored. A `wow.http.errors` code of the application's that no Manifest on the class path declares starts with a warning.

## #7121 (en 1 / zh 1)
**wow-generator: the generated clients' names follow Kotlin's, and the generator never guesses a value it does not know.** Regenerate every client with this version.
- A command client sends through `commands` where it had `client`.
- A context's wait hops are `<Context>Contract.Wait` (`EshopCatalogContract.Wait.committed()`) where they were `eshopCatalogWait`.
- An aggregate's descriptor has no `path`, and its command marks are keyed by full name.
- A lifecycle body is wow-client's `TransferOwner` or `TransferSpace`.
- A service route's method is camel-cased, as a command's is.
- A command body of no properties is `Record<string, never>`.
- `<Aggregate>StateField` holds the paths the Kotlin contract names.
- An aggregate, command or service route with a value the generator does not know (a newer Wow's) is left out, with a warning; generate with a newer generator.

## #7122 (en 1 / zh 1)
**Every refused setting names its key.** `wow.log.partitions` and `wow.memory.topic-retention` out of range, a store's query settings, a `wow.subscriptions` entry's retry and parked settings, and `wow.query.budgets.in-process.default-list-size` now refuse the start naming the key that is set, where some named another key, the wrong group, or none.

## #7123 (en 1 / zh 1)
**wow-generator: no tsconfig option, line ends that do not matter, and wow-client an optional peer.**
- Drop `-t`/`--ts-config-file-path` from the `generate` command and `tsConfigFilePath` from `GeneratorOptions`: the output never depended on them, and the CLI now refuses the option.
- A checkout that converts line ends (`core.autocrlf`) no longer fails `--check`.
- A regeneration removes the directories that removing old files leaves empty.
- A Manifest run warns when the configuration sets `apiClients`, which it never read.
- `@ahoo-wang/wow-client` is an optional peer of the generator.

## #7124 (en 1 / zh 1)
**`wow.dev-profiles` defaults to `dev, local`.** A `test` or `demo` profile no longer makes a node run for development (memory backend, callers from request headers, no durability warning): a node that relies on one refuses to start as a deployment. List the profile in `wow.dev-profiles`, or use `dev`.

## #7125 (en 1 / zh 1)
**wow-client: a command request names its aggregate's id `aggregateId`.**
- Rename `id` to `aggregateId` in a `CommandRequest` (`commands.wait({ command, body, aggregateId })`) and in a generated creation method's options (`createBrand(body, { aggregateId })`). This matches Kotlin's `CommandRequest.aggregateId`.
- Regenerate the generated clients.
- A by-id method's positional `id`, a state read's options and the HTTP requests are unchanged.

## #7126 (en 1 / zh 1)
**wow-view-store: wow-client's options, and no reserved scopes on the entry.**
- `WowViewStoreOptions` and `WowDatasetWordingsOptions` extend wow-client's `WowClientOptions`. A gateway's prefix goes into `basePath` instead of a fetcher that rewrites URLs, and a service with longer default waits sets `defaultWaitMs`. Their members are `readonly`.
- `SHARED_SCOPE` and `SYSTEM_SCOPE` are no longer exported. Write the `Wow-Scope` your own Wow client needs yourself: `{ ownerId: '(shared)' }`, or `{ tenantId: '(platform)', ownerId: '(system)' }`.
- `WowDatasetWordings.set` and `clear` refuse a revision the store never issued as `INVALID` before sending.

## #7127 (en 1 / zh 1)
A MongoDB timer store whose account may read `wow_timer_aggregate` but not insert into it serves a timer collection already recorded there again, as before #6792; the account needs `insert` only to record a collection that has no record yet (or insert the record the refusal names). A log store's account still writes `wow_log_aggregate` under every `ddl`, where the log keeps its partition count.

## #7128 (en 1 / zh 1)
**The public port no longer serves the Manifest by default.** `GET /wow/manifest` answers 404 on the public port unless `wow.http.manifest.enabled: true`; the internal port serves it to the cluster's services as before, and `GET /wow/openapi` stays on. The analytics catalog reads the owning services' Manifests from their internal ports (`wow.peer.mode: service`, the default; with `user`, each owning service sets the key). A TypeScript `CommandClient` given no Manifest, and the generator pointed at a service's URL, still read `GET /wow/manifest`: give them the generated Manifest or `manifest/baseline.json`, or set the key on the service. The eShop's end-to-end harness reads the internal port.

## #7130 (en 1 / zh 1)
**View store: a stored system board keeps the views it shows shared.**
- Claiming a shared view (making it personal) is refused when a stored system dashboard shows it, as when a shared dashboard does: `409 view-store.referenced-by-shared-dashboard`, the boards named in `boards`.
- `MemoryViewStore` refuses it too, and no longer refuses one a configured system board shows, which the service never checked.
- To make such a view personal, first remove it from the system board, or unpublish the board.

## #7131 NONE: None: records only.

## #7132 (en 1 / zh 1)
A few declarations no application needs left the public API: `wow-client`'s `ClientMode`, `HttpCommandClient` and `HttpQueryApi` (use `WowClient`'s builder), the test DSL's spec constructors (use `aggregateSpec`, `sagaSpec`, `scheduleSpec`), `JobState`'s setters, the TCK suites' constants (`TAIL_TIMEOUT_MS` is `tailTimeout`), and, now internal to Wow, `CommandWire`, `CallerWire`, `ReplyTo`, `EnvelopeCodec` (the protocol page is the contract of a command topic) and `WowHttpContract`. `ServerCommandRequest` is built with `of`, its facts named: `ServerCommandRequest.of(command, ref, caller = caller, wait = plan)`. `WowContainers`' image tags are no longer constants.

## #7133 NONE: None: records only.

## #7134 (en 1 / zh 1)
CoSec's device id, `CoSec-Device-Id`, is no longer recorded in a command's request facts unless the service lists it in `wow.http.request-facts`. It is personal data that the immutable log keeps, so Wow records it only when the application asks. A service with CoSec turned off (`wow.cosec.mode: disabled`) reads no CoSec header, `CoSec-Request-Id` included: a client sends `Wow-Request-Id`.

## #7138 (en 1 / zh 1)
**PostgreSQL stores are recreated.** The pre-release layout is not read: start from an empty database. The log stores an event's data as `events[].body`; timers and reservations name their aggregate `aggregate_tenant_id`/`aggregate_id`, and a timer's scope and operator are flat columns; `wow_lease_member` is keyed by `service` and records each node's version of Wow (`wow_version`); the failure ledger names `aggregate` and `last_retry_at`; stored enumerations are their lower-kebab names. A column Wow adds within the format later is added by `ddl: auto` and refused, named, by `validate`.

## #7139 (en 1 / zh 1)
A dataset definition's case-insensitive text test, `containsIgnoreCase`, `startsWithIgnoreCase` or `endsWithIgnoreCase`, now compares both texts after Unicode's simple case folding, as a query's case-insensitive filters do on every store, and takes a literal text as its second operand: a definition that compares two expressions that way is refused when it is drafted or revised, naming the call. Rewrite it to compare with a text in quotes; a published dataset that does so is marked as needing revision at its next recompilation and serves its published version meanwhile. A published dataset whose test compares with a text in quotes gets the folded match at its next recompilation, by itself.

## #7140 (en 1 / zh 1)
`wow.query.require-explicit-entry` and `wow.query.require-authenticated-scope` are removed; delete them, as a node now refuses either at startup as an unknown key. Every query states where it comes from, and every query is already restricted to the tenant of the caller the service resolves from the request: the second key's check read a scope the query routes never set, so with it on every HTTP query of a built-in model was refused, and with it off its removal changes nothing. In code, `QueryEntry.UNSPECIFIED` and `QueryCaller.UNSPECIFIED` are gone: a `QueryCaller` runs in process unless it says otherwise (`QueryCaller.IN_PROCESS`), and the query error code `explicit-entry-required` is gone with them.

## #7142 NONE: None: records only.

## #7143 (en 1 / zh 1)
**wow-view-engine: the persisted summary and binding names, and names that say one thing.**
- In stored configs, write a record summary's function as `function` (it was `fn`) and a dashboard panel binding's field as `dashboardField` (it was `globalField`). Nothing migrates a stored config: re-save each affected view, or rewrite its stored JSON.
- In code:
  - `/react`'s `ViewListOptions` is `UseViewListOptions`.
  - The `ViewKind[]` options and members are `viewKinds`.
  - `ViewEngineOptions.newId` is `newRequestId`.
  - A query error's context says `code`.
  - `CreateInput.scope` is `audience`.
  - The workbench's board is `boardOrigin`, and `DashboardExtensionsOptions.board` is `runtime`.
  - `DashboardController.kinds` is `undefined` without a board.
  - `en` and `zhCN` are typed `Readonly<Record<MessageKey, string>>`.

## #7144 (en 1 / zh 1)
A `ParkedNotifier` reads a `ParkedNotice`'s own properties: `ref`, `subscription`, `fromVersion`, `reason`, `failedAt`, `retries`, `maxRetries` and `nextRetryAt`. The ledger's records behind it, `notice.entry` and `notice.spec`, and `ParkedNotice.of` are now at the SPI's level (`@OptIn(WowSpi::class)`): a notifier that read `notice.entry.reason` reads `notice.reason`.

## #7146 (en 1 / zh 1)
**TypeScript packages: fetcher 8 accepted.** Wow's TypeScript packages accept fetcher `^5.1.5 || ^6.0.0 || ^8.0.0-rc.1` (`@ahoo-wang/fetcher`, `fetcher-decorator`, `fetcher-eventstream`). An application may move to fetcher 8 with them, from its first release candidate on, or stay on 5.1.5 or later, or on 6.x. Fetcher 8 removes `fetcher-cosec` and `fetcher-react`'s security hooks. An application that used them takes CoSec's own packages (`@ahoo-wang/cosec-fetcher`, `@ahoo-wang/cosec-react`).

## #7148 (en 1 / zh 1)
This version runs CoSec 8.0.0-rc.1. From 8.0.0-beta.9 no order is needed: the services, the gateway and a control plane move in any order, as CoSec's [Upgrading: from 8.0.0-beta.9](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-9) says. A service in gateway mode now takes request headers up to 16 KB however its mode is set: CoSec derives the limit from the identity's 8 KB plus 8 KB for the other headers. A service that set `server.max-http-request-header-size: 16KB` for it may remove the key. A request past the limit is answered 431 naming the limit the server applies. An application that builds a `TokenAuthenticator` itself, reads `ChangeEvent.change` or implements CoSec's control store follows CoSec's page; Wow's own code uses none of them. `@ahoo-wang/cosec-fetcher` moves to 8.0.0-rc.1 (`@rc`) with nothing else to change.

## #7149 (en 1 / zh 1)
An events index on Elasticsearch, or the ClickHouse warehouse, is filled again from the log by a new generation of its sink: empty it (delete the concrete index `<name>--1`, or drop the sink's tables), raise `wow.stores.<name>.sink.generation` (Elasticsearch) or `wow.clickhouse.sink.generation` (ClickHouse) by one, and roll the nodes. The setting is 1 by default, which keeps the sinks' names; only raise it. The old generation's checkpoints and parked entries stay, listed as `orphans` by the ops service and warned of at start; skip its parked entries.

## #7150 (en 1 / zh 1)
A parked entry's last automatic retry is `lastRetryAt`, beside `nextRetryAt`: in the SPI (`ParkedAggregate`, `ParkedAmendment.Restate`), in the record a service publishes to ops, in the operator routes' JSON and in the ops console. The failure ledger stores it as `last_retry_at`, on MongoDB as on PostgreSQL (#7138). Upgrade the ops service with the services that publish to it: each refuses the other's record across the rename, and a service publishes its entries again once both run this version. A pre-release store is not carried over; to keep the value in a MongoDB one, rename it before starting this version: `db.wow_failure_ledger.updateMany({retry_at: {$exists: true}}, {$rename: {retry_at: "last_retry_at"}})`.

## #7156 NONE: Nothing changes for an application. A backend that runs `StoreTck` no longer has a store left open by its options test.

应用无需任何改动。运行 `StoreTck` 的后端，不会再有选项测试遗留下来未关闭的存储。

## #7158 (en 1 / zh 1)
A subscription's `handle` or `process` span for an entry it skipped behind its aggregate's park, or as a copy a topic delivered again, now ends with `wow.outcome` `skipped` instead of `ok`. A dashboard or alert that reads `ok` on these spans as "handled" no longer counts those skips. Reference › Observability lists the outcomes of these spans.

## #7160 NONE: None: records only.

## #7161 (en 1 / zh 1)
**MongoDB stores are recreated.** The pre-release layout is not read: start from an empty database. The log stores an event's data as `events[].body`; timers and reservations name their aggregate `aggregate: {tenant_id, id}`; `wow_lease_member` is keyed by `service` and records each node's version of Wow (`wow_version`); the failure ledger names `aggregate`; stored enumerations are their lower-kebab names; a timer's null field is left out of its document.

## #7162 (en 1 / zh 1)
A rejection's or a service route refusal's extension named like a member of the problem (`type`, `title`, `status`, `detail`, `code`, `retry`, `violations`, `result`, `version`, `instance`) is never written, even on a problem without that member. Nor is one named `requestId`, `traceId` or `errors`, which Wow keeps for members of its own. A domain that used one of these names renames the extension. The command's `result` still carries it as written.

## #7163 (en 1 / zh 1)
**A published state event names an event's data `body`.** In the record a Kafka envelope carries, each event is `{name, revision, body}` where it was `{name, revision, payload}`, as in the log and the events query model; the envelope's own `payload`, the record, is unchanged. The published state topics a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are. A record of the old spelling that still reaches a subscription parks its aggregate, visibly: retry it once its publisher is upgraded, or skip it.

## #7164 (en 1 / zh 1)
An application can now send a snapshot rebuild by its class and name its type in an authorizer: `RebuildSnapshot.of(ids)` and `SnapshotRebuilds.REBUILD_SNAPSHOT` (`me.ahoo.wow.spi.snapshot`), without opting in to internal API. A class that implements one of the SPI seams the host takes (`GlobalIdGenerator`, `AggregateIdGenerator`, `FailureClassifier`, `Observer`, `CommandCaller`, `QueryObserver`) opts in with `@OptIn(WowSpi::class)`, as the Spring Boot guide now says.

## #7167 (en 1 / zh 1)
**The ClickHouse Sink's tables name an event's data `body`.** `_state_events.events` is `Array(Tuple(name, revision, body))` and `_events` has `body`, where both said `payload`; a dataset or a query reads `body`, and the catalog's sensitive paths are `events.body.<path>` and `body.<path>`. The tables a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are: the Sink refuses them, naming the column; drop them and raise the Sink's generation, which fills them from the log. A table may hold columns of its operator's own, which the Sink leaves as they are.

## #7169 (en 1 / zh 1)
A command route now refuses any query parameter, on the public and the internal port: `400 wow.request.malformed`, naming it, and the command does not run. A command reads its body and its headers alone, and a parameter used to be dropped without a word. The case most likely to meet it is a client, or a proxy in front of the service, that appends a parameter of its own to a command's URL, such as a cache-buster or a request signature. Send that value as a header instead, or have the gateway strip it before it forwards the request. A service route likewise refuses a query parameter it does not declare, or a declared one given twice: `400 wow.route.malformed`, naming it, where the first value used to be read and the rest dropped.

## #7170 (en 1 / zh 1)
**wow-view-engine: one shape per kernel and option, and Dashboard names.**
- The compile and project kernels take a trailing options bag: `compileRecord(def, config, { kinds, context, page })`, `projectAnalysis(def, config, result, { totals, kinds, context, limits })`.
- Every `limits` option takes a partial `RuntimeLimits`. `validateDashboard`'s `definitions` is `lookup`.
- `actionHarness`'s `now` is a clock (`() => new Date(at)`).
- `fetchRecord` and a value candidate source's `search` take `{ signal }`.
- Renamed:
  - `refusedScopeFilter`, `scopeFilterIssues`, `removeFixed`, `SummaryRow.over`, `RecordExportExtent`;
  - the `Board…` types are `Dashboard…`, and the workbench's `boardOrigin` is `dashboardOrigin`;
  - a binding's `reading` is `detail`;
  - `DashboardWorkbench` takes `defaultFilters` and `defaultTab`;
  - `DatasetResourcesOptions`, `UseDatasetsOptions`;
  - `RuntimeLimits.maxExportRows` and `maxListedViews`;
  - `builtinFieldKindRegistry`.
- `isRecordRuntime` accepts any runtime, or none.

## #7171 (en 1 / zh 1)
**A snapshot table registry names who fills a table by its wire name.** `fill` is `subscription` or `backfill` in every snapshot store, where a pre-release wrote `SUBSCRIPTION`/`BACKFILL`; the stores a 10.0 pre-release wrote are recreated with the upgrade, as its other stores are. **Elasticsearch indices a 10.0 pre-release created are recreated with the upgrade.** The snapshot table registry is mapped `dynamic: false`, as the snapshot and events indices are, so a field a later version adds is kept rather than refused; an existing registry keeps the mapping it was created with. A snapshot document names its `stateRevision`, and an events document holding access attributes names the `accessVersion` they are of: a pre-release's document without them is refused rather than read with a default.

## #7172 (en 1 / zh 1)
A state topic now has a record for every commit of a published aggregate: a commit whose events the aggregate all retired is published with no events, and a commit that only sets or cancels schedules with its schedule events. An aggregate's versions on its topic are so contiguous from its first record. A Wow subscription passes over a record with no events; an outside consumer reads `events` as possibly empty. Create a state topic written by a 10.0 pre-release again before a 10.0 release publishes to it.

## #7173 (en 1 / zh 1)
A command's event stream now ends with `end` `{"reason":"complete","resumable":false}`, the shape of a streamed list's `end`, where it was `{"reason":"complete"}`. A reader that takes `end` by its name, or reads only its `reason`, as Wow's clients do, is unaffected. The OpenAPI document now lists `end` among the stream's events, and gives an aggregate ref's `id` as a string or null: null in the result of a creation refused before admission chose its id.

## #7174 (en 1 / zh 1)
A node now refuses to start when a type's events queries would be served by a store that cannot keep the history's access attributes current, naming the type, the store and the missing capability. Wow's own backends all keep them current, so no deployment on them is affected. A log written outside Wow cannot keep them current in 10.0, so its store does not serve events queries: a node whose events model it would serve does not start.

## #7176 (en 1 / zh 1)
**wow-view-engine: the names no host used leave the entries.** The root entry, `/react` and `/ui` no longer export 156 names that only the package itself used: its parts, its internal hooks and helpers, and the types only they named. The release notes list them. A host that imported one has three ways out. It can compose the documented components and hooks instead. It can copy the few lines it needs. Or it can open an issue for the name, which then comes back with a reference page. Import `ViewRouteOf` from the root entry, not `/ui`. Tell a reference-label timeout apart with `isReferenceLabelTimeoutError(error)`.

## #7177 (en 1 / zh 1)
This version runs CoSec 8.0.0, CoSec 8's release. From 8.0.0-rc.1 or rc.2 there is nothing to change and no order to upgrade in: no JVM source of CoSec changed since rc.1. A front end on `@ahoo-wang/cosec-fetcher` installs 8.0.0 without a tag, and may move to fetcher 8 or stay on 6.1. CoSec 8's images publish no `latest`, so pin the gateway by version or digest. `wow-cosec` stays experimental in 10.0: a 10.x minor release drops the marker.

## #7178 (en 1 / zh 1)
**`wow_catalog` names its closed sets in lower case, keeps a sensitivity level it does not know, and the ClickHouse Sink refuses an insert a table has no place for.** `ownership` is `owner` or `tenant`, a sensitive `level` `display` or `confidential`, `access` `policy` or `default`; a tool that reads `wow_catalog` itself reads those names. A sensitivity level a node does not know, a later version's, is kept in `wow_catalog` as written and masked as `CONFIDENTIAL`, never less. Every insert of the Sink refuses a field the table has no column or tuple element for, under every `ddl` mode, where ClickHouse dropped it: a pre-release table kept under `ddl: none` parks the aggregate until it is recreated (drop it and raise the Sink's generation). The parked entry's reason names ClickHouse's error code, and the node's warning of the park names the field.

## #7179 (en 1 / zh 1)
The runtime's options are named as the configuration keys that set them: `ClusterOptions.maxInFlightPerNode`, `SnapshotOptions.maintenanceInterval`, `rebuildPageSize`, `rebuildMaxPages`, `AccessStampOptions.stampBatch`, `TimerOptions.claimTtl`, `SourceTopics.partitionRefreshInterval` (also `SourceTopics.of`'s parameter), with their defaults. `wow-cosid`'s `CosIdProperties` is `WowCosIdProperties`. A backend's TCK test builds a stream it refuses with `stream.withEvents(...)`.

## #7180 (en 1 / zh 1)
A `wow.storage.unavailable` problem on the operator's parked-entry routes (`/wow/ops/parked/…`) and on the internal port's peer reads is titled "Storage unavailable", the code's title everywhere else, where it was "Service Unavailable". Its code, status, detail and `Retry-After` are unchanged, and a client keys on the code. The OpenAPI document now lists 414 and 431 on every operation, the server's own refusals of a request line or headers past its limits, and declares the `<context>` tag its service routes carry: a client generated from it gains those two responses.

## #7183 (en 1 / zh 1)
A subscription that reads another context's state topic now parks an aggregate when a record arrives above the version after the last one it saw, at the version missing, and holds the aggregate's later records behind it. Its automatic retries resolve it in order once a late record arrives. An operator's retry accepts the gap and goes on from the next version present, as for a commit the publishing service skipped; a skip drops the held records. The parked reason names `wow.subscription.version-gap`.

## #7185 (en 1 / zh 1)
**The internal port's readers compare the format of what they read.** A node refuses a forward of a format it does not read (`refused: "unknown-format"`) and one it cannot read at all (`refused: "malformed"`, where it answered 400), so its sender runs the command itself; it answers 400 to wait signals of another format and to a `Wow-Call` of another (`wow.command.malformed`), and reads a forward's reply of another format as an unknown outcome. The cluster channel's forward and reply name their format `format`, where they named it `v`, and `Wow-Call` carries one. A batch of wait signals refused as too large is sent again in halves instead of dropped. Every structure is format 1, so nothing changes between nodes of this version.

## #7186 (en 1 / zh 1)
The request ids Wow gives its own commands now start with `wow:`: a timer's (`wow:<timer>#<slot>`), a saga's derived commands and compensations, the calls a decide function sends, and the ledger's records. No caller's request id may: a `Wow-Request-Id` (or `CoSec-Request-Id`, or an in-process `CommandRequest.requestId`) that starts with `wow:` is refused as `400 wow.command.malformed`. Otherwise anyone could commit first under an id the framework will send, which settles the framework's command. A command handled again across the upgrade derives the new id, so a saga or a timer whose command an old node already sent would send it again under an id its target does not recognize. Let the sagas and the timers catch up before the rolling update, as for the previous derived-id change: no saga lag, and no timer due within the update.

## #7189 (en 1 / zh 1)
The service plugin's task that renders the Kubernetes manifests is `generateWowKubernetes` (it was `generateWowManifests`): a build script or CI that names it changes the name. The constants of `WowPlugin`'s companion other than the plugin ids (`ID`, `API_ID`, `SERVICE_ID`) are internal; a build script names the tasks and configurations by their strings.

## #7191 (en 1 / zh 1)
The internal port's call routes now refuse a path with matrix parameters, as the public port does: a call to `/shop/cart/c-1;x=1/add-item` is answered 400 `wow.request.malformed`. A Wow client is not affected.

## #7192 NONE: None: records only.

## #7194 (en 1 / zh 1)
A record a service publishes on its state topics no longer carries the client's address or `User-Agent` (`remote_ip`, `user_agent`) among its `headers`. They are personal data, which stay with the service the request reached, in its log and its own stores. `app_id` and the request facts the host lists go on. A consumer in another context that read them reads them from the source service's own records instead, if it is allowed to.

## #7195 (en 1 / zh 1)
With `spring.main.lazy-initialization=true`, a refused `wow.health` configuration (`parked-degraded-at` past `wow.node.max-parked`, a liveness stall timeout not longer than the cluster's reconcile timeout) now refuses the node's start, as it does without lazy initialization, instead of failing its first health probe.

## #7197 (en 1 / zh 1)
The `wow-service` chart now refuses a `config` key that names one of the keys it sets itself in any spelling Spring binds (such as `WOW_WAIT_MAX_TIMEOUT` or `server_port`), as it already refused them in `settings` and `env`; such a key used to be rendered and silently shadowed. Remove it, or set the chart's own value the message names.

## #7202 (en 1 / zh 1)
A request body that stalls, with nothing of it arriving for 30 seconds, is now answered `408` and its connection closed, on the public port and the internal port. So is a body still arriving after its request was answered (a `401`, a `404`) that stalls as long. `wow.http.read-timeout` sets the bound. It times the gap between two reads, not the whole request: an upload that keeps sending is not cut however long it takes, and neither is an answer, a query stream or a wait. A client that must pause its body for longer raises the key on the service. The timeout counts the time nothing of the body is read, whoever stops the reads: an application's own route that reads its body as a stream and asks for none of it for 30 seconds (subscribing late, pausing between pieces) is answered `408` too, while its client is still sending; raise the key, or read the body without pausing. A client that hangs up mid-request is no longer logged as an unexpected error.

## #7203 (en 1 / zh 1)
**wow-view-engine: one word for each thing.**
- The host's resolver for remote candidates is `ViewEngineOptions.optionSource` (it was `resolveOptions`), as the engine's `ViewEngine.optionSource` is.
- A date column's bucket still under way is `AnalysisColumnView.unfinished` (it was `ongoing`). A metric card whose only period has not ended has `MetricPeriod.unfinished` (it was `partial`).
- `/react`:
  - read a view list again with `ViewListState.refresh` (`reload`, with `ViewListRefreshOptions`);
  - `destinationBoard` is `destinationDashboard`, `toBoard` is `toDashboard`, and `SaveCommands.boards` is `dashboards`.
- `/ui`: `RecordViewProps` is `RecordViewOptions`, `ExportWindowProps` is `ExportWindowOptions`, and `BUILT_IN_PRESETS`/`BuiltInPreset` are `BUILTIN_PRESETS`/`BuiltinPreset`.

## #7205 (en 1 / zh 1)
A `Wow-Scope` header that does not parse (a member given twice, a member with no value, several tenants on a route other than a query's) is now answered `400 wow.request.malformed` on every route. Before, it was `wow.command.malformed` on a command, `wow.query.malformed` on a read or a dataset route, and `wow.route.malformed` on a service route. What a well-formed `Wow-Scope` names and the type refuses keeps its route's code. A client that matched one of the old codes for this header matches `wow.request.malformed`.

## #7206 (en 1 / zh 1)
A state read and a query model's or a dataset's descriptor, the answers Wow tags with an `ETag`, now carry `Cache-Control: private, no-cache`, their 304 too. A shared cache in front of a service (a CDN, a caching proxy) stores none of them, since each depends on its caller. A client's own cache keeps them and revalidates with `If-None-Match`, a 304 when nothing changed. A deployment that cached state reads at its gateway stops doing so; it was answering one caller with another's.

## #7207 (en 3 / zh 3)
The OpenAPI document a running service serves now lists what its host adds to the routes:

- the headers `wow.http.request-facts` names, and an integration's request id header beside `Wow-Request-Id` (`CoSec-Request-Id`), as parameters of every command;
- with CoSec on, its 429 `wow.request.rate-limited` and 503 `wow.auth.unavailable` on every operation.

A client generated from the document sees those headers and statuses. The document a build writes is unchanged. `OpenApiOptions` takes three more constructor parameters; a call that names its arguments is unchanged.

## #7208 NONE: None: records only.

## #7209 NONE: None: the version only.

## #7210 (en 1 / zh 1)
With `wow.cosec.authorizer.enabled`, CoSec's authorizer now denies a command, Wow's operational commands included, and a service route call whose caller is not the principal CoSec authenticated for the request. Such a caller comes from a `CallerResolver` of your own that maps CoSec's principal to another subject or tenant, or answers anonymous callers, or from code that sends a command as another caller while it handles a request. It is answered `403 wow.auth.forbidden` rather than allowed. Send such a command as the request's caller, or decide those callers with a `CommandAuthorizer` of your own instead of the switch. Commands no request carries (sagas, timers, scheduled jobs, the internal port) are unchanged. The switch set where CoSec does not decide the requests in the service now refuses the start and says why: behind a CoSec gateway (`wow.cosec.mode: gateway`), with CoSec off, or without wow-cosec. Remove it there; the gateway authorizes those requests.

## #7215 (en 1 / zh 1)
**A body with no `Content-Type` is 415.** A command, a query, a service route that takes a body and an operator's route answered a body sent with no `Content-Type` as JSON; they now refuse it `415`, as they refuse one of another type, since a browser sends a cross-site body without a preflight with no type as well as with a simple one. Send `Content-Type: application/json` with every body: Wow's Kotlin and TypeScript clients do, and so does `curl` with `-H 'Content-Type: application/json'`. A request with no body needs none.

## #7216 (en 1 / zh 1)
**A model's descriptor asks the read authorizers.** The `GET` of a query route, and of a dataset's, is now refused to a caller the service's `ReadAuthorizer` beans refuse, with their problem (`403 wow.auth.forbidden` unless they name another code), as a query of the model is. The authorizers are told the operation `QueryType.DESCRIBE`: one that allows only some operations decides the descriptor as well, so a client that offers a model's fields to a user the authorizers admit keeps working.

## #7217 (en 2 / zh 1)
**PostgreSQL reads a field named `null`.** A snapshot query on PostgreSQL read a property or a map key named `null` (in any case) as missing: a filter on it matched no row. It now reads the value the field holds. An expression index on such a field is planned anew; no other index changes.

Fixes #7212

## #7218 (en 2 / zh 1)
**Long ignore-case operands and lone surrogates are queries to mend.** On PostgreSQL an ignore-case `contains`, `starts-with` or `ends-with` longer than about 8,000 letters was answered and is now refused `400 wow.query.invalid` (`storage-unsupported`): shorten the operand or match case-sensitively. On ClickHouse one longer than about 16,000 letters, and any text operand past its 128 KiB query parameter, is `400` instead of a storage failure. A text operand or a filter's id holding an unpaired surrogate is `value-mismatch` on every store: PostgreSQL and ClickHouse read it as `?` and answered rows it never named.

Fixes #7211

## #7219 (en 2 / zh 1)
**MongoDB refuses a metric aliased `_id`.** An aggregation on MongoDB whose metric is aliased `_id`, the field a group's key is kept in, answered rows that were not what it asked; it is now refused `400 wow.query.invalid` (`storage-unsupported`). Give the metric another alias; a group aliased `_id` is taken.

Fixes #7213

## #7220 (en 2 / zh 1)
**A non-ASCII digit in a dataset expression is a problem of the definition.** An analytics catalog dataset whose expression held a decimal digit of another script (`٣`, `３`) failed its command with an unexpected error; it is now refused as an expression the catalog cannot read, naming the digit. Write numbers with ASCII digits.

Fixes #7214

## #7222 NONE: None: records only.

## #7225 NONE: None: the benchmark harness only.

Fixes #7223

## #7227 NONE: None: records only.

## #7228 NONE: None: records only.
