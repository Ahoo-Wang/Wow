# Migration Risk Map

Use this reference to build a target-specific migration matrix. Every concrete API, version, default, storage layout, and removed symbol must be verified against the existing application and the pinned target tag or release.

## Platform and dependency surface

Inventory resolved versions and compatibility for Java, Kotlin/KSP, Spring Boot, Jackson, Reactor, build plugins, Wow BOM/modules, and third-party integrations. Inspect dependency resolution rather than declarations alone, and keep compile/test configurations separate from the launched application's `runtimeClasspath`.

Compare the pinned Wow tag, BOM, official template, selected starters/storage modules, and published metadata. When a target Spring Boot release splits auto-configuration into new modules, map every critical class referenced by the selected Wow modules to its owning target artifact and prove that artifact is present at runtime. A successful build or compile classpath is not evidence of runtime presence.

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

1. compile affected modules and run focused tests;
2. resolve the application runtime graph and verify critical classes on `runtimeClasspath`;
3. regenerate and review contracts;
4. start an isolated target-version process and exercise the real REST stack using `runtime-rest-validation.md`;
5. run authorized store/bus integration tests;
6. verify replay and snapshot regeneration;
7. verify runtime readiness, drain, and shutdown;
8. rehearse data and deployment using `cutover-evidence.md`.

Compilation proves only source compatibility. Startup proves only that one configuration path initialized. Local HTTP evidence does not prove external integration, data reconciliation, deployability, or production readiness.
