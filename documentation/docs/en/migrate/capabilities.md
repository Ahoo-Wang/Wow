---
title: 9.x Capabilities in V10
description: Where each user-facing capability of Wow 9.x went in V10 — kept, replaced by another mechanism, dropped, or planned for a later 10.x release.
outline: deep
---

# 9.x Capabilities in V10

Use this page to find out, before you migrate, what happens to each 9.x capability your service uses. Each row says where the capability went:

- **Kept**: V10 has it.
- **Replaced**: V10 provides it another way. The note says how; the `wow-migrate` Skill rewrites code that uses it.
- **Dropped**: V10 does not provide it, on purpose. The note says what to do instead.
- **10.x**: not in 10.0; planned for a later 10.x release.

## Commands

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Command gateway: send, wait, execute | Kept | Coroutine-based; results arrive as a flow of command results. |
| Reactor-shaped command gateway | Dropped | Call the gateway from coroutines, or from Java through the client's `CompletableFuture` facade. Handlers may still return `Mono` or `Flux`. |
| Wait stages `SENT`, `PROCESSED`, `SNAPSHOT`, `PROJECTED`, `EVENT_HANDLED`, `SAGA_HANDLED` | Replaced | Three stages, `ACCEPTED`, `COMMITTED` and `HANDLED(subscription)`, with `snapshot` as the default. See [Waiting for outcomes](../concepts/waits). |
| Wait chain tail headers (`Command-Wait-Tail-*`) | Replaced | A multi-hop `Wow-Wait` plan. |
| Waiting across contexts | Kept | Signals go straight to the originating node. |
| Server-sent events for results | Kept | One event per hop reached. |
| Request id idempotency | Kept | The request id is unique per tenant and aggregate type; a reuse with another command type is refused. |
| Bloom filter idempotency checker | Replaced | Each cached aggregate keeps its most recent requests, derived from the log; storage answers the rest. |
| Command rewriter (`CommandBuilderRewriter`) | Replaced | A command transformer at admission; it may not change the command's identity. |
| Commands mounted on another aggregate (`@AggregateRoot(commands)`) | Replaced | `@MountedCommand`, and `@Unaddressed` for commands that do not name their aggregate. |
| Command validation (Jakarta Bean Validation) | Replaced | Validation at admission: Jakarta constraints on the command class, checked for every command (HTTP, in process, sagas, other contexts) and answered `wow.command.invalid` (400). A rule that needs the state is a rejection. |
| `@VoidCommand` | Dropped | Send the command and do not wait past `ACCEPTED`. |
| Custom routes (`@CommandRoute`, `@AggregateRoute`) | Dropped | Routes are derived from the Manifest: `POST /{context}/{aggregate}/{id}/{command}`, or without `{id}` for a creation. |
| `Command-*` request headers | Replaced | `Wow-Wait`, `Wow-Wait-Timeout`, `Wow-Request-Id`, `If-Match`, `Wow-Scope`, `Wow-Space`. |
| Request header aliases | Dropped | Rewrite the headers in your gateway. |
| Request facts copied from headers | Kept | A configured list of headers, plus the authenticated application, the remote address and the user agent. |
| Operator of a command | Kept | Recorded with every commit. |
| Global and per-aggregate id generation, CosId | Kept | Also available to decide through the decide scope. |
| Command batching | Replaced | The partition owner commits in batches. |
| Extra command results (`CommandResultAccessor`) | Replaced | Decide returns extra results through the decide scope; they are not stored, so a duplicate does not get them. |
| Unique keys across aggregates (`PrepareKey`, `@PreparableKey`) | Replaced | Reserved keys through the decide scope, stored beside the log; the data tool imports the confirmed 9.x keys. |
| Looking up a `PrepareKey`, its value, its time to live | 10.x | A read-only lookup of reservations is planned. Time to live is dropped: schedule a command that releases the key. |
| Expected version | Kept | `If-Match`; a mismatch answers 412. |
| Command buses: in-memory, local-first, Kafka | Replaced | Partitioned routing over the cluster channel by default; topic routing per aggregate type for durable intake. |
| Redis command bus | Dropped | — |
| One command endpoint (`/wow/command/send`) | Replaced | One route per command. |
| Command filters and instrumentation | Replaced | An observation port, implemented by the observability module (`wow-observability`). |
| Error code to HTTP status mapping | Kept | Declared with `@ErrorCode`, or configured per code. |
| Domain exceptions converted to `ErrorInfo` | Replaced | A decision returns a rejection with a declared error code. |

## Domain model

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Core annotations | Kept | A smaller, redesigned set in the same package. |
| Event revisions and upgraders (`EventUpgrader`) | Replaced | `@Revision`, `@Upgrade` and `@Alias`. |
| Dropped events (`DroppedEvent`) | Replaced | Retired events, listed on the aggregate. |
| Fixed tenant (`@StaticTenantId`) | Replaced | A fixed tenant per aggregate type in configuration. |
| Fixed aggregate id (`@StaticAggregateId`) | Dropped | — |
| Owner and space policies (`@AggregateOwner`, `@Spaced`) | Replaced | Ownership per aggregate type, owner or tenant; scope comes from the caller. See [Identity, tenancy and access](../concepts/identity). |
| Identity annotations on command fields (`@AggregateId` and others) | Dropped | The aggregate comes from the route or the message; command bodies hold business data only. |
| Tags derived from state (`StateAggregateTagsExtractor`) | Replaced | `@Tags` on the state. |
| Applying tags | Kept | The `ApplyTags` lifecycle command. |
| Lifecycle commands: delete, restore, transfer owner or space | Kept | — |
| `@EntityObject`, `@ValueObject`, `@Order` | Dropped | — |
| `@OnError` | Dropped | Return a rejection from decide, with events when the refusal is to be recorded; put compensation in a saga's `@OnRejected` handler. |
| Domain models written in Java | Kept | The compiler reads Java sources. A Java-friendly test API is planned for 10.x. |
| Domain services injected into command functions | Kept | Bound once, through the generated model factory. |
| Error events (`IgnoreSourcing`) | Replaced | A rejection that carries the events (`reject(code, message, events)`), each with a sourcing function, empty when it changes nothing; they are committed with the rejection. |
| Aggregate with a separate state class | Kept | — |
| `@AfterCommand`, `@Blocking`, `@CreateAggregate`, `@AllowCreate` | Kept | — |
| Snapshot system fields `eventId`, `snapshotTime`, `eventTime` | Replaced | `firstEventTime`, `lastEventTime`, `operator`, `firstOperator` and the other snapshot fields. |

## Events and storage

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| MongoDB event store | Kept | A first-class log backend; PostgreSQL is the other. |
| Redis event and snapshot stores | Dropped | The data tool reads only the 9.x MongoDB store: move Redis data to MongoDB in 9.x first. |
| Elasticsearch event store | Dropped | Elasticsearch receives copies of snapshots and event streams for search; it never holds the log. Move 9.x data to MongoDB before migrating. |
| MongoDB and Elasticsearch snapshot stores | Kept | PostgreSQL as well. |
| Snapshot strategy (every n versions) | Replaced | A snapshot is written for every version, batched per aggregate. |
| Turning snapshots off for an aggregate | Kept | New in V10. |
| Storage per aggregate (`storage-routing`) | Kept | Named stores, chosen per aggregate for the log and the snapshots. |
| Regenerating snapshots, one or in bulk | Kept | A system command, and automatic rebuilds when the state revision rises. |
| Historical state: by version, by time, traced | Kept | `GET …/state?version=`, `?at=` and `…/state/trace`. |
| Reading one aggregate's event stream | Kept | The `events` query model. |
| Recovering or skipping failed processing | Replaced | Parking and the failure ledger, operated through the ops service. |
| Targeted replay of a version range; resending state events in bulk | 10.x | Until then, resume parked aggregates or rebuild a whole subscription. |
| Re-dispatching stored events to one handler (`DomainEventCompensator`), as a backfill | Replaced | A handler of a new name that starts at the beginning of its stream reads the whole history once. See [Rebuild and Replay](../operate/replay). |
| Domain and state event buses | Replaced | The log is the bus; other contexts read published streams on Kafka. See [The log is the bus](../concepts/event-log). |
| Creating collections and indexes | Kept | Managed by the stores' DDL setting. |

## Processors and subscriptions

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Sagas, projections, event processors | Replaced | `@Saga`, `@Projection` and `@Reactor`; each handler function is its own subscription. |
| `@OnStateEvent`, `@OnMessage` | Replaced | `@OnEvent`, taking the state event, or one event type of it. |
| Handling another context's events | Kept | Through its published stream. |
| Decoding another context's events into shared classes | Kept | With the other context's API jar on the classpath. |
| `@Retry`: attempts, backoff, timeout | Replaced | V10's `@Retry`, configurable per subscription, then long-horizon retries of parked aggregates. |
| Recoverable and unrecoverable exception classes | Kept | On `@Retry`, plus pluggable failure classifiers. |
| Where a subscription starts | Kept | New in V10: declared on the handler or configured. |
| Periodic jobs in an external scheduler (Simba, `@Scheduled`) | Replaced | `@OnSchedule` jobs of a saga, on cron rules, with no external lock. A job that waits for each command's outcome before the next stays outside and sends through the command client. See [Scheduled Commands](../guide/scheduling). |
| Processor filters, interceptors, `@Order` | Dropped | — |
| Skipping Kafka messages that do not decode | 10.x | V10 parks them. |
| Reading other aggregates' state or querying in a handler | Replaced | The typed query API, available to handlers. |

## Queries

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Single, list, paged, cursor, count and aggregation queries | Kept | — |
| Filter expressions, relative time, metadata filters, aggregation windows | Kept | — |
| The `Condition` protocol | Dropped | Use filter expressions; the Skill rewrites conditions. |
| Kotlin query DSL and generated `<State>Properties` | Kept | — |
| Wow query types stored in events or state (`Condition`, `Sort`, `Projection`) | Dropped | The kernel's JSON has no codec for the query protocol: store a type of your own and upgrade the 9.x JSON into it. |
| Query schema overrides on the classpath (`META-INF/wow/query-schema`) | Dropped | The schema comes from the state classes; move each override onto the state as a field annotation. |
| Implementing or subclassing the query types (`IPagedList`) | Dropped | The V10 query types are final classes built with their factories or the DSL. |
| Query field annotations (`@Sensitive`, `@QueryAlias`, `@QueryReference`, …) | Kept | — |
| Masking sensitive fields, in queries and state reads | Kept | — |
| ABAC query policies, CoSec `abac.` attributes | Kept | Off by default. |
| Query policy SPI and scopes | Kept | — |
| Snapshot queries on MongoDB and Elasticsearch | Kept | PostgreSQL as well. |
| Event stream queries and aggregation across aggregates | Kept | One query engine for snapshots, events and datasets. |
| HTTP query routes (`snapshot/list` and others) | Replaced | `POST /{context}/{aggregate}/{model}/query`, with the query kind in the body. |
| Streaming a list | Kept | Server-sent events that resume where they stopped. |
| Query schema routes and refresh | Replaced | `GET` on the query route describes what the model can do; the schema comes from the Manifest. |
| Datasets: catalog, gateway, routes | Kept | — |
| Dataset registration (dataset-registry) | Replaced | The analytics catalog, a platform service. |
| ClickHouse dataset backend | Kept | — |
| Query budgets, bounded counts, cursors | Kept | Plus a query bulkhead per store. |

## Messaging, BI and external stores

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Kafka transport | Replaced | A sink for published streams, subscriptions to other contexts' streams, command topics and receipts. |
| Kafka topic prefix and topic management | Kept | — |
| Separate producer and consumer settings | 10.x | One `wow.kafka.properties` until then. |
| BI sync scripts (`/wow/bi/script`) | Replaced | The ClickHouse sink writes the warehouse directly, with flattened views for datasets. |
| Nested expansion of state in BI tables | 10.x | — |
| External consumers of 9.x topics | Dropped | Move each consumer to the V10 published stream at the cut-over. There is no 9.x-format sink. |

## Tenancy and security

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| CoSec integration | Kept | — |
| Tenant and owner route prefixes | Dropped | Identity comes from the caller; a caller allowed to act elsewhere names the scope in `Wow-Scope`. |
| Propagating the CoSec principal to messages | Replaced | The operator and the propagated request facts travel with every command a saga sends. |
| Contexts across tenants | Kept | — |

## Contract, OpenAPI and HTTP

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| `wow-metadata.json` and `/wow/metadata` | Replaced | The Manifest and `GET /wow/manifest`. See [One contract](../concepts/contract). |
| JSON Schema generation | Replaced | Schemas generated at build time from Jackson's own view of the classes. |
| Registering custom Jackson modules | Dropped | Wow's JSON registers a fixed set of modules, so the wire form does not depend on the host. |
| OpenAPI document | Kept | Derived from the Manifest; also a Gradle task. |
| springdoc integration | Kept | — |
| Error bodies (`ErrorInfo`) | Replaced | `application/problem+json` (RFC 9457) with the error code. |

## Observability

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| OpenTelemetry tracing | Kept | Every metric and span is renamed: [Metrics and Spans](./metrics). |
| Metrics | Kept | — |
| Health indicators | Kept | New in V10: readiness and liveness groups for Actuator. |

## Testing

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Aggregate tests | Kept | The V10 test DSL, artifact `wow-test`. |
| `fork`, `givenState`, `expectError` by type | Kept | — |
| Saga, projection and reactor tests | Kept | — |
| Java test API | 10.x | The test DSL is Kotlin-only until then. |
| `TestValidator` | Dropped | — |
| TCK and Testcontainers fixtures | Kept | — |
| `wow-mock` | Dropped | — |

## Platform services

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Compensation service | Replaced | The ops service and console over the services' failure ledgers. The data tool imports unresolved executions. |
| Long-horizon automatic retries | Kept | — |
| Resume, skip, mark recoverable, apply a retry spec | Kept | — |
| Changing an execution's function; forced prepare | Dropped | — |
| WeCom (WeChat Work) webhook | Kept | And a generic webhook. |
| Console links and templates in notifications | 10.x | — |
| Stack traces in the console | Dropped | — |
| Daily trend analysis in the console | 10.x | — |
| Editable boards in the console | Dropped | — |
| View store | Kept | — |
| Dataset registry and analytics service | Replaced | The analytics catalog. |

## Extension modules

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| `wow-models` | Kept | — |
| `wow-cocache` | Replaced | One shared cache per aggregate type, written by the context that owns it and read as `StateViewCache`; a reader's own index and join caches have no counterpart. |
| `wow-apiclient` | Replaced | Typed command and query clients that run in-process for the service's own contexts and over HTTP for others. See [Between contexts](../concepts/cross-context#typed-clients). |
| CosId | Kept | Through `wow-cosid` on CosId 5, for aggregate ids: segment generators move to CosId 5's configuration, and their state with CosId's migration tool. |
| Spring integration and starter features | Replaced | One Spring Boot host module, wiring each backend by classpath. |

## Configuration and operations

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| `wow.*` configuration | Replaced | New keys. An unknown `wow.*` key stops the service from starting, naming it. |
| Graceful shutdown | Kept | — |
| Failing fast without a durable store | Kept | — |
| Maven builds | Dropped | V10 needs Gradle for its compile-time binding. |
| TypeScript client generator | Kept | It reads the Manifest. |

## TypeScript

| 9.x capability | In V10 | Notes |
| --- | --- | --- |
| Command client and waiting over SSE | Kept | — |
| Snapshot, event, dataset and description clients | Kept | — |
| `Condition` and localized operators | Dropped | — |
| Reading state by owner path | Dropped | Identity comes from the caller. |
| React query hooks (`wow-react`) | Kept | — |
| View engine and view store clients | Kept | — |

## Data migration

| 9.x source | In V10 | Notes |
| --- | --- | --- |
| MongoDB event store: import, follow, compare, snapshots | Kept | See [Migrate the data](./data). |
| Redis event store | Dropped | Move it to MongoDB in 9.x first. |
| Elasticsearch event store | Dropped | Move it to MongoDB in 9.x first. |
| `PrepareKey` keys | Kept | Imported as confirmed reservations. |
| Unresolved compensation executions | Kept | Imported as parked aggregates. |
