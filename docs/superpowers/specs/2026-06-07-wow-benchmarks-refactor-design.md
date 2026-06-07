# wow-benchmarks Refactor Design

## Context

`wow-benchmarks` has grown from a small JMH suite into a benchmark system with
smoke runs, grouped benchmark runs, baseline comparison, README report
generation, metadata packaging, and Redis/Mongo infrastructure benchmarks.
Recent changes expanded coverage, but the current structure still mixes three
concerns:

- benchmark measurement code
- Wow runtime fixture/scenario construction
- Gradle task/report/baseline orchestration

The refactor should make the benchmark taxonomy clearer, reduce repeated
runtime setup code, and keep local benchmark runs isolated from Redis/Mongo
dependencies.

Old benchmark names and old baseline compatibility are not hard requirements.
The suite may be renamed and regrouped around clearer measurement intent.

## Goals

- Keep `wow-benchmarks` as a single Gradle module.
- Rename benchmark run groups from `internal/external` to clearer terminology.
- Separate pure local runtime benchmarks from Redis/Mongo infrastructure I/O.
- Extract reusable benchmark fixtures and runtime scenario builders.
- Keep Gradle reporting logic inside `wow-benchmarks/gradle/`.
- Preserve the current JMH packaging behavior for Wow metadata and service
  provider merging.
- Make failure messages explicit when infrastructure services are unavailable.
- Validate the refactor with compile, JMH packaging, smoke, and report/baseline
  task checks.

## Non-Goals

- Do not add a new Gradle module.
- Do not introduce `buildSrc` or move benchmark-private logic into global build
  infrastructure.
- Do not change production Wow runtime behavior.
- Do not move benchmark support code into production modules.
- Do not preserve old benchmark class/method names solely for baseline
  compatibility.
- Do not make Redis/Mongo part of the default local benchmark path.
- Do not hand-edit generated benchmark result tables in `README.md` except for
  generator instructions or stable explanatory text.

## Taxonomy

The benchmark suite will use these run names:

- `benchmarkSmoke`: a PR-safe JMH smoke run.
- `benchmarkLocal`: pure JVM, Noop, and InMemory benchmarks with no external
  service dependencies.
- `benchmarkInfrastructure`: Redis/Mongo infrastructure I/O benchmarks.

Reports will use these group names:

- `Local Runtime`: local JVM/runtime benchmark results.
- `Infrastructure I/O`: Redis/Mongo benchmark results.

`benchmarkInternal` and `benchmarkExternal` will be replaced by the local and
infrastructure names.

## Module Structure

The module remains under `wow-benchmarks`. Source code stays in
`wow-benchmarks/src/jmh/kotlin`.

Recommended source organization:

- `me.ahoo.wow.benchmark.fixture`
  Stable benchmark data and low-level factories:
  commands, events, aggregate metadata, aggregate ids, headers, global ids, and
  idempotency checkers.
- `me.ahoo.wow.benchmark.scenario`
  Runtime object graphs with lifecycle:
  command gateway, command dispatcher, command processing pipeline, event store,
  state aggregate repository, wait notifier, and handler chain scenarios.
- `me.ahoo.wow.hotpath`
  Fine-grained low-level hot path benchmarks only.
- Existing capability packages such as `command`, `event`, `eventsourcing`,
  `messaging`, `projection`, `saga`, and `serialization`
  Local runtime benchmarks grouped by Wow capability.
- `me.ahoo.wow.infrastructure`
  Redis/Mongo benchmarks and infrastructure availability checks. Existing Redis
  and Mongo fixture classes may move here or be renamed to match the new group.

The exact package names can be adjusted during planning if current imports make
one spelling materially cleaner, but the boundaries above are the intended
direction.

## Fixture Design

Benchmark fixtures should be cheap, explicit, and lifecycle-free unless they
represent an external resource.

Candidate fixture responsibilities:

- `BenchmarkCommands`
  Creates fixed-aggregate, new-aggregate, and smoke command messages.
- `BenchmarkEvents`
  Creates single-event and multi-event `DomainEventStream` instances.
- `BenchmarkAggregates`
  Provides cart aggregate metadata, named aggregate, fixed aggregate id, and
  reusable aggregate id helpers.
- `BenchmarkIds`
  Centralizes deterministic benchmark id generator setup and generated id
  helpers.
- `BenchmarkHeaders`
  Creates headers used by message wrapping and hot path benchmarks.
- `BenchmarkIdempotency`
  Creates Bloom filter idempotency checkers used by command and hot path
  benchmarks.

These fixtures should replace duplicated logic currently split across command,
eventsourcing, and hotpath helper files.

## Scenario Design

Runtime scenarios should own expensive or stateful Wow runtime setup and expose
an idempotent `close()`/`AutoCloseable` lifecycle.

Candidate scenarios:

- `CommandGatewayScenario`
  Builds `DefaultCommandGateway`, command bus, validator, idempotency provider,
  wait endpoint, wait registrar, and wait notifier.
- `CommandDispatcherScenario`
  Builds command gateway, event store, snapshot repository, state aggregate
  repository, aggregate processor factory, handler chain, and dispatcher.
- `CommandPipelineScenario`
  Builds comparable command handler chain variants:
  aggregate only, aggregate without retry, aggregate plus domain event, aggregate
  plus state event, and aggregate plus processed notifier.
- `EventStoreScenario`
  Provides local Noop/InMemory event store setup for event-sourcing benchmarks.

Benchmarks should consume scenarios instead of rebuilding object graphs in each
class. This keeps measurement intent visible and reduces accidental drift
between similar benchmarks.

## Infrastructure Design

Infrastructure benchmarks remain in the module but are isolated from local
runtime benchmarks.

Redis and Mongo fixtures should:

- Use explicit benchmark database/collection defaults.
- Flush only benchmark-owned data.
- Close connections in `tearDown`.
- Fail with clear setup messages when services are unavailable.
- Be reachable only through `benchmarkInfrastructure` and grouped reports.

The infrastructure runner should probe Redis/Mongo availability before launching
the relevant JMH run. If a service is unavailable, the message should tell the
developer which service is required and how to rerun the task after starting it.

Grouped report generation may tolerate a missing infrastructure result file and
render a clear "not run / unavailable" note. Missing local results should remain
a failure.

## Gradle Task Design

Gradle logic stays inside `wow-benchmarks`.

`wow-benchmarks/build.gradle.kts` should contain:

- dependencies
- high-level JMH configuration
- top-level task registrations
- shared JMH run settings only where they are easiest to understand

`wow-benchmarks/gradle/jmh-packaging.gradle.kts` continues to own:

- merged `META-INF/wow-metadata.json`
- merged SPI service files
- `jmhJar` packaging adjustments

`wow-benchmarks/gradle/benchmark-reporting.gradle.kts` owns:

- result parsing
- grouped report rendering
- README report rendering
- baseline comparison
- baseline update

Result paths:

- Smoke JSON: `build/reports/jmh/benchmark-smoke.json`
- Local JSON: `build/results/jmh/local.json`
- Local human output: `build/reports/jmh/local-human.txt`
- Infrastructure JSON: `build/results/jmh/infrastructure.json`
- Infrastructure human output: `build/reports/jmh/infrastructure-human.txt`
- Grouped report: `build/reports/jmh/grouped.md`
- Baseline: `results/baseline.json`

`generateBenchmarkReport` should read local results by default.
`generateGroupedBenchmarkReport` should read local plus infrastructure results.
`benchmarkCompare` should compare local results against `results/baseline.json`.
`updateBaseline` should update the baseline from local results.

## Error Handling

- `benchmarkSmoke` and `benchmarkLocal` must not connect to Redis or Mongo.
- `benchmarkInfrastructure` should detect unavailable Redis/Mongo services
  before a long JMH run starts.
- Report parsing should reject malformed local result rows with file path and row
  index context.
- Missing infrastructure results should be reportable as unavailable when the
  report task is explicitly grouped.
- Scenario `close()` methods should be safe to call after partial setup failure.
- JMH benchmark methods should keep using `Blackhole` consumption and should
  catch expected Wow runtime exceptions only when the benchmark intentionally
  measures a path that may surface them.

## Testing And Validation

Required validation:

- `./gradlew :wow-benchmarks:compileJmhKotlin`
- `./gradlew :wow-benchmarks:jmhJar`
- `./gradlew :wow-benchmarks:benchmarkSmoke`
- Report and baseline task validation with minimal or generated local result
  input, including:
  - `generateBenchmarkReport`
  - `generateGroupedBenchmarkReport`
  - `benchmarkCompare`
  - `updateBaseline`

Preferred validation when time allows:

- `./gradlew :wow-benchmarks:benchmarkLocal`

Conditional validation:

- `./gradlew :wow-benchmarks:benchmarkInfrastructure` only when Redis and Mongo
  are available locally. Otherwise validate the service detection and failure
  message.

## Implementation Boundaries

The implementation plan should proceed in small steps:

1. Rename Gradle tasks and result paths while preserving old behavior through
   equivalent local/infrastructure commands.
2. Extract lifecycle-free fixtures and migrate simple benchmarks.
3. Extract runtime scenarios and migrate dispatcher/pipeline benchmarks.
4. Move or rename Redis/Mongo benchmarks into the infrastructure taxonomy.
5. Update reporting and baseline comparison to the new result paths and group
   names.
6. Update stable README instructions if the generator command names changed.
7. Run the required validation ladder and record any intentionally skipped
   long-running or infrastructure-dependent commands.

Each step should keep the module compiling before moving to the next one.
