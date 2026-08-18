# Query Service Architecture Upgrade Verification

Date: 2026-08-18 (Asia/Shanghai)

## Scope

- Upgrade baseline: `53dc6d9c860379aa799c46c62ee3d3fe7aee4548`
- Verified revision: `5b120f3d4445fe6720851e45768df0343c612bc5`
- Final upgrade delta: 374 files, 52,163 insertions, 2,989 deletions.
- Final simplification commit removed a net 2,934 lines without changing a stable ABI baseline or adding a dependency.

## Verification

| Gate | Result |
| --- | --- |
| `queryApiCheck --rerun-tasks --no-build-cache` | PASS, 76/76 tasks, all 8 module ABI/source gates |
| Query, starter, Mongo and Elasticsearch checks plus both real integration suites | PASS, 127/127 tasks in 5m 7s |
| `allContractTest allIntegrationTest --rerun-tasks --no-build-cache --no-parallel` | PASS, 105/105 tasks in 5m 59s |
| `detekt build --no-parallel` | PASS, 336 tasks |
| `pnpm docs:build` | PASS, 66 English Markdown inputs, rendered pages and sitemap |

The first parallel repository build exposed two pre-existing timing-sensitive tests: the TCK 50 ms
pre-subscription deadline and a core local-message ordering test. Their assertions were not changed. The serial repository
build passed, matching the serial container policy used by the authoritative integration gate.

Current XML reports contain 4,850 tests, zero failures/errors and two existing skips. The directly changed modules account
for 1,822 tests with zero failures, errors or skips:

- wow-query: 420
- wow-spring-boot-starter: 260
- wow-mongo: 247 unit + 381 integration
- wow-elasticsearch: 201 unit + 313 integration

## Contract Evidence

- Stable ABI baselines have no diff; exact exclusions have no stale or unclassified entries.
- External Java/Kotlin positive and negative source fixtures pass.
- MongoDB and Elasticsearch legacy Gateway suites run against real containers and retain policy, projection, time-wire,
  cancellation and cleanup evidence.
- Container images exercised by the integration layer include MongoDB 6.0.6, Elasticsearch 9.2.6, Kafka 7.4.0,
  Redis 7.4 and ClickHouse 24.8.14.39.
- Legacy `NoOp*QueryServiceFactory` references remain only in the retained deprecated compatibility projection. The active
  WebFlux/Gateway path has no legacy Filter/Handler execution engine.
- No second query execution engine, blocking bridge, generated OpenAPI change or dependency addition was introduced.

## Result

PASS. The planned QueryGateway architecture, security policies, backend routing, compatibility cutover, ABI governance,
reactive cancellation and real backend behavior are verified at the revision above.
