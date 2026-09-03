# Task 5 Report: Mongo aggregation, backend wiring, and factory cleanup

## Outcome

- Removed the two unused Mongo `FieldConverter` implementations.
- Added aggregation regressions proving Snapshot `aggregateId` and EventStream `id` use the physical path declared by `QueryFieldBinding`, rather than the legacy `_id` conversion convention.
- The aggregation compiler, count/find/cursor wiring, factories, and concrete compiler defaults were already schema-based in the starting commit (`0e5762879`, Task 3; `4f037f402`, Task 4). No further production wiring change was necessary.
- Preserved the optional `AbstractMongoFilterCompiler` backend constructor parameter for source compatibility; its defaults remain `SnapshotFilterCompiler` and `EventStreamFilterCompiler`.

## Actual changed files

- Deleted `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFieldConverter.kt`.
- Deleted `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFieldConverter.kt`.
- Updated `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`.
- Added this report: `.superpowers/sdd/2026-09-03-query-model-schema-physical-binding-cleanup/task-5-report.md`.

## Tests and checks

| Command | Result |
| --- | --- |
| `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest"` (before changes) | Exit 0, `BUILD SUCCESSFUL`; the brief's expected RED was already invalid because Task 3 had migrated the compiler and fixtures. |
| `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest"` (after regressions) | Exit 0, `BUILD SUCCESSFUL`; 38 actionable tasks, 4 executed. |
| `./gradlew :wow-mongo:test` | Exit 0, `BUILD SUCCESSFUL`; 38 actionable tasks, 4 executed. |
| `./gradlew :wow-mongo:check` | Exit 0, `BUILD SUCCESSFUL`; 39 actionable tasks, 1 executed. |
| `rg -n "FieldConverter|convertField" wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query` | No matches (`rg` exit 1, expected for no matches; the verification wrapper exited 0). |
| `git diff --check` | Exit 0 with no output. |

Gradle emitted pre-existing deprecation notices for Gradle 10 compatibility and redundant Kotlin annotation-target flags; neither is caused by this change.

## Self-review

- The new tests use non-`_id` physical identity paths, so restoring either legacy converter would fail them.
- Existing aggregation tests continue to cover physical element/root filters, temporal fields, dynamic accepted paths, and unsupported-capability failures.
- No `FieldConverter` or `convertField` references remain under Mongo query production code; `MongoCursorFilterCompiler` remains unchanged.
- No provider routing, backend binding, `ResolvedQuery`, or wire contracts changed.

## Unresolved concerns

- No code concern. The requested RED phase could not be observed because predecessor Task 3 had already applied the schema-only aggregation/compiler wiring before this task began.
