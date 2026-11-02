# V10 milestone snapshot 3 on Apple M5 Max

The third milestone saturation snapshot of Wow 10 on an Apple M5 Max: single-node closed-loop sweeps on disk (PostgreSQL and MongoDB), detailed investigation of three-node cluster internal stream exhaustion and concurrency conflicts (X-55), and verification of overload error behavior (X-40). Measured on 2026-11-02 at `fe6f38f224` (`main`), compared against snapshot 2 (`30d0c0348e`, 2026-11-01) and snapshot 1 (`cebdb88d0`, 2026-10-30). Between snapshot 2 and snapshot 3, `main` moved by exactly 34 commits (`git rev-list --count 30d0c0348e..HEAD`). The raw runs, scripts, and logs are in [`v10-m5max-snapshot-3/`](v10-m5max-snapshot-3/).

## Setup

| | |
| --- | --- |
| Commit | `fe6f38f224759890e510356ad3419a7eaf41791d` (`fe6f38f224`, `main`) |
| Baseline | `30d0c0348e11a14b0b1444d3202521199321dafa` (`30d0c0348e`, Snapshot 2) |
| Commits since baseline | 34 (`git rev-list --count 30d0c0348e..HEAD`) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB unified memory, macOS 27.2 (26B123) |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB (33,670,660,096 bytes) |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`); MongoDB 7.0 (WiredTiger cache 2 GB); Kafka 7.4.0 (KRaft mode) |
| Node | One node (3 nodes in X-55), 1 GB heap (`-Xms1g -Xmx1g -XX:+UseG1GC`), Eclipse Adoptium Temurin 21.0.13+9 |
| Data | 100,000 accounts (`--accounts=100000`), primed once per session (`--prime --seed-concurrency=128`), uniform keys unless noted |
| Sweep | Closed-loop concurrency 1, 4, 16, 64, 256, 1024, 4096 (`--growth=4 --max-concurrency=4096 --step=30 --warm-up=10`) |

## Ceilings

Closed-loop sweeps, 30 s per step (10 s warm-up), concurrency 1 to 4,096 (`--growth=4`), one session per backend on disk. Comparing snapshot 3 (`fe6f38f224`) with snapshot 2 (`30d0c0348e`) and snapshot 1 (`cebdb88d0`):

| Peak throughput (/s) | PostgreSQL (Snapshot 3) | PostgreSQL (Snapshot 2) | PostgreSQL (Snapshot 1) | MongoDB (Snapshot 3) | MongoDB (Snapshot 2) | MongoDB (Snapshot 1) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| E1, uniform | ≥ 35,225.6 (step 4096) | ≥ 36,388.7 (step 4096) | ≥ 33,180 (step 4096) | ≥ 21,961.3 (step 4096) | ≥ 23,382.3 (step 4096) | ≥ 22,745 (step 4096) |
| E1, Zipf | ≥ 20,257.1 (step 4096) | ≥ 21,227.8 (step 4096) | ≥ 19,599 (step 4096) | ≥ 18,194.5 (step 4096) | ≥ 18,050.6 (step 4096) | ≥ 17,871 (step 4096) |
| E1, one hot account | ≥ 20,190.9 (step 4096) | ≥ 20,184.5 (step 4096) | ≥ 21,191 (step 4096) | ≥ 19,433.3 (step 4096) | ≥ 19,829.2 (step 4096) | ≥ 18,987 (step 4096) |
| E3, uniform | 9,441.7 (step 1024) | 8,674.6 (step 1024) | 9,101 (step 1024) | 6,226.3 (step 1024) | 6,531.7 (step 1024) | 6,755 (step 1024) |

- **E1 uniform**: Peaked at the top step (4,096) on both backends across all snapshots. Because the peaks fall on the highest step, these throughput figures are lower bounds (marked with `≥`); a comparison between two lower bounds does not represent an improvement or regression ratio. Neither E1 uniform sweep recorded any failed commands (0 errors).
- **E1 Zipf and hot account**: In both backends, throughput peaked at step 4,096 where the single node refused excess commands with HTTP 429 `wow.command.busy`. On PostgreSQL, Zipf refused 94,412 commands (vs 102,964 in snapshot 2, 82,807 in snapshot 1) and the single hot account refused 543,907 commands (vs 548,717 in snapshot 2, 590,131 in snapshot 1). On MongoDB, Zipf refused 82,851 commands (vs 80,682 in snapshot 2, 81,031 in snapshot 1) and the single hot account refused 587,415 commands (vs 583,009 in snapshot 2, 558,260 in snapshot 1). Below concurrency 4,096, neither Zipf nor hot account recorded any errors.
- **E3 uniform**: Peaked at concurrency 1,024 on both backends: 9,441.7/s on PostgreSQL (vs 8,674.6/s in snapshot 2, 9,101/s in snapshot 1) and 6,226.3/s on MongoDB (vs 6,531.7/s in snapshot 2, 6,755/s in snapshot 1). At concurrency 4,096, throughput degraded as command queues filled, refusing commands with 429 `wow.command.busy` and timing out waits with 504 `wow.wait.timeout`. Zero HTTP 500 errors were observed.

## E3 Past Its Knee

Single-node E3 uniform saturation across step 1,024 (the knee) and step 4,096:

| Session | E3 at 1,024 (/s) | E3 at 4,096 (/s) | `wow.command.busy` (429) | `wow.wait.timeout` (504) | Other errors |
| :--- | :--- | :--- | :--- | :--- | :--- |
| PostgreSQL (Snapshot 3) | 9,441.7 | 4,821.1 | 388,629 | 1,210 | 0 |
| PostgreSQL (Snapshot 2) | 8,674.6 | 5,021.4 | 415,664 | 469 | 0 |
| PostgreSQL (Snapshot 1) | 9,101.0 | 5,818.0 | 301,588 | 791 | 0 |
| MongoDB (Snapshot 3) | 6,226.3 | 4,149.2 | 227,997 | 2,196 | 0 |
| MongoDB (Snapshot 2) | 6,531.7 | 4,194.6 | 292,011 | 2,152 | 0 |
| MongoDB (Snapshot 1) | 6,755.0 | 3,343.0 | 202,247 | 2,535 | 0 |

- Across both backends, all single-node E3 errors were confined to concurrency 4,096. No errors occurred at or below concurrency 1,024.
- `wow.command.busy` (429) reflects admission lane backpressure when aggregate lanes or the node's inflight command quota is reached.
- `wow.wait.timeout` (504) indicates transfers where the second hop (`Entry` on target) was not appended to the event store within the wait deadline.
- Zero HTTP 500 errors or unhandled exceptions occurred on either backend.

---

## Investigation X-55: Three-Node Internal Stream Exhaustion and Contended Commands (409 wow.command.contended)

### 1. Phenomenon & Reproduction Status
- **Status**: **CONFIRMED AND DETAILED** on both PostgreSQL and MongoDB 3-node clusters.
- **Background**: In snapshot 2, under concurrency 1,024 on 3 nodes co-located on a single host, inter-node HTTP/2 multiplexing on internal ports (19090..19092) threw `java.io.IOException: too many concurrent streams`. This dropped over 1,300 wait signals and timed out forwards, while version contention (409 `wow.command.contended`) appeared concurrently.
- **Observation Plan for Snapshot 3**:
  - Run closed-loop sweeps (E1, E3 to concurrency 4,096) and fixed concurrency 1,024 tests (60 s) on 3-node clusters.
  - Count exact occurrences of `too many concurrent streams` across all 3 nodes, identify the earliest concurrency step where it appears, and tally the concurrent 409, 503, and 504 errors.
  - Preserve all node logs, health metrics, and result JSONs before session teardown (`bench.sh down`).

### 2. Quantitative Evidence & Error Breakdown

Complete logs for all three nodes (`node-1.log`, `node-2.log`, `node-3.log`) and health/metrics dumps are preserved in `2-X55-postgres/` and `2-X55-mongo/`.

| Cluster | Scenario | Concurrency | Throughput (/s) | 409 `wow.command.contended` | 503 `wow.command.unknown` | 504 `wow.wait.timeout` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| PostgreSQL 3-Node | E1 sweep | 1,024 | 6,095.3 | 0 | 2,151 | 1,136 |
| PostgreSQL 3-Node | E3 sweep | 256 | 5,562.8 | 0 | 67 | 57 |
| PostgreSQL 3-Node | E3 sweep | 1,024 | 2,195.8 | **11** | 1,299 | 1,114 |
| PostgreSQL 3-Node | E1 fixed 60s | 1,024 | 5,437.8 | 0 | 3,710 | 2,727 |
| PostgreSQL 3-Node | E3 fixed 60s | 1,024 | 1,999.8 | **26** | 2,400 | 2,068 |
| MongoDB 3-Node | E1 sweep | 1,024 | 3,281.4 | **1** | 2,318 | 947 |
| MongoDB 3-Node | E3 sweep | 256 | 341.2 | 0 | 22 | 71 |
| MongoDB 3-Node | E3 sweep | 1,024 | 289.1 | 0 | 297 | 1,161 |
| MongoDB 3-Node | E1 fixed 60s | 1,024 | 2,923.4 | 0 | 4,187 | 2,863 |
| MongoDB 3-Node | E3 fixed 60s | 1,024 | 208.7 | 0 | 418 | 2,169 |

### 3. Timeline of Stream Exhaustion & Dropped Signals

Analysis of `too many concurrent streams` across cluster node logs:

| Cluster | Node | Log Warnings Count | Earliest Timestamp | Concurrency Step at Emergence | Max Dropped Wait Signals Logged |
| :--- | :--- | :--- | :--- | :--- | :--- |
| PostgreSQL | `node-1` | 46 occurrences | 02:08:50.212-08:00 | **E3 sweep, step 256** | 4,500 dropped signals |
| PostgreSQL | `node-2` | 45 occurrences | 02:08:56.283-08:00 | **E3 sweep, step 256** | 4,400 dropped signals |
| PostgreSQL | `node-3` | 3 occurrences | 02:09:04.302-08:00 | **E3 sweep, step 256** | 200 dropped signals |
| MongoDB | `node-1` | 22 occurrences | 02:22:07.112-08:00 | **E3 sweep, step 256** | 2,100 dropped signals |
| MongoDB | `node-2` | 30 occurrences | 02:22:19.387-08:00 | **E3 sweep, step 256** | 2,900 dropped signals |
| MongoDB | `node-3` | 16 occurrences | 02:22:55.314-08:00 | **E3 sweep, step 256** | 1,700 dropped signals |

#### Key Observations:
1. **First Emergence at Concurrency 256 in E3**:
   On both PostgreSQL and MongoDB clusters, internal HTTP/2 stream exhaustion does not wait for concurrency 1,024; it first emerges during concurrency 256 of the E3 sweep (02:08:50 on PostgreSQL, 02:22:07 on MongoDB). E3 involves two hops and inter-node wait signaling, generating substantially more concurrent internal streams than E1.
2. **Cumulative Dropped Signals**:
   - On PostgreSQL: Cumulative dropped wait signals exceeded 4,500 on `node-1`, 4,400 on `node-2`, and 200 on `node-3` (~9,100 total signals dropped).
   - On MongoDB: Cumulative dropped wait signals exceeded 2,100 on `node-1`, 2,900 on `node-2`, and 1,700 on `node-3` (~6,700 total signals dropped).
3. **Correlation with 503, 504, and 409**:
   - When internal streams are saturated, `HttpSignalSender` drops wait signals. Clients waiting for completion time out after the wait deadline, answering HTTP 504 `wow.wait.timeout`.
   - Inter-node command forwarding requests fail or time out, answering HTTP 503 `wow.command.unknown` (indicating the partition owner did not reply in time and the command commit status is unknown).
   - When a forward fails before delivery confirmation, `CommandRouter` falls back to degraded local execution without holding the partition lease (`ExecutionLease.NOT_HELD`). When multiple nodes execute commands for the same aggregate concurrently, the event store detects optimistic version conflicts, producing HTTP 409 `wow.command.contended`. A total of 37 contended commands (11 in sweep, 26 in fixed) occurred on PostgreSQL, and 1 on MongoDB.
4. **Safety Verification**:
   The underlying event store version checks (PostgreSQL `event_stream` unique version index, MongoDB `events` unique index) safely refused the conflicting appends. No events were duplicated or lost; the 409 status cleanly alerted callers to retry.

---

## Investigation X-40: Verification of Overload Behavior (HTTP 500)

In snapshot 2, earlier runs passing an empty Kafka address (`--wow.kafka.bootstrap-servers=`) selected unbounded in-memory topics that leaked heap and triggered HTTP 500 errors under heavy load. Fix X-33 introduced `wow.transport=none` to cleanly disable Kafka without engaging in-memory queues.

In this snapshot run:
- All single-node saturation sweeps (1 GB heap) on PostgreSQL and MongoDB completed with **zero HTTP 500 errors**.
- All three-node cluster sweeps and fixed runs (1 GB heap) completed with **zero HTTP 500 errors**.
- All Axon comparison rerun sessions with an 8 GB heap (using `--wow.transport=none` for Wow Kafka off) completed with **zero HTTP 500 errors**.
- Every overloaded request was answered deterministically with HTTP 429 `wow.command.busy`, HTTP 504 `wow.wait.timeout`, HTTP 503 `wow.command.unknown`, or HTTP 409 `wow.command.contended`.

---

## Summary of Observed Error Codes

| Error Code | HTTP Status | Meaning | Where Observed |
| :--- | :--- | :--- | :--- |
| `wow.command.busy` | 429 | Aggregate command lane full or node-level inflight limit reached | Single-node E1 Zipf, E1 hot, and E3 at concurrency 4,096 |
| `wow.wait.timeout` | 504 | Wait plan deadline expired before target aggregate entry committed | Single-node E3 at concurrency 4,096; 3-node cluster at 256 and 1,024 |
| `wow.command.unknown` | 503 | Forwarded command response timed out between cluster nodes | 3-node cluster co-located on single host under concurrency 256 and 1,024 |
| `wow.command.contended` | 409 | Concurrent writers won aggregate version slot (exceeded retry attempts) | 3-node cluster under concurrency 1,024 (PostgreSQL: 37 total; MongoDB: 1 total) |
| HTTP 500 / EOF | 500 | Server crash or unhandled internal error | **Zero observed in any run** |

---

## What Follows

1. **Capacity Consistency**:
   - Single-node throughput on Apple M5 Max remains stable across all three snapshots. On PostgreSQL, E1 uniform reaches ≥ 35,225.6/s and E3 uniform reaches 9,441.7/s at concurrency 1,024. On MongoDB, E1 uniform reaches ≥ 21,961.3/s and E3 uniform reaches 6,226.3/s at concurrency 1,024.
2. **X-55 Actionable Findings**:
   - Internal HTTP/2 channel saturation on co-located nodes is confirmed to begin at concurrency 256 on multi-hop scenarios (E3).
   - The stream exhaustion directly causes dropped wait signals (thousands per node) and command forward failures, which subsequently triggers degraded unleased execution and version conflicts (409) at the storage boundary.
   - Tuning or multiplexing pool expansion for the internal HTTP/2 client represents the primary technical lever to eliminate 503s and 409s under single-host high-concurrency conditions.
3. **Overload Stability**:
   - Disabling transport via `--wow.transport=none` completely eliminates memory leakage and unhandled HTTP 500 errors under extreme load, upholding strict admission backpressure semantics.
