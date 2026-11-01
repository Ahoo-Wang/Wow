# Wow V10 Milestone Saturation Snapshot 2 (Apple M5 Max)

## Environment

| Item | Value |
| :--- | :--- |
| Commit | `30d0c0348e11a14b0b1444d3202521199321dafa` (`30d0c0348e`) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB RAM |
| OS | macOS 27.2 (26B123) |
| JDK | Eclipse Adoptium Temurin 21.0.13+9 (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) |
| Container Engine | Docker Engine 29.8.2 on OrbStack 2.2.5 (16 CPUs, 32 GB) |
| Storage | Disk (virtioFS, non-tmpfs) |
| Dataset | 100,000 accounts (`--accounts=100000`), primed once per session (`--prime --seed-concurrency=128`) |
| Node Heap | 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`, 8 GB for X-40) |

## Phase 1: Single-Node Saturation Sweeps (1-S-*)

### PostgreSQL

- **E1 uniform**: Peak ≥ 36,388.7/s at concurrency 4,096 (p50: 109.57 ms, p99: 222.21 ms, 0 errors).
- **E1 Zipf**: Peak ≥ 21,227.8/s at concurrency 4,096 (p50: 57.34 ms, p99: 1,654.78 ms, 102,964 `wow.command.busy` 429 errors).
- **E1 Hot-keys=1**: Peak ≥ 20,184.5/s at concurrency 4,096 (p50: 90.62 ms, p99: 1,327.10 ms, 548,717 `wow.command.busy` 429 errors).
- **E3 uniform**: Peak 8,674.6/s at concurrency 1,024 (p50: 104.96 ms, p99: 503.81 ms, 0 errors). At concurrency 4,096: 5,021.4/s, 415,664 `wow.command.busy` 429 errors, 469 `wow.wait.timeout` 504 errors.

### MongoDB

- **E1 uniform**: Peak ≥ 23,382.3/s at concurrency 4,096 (p50: 172.03 ms, p99: 323.58 ms, 0 errors).
- **E1 Zipf**: Peak ≥ 18,050.6/s at concurrency 4,096 (p50: 68.61 ms, p99: 1,933.31 ms, 80,682 `wow.command.busy` 429 errors).
- **E1 Hot-keys=1**: Peak ≥ 19,829.2/s at concurrency 4,096 (p50: 144.38 ms, p99: 4,227.07 ms, 583,009 `wow.command.busy` 429 errors).
- **E3 uniform**: Peak 6,531.7/s at concurrency 1,024 (p50: 151.55 ms, p99: 337.92 ms, 0 errors). At concurrency 4,096: 4,194.6/s, 292,011 `wow.command.busy` 429 errors, 2,152 `wow.wait.timeout` 504 errors.

## Phase 2: X-34 Reproduction (3-Node Cluster, 2-X34-*)

- **PostgreSQL 3-node**:
  - E1 sweep at 1,024: 7,814.6/s, 2 × 409 `wow.command.contended`, 4,536 × 503 `wow.command.unknown`, 408 × 504 `wow.wait.timeout`.
  - E3 sweep at 1,024: 3,081.0/s, 4 × 409 `wow.command.contended`, 4,764 × 503 `wow.command.unknown`, 1,835 × 504 `wow.wait.timeout`.
  - E1 fixed 1,024: 6,896.4/s, 1 × 409 `wow.command.contended`, 11,180 × 503 `wow.command.unknown`, 1,876 × 504 `wow.wait.timeout`.
  - E3 fixed 1,024: 2,836.8/s, 16 × 409 `wow.command.contended`, 6,086 × 503 `wow.command.unknown`, 2,096 × 504 `wow.wait.timeout`.
- **MongoDB 3-node**:
  - E3 sweep at 1,024: 347.6/s, 3 × 409 `wow.command.contended`, 199 × 503 `wow.command.unknown`, 1,388 × 504 `wow.wait.timeout`.
  - E1 fixed 1,024: 4,053.5/s, 1 × 409 `wow.command.contended`, 7,085 × 503 `wow.command.unknown`, 2,337 × 504 `wow.wait.timeout`.
- **Findings**:
  - Inter-node HTTP/2 stream saturation (`too many concurrent streams`) drops wait signals and times out forwards (503).
  - Forward failure degrades to unleased local execution (`ExecutionLease.NOT_HELD`), causing concurrent writes on the same aggregate and leading to store-level version conflicts (409).

## Phase 3: X-40 Investigation (8 GB Heap Transport Sweeps, 3-X40-*)

- **Arm 1 (8 GB Heap, in-memory transport via blank Kafka address)**:
  - E1 sweep: Peak ≥ 40,945.1/s at 4,096 (0 errors).
  - E3 sweep: Peak 10,386.6/s at 1,024 (0 errors). At 4,096: 8,079.8/s, 423,923 × 429, 310 × 504. Zero 500 errors.
- **Arm 2 (8 GB Heap, `wow.transport=none`)**:
  - E1 sweep: Peak ≥ 41,303.8/s at 4,096 (0 errors).
  - E3 sweep: Peak 10,546.4/s at 1,024 (0 errors). At 4,096: 7,156.8/s, 395,560 × 429, 608 × 504. Zero 500 errors.
- **Findings**:
  - X-40 (HTTP 500 under in-memory transport overload) does not reproduce under current code with X-33 bounded in-memory topics.
  - Overload produces strictly 429 and 504 responses without crashes or 500 errors.
