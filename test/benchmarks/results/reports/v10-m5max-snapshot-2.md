# V10 milestone snapshot 2 on Apple M5 Max

The second milestone saturation snapshot of Wow 10 on an Apple M5 Max: single-node closed-loop sweeps on disk (PostgreSQL and MongoDB), reproduction of multi-node concurrency conflicts (X-34), and investigation of in-memory transport behavior under overload (X-40). Measured on 2026-11-01 at `30d0c0348e` (`main`), compared against the first M5 Max snapshot (`cebdb88d0`, 2026-10-30). Between the two snapshots, `main` moved by 6 commits. The raw runs, scripts, and logs are in [`v10-m5max-snapshot-2/`](v10-m5max-snapshot-2/).

## Setup

| | |
| --- | --- |
| Commit | `30d0c0348e11a14b0b1444d3202521199321dafa` (`30d0c0348e`, `main`) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB unified memory, macOS 27.2 |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB (33,670,660,096 bytes) |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`); MongoDB 7.0 (WiredTiger cache 2 GB); Kafka 7.4.0 (KRaft mode) |
| Node | One node (3 nodes in X-34), 1 GB heap (`-Xms1g -Xmx1g -XX:+UseG1GC`, 8 GB in X-40), Eclipse Adoptium Temurin 21.0.13+9 |
| Data | 100,000 accounts (`--accounts=100000`), primed once per session (`--prime --seed-concurrency=128`), uniform keys unless noted |
| Sweep | Closed-loop concurrency 1, 4, 16, 64, 256, 1024, 4096 (`--growth=4 --max-concurrency=4096 --step=30 --warm-up=10`) |

## Ceilings

Closed-loop sweeps, 30 s per step (10 s warm-up), concurrency 1 to 4,096 (`--growth=4`), one session per backend on disk. Comparing snapshot 2 (`30d0c0348e`) with snapshot 1 (`cebdb88d0`):

| Peak throughput (/s) | PostgreSQL (Snapshot 2) | PostgreSQL (Snapshot 1) | MongoDB (Snapshot 2) | MongoDB (Snapshot 1) |
| :--- | :--- | :--- | :--- | :--- |
| E1, uniform | ≥ 36,388.7 (step 4096) | ≥ 33,180 (step 4096) | ≥ 23,382.3 (step 4096) | ≥ 22,745 (step 4096) |
| E1, Zipf | ≥ 21,227.8 (step 4096) | ≥ 19,599 (step 4096) | ≥ 18,050.6 (step 4096) | ≥ 17,871 (step 4096) |
| E1, one hot account | ≥ 20,184.5 (step 4096) | ≥ 21,191 (step 4096) | ≥ 19,829.2 (step 4096) | ≥ 18,987 (step 4096) |
| E3, uniform | 8,674.6 (step 1024) | 9,101 (step 1024) | 6,531.7 (step 1024) | 6,755 (step 1024) |

- **E1 uniform**: Peaked at the top step (4,096) on both backends across both snapshots. Because the peaks fall on the highest step, these throughput figures are lower bounds (marked with `≥`); a comparison between two lower bounds does not represent an improvement ratio. Neither E1 uniform sweep recorded any failed commands.
- **E1 Zipf and hot account**: In both backends, throughput peaked at step 4,096 where the single node refused excess commands with HTTP 429 `wow.command.busy`. On PostgreSQL, Zipf refused 102,964 commands (vs 82,807 in snapshot 1) and the hot account refused 548,717 commands (vs 590,131 in snapshot 1). On MongoDB, Zipf refused 80,682 commands (vs 81,031 in snapshot 1) and the hot account refused 583,009 commands (vs 558,260 in snapshot 1). Below concurrency 4,096, neither Zipf nor hot account recorded errors.
- **E3 uniform**: Peaked at concurrency 1,024 on both backends: 8,674.6/s on PostgreSQL (vs 9,101/s in snapshot 1) and 6,531.7/s on MongoDB (vs 6,755/s in snapshot 1). At concurrency 4,096, throughput degraded as command queues filled, refusing commands with 429 `wow.command.busy` and timing out waits with 504 `wow.wait.timeout`.

## E3 Past Its Knee

Single-node E3 uniform saturation across step 1,024 (the knee) and step 4,096:

| Session | E3 at 1,024 (/s) | E3 at 4,096 (/s) | `wow.command.busy` (429) | `wow.wait.timeout` (504) | Other errors |
| :--- | :--- | :--- | :--- | :--- | :--- |
| PostgreSQL (Snapshot 2) | 8,674.6 | 5,021.4 | 415,664 | 469 | 0 |
| PostgreSQL (Snapshot 1) | 9,101 | 5,818 | 301,588 | 791 | 0 |
| MongoDB (Snapshot 2) | 6,531.7 | 4,194.6 | 292,011 | 2,152 | 0 |
| MongoDB (Snapshot 1) | 6,755 | 3,343 | 202,247 | 2,535 | 0 |

- Across both backends, all single-node E3 errors were confined to concurrency 4,096. No errors occurred at or below concurrency 1,024.
- `wow.command.busy` (429) reflects admission lane backpressure when aggregate lanes or the node's inflight command quota is reached.
- `wow.wait.timeout` (504) indicates transfers where the second hop (`Entry` on target) was not appended to the event store within the wait deadline.

---

## Investigation X-34: Three-Node Contended Commands (409 wow.command.contended)

### 1. Phenomenon & Reproduction Status
- **Status**: **REPRODUCED** on both PostgreSQL and MongoDB 3-node clusters.
- **Background**: In snapshot 1, 3 nodes co-located on the same host under PostgreSQL concurrency 1,024 produced 7 (E1) and 9 (E3) commands answered with HTTP 409 `wow.command.contended`. By design, an aggregate has exactly one owner node processing its writes, so optimistic version contention should not happen unless multiple nodes write to the same aggregate concurrently.
- **Reproduction Matrix (Snapshot 2)**:
  - 3-node cluster, 100,000 accounts seeded and primed, 1 GB heap per node, load generator distributing requests round-robin across all 3 nodes (ports 18080, 18081, 18082; internal ports 19090, 19091, 19092).

| Backend | Scenario | Concurrency | Throughput (/s) | 409 `wow.command.contended` | 503 `wow.command.unknown` | 504 `wow.wait.timeout` |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| PostgreSQL | E1 sweep | 1,024 | 7,814.6 | **2** | 4,536 | 408 |
| PostgreSQL | E3 sweep | 1,024 | 3,081.0 | **4** | 4,764 | 1,835 |
| PostgreSQL | E1 fixed | 1,024 (60s) | 6,896.4 | **1** | 11,180 | 1,876 |
| PostgreSQL | E3 fixed | 1,024 (60s) | 2,836.8 | **16** | 6,086 | 2,096 |
| MongoDB | E1 sweep | 1,024 | 3,302.7 | 0 | 1,742 | 351 |
| MongoDB | E3 sweep | 1,024 | 347.6 | **3** | 199 | 1,388 |
| MongoDB | E1 fixed | 1,024 (60s) | 4,053.5 | **1** | 7,085 | 2,337 |
| MongoDB | E3 fixed | 1,024 (60s) | 263.7 | 0 | 386 | 2,637 |

### 2. Evidence & Timeline Analysis
Complete logs for all three nodes (`node-1.log`, `node-2.log`, `node-3.log`) were captured before session teardown and are preserved in `2-X34-postgres/logs/` and `2-X34-mongo/logs/`.

#### Timeline of Critical Failure Chain:
1. **Internal Cluster Stream Exhaustion**:
   During concurrency 1,024 on a single host, inter-node HTTP/2 multiplexing on internal ports (19090..19092) hits connection stream limits:
   ```text
   2026-11-01T00:36:08.538-07:00 WARN 33804 --- [transfer-service] [atcher-worker-9] m.a.wow.http.internal.HttpSignalSender :
   Dropped the signal of wait ... to 127.0.0.1:19092 (java.io.IOException: too many concurrent streams); 1 dropped so far.
   ...
   2026-11-01T00:40:22.605-07:00 WARN 33856 --- [transfer-service] [atcher-worker-8] m.a.wow.http.internal.HttpSignalSender :
   Dropped the signal of wait ... to 127.0.0.1:19090 (java.io.IOException: too many concurrent streams); 1300 dropped so far.
   ```
2. **Forwarding Timeout & Outcome Unknown (503)**:
   When node $A$ attempts to forward a command to peer node $B$ (the partition owner), the request times out due to channel saturation or thread starvation:
   ```text
   me.ahoo.wow.api.problem.WowProblemException: The derived command e33b0990-... on transfer.account/(0)/acc-0053997 failed:
   wow.command.unknown The owner of transfer.account/(0)/acc-0053997 did not reply in time; whether the command committed is unknown.
   ```
3. **Partition Pauses**:
   Repeated derived command timeouts trigger saga partition pauses (`PartitionPause` pauses partition after 3 aggregate failures within 10s):
   ```text
   Subscription TransferSaga.onPrepared pauses partition 10 of group transfer-service/transfer on 3 aggregates failed within 10s...
   ```
4. **Degradation to Local Execution without Lease (Root Cause of 409)**:
   In `CommandRouter.kt`:
   ```kotlin
   val degraded: suspend () -> Settlement = { withContext(ExecutionLease.NOT_HELD) { local() } }
   val peer = ownership.forwardTarget(partition) ?: return degraded()
   return forward(ownership, peer, model, command, wait) ?: degraded()
   ```
   When `forward()` fails before transmission or is refused by an overloaded peer, it calls `unsent()` and returns `null`. This causes `CommandRouter` to fall back to `degraded()`, executing the command **locally on the receiving node without holding the partition lease** (`ExecutionLease.NOT_HELD`).
   Simultaneously, the true partition owner (or another node that also degraded) executes in-flight commands on the exact same aggregate. Both nodes attempt to append version $V+1$ of the aggregate to the shared event store (PostgreSQL `event_stream` or MongoDB `events`). One node commits; the other receives `AppendOutcome.Conflict`.
   After exhausting optimistic retry attempts in `Kernel.execute`, the failing node returns `CommandOutcome.Contended(maxAttempts)`, answered to the client as HTTP 409 `wow.command.contended`.

### 3. Verdict
- **Confirmed**: 409 `wow.command.contended` is directly caused by `CommandRouter`'s fallback degradation (`degraded()` running `local()` with `ExecutionLease.NOT_HELD`) when inter-node forward calls fail or time out under severe single-host resource saturation.
- **Scope**: Occurs on both PostgreSQL and MongoDB when cluster internal communication degrades.

---

## Investigation X-40: In-Memory Transport Overload Behavior (HTTP 500)

### 1. Phenomenon & Reproduction Status
- **Status**: **NOT REPRODUCED**.
- **Background**: In an earlier Axon comparison with 8 GB heap, runs passing an empty Kafka address (`--wow.kafka.bootstrap-servers=`) selected in-memory topics (`wow.transport=memory`). Under concurrency 4,096, that run reported 21 and 11 HTTP 500 errors, along with EOF and timeout exceptions. This occurred prior to fix X-33 (which added capacity caps to in-memory topics and introduced `wow.transport=none`).
- **Test Configuration**:
  - Node heap: 8 GB (`-Xms8g -Xmx8g -XX:+UseG1GC`)
  - Arm 1: Blank Kafka address (`--server-args=--wow.kafka.bootstrap-servers=`, in-memory transport)
  - Arm 2: Transport disabled (`--server-args=--wow.transport=none`)
  - Both arms executed closed-loop sweeps from concurrency 1 to 4,096 (growth 4) for scenarios E1 and E3.

### 2. Experimental Results

| Arm | Scenario | Peak Throughput (/s) | Peak Concurrency | Concurrency 4,096 Errors | HTTP 500 / EOF |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Arm 1: 8 GB Heap, in-memory transport | E1 uniform | ≥ 40,945.1 | 4,096 | **0 errors** | **0** |
| Arm 1: 8 GB Heap, in-memory transport | E3 uniform | 10,386.6 | 1,024 | 423,923 `wow.command.busy` (429)<br>310 `wow.wait.timeout` (504) | **0** |
| Arm 2: 8 GB Heap, `wow.transport=none` | E1 uniform | ≥ 41,303.8 | 4,096 | **0 errors** | **0** |
| Arm 2: 8 GB Heap, `wow.transport=none` | E3 uniform | 10,546.4 | 1,024 | 395,560 `wow.command.busy` (429)<br>608 `wow.wait.timeout` (504) | **0** |

### 3. Server Logs, Heap, and GC Analysis
- **HTTP 500 Count**: Exactly **0**.
- **Server Logs**: Server logs (`3-X40-inmem/logs/node-1.log` and `3-X40-notransport/logs/node-1.log`) contain no `InternalServerError`, no `OutOfMemoryError`, and no uncaught exceptions.
- **Client Disconnections**: The only warnings in server logs were standard Netty socket reset notices (`java.net.SocketException: Connection reset`) emitted when the load generator closed client connections between sweep steps.
- **Heap and GC**: On 8 GB heap, G1 GC maintained young generation collections comfortably; average heap usage remained under 2.5 GB throughout the sweeps, and in-memory bounded queues did not accumulate uncollected garbage.

### 4. Verdict
- **Confirmed Fixed by X-33**: Under the current codebase, in-memory transport under 8 GB heap behaves strictly within design specifications during overload: all excessive requests are handled cleanly as HTTP 429 `wow.command.busy` (aggregate lane admission rejection) or HTTP 504 `wow.wait.timeout` (wait plan expiration). HTTP 500 and EOF errors no longer occur.

---

## Summary of Observed Error Codes

| Error Code | HTTP Status | Meaning | Where Observed |
| :--- | :--- | :--- | :--- |
| `wow.command.busy` | 429 | Aggregate command lane full or node-level inflight limit reached | Single-node E1 Zipf, E1 hot, and E3 at concurrency 4,096; X-40 8 GB E3 at 4,096 |
| `wow.wait.timeout` | 504 | Wait plan deadline expired before target aggregate entry committed | Single-node E3 at concurrency 4,096; 3-node cluster at 1,024 |
| `wow.command.unknown` | 503 | Forwarded command response timed out between cluster nodes | 3-node cluster co-located on single host under concurrency 1,024 |
| `wow.command.contended` | 409 | Concurrent writers won aggregate version slot (exceeded retry attempts) | 3-node cluster under concurrency 1,024 (PostgreSQL: 23 total; MongoDB: 4 total) |
| HTTP 500 / EOF | 500 | Server crash or unhandled internal error | **None observed in any run** |

---

## What Follows

1. **Capacity Consistency**:
   - Single-node throughput on M5 Max remains consistent with the first snapshot: E1 uniform reaches ≥ 36,388.7/s on PostgreSQL and ≥ 23,382.3/s on MongoDB; E3 uniform reaches 8,674.6/s on PostgreSQL and 6,531.7/s on MongoDB at concurrency 1,024.
2. **X-34 Root Cause Identified**:
   - 409 contention in 3-node clusters on a single host is verified to stem from `CommandRouter`'s fallback to unleased local execution (`ExecutionLease.NOT_HELD`) upon forward timeouts. When co-located nodes starve on shared CPU and cluster HTTP/2 channels drop signals, concurrent local writes collide on the database version slot.
3. **X-40 Resolution Verified**:
   - The bounded in-memory transport introduced in X-33 prevents unbounded memory growth; under 8 GB heap, high-concurrency overload produces only expected 429 and 504 status codes with zero HTTP 500 failures.
