# Wow Mongo Append Cost Breakdown Design

## Goal

Add a benchmark-only cost breakdown for the `MongoEventStore.appendStream` write path so the Raw BSON result can be interpreted against the current Jackson and Mongo driver encoding costs.

## Scope

This phase does not change production `wow-mongo` code and does not register another benchmark into the full component suite. The benchmark is intentionally run by a direct JMH include pattern so it cannot accidentally expand `benchmarkQuickComponent`.

## Design

Create `MongoAppendPathBreakdownBenchmark` under `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/component`.

`MongoAppendPathBreakdownBenchmark` uses the same `BenchmarkEvents.singleEventStream()` fixture as the Raw BSON comparison and measures these pure client-side slices:

- `domainEventBodyToJsonNode`: Jackson/Wow body conversion only.
- `eventStreamToLinkedHashMap`: current serializer map conversion used before `Document`.
- `eventStreamToDocument`: current Mongo event-stream document construction.
- `documentToBsonBytes`: Mongo driver `DocumentCodec` encoding for a prebuilt `Document`.
- `eventStreamToDocumentToBsonBytes`: current document construction plus driver BSON encoding.
- `eventStreamToRawBsonDocument`: experimental Raw BSON construction.

The compatibility check decodes both driver-encoded `Document` bytes and Raw BSON bytes back to `Document`, then verifies they are readable through the existing `toDomainEventStream()` path.

Create `MongoAppendInsertBreakdownBenchmark` under `wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/infrastructure/mongo`.

The insert benchmark creates the same temporary Mongo fixture used by the existing Mongo E2E benchmarks, then measures:

- `eventStreamToDocumentInsert`: event-stream fixture creation, current `toDocument()` conversion, and acknowledged Mongo `insertOne`.
- `eventStreamToRawBsonInsert`: event-stream fixture creation, Raw BSON conversion, and acknowledged Mongo `insertOne`.

Each operation creates a new event stream so Mongo unique indexes are not bypassed or relaxed. This layer excludes command dispatch and wait-notification overhead, making it the bridge between pure encoding and full command E2E.

## Success Criteria

- The benchmark compiles through `:wow-benchmarks:compileJmhKotlin`.
- Targeted JMH runs only include `MongoAppendPathBreakdownBenchmark` and `MongoAppendInsertBreakdownBenchmark`.
- The final interpretation uses `gc.alloc.rate.norm` first and throughput second.
- The result explains whether Raw BSON's E2E weakness is caused by body serialization, driver `DocumentCodec` encoding, or non-encoding command/Mongo insert costs.
