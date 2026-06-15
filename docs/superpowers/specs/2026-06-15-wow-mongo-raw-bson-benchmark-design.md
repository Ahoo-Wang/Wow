# Wow Mongo Raw BSON Benchmark Design

## Context

`wow-mongo` currently writes event streams through `MongoEventStore.appendStream`.
The hot path builds a Mongo `Document` before calling the reactive Mongo driver:

```text
DomainEventStream -> toLinkedHashMap() -> Document -> id/_id rewrite -> insertOne(Document)
```

The checked-in quick infrastructure report shows Mongo command-write E2E at
about `39-41 KB/op` allocation, while the Redis path is about `5.8-7.1 KB/op`.
This makes Mongo event-stream encoding a strong candidate for benchmark-backed
investigation.

The goal of this design is to compare the current production path with a more
aggressive Raw BSON write path before changing production `wow-mongo` code.

## Goals

- Measure the allocation and throughput difference between the current
  `MongoEventStore.appendStream` path and a Raw BSON append experiment.
- Keep the experiment inside `wow-benchmarks`, not `wow-mongo`, until benchmark
  evidence justifies production work.
- Preserve the current durable write semantics in the experiment: command
  completion must still wait for Mongo `insertOne` acknowledgement.
- Preserve the current persisted event-stream document shape.
- Preserve Jackson/Wow compatibility for domain event `body` serialization.

## Non-Goals

- Do not introduce asynchronous buffering, deferred flushing, or background
  writes. Those change durability semantics and are not comparable to the
  current `appendStream` contract.
- Do not replace the read/query/snapshot production paths.
- Do not commit generated benchmark result files unless the user explicitly
  asks for updated reports.
- Do not promote Raw BSON into `wow-mongo` production code in this phase.

## Compared Implementations

### Baseline

Use the existing production implementation:

```text
MongoEventStore.appendStream
DomainEventStream -> Documents.toDocument() -> insertOne(Document)
```

This benchmark continues to use the existing
`MongoCommandWriteE2EBenchmark.sendAndWaitProcessed`.

### Raw BSON Experiment

Add benchmark-only code under `wow-benchmarks/src/jmh/kotlin`.

The experimental event store should implement the same append behavior as
`MongoEventStore`, but use a typed Raw BSON collection for writes:

```text
RawBsonMongoEventStore.appendStream
DomainEventStream -> RawBsonDocument -> insertOne(RawBsonDocument)
```

The experiment must reuse the existing Mongo schema initializer and collection
names so that unique indexes, duplicate request handling, and version-conflict
behavior are exercised against the same database shape.

## Event Body Compatibility

The Raw BSON encoder must not invent a new event body representation.

Top-level event-stream metadata can be written directly:

- `_id`
- `contextName`
- `aggregateName`
- `aggregateId`
- `tenantId`
- `ownerId`
- `spaceId`
- `commandId`
- `requestId`
- `version`
- `header`
- `body`
- `createTime`
- `size`

Each stream body element must match the current event-stream JSON/BSON contract:

- `id`
- `name`
- `revision`
- `bodyType`
- `body`

For normal domain events, `bodyType` must remain `event.body.javaClass.name`.
For `JsonDomainEvent`, `bodyType` must preserve the stored unknown/original body
type. The `body` field must be encoded with the existing Jackson/Wow
configuration so event replay, event upgrading, unknown event fallback, and
cross-version compatibility keep working.

This means the experiment should hand-write the event-stream envelope but still
delegate domain event body encoding to the current Jackson-compatible
serialization path.

## Benchmark Coverage

Add two levels of comparison.

### Component Benchmark

Add or extend a Mongo document component benchmark with:

```text
eventStreamToDocument
eventStreamToRawBsonDocument
```

This isolates the encoding cost and gives a fast signal before running
infrastructure E2E.

### Infrastructure E2E Benchmark

Add a new benchmark next to the current Mongo benchmark:

```text
RawBsonMongoCommandWriteE2EBenchmark.sendAndWaitProcessed
```

It should reuse `CommandDispatcherScenario` with the experimental event store.
The scenario must remain equivalent to the current benchmark except for the
event-store implementation.

## Metrics And Acceptance

Use `gc.alloc.rate.norm` as the primary decision metric. Throughput and average
time are secondary signals.

The initial success target is one of:

- Reduce Mongo command-write allocation from the current `~39-41 KB/op` to
  around `30 KB/op`, or
- Reduce allocation by at least about `20%` on the same machine and benchmark
  profile.

If allocation drops only `5-10%`, treat Raw BSON as an inconclusive or weak
optimization and investigate body serialization or Mongo driver overhead before
promoting production changes.

## Validation Plan

Run the narrow checks first:

```bash
./gradlew :wow-benchmarks:compileJmhKotlin
./gradlew :wow-benchmarks:jmhJar --stacktrace
```

Then run focused JMH comparisons for the component benchmark and the Mongo E2E
benchmark. If local Mongo is unavailable, report that the infrastructure E2E
comparison is blocked rather than substituting a non-Mongo benchmark.

After any benchmark-only code is added, also run:

```bash
./gradlew :wow-benchmarks:check --stacktrace
```

Generated report/result files should be restored or left unstaged unless the
user asks to refresh benchmark evidence in the repository.

## Error Handling

The experimental event store must preserve the baseline error mapping:

- `MongoWriteException` duplicate aggregate/version conflicts map through the
  same `toWowError(eventStream)` path.
- Write acknowledgement must still be checked.
- Non-duplicate recoverable write errors must keep the same recoverable mapping.

Because the experiment uses the same indexes and Mongo acknowledgement contract,
any behavior difference should come from encoding and driver write input type,
not from durability or conflict semantics.

## Follow-Up Decision

After benchmark results are available:

- Promote the Raw BSON direction only if allocation improves materially and
  compatibility tests pass.
- Keep it benchmark-only if gains are small, noisy, or dependent on changed
  semantics.
- If promoted, write a separate production implementation plan for `wow-mongo`
  instead of folding production changes into the benchmark experiment.
