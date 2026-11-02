# Wow V10 Milestone Saturation Snapshot 3 (Apple M5 Max)

## Environment

| Item | Value |
| :--- | :--- |
| Commit | `fe6f38f224759890e510356ad3419a7eaf41791d` (`fe6f38f224`, `main`) |
| Baseline | `30d0c0348e11a14b0b1444d3202521199321dafa` (`30d0c0348e`, Snapshot 2) |
| Commits Since Baseline | 34 (`git rev-list --count 30d0c0348e..HEAD`) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB RAM |
| OS | macOS 27.2 (26B123) |
| JDK | Eclipse Adoptium Temurin 21.0.13+9 (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`) |
| Container Engine | Docker Engine 29.8.2 on OrbStack 2.2.5 (16 CPUs, 32 GB) |
| Storage | Disk (virtioFS, non-tmpfs) |
| Dataset | 100,000 accounts (`--accounts=100000`), primed once per session (`--prime --seed-concurrency=128`) |
| Node Heap | 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`) |

## Phase 1: Single-Node Saturation Sweeps (1-S-*)

### PostgreSQL

- **E1 uniform**: Peak ≥ 35,225.6/s at concurrency 4,096 (p50: 113.79 ms, p99: 227.84 ms, 0 errors).
- **E1 Zipf**: Peak ≥ 20,257.1/s at concurrency 4,096 (p50: 61.95 ms, p99: 1,691.65 ms, 94,412 `wow.command.busy` 429 errors).
- **E1 Hot-keys=1**: Peak ≥ 20,190.9/s at concurrency 4,096 (p50: 97.41 ms, p99: 1,417.22 ms, 543,907 `wow.command.busy` 429 errors).
- **E3 uniform**: Peak 9,441.7/s at concurrency 1,024 (p50: 104.96 ms, p99: 536.58 ms, 0 errors). At concurrency 4,096: 4,821.1/s, 388,629 `wow.command.busy` 429 errors, 1,210 `wow.wait.timeout` 504 errors. Zero 500 errors.

### MongoDB

- **E1 uniform**: Peak ≥ 21,961.3/s at concurrency 4,096 (p50: 176.64 ms, p99: 337.92 ms, 0 errors).
- **E1 Zipf**: Peak ≥ 18,194.5/s at concurrency 4,096 (p50: 68.61 ms, p99: 1,933.31 ms, 82,851 `wow.command.busy` 429 errors).
- **E1 Hot-keys=1**: Peak ≥ 19,433.3/s at concurrency 4,096 (p50: 148.48 ms, p99: 4,321.28 ms, 587,415 `wow.command.busy` 429 errors).
- **E3 uniform**: Peak 6,226.3/s at concurrency 1,024 (p50: 152.06 ms, p99: 341.50 ms, 0 errors). At concurrency 4,096: 4,149.2/s, 227,997 `wow.command.busy` 429 errors, 2,196 `wow.wait.timeout` 504 errors. Zero 500 errors.

## Phase 2: Three-Node Cluster Measurements (2-X55-*)

### PostgreSQL 3-Node

- **E1 sweep**: Peak 15,293.8/s at concurrency 256. At 1,024: 6,095.3/s, 2,151 × 503 `wow.command.unknown`, 1,136 × 504 `wow.wait.timeout`, 0 × 409.
- **E3 sweep**: Peak 5,562.8/s at concurrency 256. At 1,024: 2,195.8/s, 1,299 × 503 `wow.command.unknown`, 1,114 × 504 `wow.wait.timeout`, 11 × 409 `wow.command.contended`.
- **E1 fixed 1,024 (60s)**: 5,437.8/s, 3,710 × 503 `wow.command.unknown`, 2,727 × 504 `wow.wait.timeout`, 0 × 409.
- **E3 fixed 1,024 (60s)**: 1,999.8/s, 2,400 × 503 `wow.command.unknown`, 2,068 × 504 `wow.wait.timeout`, 26 × 409 `wow.command.contended`.

### MongoDB 3-Node

- **E1 sweep**: Peak 11,886.2/s at concurrency 256. At 1,024: 3,281.4/s, 2,318 × 503 `wow.command.unknown`, 947 × 504 `wow.wait.timeout`, 1 × 409 `wow.command.contended`.
- **E3 sweep**: Peak 506.0/s at concurrency 16. At 1,024: 289.1/s, 297 × 503 `wow.command.unknown`, 1,161 × 504 `wow.wait.timeout`, 0 × 409.
- **E1 fixed 1,024 (60s)**: 2,923.4/s, 4,187 × 503 `wow.command.unknown`, 2,863 × 504 `wow.wait.timeout`, 0 × 409.
- **E3 fixed 1,024 (60s)**: 208.7/s, 418 × 503 `wow.command.unknown`, 2,169 × 504 `wow.wait.timeout`, 0 × 409.

### Observations on X-55

- **HTTP/2 Stream Exhaustion**: Both clusters show repeated `Dropped the signal of wait ... (java.io.IOException: too many concurrent streams)` in node logs on internal ports (19090..19092).
- **First Emergence**: First recorded during step 256 on E3 (at 02:08:56 on PostgreSQL node-2, 02:22:19 on MongoDB node-2).
- **Signal Drop Magnitude**: Cumulative dropped wait signals reached over 4,400 on PostgreSQL node-2 (plus 200 on node-3) and over 1,700 on MongoDB node-3.
- **Contended Commands (409)**: Total 37 on PostgreSQL (11 sweep + 26 fixed) and 1 on MongoDB. Contention occurs concurrently with stream saturation and command forward timeouts (503).
