# Task 3 Report

## RED

Added typed Projection/Sort JSON and aggregation-alias assertions. The prescribed focused Gradle command failed because `Projection` accepted `List<String>` and `Sort` accepted `String`.

## GREEN

- `Projection.include/exclude`, `Sort.field`, cursor uniqueness, DSLs, converters, MongoDB and Elasticsearch boundaries now use `QueryField`.
- Aggregation aliases remain strings and compare through `sort.field.path`.
- Removed obsolete wildcard projection/sort tests because `QueryField` rejects wildcard paths at the public contract.

## Verification

- Focused API/DSL tests: passed.
- `./gradlew :wow-query:test :wow-mongo:compileTestKotlin :wow-elasticsearch:compileTestKotlin :wow-webflux:compileTestKotlin`: passed.
- `./gradlew :wow-api:check :wow-query:check :wow-mongo:compileTestKotlin :wow-elasticsearch:compileTestKotlin :wow-webflux:compileTestKotlin :wow-tck:compileKotlin`: passed before the final equivalent focused run; all listed compile tasks passed.
- `git diff --check`: passed.
