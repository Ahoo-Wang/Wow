# Post-Merge Pure-Create MongoDB/Redis Profile Evidence

## Purpose

This package identifies the next optimization candidates after the successful-`SENT` fast path was
merged. It profiles the real MongoDB/Redis path for a fresh aggregate command through
`PROCESSED`.

All profile throughput values are diagnostic only. The unprofiled paired A/B in
[`../2026-07-24-command-wait-sent-paired-ab`](../2026-07-24-command-wait-sent-paired-ab/README.md)
is the throughput evidence of record.

## Fixed workload

- 14 JMH callers.
- `schedulerStrategy=PARALLEL`.
- Scheduler pool `14`.
- `896` ordering stripes.
- G1, fixed 4 GiB heap, `AlwaysPreTouch`, and `DebugNonSafepoints`.
- Every operation creates a fresh aggregate and asserts successful `PROCESSED`, aggregate identity,
  and initial aggregate version.
- No snapshot/history replay, projection, saga, or downstream consumer.

The profile JAR was built from `ad427df203905485279f15e1efc719f2a371dd86`. Its production
sources are equivalent to merged `main`; the overlay only supplies the neutral benchmark harness
and test changes.

## Exact allocation and GC measurements

The `gc` runs used `2 x 5s` warmup, `3 x 10s` measurement, one fork, and JMH `-prof gc`.

| Backend | Repeat | Throughput | `gc.alloc.rate.norm` | Allocation rate | GC count | GC time |
|---|---:|---:|---:|---:|---:|---:|
| MongoDB | 1 | 19,595.26 ops/s | 11,965.31 B/op | 221.17 MB/s | 10 | 30 ms |
| MongoDB | 2 | 19,767.75 ops/s | 11,938.29 B/op | 222.84 MB/s | 10 | 38 ms |
| Redis | 1 | 25,504.99 ops/s | 4,321.27 B/op | 100.48 MB/s | 11 | 36 ms |
| Redis | 2 | 22,276.39 ops/s | 4,342.39 B/op | 88.46 MB/s | 9 | 34 ms |

Mean normalized allocation is 11,951.80 B/op for MongoDB and 4,331.83 B/op for Redis. The
between-repeat B/op range is below 0.5% for both backends. GC time is about 0.1% of the measured
wall-clock period, so a GC-pause-only optimization cannot produce a 20% throughput gain.

## Allocation-stack findings

The allocation runs used async-profiler 4.2.1 with `event=alloc`, `2 x 5s` warmup, and one 20-second
measurement. Sample percentages approximate sampled allocation bytes; they are not CPU-time or
throughput shares.

### Redis

`CanonicalRedisKeyCodec.encodeSortableId` accounts for 7,745 samples, or 26.61% of sampled
allocation bytes. Its samples partition by caller as follows:

| Mutually exclusive caller | Encoding-body samples | Encoding-body share | Full-path samples | Full-path share |
|---|---:|---:|---:|---:|
| Aggregate-ID index member | 2,545 | 8.74% | 2,699 | 9.27% |
| Aggregate-ID index prefix | 2,566 | 8.82% | 2,665 | 9.16% |
| Aggregate-ID index lower bound | 2,634 | 9.05% | 2,697 | 9.27% |

The full-path columns include allocations outside `encodeSortableId`, such as delimiter and tenant
assembly, so they are not a partition of the 7,745 encoding-body samples. The same aggregate ID is
fully validated and hex-encoded three times in one append. A compatible candidate should encode
once and derive the three existing wire-format strings.

Other Redis signals:

- Spring Redis script key/argument construction: 6.37% of samples.
- Event JSON serialization: 3.63%.
- Lettuce subscription queue allocation: 3.04%.
- State-event publication path: 3.68%.
- In-memory bus path: 1.43%.

Open the retained allocation flamegraphs:

- [Redis allocation callers](alloc/redis/flame-reverse.html)
- [Redis allocation callees](alloc/redis/flame-forward.html)

### MongoDB

The broad Mongo driver/BSON family is present in 61.73% of allocation samples. Inclusive families
overlap, so that value must not be added to the narrower rows below:

- `DomainEventStream.toDocument()`: 10.91%.
- Jackson `toLinkedHashMap()` conversion: 8.84%.
- BSON `DocumentCodec.encode`: 8.33%.
- BSON codec lookup: 6.54%.
- State-event publication path: 3.06%.
- In-memory bus path: 1.26%.

Open the retained allocation flamegraphs:

- [MongoDB allocation callers](alloc/mongo/flame-reverse.html)
- [MongoDB allocation callees](alloc/mongo/flame-forward.html)

## CPU-profile limitation

CPU runs used async-profiler `event=cpu`, `2 x 5s` warmup, and one 30-second measurement, with two
repeats per backend.

The two MongoDB runs produced 8,336 and 8,665 samples, but macOS process-timer sampling placed a
large fraction on parked native frames. Redis repeat 1 produced only 229 samples; repeat 2 produced
6,383 samples but about one third of its stacks terminated at the JMH stub. These profiles are
retained for call-tree inspection but cannot support quantitative CPU percentages or an Amdahl
throughput claim.

Useful flamegraphs:

- [MongoDB CPU repeat 1 callers](cpu/mongo-1/flame-reverse.html)
- [MongoDB CPU repeat 2 callers](cpu/mongo-2/flame-reverse.html)
- [Redis CPU repeat 1 callers](cpu/redis-1/flame-reverse.html)
- [Redis CPU repeat 2 callers](cpu/redis-2/flame-reverse.html)

## Layout

```text
gc/<backend>-<repeat>/
  result.json
  jmh.txt

cpu/<backend>-<repeat>/
  result.json
  jmh.txt.gz
  summary.txt.gz
  collapsed.csv.gz
  flame-forward.html
  flame-reverse.html

alloc/<backend>/
  result.json
  jmh.txt.gz
  summary.txt.gz
  collapsed.csv.gz
  flame-forward.html
  flame-reverse.html
```

Inspect compressed text without changing the evidence:

```bash
gzip -dc alloc/redis/summary.txt.gz | less
```

`profile-summary.csv` retains the principal numeric results. `hotspots.csv` retains the allocation
sample counts used in this report. `provenance.json` records source, runtime, profiler, container,
protocol, and run-window identity.

Verify all retained files:

```bash
shasum -a 256 -c SHA256SUMS
```
