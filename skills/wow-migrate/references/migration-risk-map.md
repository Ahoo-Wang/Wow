# Migration Risk Map

Use this reference to build a target-specific migration matrix. Every concrete API, version, default, storage layout, and removed symbol must be verified against the existing application and the pinned target tag or release.

## Platform and dependency surface

Inventory resolved versions and compatibility for Java, Kotlin/KSP, Spring Boot, Jackson, Reactor, build plugins, Wow BOM/modules, and third-party integrations. Inspect dependency resolution rather than declarations alone.

## Source and generated contracts

Search for:

- removed or changed annotations, handlers, gateway/wait APIs, Query DSL, lifecycle ownership, stores, buses, and extension points;
- custom compiler/KSP assumptions and generated metadata;
- OpenAPI, JSON Schema, client SDK, serialization, and event revision outputs;
- custom auto-configuration, `@ConfigurationProperties`, exclusions, and bean overrides;
- custom `SnapshotStore`, `EventStore`, bus, processor, lifecycle, or routing implementations.

For each item record current evidence, target-tag evidence, required action, owner, verification, and rollback effect.

## Runtime and data coupling

Identify every writer, reader, database/namespace, bounded context, aggregate route, ownership marker, stream/topic, snapshot/event format, PrepareKey store, index, and background process. Determine whether source and target versions can safely coexist; assume they cannot unless the pinned contract proves otherwise.

## Proof sequence

1. compile affected modules;
2. regenerate and review contracts;
3. run domain and processor tests;
4. run store/bus integration tests;
5. verify replay and snapshot regeneration;
6. verify runtime readiness, drain, and shutdown;
7. rehearse data and deployment using `cutover-evidence.md`.

Compilation proves only source compatibility. Startup proves only that one configuration path initialized.
