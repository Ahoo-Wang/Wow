# Command-Wait Successful-`SENT` Paired E2E Evidence

## Decision

This evidence isolates the final production change that skips an unobservable successful `SENT`
signal when the built-in last-result handle is waiting for a later stage. Every measured operation
creates a fresh aggregate and waits through `PROCESSED` on a real MongoDB or Redis event store.

| Backend | n | Baseline mean | Candidate mean | Geometric ratio | Gain | Two-sided 95% CI | One-sided 95% LCB | `LCB > 1.20` |
|---|---:|---:|---:|---:|---:|---:|---:|:---:|
| MongoDB | 8 | 21,372.00 | 21,508.65 | 1.006384 | +0.64% | 1.001631-1.011160 | 1.002574 | No |
| Redis | 8 | 26,687.93 | 27,338.49 | 1.024356 | +2.44% | 1.017578-1.031180 | 1.018922 | No |

The change is a small, repeatable Redis improvement and an incremental MongoDB improvement. It is
not evidence of a 20% throughput increase.

## Protocol

- One unscored warm-up pair preceded the formal run.
- The eight formal orders were frozen before score inspection:
  `AB, BA, BA, AB, BA, AB, AB, BA`.
- Every position was a separate serial JMH process.
- Each process ran MongoDB and Redis with:
  - 14 callers;
  - `schedulerStrategy=PARALLEL`;
  - scheduler pool `14`;
  - `896` ordering stripes;
  - `2 x 5s` warmup and `3 x 10s` measurement;
  - one fork, G1, fixed 4 GiB heap, and no profiler.
- All 16 manifests report `SUCCESS`, `dirty=false`, and two result rows.
- No result was excluded or repeated after score inspection.

For pair `i` and backend `b`:

```text
x[i,b] = ln(candidate throughput / baseline throughput)
ratio[b] = exp(mean(x[,b]))
LCB95[b] = exp(mean(x[,b]) - t(0.95, 7) * sd(x[,b]) / sqrt(8))
```

The acceptance gate requires the MongoDB and Redis lower bounds to each exceed `1.20`.

## Source identity

- Baseline production commit: `a37beee3a0b09b220bb857a24008ca77984f4785`
- Neutral baseline harness commit: `b8dbb0da67dc8b471ffe1e8b5e3418fb744da6ea`
- Candidate production commit: `c7697de1e62ee5b5d5c3231233424b5122e0193a`
- Neutral candidate harness commit: `ad427df203905485279f15e1efc719f2a371dd86`
- Final local commit with production-equivalent source:
  `a654f626d799d3f7a434e276a90592970cade947`
- Squash-merged `main` commit: `07b46a384e18d7d52c476406bee4b30389bc2f32`
- Merged tree: `73f418d5942155c089621b9a5345eb92187e3262`
- Identical baseline/candidate `wow-benchmarks` tree:
  `2c94013d564e636413d9a1bd34632465093d84db`
- Baseline JMH JAR SHA-256:
  `239148992aee05ce23d0e8bf1c1112f87064d5f2e48d62197f6820c2e941a91c`
- Candidate JMH JAR SHA-256:
  `198936b1cc0e1fdb133bbf1d0e877a52a19b273b10b323bc99a305c82893ffd6`

The candidate harness commit and the final merged-equivalent local commit have identical
production sources. Their differences outside `wow-benchmarks` are test-only.

The exact temporary commit and tree objects are retained in the
[`source-objects.bundle`](source/README.md). The bundle uses the baseline production commit as its
only prerequisite and preserves the baseline harness, candidate production and harness, final
local commit, and measured benchmark tree even if their original refs are later deleted.

## Layout and verification

Each `pair-NN-ab` or `pair-NN-ba` directory contains `baseline` and `candidate` subdirectories.
The JMH JSON, human output, and manifest retain the filenames recorded inside each manifest.

- `summary.csv` contains every per-pair score and ratio.
- `provenance.json` records the cross-run source, runtime, container, and protocol identity.
- `source/` retains the exact otherwise-unreachable Git objects and their machine-readable
  manifest.
- Every per-run manifest retains its own result and human-output hashes.
- `SHA256SUMS` covers the retained files other than itself.

Verify the retained package:

```bash
shasum -a 256 -c SHA256SUMS
git bundle verify source/source-objects.bundle
```

## Interpretation limits

This is local, fixed-concurrency, real-backend E2E evidence rather than a production-capacity
claim. MongoDB and Redis ran in Docker Desktop on tmpfs-backed benchmark storage. The workload
does not include snapshot/history replay, projection, saga, downstream consumers, an open-loop
arrival process, or a latency SLA.
