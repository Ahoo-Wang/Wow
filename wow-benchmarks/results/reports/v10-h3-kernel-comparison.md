# V10 Spike H3: Coroutine Kernel Against The 9.3 Reactor Kernel

Spike H3 of the V10 design (`documentation/designs/v10/decisions.md` on the `v10` branch) asks whether the V10 coroutine write kernel costs more than the 9.3 Reactor kernel. It does not: on the same JVM it processes the same command about 7.7 times faster with half the allocation, while also doing work 9.3 leaves to the store and the bus.

## What Is Measured

`test/wow-kernel-benchmarks`, `KernelComparisonBenchmark`. Each operation processes one `AddCartItem` on a new cart with the example cart's decide logic, against a store that accepts every append and holds nothing:

| Benchmark | What one operation does |
|---|---|
| `v93ProcessCommand` | wow-benchmarks' `AggregateHandleComponentBenchmark.processCommandAggregate`: build the command message, create the state and command aggregates, `process(...).block()` |
| `v93BuildCommandOnly` | only the command message `v93ProcessCommand` builds, to subtract it |
| `v10ExecuteCommand` | `Kernel.execute`: deduplicate by request id, load (empty), decide, evolve, serialize the events to the log format, fingerprint the command (SHA-256), append (no-op); run with `startCoroutine` on the calling thread, since nothing suspends |
| `v10SerializationFreeShare` | only the event serialization and the command fingerprint `v10ExecuteCommand` does |

The comparison is of the two kernel designs as a whole (tables compiled once against reflection, coroutines against Reactor), not of coroutines against Reactor alone.

## Results

| Benchmark | Throughput (ops/s) | Error | Allocation (B/op) |
|---|---:|---:|---:|
| `v93ProcessCommand` | 37,371 | ±14,259 | 22,891 |
| `v93BuildCommandOnly` | 4,857,633 | ±106,359 | 1,112 |
| `v10ExecuteCommand` | 288,501 | ±4,592 | 11,224 |
| `v10SerializationFreeShare` | 343,758 | ±4,263 | 10,016 |

Per operation, after subtracting the command message 9.3 builds:

| | 9.3 kernel | V10 kernel | of which V10 serialization and fingerprint |
|---|---:|---:|---:|
| Time | ≈ 26.6 µs | ≈ 3.5 µs | ≈ 2.9 µs |
| Allocation | ≈ 21.8 KB | 11.2 KB | 10.0 KB |

`v93ProcessCommand`'s error comes from its first measured iteration (30,864 ops/s); the other four are 38,127 to 40,069 ops/s. Three warm-up iterations of two seconds are not quite enough for it; the conclusion does not depend on that iteration.

## Findings

- **H3 holds.** The coroutine kernel is not slower; D9 keeps its direction with no hot spot to fix first.
- **Serialization dominates V10's cost.** About 0.6 µs and 1.2 KB of the V10 operation are the kernel itself; the rest is Jackson writing the events and SHA-256 over the command body. The fingerprint only has to tell commands apart, not resist an attacker, so a faster 128-bit hash is a candidate once the log backends land (the payload is serialized for the log anyway).

## Reproduce

- Source: the `v10` branch at `ae2c2d6f48` plus the pull request that adds this report and `test/wow-kernel-benchmarks`.
- Command: `./gradlew :wow-kernel-benchmarks:jmh` (1 fork, 3 × 2 s warm-up, 5 × 2 s measurement, `-prof gc`).
- Environment: Apple M4 Pro, 14 cores, 24 GiB, macOS 27.0.1; Amazon Corretto 21.0.12.1 (OpenJDK 64-Bit Server VM 21.0.12.1+9-LTS), default JVM flags.
- Raw results: [`v10-h3-kernel-comparison.json`](v10-h3-kernel-comparison.json).
