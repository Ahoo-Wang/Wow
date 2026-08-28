# Migration Risk Map

Use this reference to build a target-specific migration matrix. Every concrete API, version, default, storage layout, and removed symbol must be verified against the existing application and the pinned target tag or release.

## Platform and dependency surface

Inventory resolved versions and compatibility for Java, Kotlin/KSP, Spring Boot, Jackson, Reactor, build plugins, Wow BOM/modules, and third-party integrations. Inspect dependency resolution rather than declarations alone, and keep compile/test configurations separate from the launched application's `runtimeClasspath`.

Compare the pinned Wow tag, BOM, official template, selected starters/storage modules, and published metadata. For Gradle feature variants, verify from the target tag whether the application needs both the base starter and a capability-qualified starter declaration; prove both `compileClasspath` and `runtimeClasspath` instead of assuming the capability retains the base API. When a target Spring Boot release splits auto-configuration into new modules, map every critical class referenced by the selected Wow modules to its owning target artifact and prove that artifact is present at runtime. A successful build or compile classpath is not evidence of runtime presence.

## Source and generated contracts

Search for:

- removed or changed annotations, handlers, gateway/wait APIs, `FilterExpression`/legacy `Condition` rewrite APIs, Query DSL, lifecycle ownership, stores, buses, and extension points;
- custom compiler/KSP assumptions and generated metadata;
- OpenAPI, JSON Schema, client SDK, serialization, and event revision outputs;
- custom auto-configuration, `@ConfigurationProperties`, exclusions, and bean overrides;
- custom `SnapshotQueryService`, `SnapshotStore`, `EventStore`, bus, processor, lifecycle, or routing implementations.

For each item record current evidence, target-tag evidence, required action, owner, verification, and rollback effect.

Source compatibility is not runtime capability: a new default interface method and generated route can compile while a custom `SnapshotQueryService` still falls into an unsupported `aggregate` implementation. When the target publishes aggregation, prove the selected service overrides or delegates `aggregate`, then call the generated endpoint for every snapshot backend actually used.

## Wow 8.14.x to 8.15.0 query entry rename

When the pinned source is 8.14.x and the target is 8.15.0 or later, apply this source/configuration migration without compatibility aliases:

| 8.14.x | 8.15.0+ |
|---|---|
| `me.ahoo.wow.query.filter.QueryHandler` / `AbstractQueryHandler` | `me.ahoo.wow.query.QueryGateway` / `AbstractQueryGateway` |
| `me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler` / `DefaultSnapshotQueryHandler` | `me.ahoo.wow.query.snapshot.SnapshotQueryGateway` / `DefaultSnapshotQueryGateway` |
| `me.ahoo.wow.query.event.filter.EventStreamQueryHandler` / `DefaultEventStreamQueryHandler` | `me.ahoo.wow.query.event.EventStreamQueryGateway` / `DefaultEventStreamQueryGateway` |
| `snapshotQueryHandler` / `eventStreamQueryHandler` bean | `snapshotQueryGateway` / `eventStreamQueryGateway` bean |

Change custom query-filter `@FilterType` targets to the corresponding Gateway. `QueryGateway` no longer extends `Handler` or exposes `handle(QueryContext)`; direct implementations must implement `aggregate`, and Gateway `count` accepts only `FilterExpression`. Do not remove aggregate `QueryService` injection, `QueryServiceProxy`, either `QueryServiceRegistrar`, backend `QueryService`, or its factory: managed aggregate services route through the Gateway, while direct factory access remains a trusted raw path that bypasses its policy chain.

The rename alone does not change HTTP/OpenAPI query shapes, wire formats, or stored events/snapshots, so it needs source compilation, Spring bean/qualifier startup, and representative managed-service/WebFlux query verification, but no data conversion. Reassess that conclusion if the same release also changes application schemas, storage layouts, or writers.

## Runtime and data coupling

Identify every writer, reader, database/namespace, bounded context, aggregate route, ownership marker, stream/topic, snapshot/event format, PrepareKey store, index, and background process. Determine whether source and target versions can safely coexist; assume they cannot unless the pinned contract proves otherwise.
