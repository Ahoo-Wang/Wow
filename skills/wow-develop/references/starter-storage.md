# Starter, Storage, and Bus Decisions

Use this reference for Spring Boot configuration, feature capabilities, auto-configuration, event/snapshot storage, routing, buses, and backend selection.

## Source of truth

Configuration keys and defaults drift. Resolve them from the target version's `@ConfigurationProperties`, auto-configurations, Gradle feature variants, and tests.

```bash
rg -n "@ConfigurationProperties|class .*Properties|data class .*Properties" . -g '*.kt'
rg -n "registerFeature|capability\(|ConditionalOn|AutoConfiguration" . -g '*.gradle.kts' -g '*.kt'
rg -n "StorageRouting|EventStore|SnapshotStore|CommandBus|EventBus" . -g '*.kt' -g '*.java'
```

In this repository, start with `wow-spring-boot-starter`, the selected backend module, `wow-dependencies`, `settings.gradle.kts`, and nearby starter tests.

## Decision sequence

1. Resolve the actual dependency graph and selected Gradle capability/variant.
2. Identify the property class and conditional auto-configuration that owns the behavior.
3. Verify which store/bus bean is created and which route selects it.
4. Check the bounded-context, aggregate, tenant, database/namespace, and ownership boundary.
5. Verify backend prerequisites, initialization, indexes, serialization, and health/readiness behavior.
6. Test the smallest configuration slice, then cross the infrastructure boundary only when required.

## Safety rules

- Do not infer a property from YAML examples; read its class and binding tests.
- Do not add a dependency when the intended feature is exposed as an existing capability/variant.
- Do not change default storage or bus selection from a component benchmark alone.
- Do not reuse a database, namespace, stream, or ownership marker across contexts without verifying the store contract.
- Treat index reconciliation, batching, migration, and destructive initialization as operational behavior requiring explicit scope and rollback evidence.
