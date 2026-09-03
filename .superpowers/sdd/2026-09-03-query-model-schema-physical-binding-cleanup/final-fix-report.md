# Final Fix Report: Query Model Schema Physical Binding Cleanup

## Scope

Applied every final-review finding in the current worktree, without adding a planner, physical query representation, interface, dependency, or wire/storage change.

## Changed Files

- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`
- `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QueryModelSchema.kt`
- `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QueryModelSchemaTest.kt`
- `documentation/docs/en/guide/query/v9-query-migration.md`
- `documentation/docs/zh/guide/query/v9-query-migration.md`
- `.superpowers/sdd/2026-09-03-query-model-schema-physical-binding-cleanup/final-fix-report.md`
- Deleted `.superpowers/sdd/2026-09-03-query-model-schema-physical-binding-cleanup/task-3-report.md`
- Deleted `.superpowers/sdd/2026-09-03-query-model-schema-physical-binding-cleanup/task-5-report.md`

## Final Review Finding Resolution

1. **Dynamic temporal COMPATIBLE fallback**
   - `MongoAggregationCompiler` now treats a missing `AGGREGATE_TEMPORAL` binding as a `$toDate` fallback only when the field is unknown or dynamically derived. It calls the existing `QueryModelSchema.resolvePhysicalField` path, preserving the accepted `COMPATIBLE` raw-path behavior.
   - A declared field without this capability still throws `QuerySchemaValidationException`.
   - Added a regression that first calls `schema.resolve(aggregation).requireAccepted(QuerySchemaValidationMode.COMPATIBLE)`, then compiles a dynamic temporal field and asserts `$toDate` with its original path. A second regression preserves rejection of a declared field without a temporal binding.

2. **Projection default physical path**
   - `QueryFieldSchema.projectionField` now defaults to the `PRESENCE` binding's `physicalField`.
   - Added a test where `resolvedField` and `physicalField` differ.

3. **V9 migration documentation**
   - English and Chinese migration tables now state that `FieldConverter`, `ProjectionConverter`, and `SortConverter` are removed. They direct users to concrete Backend `*Compiler` implementations and Schema bindings for physical paths.

4. **SDD scratch cleanup**
   - Removed only the two incorrectly tracked scratch reports requested by review. Other SDD plan artifacts remain untouched.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QueryModelSchemaTest"` | PASS |
| `./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest"` | PASS |
| `./gradlew :wow-query:check` | PASS |
| `./gradlew :wow-mongo:check` | PASS |
| `cd documentation && pnpm docs:build` | PASS |
| `git diff --check` | PASS |

The initial projection-default test failed as expected before the production change (`expected storage.name, but was document.name`). The documentation build restored 327 locked dependencies from the local pnpm content-addressable store; no network downloads were required.

## Self-Review

- The temporal fallback condition distinguishes declared fields (`schema.fields`) from derived dynamic fields, so the new compatibility branch cannot broaden declared-field capability admission.
- The dynamic regression exercises the same Schema admission call used by the Gateway before Backend compilation.
- The projection change affects only the default value; explicit projection paths remain unchanged.
- No Query JSON, Schema HTTP JSON, Mongo storage layout, Cursor wire contract, `ResolvedQuery`, or public query-planning surface changed.
- `git diff --check` is clean and the diff is limited to the listed review findings and required report.

## Concerns

No code concerns. Tool output retains existing Gradle 10 deprecation/Kotlin compiler notices and a VitePress Rollup chunk-size warning; all requested commands completed successfully.
