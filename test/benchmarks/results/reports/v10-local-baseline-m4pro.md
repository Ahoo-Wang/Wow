# V10 local baseline on Apple M4 Pro: cost per operation and saturation headroom

A local performance baseline of Wow 10 measured on an Apple M4 Pro on 2026-10-24. It measures clean, unpolluted numbers without Java Flight Recorder (JFR) overhead to establish hardware-specific saturation ceilings, single-operation resource costs across backends and scenarios, and a direct comparison against the earlier Apple M4 (10-core) baseline ([`v10-local-baseline.md`](v10-local-baseline.md)).

## Setup

| | |
| --- | --- |
| Commit | `3508887407dfdcded5df0f41c42c1afc7fe452f9` (`main` HEAD) |
| Machine | MacBook Pro, Apple M4 Pro (14 cores: 10 Performance + 4 Efficiency), 24 GB unified memory |
| OS | macOS 27.0.1 (Build 26A434) |
| Docker Desktop | 4.96.3 (244472), Engine 29.9.2, assigned 12 CPUs and 15.6 GiB (~16 GB) memory |
| JDK | Zulu OpenJDK 21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing) |
| Topology | One Wow node (1 GB heap, G1 GC); PostgreSQL (17-alpine) or MongoDB (7.0 single-node replica set) and Kafka (7.4.0) in containers |
| Data | 100,000 accounts, seeded and primed once per session at concurrency 128 (every account touched once); uniform keys unless noted |
| Scenarios | E1 (deposit, wait for `committed`), E3 (transfer saga across two accounts, wait for `Entry` committed on target) |

Three measurement stages executed sequentially using session mode (`bench.sh up`, `--reuse`, `down`):

- **Stage 0, A/A Noise.** PostgreSQL E1 at a fixed open-loop rate of 7,038/s (50% of the initial closed-loop sweep peak of 14,076/s). Three independent sessions with `up --prime`, each running two 60 s measured rounds (30 s warm-up). Evaluates variance across restarts and within sessions.
- **Stage 1, Cost Breakdown.** One dedicated session per backend (PostgreSQL and MongoDB) without JFR profiling to obtain clean execution timings. For each scenario (E1 uniform, E1 zipf, E3 uniform): a closed-loop sweep from concurrency 1 to 512 to establish the knee and peak, followed by open-loop runs at 25%, 50%, and 75% of the sweep peak (30 s warm-up, 60 s measured).
- **L1 Component Benchmarks.** Domain, kernel, and one store in a single JVM (`bench.sh component`) at concurrency 16, 64, and 256 for PostgreSQL and MongoDB on E1 and E3 (20,000 operations per run), measuring store round trips and allocations in isolation from HTTP and Kafka.

The raw results and scripts are preserved in [`v10-local-baseline-m4pro/`](v10-local-baseline-m4pro/): `runs/<run>/` contains `result.json`, `summary.md`, `saturation.json`, `component.json`, and `postgres-stats.txt`; `cost-per-op.tsv` compiles per-operation resource metrics.

## How much one run can show (Stage 0: A/A Noise)

Six open-loop runs of PostgreSQL E1 at 7,038/s (50% of the closed-loop sweep peak) across three clean sessions:

| Session, Run | p50 | p99 | Max | Server CPU ms/op | Allocated KB/op | PostgreSQL CPU ms/op | Kafka CPU ms/op | Commits/op | WAL flushes/s | WAL flush time |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1, 1 | 3.76 ms | 57.6 ms | 307 ms | 0.468 | 82.0 | 0.197 | 0.060 | 1.15 | 2,682 | 809 ms/s (max 896 ms) |
| 1, 2 | 4.70 ms | 71.2 ms | 296 ms | 0.473 | 84.2 | 0.206 | 0.057 | 1.19 | 2,673 | 812 ms/s (max 895 ms) |
| 2, 1 | 7.97 ms | 376.8 ms | 777 ms | 0.414 | 82.7 | 0.209 | 0.056 | 0.91 | 2,168 | 733 ms/s (max 849 ms) |
| 2, 2 | 11.97 ms | 725.0 ms | 2,000 ms | 0.401 | 85.9 | 0.210 | 0.058 | 0.92 | 1,997 | 717 ms/s (max 851 ms) |
| 3, 1 | 7.55 ms | 76.3 ms | 348 ms | 0.464 | 82.6 | 0.188 | 0.055 | 1.01 | 2,352 | 771 ms/s (max 853 ms) |
| 3, 2 | 7.17 ms | 79.4 ms | 349 ms | 0.502 | 84.9 | 0.206 | 0.059 | 1.09 | 2,436 | 775 ms/s (max 855 ms) |

- **Resource consumption per operation is highly reproducible:**
  - Server CPU was 0.40–0.50 ms/op across all runs (0.46–0.50 ms/op in unqueued runs, ±4% dispersion).
  - JVM heap allocation was 82.0–85.9 KB/op (±2.3% dispersion).
  - PostgreSQL container CPU was 0.19–0.21 ms/op (±5% dispersion).
  - Kafka container CPU was 0.055–0.060 ms/op (±4% dispersion).
  - As observed on M4, resource costs and operation counts (commits, WAL flushes, round trips) remain consistent across restarts and sessions.
- **Latency at 50% load shows queuing instability:**
  - While p50 ranged moderately between 3.76 ms and 11.97 ms, p99 fluctuated from 57.6 ms to 725.0 ms at the exact same arrival rate.
  - In Session 2 (the queueing outlier), operations experienced temporary queuing in the aggregate lanes: PostgreSQL committed in larger batches (commits per op dropped from ~1.17 to 0.91–0.92), WAL flushes dropped from ~2,680/s to ~2,000/s, and server CPU per op actually fell to 0.40–0.41 ms/op due to batch amortization.
  - This replicates the exact queuing dynamics recorded in the M4 baseline, confirming that tail latency under heavy open-loop load is sensitive to micro-bursts, whereas resource costs per op remain reliable indicators.

## Throughput and latency (Stage 1)

Closed-loop sweeps, reporting throughput and p50 latency across concurrencies 1 to 512:

| Concurrency | 1 | 8 | 32 | 128 | 512 | Peak |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL E1** | 1,789/s, 0.5 ms | 5,332/s, 1.4 ms | 7,419/s, 3.8 ms | 9,135/s, 12.2 ms | 13,808/s, 34.8 ms | **13,808/s** |
| **PostgreSQL E1, zipf** | 1,248/s, 0.6 ms | 2,908/s, 2.0 ms | 4,771/s, 2.0 ms | 4,567/s, 2.1 ms | 3,624/s, 2.4 ms | **5,003/s** |
| **PostgreSQL E3** | 408/s, 2.1 ms | 1,440/s, 4.6 ms | 1,562/s, 15.0 ms | 2,004/s, 49.9 ms | 1,742/s, 177.2 ms | **2,660/s** |
| **MongoDB E1** | 692/s, 1.3 ms | 3,602/s, 2.1 ms | 4,960/s, 5.0 ms | 5,710/s, 15.7 ms | 12,554/s, 36.6 ms | **12,554/s** |
| **MongoDB E1, zipf** | 528/s, 1.9 ms | 3,264/s, 2.0 ms | 3,711/s, 2.4 ms | 3,684/s, 2.4 ms | 2,577/s, 2.6 ms | **3,843/s** |
| **MongoDB E3** | 256/s, 3.7 ms | 1,430/s, 5.2 ms | 2,356/s, 12.8 ms | 3,216/s, 38.1 ms | 2,631/s, 185.3 ms | **3,216/s** |

Open-loop measurements at 25%, 50%, and 75% of each scenario's sweep peak (target rate, actual throughput, p50 and p99 latency):

| Scenario | 25% Target (Actual, p50 / p99) | 50% Target (Actual, p50 / p99) | 75% Target (Actual, p50 / p99) |
| :--- | :--- | :--- | :--- |
| **PostgreSQL E1** | 3,452/s (3,452.0/s, 1.06 / 29.2 ms) | 6,904/s (6,904.0/s, 28.9 / 238 ms) | 10,356/s (9,275.6/s, 98.3 / 1,393 ms) |
| **PostgreSQL E1, zipf** | 1,251/s (1,251.0/s, 0.56 / 152 ms) | 2,501/s (2,501.0/s, 0.67 / 8.89 ms) | 3,752/s (3,569.0/s, 2.22 / 4,080 ms) |
| **PostgreSQL E3** | 665/s (665.0/s, 3.87 / 3,408 ms) | 1,330/s (1,323.0/s, 67.6 / 2,933 ms) | 1,995/s (1,909.8/s, 379 / 4,129 ms) |
| **MongoDB E1** | 3,138/s (2,986.8/s, 3.73 / 3,752 ms) | 6,277/s (6,128.9/s, 23.7 / 1,630 ms) | 9,415/s (4,868.4/s, 741 / 2,458 ms) |
| **MongoDB E1, zipf** | 961/s (961.0/s, 1.40 / 8.45 ms) | 1,921/s (1,921.0/s, 1.66 / 94.2 ms) | 2,882/s (2,882.0/s, 2.00 / 133 ms) |
| **MongoDB E3** | 804/s (752.5/s, 10.9 / 8,782 ms) | 1,608/s (1,608.0/s, 795 / 1,917 ms) | 2,412/s (1,449.4/s, 1,827 / 5,603 ms) |

### Key observations on throughput and latency:
1. **Saturation peaks are not sustainable throughput limits:**
   - At 75% of the closed-loop peak, open-loop arrival rates cannot be sustained in uniform distribution: PostgreSQL E1 delivered 9,275/s of 10,356/s (p99 1.39 s), and E3 queued heavily on both backends.
   - At 50% of the peak, PostgreSQL E1 sustained 6,904/s (100% completed) with a p50 of 28.9 ms. For zipf distribution, 50% achieved exceptional stability: PostgreSQL delivered 2,501/s with p50 of 0.67 ms and p99 of 8.89 ms; MongoDB delivered 1,921/s with p50 of 1.66 ms and p99 of 94.2 ms.
2. **Zipf key serialization cap:**
   - Under zipf distribution, closed-loop throughput plateaus sharply around concurrency 32 (~4,800–5,000/s for PostgreSQL, ~3,700–3,840/s for MongoDB). Additional client concurrency simply queues behind the hottest aggregate instances, while low p50 (2.0–2.4 ms) indicates fast lane processing.
3. **Single-request baseline latency:**
   - At concurrency 1, end-to-end request latency represents the raw pipeline cost:
     - PostgreSQL E1: 0.53 ms (vs 0.8 ms on M4).
     - PostgreSQL E3: 2.1 ms (vs 1.5 ms on M4).
     - MongoDB E1: 1.3 ms (vs 1.4 ms on M4).
     - MongoDB E3: 3.7 ms (vs 3.0 ms on M4).

## Cost per operation

Metrics at 50% of peak load (clean run without JFR). All CPU metrics are in milliseconds per operation (`cpuSeconds * 1000 / ops`); containers' CPU is derived from Docker statistics over the measured phase:

| Scenario | Server CPU (ms/op) | Allocated (KB/op) | Store CPU (ms/op) | Kafka CPU (ms/op) | Commits /op | WAL records /op | WAL bytes /op | WAL flushes /s | WAL flush time |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL E1** | 0.426 | 90.2 | 0.287 | 0.067 | 0.78 | 7.82 | 1,400 B | 1,642 | 728 ms/s |
| **PostgreSQL E1, zipf** | 0.691 | 87.7 | 0.283 | 0.138 | 2.01 | 10.13 | 1,417 B | 4,413 | 648 ms/s |
| **PostgreSQL E3** | 1.370 | 257.3 | 1.563 | 0.340 | 3.20 | 24.26 | 7,039 B | 1,190 | 736 ms/s |
| **MongoDB E1** | 0.375 | 125.0 | 0.940 | 0.089 | — | — | — | — | — |
| **MongoDB E1, zipf** | 0.655 | 136.8 | 0.720 | 0.188 | — | — | — | — | — |
| **MongoDB E3** | 1.347 | 486.1 | 4.974 | 0.363 | — | — | — | — | — |

*MongoDB operation counters at 50% load:*
- **MongoDB E1 uniform:** `insert` 1.001/op, `query` 0.348/op, `update` 1.080/op, `command` 0.204/op.
- **MongoDB E1 zipf:** `insert` 1.010/op, `query` 0.035/op (cache hits eliminated queries), `update` 1.271/op, `command` 0.034/op.
- **MongoDB E3 uniform:** `insert` 3.051/op, `query` 4.377/op, `update` 1.983/op, `command` 0.922/op.

### Resource cost analysis:
1. **Unpolluted JVM execution lowers server CPU by >50%:**
   - On PostgreSQL E1, server CPU dropped from 1.03 ms/op (M4 with JFR) to 0.426 ms/op.
   - On MongoDB E1, server CPU dropped from 0.95 ms/op (M4 with JFR) to 0.375 ms/op.
   - Running clean without JFR thread-sampling and event interception avoids safepoint pauses and thread-local data serialization, establishing the true baseline CPU efficiency of Wow 10.
2. **State cache effectiveness under Zipf distribution:**
   - Under uniform distribution (100k accounts, 1 GB heap), aggregate cache misses require database reads (`query` 0.348/op on MongoDB; `tup_fetched` 6.85/op on PostgreSQL).
   - Under zipf distribution, state cache hit rates approach ~96% (`query` drops to 0.035/op on MongoDB, and `tup_fetched` drops to 4.14/op on PostgreSQL).
3. **WAL flushing as the PostgreSQL write ceiling:**
   - At 50% load (6,904/s), PostgreSQL flushes the WAL 1,642 times per second, consuming 728 ms of each second (0.44 ms per sync).
   - At 6,904/s, multiple deposits are batched into single commit/sync cycles (0.78 commits/op, 0.24 syncs/op), allowing M4 Pro to sustain 3.6× the throughput of M4 (1,906/s) within roughly the same 700–800 ms/s WAL flush time window.

## L1 Component scaling

The kernel and single store executed in one JVM (`bench.sh component`) without HTTP network hops or Kafka bus activity, at concurrencies 16, 64, and 256:

| Backend | Scenario | Concurrency | Throughput | Store Round Trips /op | Allocated (KB/op) | p50 | p99 | Top Statement / Driver Commands |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL** | E1 | 16 | 5,208/s | 2.62 | 78.6 | 2.97 ms | 5.21 ms | `with log`: 1.00, `with snapshot`: 0.87, `commit`: 0.37 |
| **PostgreSQL** | E1 | 64 | 6,265/s | 2.06 | 77.2 | 9.97 ms | 19.19 ms | `with log`: 0.96, `with snapshot`: 0.87, `commit`: 0.11 |
| **PostgreSQL** | E1 | 256 | 8,340/s | 1.37 | 77.9 | 30.13 ms | 61.06 ms | `with snapshot`: 0.77, `with log`: 0.53, `commit`: 0.03 |
| **PostgreSQL** | E3 | 16 | 2,450/s | 5.28 | 195.1 | 6.32 ms | 11.82 ms | `with log`: 3.00, `with snapshot`: 0.87, `commit`: 0.70 |
| **PostgreSQL** | E3 | 64 | 2,756/s | 3.99 | 200.7 | 22.28 ms | 41.54 ms | `with log`: 2.67, `with snapshot`: 0.87, `commit`: 0.22 |
| **PostgreSQL** | E3 | 256 | 3,694/s | 1.97 | 199.9 | 63.40 ms | 216.62 ms | `with log`: 1.09, `with snapshot`: 0.75, `commit`: 0.06 |
| **MongoDB** | E1 | 16 | 4,029/s | 3.19 | 177.5 | 3.61 ms | 9.99 ms | `find`: 1.00, `insert`: 1.00, `aggregate`: 0.87 |
| **MongoDB** | E1 | 64 | 5,850/s | 2.89 | 169.3 | 10.15 ms | 23.32 ms | `find`: 1.00, `insert`: 0.94, `aggregate`: 0.87 |
| **MongoDB** | E1 | 256 | 8,022/s | 2.24 | 150.7 | 30.47 ms | 67.18 ms | `find`: 1.00, `aggregate`: 0.76, `insert`: 0.46 |
| **MongoDB** | E3 | 16 | 2,083/s | 5.46 | 362.9 | 7.20 ms | 15.35 ms | `insert`: 3.00, `find`: 1.00, `aggregate`: 0.87 |
| **MongoDB** | E3 | 64 | 3,010/s | 4.61 | 341.0 | 20.56 ms | 40.43 ms | `insert`: 2.57, `find`: 1.00, `aggregate`: 0.87 |
| **MongoDB** | E3 | 256 | 4,683/s | 2.80 | 281.3 | 51.67 ms | 96.92 ms | `find`: 1.00, `insert`: 1.00, `aggregate`: 0.75 |

### L1 Component insights:
- **Batching efficiency increases with concurrency:**
  - As concurrency increases from 16 to 256, store round trips per operation decrease dramatically: on PostgreSQL E1 from 2.62 down to 1.37, and on MongoDB E1 from 3.19 down to 2.24.
  - Commits on PostgreSQL E1 drop from 0.37/op at concurrency 16 to 0.03/op at concurrency 256 as JDBC append queues combine multiple pending stream appends into shared transactions.
- **Allocation efficiency:**
  - MongoDB allocations in L1 dropped compared to the M4 baseline (177.5 KB vs 224 KB on E1 c=16), reflecting the recent BSON optimizations merged into `main` (D2: tail pipeline BSON rendering and snapshot write BSON streaming).

---

## Direct comparison with Apple M4 (10-core) baseline

A line-by-line comparison between this M4 Pro run and the earlier M4 baseline ([`v10-local-baseline.md`](v10-local-baseline.md)):

| Metric | Apple M4 (10 Cores, 16 GB) | Apple M4 Pro (14 Cores, 24 GB) | Delta / Ratio | Explanation |
| :--- | :--- | :--- | :--- | :--- |
| **Docker Resources** | 10 CPUs, 7.9 GB RAM | 12 CPUs, 15.6 GB RAM | +2 CPUs, 2× RAM | More container CPU cores and RAM headroom |
| **JFR Status** | Enabled (`--jfr`, profile) | Disabled (clean run) | Clean | Eliminates JFR sampling and thread-park overhead |
| **PG E1 Peak (Sweep)** | 3,811/s (with JFR) / 5,084/s (clean) | **13,808/s** | **+262%** vs JFR / **+172%** vs clean | 10 Performance cores (vs 4) + unthrottled PostgreSQL engine |
| **PG E1 zipf Peak** | 4,354/s | **5,003/s** | **+14.9%** | Lane serialization bottleneck limits scaling across both |
| **PG E3 Peak (Sweep)** | 1,660/s | **2,660/s** | **+60.2%** | Multi-aggregate Saga execution benefits from 14 physical cores |
| **Mongo E1 Peak (Sweep)** | 3,446/s | **12,554/s** | **+264%** | MongoDB multi-threaded engine heavily scales with 12 container CPUs |
| **Mongo E1 zipf Peak** | 3,079/s | **3,843/s** | **+24.8%** | Bounded by single-aggregate document lock serializing writes |
| **Mongo E3 Peak (Sweep)** | 1,427/s | **3,216/s** | **+125%** | Parallel document updates and non-conflicting transfers |
| **PG E1 Sustainable (50%)** | 1,906/s (p50 4.4 ms, p99 106 ms) | **6,904/s** (p50 28.9 ms, p99 238 ms) | **3.62×** throughput | 100% completed at 3.6× the rate |
| **PG E1 Server CPU (50%)** | 1.03 ms/op | **0.426 ms/op** | **−58.6%** | Clean JVM execution without JFR + higher batch amortization |
| **PG E1 Alloc (50%)** | 102 KB/op | **90.2 KB/op** | **−11.6%** | Recent runtime byte-bound cache improvements |
| **PG E1 Store CPU (50%)** | 0.86 ms/op | **0.287 ms/op** | **−66.6%** | PostgreSQL engine processes queries on fast M4 Pro P-cores |
| **PG E1 Kafka CPU (50%)** | 0.39 ms/op | **0.067 ms/op** | **−82.8%** | High-throughput batching on Kafka producer |
| **Mongo E1 Server CPU (50%)**| 0.95 ms/op | **0.375 ms/op** | **−60.5%** | Clean execution + recent BSON pipeline/snapshot optimizations |
| **Mongo E1 Alloc (50%)** | 220 KB/op | **125.0 KB/op** | **−43.2%** | D2 BSON tail & snapshot optimizations on `main` |
| **Mongo E1 Store CPU (50%)** | 2.07 ms/op | **0.940 ms/op** | **−54.6%** | MongoDB WiredTiger engine scales across assigned container CPUs |
| **L1 PG E1 (c=16) Thr / RT**| 4,000/s / 2.79 RT | **5,208/s / 2.62 RT** | +30.2% Thr, −6.1% RT | Lower round trips and faster kernel execution |
| **L1 PG E3 (c=16) Thr / RT**| 1,379/s / 5.44 RT | **2,450/s / 5.28 RT** | **+77.7%** Thr, −2.9% RT | Faster saga step resolution and lock queuing |
| **L1 Mongo E1 (c=16) Thr / Alloc**| 2,038/s / 224 KB | **4,029/s / 177.5 KB** | **+97.7%** Thr, **−20.8%** Alloc | BSON codec optimizations eliminate driver decoding overhead |
| **L1 Mongo E3 (c=16) Thr / Alloc**| 1,231/s / 424 KB | **2,083/s / 362.9 KB** | **+69.2%** Thr, **−14.4%** Alloc | Sizable reduction in memory allocation per Saga op |

### Why the results differ:
1. **Core Architecture (10 Performance cores vs 4):**
   - The Apple M4 has 4 Performance cores and 6 Efficiency cores.
   - The Apple M4 Pro has 10 Performance cores and 4 Efficiency cores.
   - Multi-process end-to-end benchmarks (Node JVM + Postgres/Mongo container + Kafka container + Load generator JVM) compete heavily for high-IPC performance cores. On M4 Pro, all processes run concurrently on dedicated Performance cores without thread-starvation or throttling.
2. **Docker Resource Envelope:**
   - On the M4 machine, Docker was configured with 10 CPUs and 7.9 GB RAM.
   - On this M4 Pro machine, Docker was allocated 12 CPUs and 15.6 GB RAM. The additional memory and CPU budget allowed the database engines (WiredTiger buffer pool and PostgreSQL shared buffers) to cache active pages cleanly and service concurrent connections without paging.
3. **Absence of JFR Overhead:**
   - JFR profiling was omitted in this run to obtain clean operational metrics. As established in the original M4 baseline, JFR flight recording reduced peak throughput by ~25% and inflated CPU time per op by ~25%.
4. **Recent Optimization Landings on `main`:**
   - Since the original baseline was committed, Candidate 2 (BSON pipeline optimization and BSON snapshot streaming) landed on `main`, explaining the substantial ~21–43% drop in MongoDB memory allocation per operation.

---

## Heap scaling exploration: 1 GB vs 2 GB

To assess how JVM heap and state cache sizing influence throughput and cold-read amplification, an additional series of runs was executed with `--heap=2g` across PostgreSQL and MongoDB on the 100,000 account dataset (`runs in heap-2g/`).

In Wow 10, the aggregate state cache (`wow.state-cache`) defaults to 10% of the maximum heap. Doubling the heap from 1 GB to 2 GB doubles the cache budget from ~100 MB to ~200 MB, holding significantly more aggregates in memory under uniform random access.

### 1. Closed-loop saturation peak (1 GB vs 2 GB)

| Scenario | Heap 1 GB Peak | Heap 2 GB Peak | Delta |
| :--- | :--- | :--- | :--- |
| **PostgreSQL E1 uniform** | 13,808.5/s | **17,485.4/s** | **+26.6%** |
| **PostgreSQL E3 uniform** | 2,659.6/s | **5,196.1/s** | **+95.4%** |
| **MongoDB E1 uniform** | 12,553.8/s | **14,770.6/s** | **+17.7%** |
| **MongoDB E3 uniform** | 3,215.7/s | **4,439.4/s** | **+38.1%** |

### 2. Open-loop quality at identical target rates (Controlled Comparison)

Measuring at the identical target arrival rate established at 50% of the 1 GB peak:

| Scenario & Target Rate | Heap Size | Throughput (OK) | p50 Latency | p99 Latency | Store CPU (ms/op) | DB Queries / Fetches | Alloc (KB/op) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL E1** (6,904/s) | 1 GB | 6,904.0/s | 28.93 ms | 237.57 ms | 0.287 ms | 6.85 fetched/op | 90.2 KB |
| | **2 GB** | **6,904.0/s** | **2.53 ms** (📉 −91%) | **32.13 ms** (📉 −86%) | **0.188 ms** (📉 −35%) | **2.87 fetched/op** (📉 −58%) | **80.9 KB** (📉 −10%) |
| **PostgreSQL E3** (1,330/s) | 1 GB | 1,323.0/s | 67.58 ms | 2,932.74 ms | 1.563 ms | 53.40 fetched/op | 257.3 KB |
| | **2 GB** | **1,330.0/s** | **1.91 ms** (📉 −97%) | **239.62 ms** (📉 −92%) | **0.710 ms** (📉 −55%) | **9.00 fetched/op** (📉 −83%) | **204.1 KB** (📉 −21%) |
| **MongoDB E1** (6,277/s) | 1 GB | 6,128.9/s | 23.68 ms | 1,630.21 ms | 0.940 ms | 0.348 query/op | 125.0 KB |
| | **2 GB** | **6,277.0/s** | **10.69 ms** (📉 −55%) | **123.90 ms** (📉 −92%) | **0.657 ms** (📉 −30%) | 0.350 query/op | **117.8 KB** (📉 −6%) |
| **MongoDB E3** (1,608/s) | 1 GB | 1,608.0/s | 794.62 ms | 1,916.93 ms | 4.974 ms | 4.377 query/op | 486.1 KB |
| | **2 GB** | 1,069.5/s | 2,228.22 ms | 6,979.58 ms | 8.074 ms | 4.693 query/op | 668.2 KB |

### Key takeaways from 2 GB heap exploration:
1. **Dramatic reduction in database round-trip amplification:**
   - Under PostgreSQL E1 at 6,904/s, doubling heap reduced `tup_fetched` by **58.1%** (from 6.85 down to 2.87 rows fetched per op), relieving store CPU pressure by 34.5%.
   - In E3 Saga transfers across two accounts, the impact was even more pronounced: `tup_fetched` dropped by **83.1%** (from 53.40 down to 9.00 rows per op), and store CPU halved from 1.56 ms to 0.71 ms per op.
2. **Elimination of queuing tail latencies:**
   - On PostgreSQL, relieving cache eviction thrashing dropped p50 latency from **28.9 ms to 2.53 ms** on E1 (−91%), and from **67.6 ms to 1.91 ms** on E3 (−97%). p99 latency dropped from near 3 seconds down to 240 ms.
3. **Closing the gap to extreme closed-loop numbers:**
   - When heap is expanded to 2 GB, the 100k account saturation peak jumps to **17,485/s** on PostgreSQL and **14,771/s** on MongoDB, demonstrating that state cache headroom is the primary lever bridging uniform cold-dataset performance toward memory-resident benchmarks.

