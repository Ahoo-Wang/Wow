# Task 5 Report: Adopt the Convention in Compensation and Document It

## Files changed

- Moved `compensation/wow-compensation-server/src/main/resources/indexs/execution_failed_index.json` unchanged to `compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json`.
- Added the packaged-resource contract to `compensation/wow-compensation-server/src/test/kotlin/me/ahoo/wow/compensation/server/ExecutionFailedQuerySchemaTest.kt`.
- Updated the English and Chinese Query Schema, Elasticsearch extension, and infrastructure configuration guides.

## TDD evidence

- RED: `./gradlew :wow-compensation-server:test --tests "me.ahoo.wow.compensation.server.ExecutionFailedQuerySchemaTest"` failed at `ExecutionFailedQuerySchemaTest.kt:51` because no resource was found at the unified Elasticsearch path.
- GREEN: after the mechanical move, the same focused test passed. It locates exactly one resource by the final snapshot index name, parses it as a native `CreateIndexRequest`, and verifies the index name and `state.status` keyword mapping.

## Verification

- `./gradlew :wow-compensation-server:check` passed.
- `cd documentation && pnpm docs:build` passed. The build emitted its existing Rollup chunk-size warning only.

## Documentation contract

- Query Schema documents unified classpath and working-directory paths, lowercase model segments, the reserved dot delimiter, legacy-only-on-miss fallback, and unchanged priority/merge/refresh behavior.
- Elasticsearch documents concrete snapshot resource locations, precedence and duplicate handling, fallback behavior, existing-index behavior, native validation, and independent creation requests.
- Infrastructure documents when concrete resources are processed and their independence from `auto-init-template`.

## Self-review

- The JSON content was not changed; only its packaged location changed.
- The test exercises the real classpath locator and Elasticsearch client parser.
- No properties, validation rules, routing behavior, generated clients, or dependencies were added.
- English and Chinese documentation cover the same approved boundaries.

## Concerns

- None. Gradle reports pre-existing deprecation warnings; documentation build reports the existing chunk-size advisory.
