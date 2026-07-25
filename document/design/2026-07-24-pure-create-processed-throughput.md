# Pure-Create Command Throughput to `PROCESSED`

## Scope

This record covers the closed-loop path:

```text
fresh aggregate command
  -> sendAndWaitForProcessed
  -> acknowledged version-1 MongoDB or Redis append
  -> local domain/state event publication attempt
  -> PROCESSED result
```

It deliberately excludes snapshot load/replay, event-history replay, projections, sagas, and
downstream consumers. The fixed configuration is 14 JMH callers, 14 aggregate-scheduler workers,
896 ordering stripes, G1, and a fixed 4 GiB heap.

The fixed scheduler settings are an experimental control, not a tuning result. This archive does
not compare scheduler-pool or stripe-count alternatives and cannot establish that `14`/`896` is
optimal.

The throughput target is interpreted as a relative gain of at least 20% on each backend:

```text
LCB95(candidate throughput / baseline throughput) > 1.20
```

## Retained evidence

- [`2026-07-24-command-wait-sent-paired-ab`](evidence/2026-07-24-command-wait-sent-paired-ab/README.md)
  contains all 48 raw artifacts from the clean eight-pair comparison of the successful-`SENT`
  fast path.
- [`2026-07-24-pure-create-main-profile`](evidence/2026-07-24-pure-create-main-profile/README.md)
  contains the post-merge MongoDB/Redis CPU, exact allocation, and allocation-stack profiles,
  including flamegraphs and SHA-256 manifests.

## Result

The clean eight-pair A/B measured:

| Backend | Baseline mean | Candidate mean | Geometric gain | Two-sided 95% CI | One-sided 95% lower gain bound |
|---|---:|---:|---:|---:|---:|
| MongoDB | 21,372.00 ops/s | 21,508.65 ops/s | +0.64% | +0.16% to +1.12% | +0.26% |
| Redis | 26,687.93 ops/s | 27,338.49 ops/s | +2.44% | +1.76% to +3.12% | +1.89% |

The merged change is useful but incremental. It does not meet the 20% gate.

The exact `-prof gc` runs on the merged-equivalent runtime measured:

| Backend | Mean allocation | Observed GC time per 30-second measurement period |
|---|---:|---:|
| MongoDB | 11,951.80 B/op | 30-38 ms |
| Redis | 4,331.83 B/op | 34-36 ms |

Measured GC time is about 0.1% of wall time, so eliminating GC pauses alone cannot provide a 20%
throughput gain. Allocation-stack samples identify backend-specific candidates:

- Redis runs `encodeSortableId` once for each index member, prefix, and lower bound. The encoding
  body accounts for 26.61% of sampled allocation bytes. The three mutually exclusive full paths,
  including their remaining string assembly, account for 9.27%, 9.16%, and 9.27%.
- MongoDB allocation is dominated by driver/BSON work. The Jackson-to-map
  `DomainEventStream.toDocument()` path accounts for 10.91% of sampled allocation bytes.
- State-event construction and the no-subscriber in-memory publication path are visible, but are
  too small in the allocation profile to justify treating them as a 20% candidate.

Allocation sample percentages are not CPU-time percentages. They select experiments; only
unprofiled, balanced real-backend A/B runs may establish a throughput gain.

## Next experiment order

1. Redis: compute the sortable aggregate ID once and derive the three unchanged wire-format
   arguments from it. Preserve Unicode validation, lexicographic ordering, and existing Redis key
   bytes. Run a short real-Redis screen before a formal paired A/B.
2. MongoDB: run a benchmark-only managed-index ablation to establish the server-side index-write
   ceiling before changing query capabilities.
3. MongoDB: separately test a schema-compatible direct `Document` builder that avoids the
   Jackson-to-map conversion. Treat it as an incremental candidate unless E2E evidence shows
   otherwise.
4. Treat scheduler-pool and stripe-count tuning as unmeasured for this real-backend workload. If
   reconsidered, run and retain a dedicated paired MongoDB/Redis configuration screen; do not use
   the fixed `14`/`896` control as evidence of optimality.

## Evidence integrity

Each evidence directory contains a `SHA256SUMS` file. From that directory:

```bash
shasum -a 256 -c SHA256SUMS
```

Profile throughput is diagnostic only and must not be compared with the unprofiled A/B scores.
