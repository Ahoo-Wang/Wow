# Saturation snapshot on Apple M5 Max

The latest saturation snapshot of Wow 10 on the local Apple M5 Max (decision D391 keeps one): single-node closed-loop ceilings on disk, PostgreSQL and MongoDB, with uniform, Zipf and one-hot-account keys for E1 and uniform keys for E3; then three nodes on this one host; then a controlled check of PostgreSQL's E1 on one hot account; and an addendum of 2026-11-11 that measures MongoDB's cost per commit below the knee, with and without the log's access indexes (D420). Measured on 2026-11-10 and 11, from 22:33 to 01:38 (PST), at `a7c372ad3e` (`main`), the benchmark's JVMs on JDK 25. It replaces the snapshot at [`b435768e49`](https://github.com/Ahoo-Wang/Wow/blob/a7c372ad3ef808f479776a6183247c39f46189fc/test/benchmarks/results/reports/m5max-snapshot.md), whose figures stand beside each of this one's below. The raw results and the scripts are in [`m5max-snapshot/`](m5max-snapshot/).

## Setup

| | |
| --- | --- |
| Commit measured | `a7c372ad3ef808f479776a6183247c39f46189fc` (`main`; the runs record it with no changed paths, `environment.txt`). The e1-hot arms ran from `1c090eb74d`, which is `a7c372ad3e` with the two script changes below; their server builds are named in that section |
| Since the last snapshot | 466 commits (`git rev-list --count b435768e49..a7c372ad3e`). Among them, on the write and subscription paths: the defect audit's fixes, the access attributes stamped with each commit and over an aggregate's earlier streams (`wow.access`, #5387, #5381, D390 C), the PostgreSQL log's version key leading with the tenant and its keys compared by code point (#5617), D411 (Elasticsearch mappings), D412 (ClickHouse `_state`), D413 (runs handed to a sink), and D415 and D416 (no behaviour) |
| The JDK | Temurin 25.0.5+8 for the servers and the load generator (D393), where the last snapshot ran Temurin 21.0.13+9. No difference below is laid to the code alone: the code and the JDK changed together, except in the e1-hot check, which separates them |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB, macOS 27.2 (26B123) |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`), MongoDB 7.0 (a one-member replica set, WiredTiger cache 2 GB), Kafka 7.4.0 (KRaft); on the engine's disk, not tmpfs ([`compose.yml`](../../harness/compose.yml)) |
| Nodes | One node, or three on this host; each `-Xms1g -Xmx1g -XX:+UseG1GC`; the server's defaults, its state stream published to Kafka |
| Data | 100,000 accounts, seeded and primed once a session (`--prime --seed-concurrency=128`) |
| Sweeps | Closed loop, 1, 4, 16, 64, 256, 1,024 and 4,096 clients (`--sweep --growth=4 --max-concurrency=4096`), 10 s of warm-up and 30 s measured a step; the load generator ends a sweep after a step that failed more than 1% of its commands. Three nodes also run E1 and E3 at a fixed 1,024 clients, 15 s of warm-up and 60 s measured |
| Past 4,096 clients | A single node's three E1 sweeps go on: 4,096 clients again, then 8,192, then 12,288 if 8,192 still rose past the noise band and failed 1% or less ([`summarize.py --peak`](m5max-snapshot/scripts/summarize.py), `e1-peak.md`, `e1-zipf-peak.md`, `e1-hot-peak.md`). No sweep got to 12,288 |
| Load generator | The most it used in any step was 3.28 cores, and 0.46 of a core for its busiest thread (PostgreSQL, one hot account, 4,096 clients): no step was generator-bound (80% of its processors, or 0.9 of a core for that thread) |
| Host record | As in the last snapshot: `host.txt` before and after each measurement, `host-load.tsv` every 10 s during it |

### Script changes since the last snapshot

- **`lib.sh`'s default JDK is 25**, not 21. `bench.sh` requires 25 since D393, so the old default stopped every session at its JDK check. The first start of this run measured nothing for that reason; it was started again with JDK 25.
- **`03-e1-hot-arms.sh` is new:** the e1-hot check below. It runs fresh single-node PostgreSQL sessions with only the e1-hot sweep, each arm changing only the server's build (`--install`) or its JDK (`--server-java`). Each sweep also records PostgreSQL's statements (`pg-statements.txt`, with `pg_stat_statements` loaded), the hot account's version every 5 s (`hot-version.tsv`), the tables' statistics before and after (`tables.txt`) and, after the sweep is over, the plan of the appends' version read on the hot account (`explain.txt`).
  - A server started from a build outside the measured checkout reads as another JVM (`lib.sh`'s `foreign_jvms` knows the benchmark's processes by the checkout's path), and every step of its sweep as void. The first old-commit arm was void for that reason alone. Its build is now copied under `results/e2e/` (ignored by git) and the arm was run again; `curate` also copies the three new files.

### The window

The machine is shared. The coordinating session cleared it: the other Wow sessions held their builds, and no other project's session ran. A step is **disturbed** when the other processes used 2 cores or more on average over it, or another container half a core, and **void** when another JVM ran, or an allowed JVM used 5% of a core or more in two samples in a row, or an allowed Gradle daemon's log records a build request (`summarize.py`).

- **Allowed JVMs**, idle and left running (`./gradlew --stop` would stop every session's daemons): a Kotlin build daemon (pid 38528) and Gradle daemons 77572 and 59696 (`ALLOW_JVM_PIDS`, `environment.txt`). Over the 510 samples of the 27 measurements kept they used 0.1% of a core on average, 27.4% at most in one sample. No other JVM ran in a kept measurement.
- **The first three-node MongoDB session was disturbed** by a container of another session, `chprobe` (ClickHouse 25.8), at 0.51 to 0.72 of a core, in its E1 sweep after two reruns (`02-three-nodes.log`). Once `chprobe` was removed, the session ran again from 00:33 to 00:47, with no step disturbed or void. That rerun is this report's.
- **The busiest kept step for the other processes** averaged 1.81 cores (PostgreSQL's E1 uniform at 8,192 clients). No kept step was disturbed or void (`SUMMARY.md`).

## How to read the numbers

- A **peak** is the throughput of the best step that failed 1% of its commands or less. A step that failed more is listed apart, with its answered throughput and its failures by kind, and is not a peak: 429 `wow.command.busy` is the node's admission refusing a command by its configured limits, 504 `wow.wait.timeout` a wait that ran out, 503 `wow.command.unknown` a forwarded command whose outcome did not come back, 409 `wow.command.contended` a write that lost its version slot.
- A peak is a lower bound, written `≥`, only where the measurement could not go further. No peak here is one.
- E1 on a single node is measured to its limit. Where the throughput was still rising at the last step that failed 1% or less, the text says so. The noise band is the difference between the two runs of 4,096 clients that failed 1% or less, 3% at least.
- The **sustainable** throughput is that of the sweep's highest step with a p99 of 200 ms or less and no error.
- Each number is one run, except the e1-hot check's arms. The differences from the last snapshot are given without a conclusion: there is no A/A or ABAB in a snapshot, and the JDK changed with the code.
- A cause is stated only where a controlled run showed it; elsewhere the text says "correlates with" or "inferred" and gives the evidence.

## Single node: ceilings

Each cell gives this snapshot's figure, then the last snapshot's at `b435768e49` and the difference.

| Measurement | PostgreSQL peak | PostgreSQL sustainable | MongoDB peak | MongoDB sustainable |
| --- | ---: | ---: | ---: | ---: |
| E1, uniform | 33,938.5/s at 4,096 (32,303.0/s; +5.1%) | 21,373.1/s at 1,024, p99 113.2 ms (24,541.9/s; −12.9%) | 21,458.6/s at 4,096 (22,886.7/s; −6.2%) | 17,946.6/s at 1,024, p99 135.2 ms (19,580.4/s; −8.3%) |
| E1, Zipf | 16,139.1/s at 1,024 (15,112.4/s; +6.8%) | 15,417.2/s at 256, p99 45.3 ms (15,063.1/s; +2.4%) | 14,536.9/s at 1,024 (3,898.2/s at 256; +273%) | 12,643.0/s at 256, p99 68.6 ms (2,819.9/s at 64; +348%) |
| E1, one hot account | 9,431.6/s at 256 (12,351.4/s at 1,024; −23.6%) | 9,431.6/s at 256, p99 149.5 ms (8,000.2/s; +17.9%) | 8,984.5/s at 1,024 (1,634.7/s; +450%) | 8,079.4/s at 256, p99 147.5 ms (1,456.7/s at 64; +455%) |
| E3, uniform | 8,298.6/s at 1,024 (7,176.3/s; +15.6%) | 4,333.5/s at 256, p99 93.7 ms (3,799.1/s at 64; +14.1%) | 7,446.5/s at 1,024 (7,629.5/s; −2.4%) | 4,436.5/s at 256, p99 135.2 ms (5,100.5/s; −13.0%) |

None of these peaks is a lower bound, and none was generator-bound. PostgreSQL's one-hot-account sweep is the one figure that fell beyond a few percent; the check below measures it in five fresh sessions and finds the fall within its own spread.

### E1 to its limit

| Sweep | Peak | Why it stopped | The steps past the peak |
| --- | ---: | --- | --- |
| PostgreSQL, uniform | 33,938.5/s at 4,096 | 8,192 clients answered 44.9% less, failing 0.53% | 4,096 again: 32,879.1/s; 8,192: 18,695.6/s answered, 2,993 × 429 |
| PostgreSQL, Zipf | 16,139.1/s at 1,024 | 4,096 clients failed 12.8% and 13.0% in the two runs; still rising when the failures began | 4,096: 19,902.2/s answered (87,288 × 429); again: 19,070.2/s (85,597 × 429) |
| PostgreSQL, one hot account | 9,431.6/s at 256 | 4,096 clients failed 47.7% and 48.0% | 4,096: 19,455.5/s answered (532,704 × 429); again: 19,078.7/s (527,870 × 429) |
| MongoDB, uniform | 21,458.6/s at 4,096 | 8,192 clients failed 4.3%; still rising when the failures began | 4,096 again: 20,694.2/s; 8,192: 17,632.7/s answered (23,949 × 429) |
| MongoDB, Zipf | 14,536.9/s at 1,024 | 4,096 clients failed 13.5% and 13.1%; still rising when the failures began | 4,096: 16,731.3/s answered (78,298 × 429); again: 16,332.9/s (73,997 × 429) |
| MongoDB, one hot account | 8,984.5/s at 1,024 | 4,096 clients failed 49.4% and 49.5%; still rising when the failures began | 4,096: 18,920.3/s answered (554,978 × 429); again: 18,025.7/s (530,249 × 429) |

- Every failure of a single node's E1 is the admission's 429. The node refused what its limits did not admit.
- The noise bands: 3.1% on PostgreSQL's uniform keys (33,938.5/s and 32,879.1/s at 4,096) and 3.6% on MongoDB's (21,458.6/s and 20,694.2/s); 3% on the others.
- As in the last snapshot, a step that the admission cuts back answers more than the clean steps below it with contended keys (both backends' hot account at 4,096 clients, about half refused). That throughput is not a peak.

## Single node: E3 past its knee

| Session | E3 at 1,024 | p99 at 1,024 | E3 at 4,096, answered | Failed at 4,096 | The last snapshot at 4,096 |
| --- | ---: | ---: | ---: | --- | --- |
| PostgreSQL | 8,298.6/s | 630.8 ms | 1,117.9/s | 19.4%: 8,064 × 504 `wow.wait.timeout`, 11 × 429 | 4,325.8/s answered; 75.6% failed, 399,748 × 429 and 1,719 × 504 |
| MongoDB | 7,446.5/s | 325.6 ms | 3,541.6/s | 4.5%: 5,058 × 504, 4 × 429 | 3,547.0/s answered; 74.6% failed, 311,424 × 429 and 1,493 × 504 |

- Neither session failed a command at 1,024 clients or below.
- **At 4,096 clients the failures changed kind.** In the last snapshot the admission refused three quarters of the commands at once (429); here it refused almost none, and the commands that failed are transfers whose wait ran out after 10 s (504). A closed-loop client blocked for 10 s sends nothing in that time, which correlates with PostgreSQL's answered throughput falling to 1,117.9/s (p50 22.9 ms, p99 9,502.7 ms). Single runs; no commit in the range is tied to it, and it is unexplained.
- The node logs (`log-digest.md`) count, in the 4,096-client step, 15,140 throttle warnings from the saga's and the transport sink's subscriptions (`PartitionThrottle`, the downstream pushed back) on PostgreSQL and 9,728 on MongoDB.

## Three nodes on one host

| Backend | Measurement | Peak or result, p99 | The last snapshot | At 4,096 clients, answered | Failed at 4,096 |
| --- | --- | ---: | ---: | ---: | --- |
| PostgreSQL | E1 sweep | 21,166.6/s at 1,024, 124.9 ms | 19,274.8/s, 143.4 ms (+9.8%) | 10,759.0/s (p99 2,261.0 ms) | 0 (last: 10.2%) |
| PostgreSQL | E3 sweep | 6,863.2/s at 1,024, 446.5 ms | 6,470.6/s, 440.3 ms (+6.1%) | 916.4/s | 28.6%: 11,005 × 504 (last: 1.4%, 2,665.9/s answered) |
| PostgreSQL | E1, 1,024 clients, 60 s | 20,698.7/s, 127.0 ms | 18,319.9/s, 151.6 ms (+13.0%) | | |
| PostgreSQL | E3, 1,024 clients, 60 s | 6,122.7/s, 712.7 ms | 5,893.5/s, 663.6 ms (+3.9%) | | |
| MongoDB | E1 sweep | 14,778.8/s at 1,024, 204.8 ms | 15,341.3/s, 194.6 ms (−3.7%) | 6,998.7/s (p99 4,489.2 ms) | 0 (last: 0) |
| MongoDB | E3 sweep | 5,332.2/s at 1,024, 536.6 ms | 5,154.1/s, 528.4 ms (+3.5%) | 2,358.7/s | 4.6%: 3,447 × 504 (last: 0.26%) |
| MongoDB | E1, 1,024 clients, 60 s | 15,269.0/s, 203.8 ms | 14,395.6/s, 216.1 ms (+6.1%) | | |
| MongoDB | E3, 1,024 clients, 60 s | 4,769.1/s, 626.7 ms | 4,892.3/s, 581.6 ms (−2.5%) | | |

- No command failed at 1,024 clients or below in either cluster. Each sweep's peak is at 1,024 clients, and at 4,096 its throughput fell.
- E3 at 4,096 failed more than in the last snapshot on both backends, all by waits that ran out (504), as on a single node.
- The three nodes share the host with the stores and the load generator; the cluster's numbers are this host's, not a deployment's.

### The nodes' warnings

From each session's `log-digest.md` (the node logs are not committed):

- **The internal channel (X-55).** No node logged "too many concurrent streams". The internal signal sender dropped a wait's signal only when the wait was over before it was sent: once on PostgreSQL's cluster and twice on MongoDB's, all in E3's 4,096-client step.
- **Throttle warnings.** The saga's, the snapshot's and the transport sink's subscriptions throttled partitions whose downstream pushed back, mostly in the 4,096-client steps. A step's window runs from the end of the step before it, so the 142,350 warnings in the 1-client step of PostgreSQL's three-node E3 sweep fall in the time after the E1 sweep's 4,096-client step (inferred: its backlog draining).
- **Single node, both backends.** Connection resets (`FluxReceive`, "observed post termination") as the load generator closes its connections at a step's end. MongoDB's node logged one request that failed with a connection reset (`WowRoutes`, 23:05:47, in E1's 1,024-client step).
- **Startup.** Each node logs the anonymous caller warning once. The JDK 21 virtual-thread pinning warning of the last snapshot is gone on JDK 25.

## Anomalies

### PostgreSQL's E1 uniform falls past its peak: there again

| | 4,096 clients | 8,192 clients |
| --- | ---: | ---: |
| Answered | 32,879.1/s (the second run) | 18,695.6/s |
| Failed | 0 | 0.53%: 2,993 × 429 |
| p50, p99 | 121.9 ms, 236.5 ms | 436.2 ms, 569.3 ms |
| Host CPU, every process | 14.4 cores | 10.0 cores |
| Load generator; its busiest thread | 2.62 cores; 0.37 | 2.06 cores; 0.16 |

- The fall is 44.9%, against 35.5% at `b435768e49`. This time the 8,192-client step failed less than 1%, so it is a clean step that answered less, not a step past the failures.
- As before, the host's CPU fell with the throughput (about 4 cores here) and the p50 matches the clients over the throughput (8,192 / 18,695.6/s = 438 ms). Inferred: something waited rather than computed. The node logged nothing in that step but connection resets.
- MongoDB fell less: from 20,694.2/s to 17,632.7/s, with 4.3% refused.
- Not measured, as before: the node's CPU and GC alone, and PostgreSQL's transactions. To find out: a `bench.sh run` of E1 uniform at 4,096 and 8,192 clients with the harness's resources, and a profile of the node at 8,192.

### MongoDB with contended keys: not there again

The last snapshot's single MongoDB node answered a fraction of the earlier rounds' throughput with Zipf keys and one hot account. This one is back at the earlier rounds' level:

| Backend, keys | Clients | Snapshot 2 (`30d0c0348e`) | `b435768e49` | This snapshot |
| --- | ---: | ---: | ---: | ---: |
| MongoDB, Zipf | 256 | 14,350.9/s | 3,898.2/s | 12,643.0/s |
| MongoDB, Zipf | 1,024 | 15,171.4/s | 2,672.6/s | 14,536.9/s |
| MongoDB, one hot account | 256 | 9,642.0/s | 1,558.3/s | 8,079.4/s |
| MongoDB, one hot account | 1,024 | 9,682.2/s | 1,634.7/s | 8,984.5/s |

- Single runs. The code and the JDK changed together since `b435768e49`, and no run here tells which one this follows; the anomaly is not explained, only not reproduced.

### PostgreSQL with one hot account: the check

In this snapshot PostgreSQL's one-hot-account sweep answered less than at `b435768e49` at most clean steps:

| Clients | `b435768e49`, JDK 21: throughput, p50, p99 | This snapshot, JDK 25 |
| ---: | ---: | ---: |
| 1 | 947.5/s, 0.97 ms, 2.72 ms | 728.1/s, 1.00 ms, 4.01 ms |
| 16 | 4,614.9/s, 3.17 ms, 7.68 ms | 2,197.3/s, 5.63 ms, 23.55 ms |
| 64 | 8,851.6/s, 6.43 ms, 18.82 ms | 6,514.0/s, 8.03 ms, 49.41 ms |
| 256 | 8,000.2/s, 30.46 ms, 155.65 ms | 9,431.6/s, 30.72 ms, 149.50 ms |
| 1,024 | 12,351.4/s, 133.12 ms, 331.77 ms | 8,446.8/s, 146.43 ms, 405.50 ms |

Both the code and the JDK changed, so five fresh single-node PostgreSQL sessions measured the same e1-hot sweep, alternating, from 00:52 to 01:38 (`03-e1-hot-arms.sh`, `e1-hot-arms/`). Each arm ran this checkout's harness and load generator, with the same seed and steps:

- **head, twice:** the server of `a7c372ad3e` on JDK 25 (`1-head-25`, `4-head-25b`);
- **the JDK:** the same server on JDK 21 (`--server-java`, `3-head-21`);
- **the code:** the server of `b435768e49`, built there (`./gradlew :benchmark-server:installDist`), on JDK 25, twice (`2-old-25`, `5-old-25b`). That commit's server ran on 25 without a change.

| Arm | 1 client | 16 clients: throughput, p50, p99 | 64 clients | 1,024 clients |
| --- | ---: | ---: | ---: | ---: |
| head, JDK 25 (1) | 955.7/s | 4,270.9/s, 3.23 ms, 10.43 ms | 10,643.1/s, 5.76 ms, 13.76 ms | 9,654.8/s |
| `b435768e49`, JDK 25 (1) | 569.4/s | 2,332.3/s, 5.66 ms, 19.07 ms | 6,460.9/s, 8.16 ms, 30.34 ms | 9,179.5/s |
| head, server on JDK 21 | 583.6/s | 2,297.3/s, 5.15 ms, 22.14 ms | 7,781.9/s, 6.72 ms, 21.25 ms | 8,290.9/s |
| head, JDK 25 (2) | 545.9/s | 2,670.8/s, 4.54 ms, 16.51 ms | 8,219.0/s, 6.43 ms, 20.48 ms | 10,353.7/s |
| `b435768e49`, JDK 25 (2) | 334.4/s | 2,579.3/s, 4.77 ms, 17.28 ms | 5,906.4/s, 8.26 ms, 166.91 ms | 9,907.9/s |

- **The two head runs differ from each other by as much as the snapshots do:** 4,270.9/s and 2,670.8/s at 16 clients (−37%), against the snapshots' −52%; 955.7/s and 545.9/s at one client. The old commit's two runs fall within the same spread, and at 16 and 64 clients neither is above either head run. The JDK 21 arm is within it too. At these steps a difference between the code or the JDKs does not show beyond the sweep's own spread; the snapshot's fall is read as that spread.
- **The hot account's version** ran alike in every arm: at 16 clients from about 40,000–60,000 to 80,000–125,000, at 1,024 from about 470,000 to 720,000 (`hot-version.tsv`). No arm's cost per step grows with it. The snapshot's sweep ran on a longer history (after the uniform and Zipf sweeps in its session, whose most frequent Zipf key is the same account), so its absolute figures are not those of a fresh session.
- **What the store did per command** (`pg-statements.txt`, `tables.txt`, `explain.txt`):
  - The appends' version read (`SELECT max(version) … WHERE aggregate_id = ? AND tenant_id = ?`) is a `Limit` over an `Index Only Scan Backward` of the version key on both commits, 5 shared buffers and under 0.1 ms at the hot account's last version (648,407 to 693,045 in the sessions explained: every arm after the first, and the void old-commit attempt), with the head's key leading with the tenant and the old one's with the aggregate id.
  - The single append's mean was 0.057–0.071 ms in every arm, the snapshot upsert's about 0.045 ms.
  - No arm updated the log table (no stamp of earlier streams), and no request-id lookup is among any arm's 25 most frequent statements.
  - The snapshot table had taken about 1.5 million updates by the sweep's end (the seed's and the prime's among them), 97% of them HOT, with 18,000–25,000 dead tuples, alike on both commits.

## Addendum: MongoDB's cost per commit below the knee (D420)

On GitHub Actions, E1 on MongoDB at 100k accounts has a higher p99 at an equal rate than `v10.0.0-beta.1` in every
run on an EPYC 7763 ([actions-snapshot](actions-snapshot.md), "E1 on MongoDB at 100k accounts"). Those comparisons
could not resolve a cost of about a tenth:

- They compare latency at the lower arm's calibrated rate, where the store sits at 92–100% of its one core.
- There a few percent more service time per commit becomes tens of percent of latency, and pairs scatter from 0.92 to
  2.16.

This check measures the cost on the store's side itself, at a fixed rate well under the knee, on 2026-11-11 from
15:31 to 16:18 (PST), with the other sessions holding their builds.

### Method

| | |
| --- | --- |
| Arms | **tag:** the server of `v10.0.0-beta.1` (`22f8ac19cb`), whose log documents carry no access attributes, with 3 log indexes. **tag-idx:** the same, with `access_owner` and `access_space` created by hand after `up` (main's keys and partial filter). **main:** the server of `4665c3f0f6`, 5 log indexes. **main-noidx:** the same, with the two dropped by hand after `up`. The host creates them only at its start, so the change lasts the session (`indexes.log`, and the indexes listed in `store-after.json`). Every arm ran `4665c3f0f6`'s harness and load generator |
| Sessions | One fresh single-node MongoDB session each, Kafka on, 1 GB heap, 100,000 accounts seeded and primed. Then one E1 run with uniform keys at a fixed 8,000/s open loop: 30 s of warm-up, 120 s measured. That is 37% of this snapshot's single-node MongoDB E1 ceiling (21,458.6/s) |
| Order | First pass ABCD DCBA (r1 tag, main, main-noidx, tag-idx; r2 the reverse). Main's two rounds of CPU were 11.5% apart, more than main and main-noidx differed, so four more sessions ran: r3 main, r3 main-noidx, r4 main-noidx, r4 main. 12 sessions |
| What is read | Around the run, from the store: mongod's CPU from its container's cgroup (`cpu.stat` `usage_usec`; no CPU limit), `serverStatus()` `opcounters`, and the log's and the snapshot's `$collStats` `latencyStats` and `storageStats`. Before them, 20 s with no load give mongod's floor, 0.12–0.15 cores. Each delta is over the run's whole window, about 150 s at the one rate, and is divided by the log documents the window added, 1,198,869–1,200,000 commits. The log is written in batched inserts (0.064–0.082 insert operations a commit), so its insert time is given per commit. p50 and p99 are the measured phase's, a by-line |
| The window | As in this snapshot (`environment.txt`). Five idle Gradle and Kotlin daemons were allowed; over the 156 samples they used 0.4% of a core on average, 37.2% at most in one sample. No other JVM ran, and no measurement was disturbed or void, so none was rerun (`SUMMARY.md`) |

### Results

| Session | mongod CPU µs/commit, net | Log insert µs/commit | Snapshot update µs/op | Log avgObjSize | Log indexes, MB | p50 / p99 ms |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| r1 tag | 193.3 | 26.35 | 79.8 | 523 | 3, 294.3 | 8.77 / 190.5 |
| r2 tag | 193.6 | 25.35 | 78.8 | 523 | 3, 295.4 | 8.45 / 193.5 |
| r1 tag-idx | 212.4 | 30.21 | 83.9 | 523 | 5, 348.8 | 7.65 / 184.3 |
| r2 tag-idx | 207.4 | 29.12 | 80.1 | 523 | 5, 350.0 | 7.74 / 120.8 |
| r1 main | 199.4 | 29.43 | 75.1 | 559 | 5, 356.9 | 7.74 / 206.8 |
| r2 main | 222.4 | 31.81 | 81.5 | 559 | 5, 357.9 | 6.82 / 90.6 |
| r3 main | 202.1 | 29.04 | 73.5 | 559 | 5, 357.8 | 6.59 / 108.0 |
| r4 main | 191.5 | 27.35 | 72.2 | 559 | 5, 357.0 | 8.45 / 124.4 |
| r1 main-noidx | 200.2 | 27.35 | 76.6 | 559 | 3, 294.2 | 7.36 / 113.7 |
| r2 main-noidx | 208.3 | 28.75 | 81.6 | 559 | 3, 291.8 | 7.78 / 178.2 |
| r3 main-noidx | 193.4 | 26.38 | 74.3 | 559 | 3, 293.2 | 8.00 / 123.9 |
| r4 main-noidx | 193.3 | 26.64 | 73.7 | 559 | 3, 295.0 | 6.94 / 113.2 |

- **Index sizes at about 1.3M log documents:**
  - The two access indexes are 27.0–27.7 MB each on the tag's documents and 30.0–31.7 MB each on main's.
  - `_id_` (40–42), `version_key` (145–146) and `request_key` (106–110 MB) are alike on every arm (`STORE-COST.md`).
- **Store operations per commit:** inserts 1.000 on every arm. Updates are 1.063–1.065 on the tag and 1.072–1.075 on
  main.
- **Snapshot documents:** 419 B on the tag and 421 B on main.
- **The load generator dropped commands in four runs:** r1 main 857, r2 tag 666, r3 main 22 and r4 main 5. Each was
  "generator-overload": its in-flight cap of 2,000 reached in one stalled second (worst-second p99 260–365 ms).
  That is under 0.1% of each run, and there was no server error in any session. They are kept.

| Comparison | What differs | mongod CPU per commit, by round | Log insert time per commit, by round |
| --- | --- | --- | --- |
| tag-idx / tag | the two index writes, on the tag's documents | 1.099, 1.071 | 1.146, 1.149 |
| main / main-noidx | the two index writes, on main's documents | 0.996, 1.068, 1.045, 0.991 (median 1.021) | 1.076, 1.106, 1.101, 1.026 (median 1.088) |
| main-noidx / tag | the documents (559 against 523 B) and the code, 3 indexes each | 1.036, 1.076 | 1.038, 1.134 |

### Reading

- **The two indexes cost the log's insert time in every comparison:** about 15% per commit on the tag's documents,
  and 3–11% (median 9%) on main's. Each adds about 30 MB per 1.3M commit documents.
- **mongod's CPU per commit:** the indexes cost 7–10% (14–19 µs) on the tag's documents, steady in both rounds. On
  main's documents the median is +2% over four rounds, inside main's own spread of 191.5–222.4 µs.
- **The documents and the code without the indexes** cost 4–8% more CPU and 4–13% more log insert time than the tag.
- **The snapshot's updates** don't move with the indexes.
- **What it says about Actions:** these are costs of the size that, on a store at one core, would show as about a
  tenth of throughput at saturation (L3's calibrated 0.90 on Actions). That is consistent with the Actions difference,
  not a measurement of it.
- **The decision (D420):** MongoDB's access indexes leave out the nulls, as PostgreSQL's do. A null access attribute
  is not written to the log document, and the two indexes are partial on the attribute's existence, so a commit of an
  aggregate with no owner or space adds no entry to either. The re-measurement of the change follows, with the same
  instrument: the tag, `main` and the change.

## Limits

- One machine, one run each, apart from the e1-hot check's five sessions; no A/A pair in the snapshot itself. The noise band rests on one repeat of 4,096 clients a sweep, and the e1-hot check shows a spread of up to 40% at the steps below 64 clients.
- The code and the JDK changed together since the last snapshot. Only the e1-hot check separates them.
- Three nodes share the host with the stores and the load generator, so the cluster's numbers are this host's, not a deployment's.
- Nothing checks the accounts' balances or the logs' continuity after a run.
- A sweep records no resources but the host's: the node's CPU, its GC and the stores' CPU are known only as the host's total (`host-load.tsv`).
- Only a single node's E1 goes past 4,096 clients; E3 and the clusters stop there.

## Reproduce

From a clean checkout at `a7c372ad3e` on this machine, with this report's `scripts/` (the commit that adds this report; at `a7c372ad3e` itself `lib.sh` defaults to JDK 21 and `03-e1-hot-arms.sh` is missing), the builds of `bench.sh build` (`./gradlew :benchmark-server:installDist :benchmark-load:installDist`), JDK 25 (`JAVA_HOME=$(/usr/libexec/java_home -v 25)`) and no other JVM running:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/run-all.sh
```

An idle JVM that cannot be stopped is allowed by its pid, `ALLOW_JVM_PIDS="<pid> …"`. `01-single-node.sh <backend>` or `02-three-nodes.sh <backend>` reruns one session. A measurement with a void or disturbed step runs again in its session once the host is quiet, at most twice, and the attempt stays as `<measurement>.void-<n>/`.

The e1-hot check, with another commit's server built in its own worktree and copied under this checkout's `results/e2e/installs/<commit>/benchmark-server`:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/03-e1-hot-arms.sh head-25=<this build> old-25=<the other build> head-21=<this build>,<JDK 21 home> head-25b=<this build> old-25b=<the other build>
```

The store-cost addendum, with the tag's server built in its own worktree and copied likewise. The arms are given in
the order of round 1, and round 2 runs them reversed. `--sequence` gives another order, as the second pass did with
`--sequence="3:main 3:main-noidx 4:main-noidx 4:main"`. `store-cost.py` writes the tables:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/04-mongo-store-cost.sh tag=<the tag's build> main=<this build> main-noidx=<this build>,drop tag-idx=<the tag's build>,create
python3 -I test/benchmarks/results/reports/m5max-snapshot/scripts/store-cost.py test/benchmarks/results/e2e/mongo-store-cost main/main-noidx tag-idx/tag main-noidx/tag
```

The addendum's sessions ran from an earlier form of `04-mongo-store-cost.sh` that fixed its arms in the script, with
the same commands. Its first start inherited JDK 21 from the shell and stopped at `bench.sh`'s check before any
session began (`04-mongo-store-cost.log` begins after it). `JAVA_HOME` must be a JDK 25.

The scripts write the raw output under `test/benchmarks/results/e2e/m5max-snapshot/` (ignored by git); `curate` in [`lib.sh`](m5max-snapshot/scripts/lib.sh) copies into [`m5max-snapshot/`](m5max-snapshot/) what this report rests on. The node logs are not committed; each session's `log-digest.md` counts their warnings and errors by message and by step.

| Path | What |
| --- | --- |
| [`SUMMARY.md`](m5max-snapshot/SUMMARY.md) | Every measurement's peak, sustainable throughput, the steps that failed more than 1%, the steps marked disturbed or void, the load before and after; the allowed JVMs' CPU over the window |
| `1-S-<backend>/` | The single-node session: `up.log`, `host.txt`, `log-digest.md`, the three `*-peak.md`, and a directory per sweep (`saturation.json`, `summary.md`, `saturate.log`, `settle.*`, `host.txt`, `host-load.tsv`, `allowed-jvms.txt`), the steps past 4,096 clients in `<sweep>-top/` |
| `3-N-<backend>/` | The three-node session, likewise, with the fixed 1,024-client runs `e1-c1024/` and `e3-c1024/` |
| `e1-hot-arms/<n>-<arm>/` | The e1-hot check's sessions, each with `hot-version.tsv`, `tables.txt`, `explain.txt` and its sweep's `pg-statements.txt` |
| `0*-*.log` | Each script's output, the disturbed first three-node MongoDB session and the void first old-commit arm included |
| `mongo-store-cost/` | The addendum: [`STORE-COST.md`](m5max-snapshot/mongo-store-cost/STORE-COST.md) (every session's figures and index sizes, and the ratios), [`SUMMARY.md`](m5max-snapshot/mongo-store-cost/SUMMARY.md) (each run's verdict and load), `environment.txt`, `verdicts.txt`, the two passes' logs, and per session `r<round>-<arm>/` with `up.log`, `indexes.log`, `idle.json`, `store-before.json`, `store-after.json`, `host.txt` and the run's `result.json`, `host-load.tsv` and `allowed-jvms.txt` |
| [`scripts/`](m5max-snapshot/scripts/) | `run-all.sh`, `01-single-node.sh`, `02-three-nodes.sh`, `03-e1-hot-arms.sh`, `04-mongo-store-cost.sh`, `lib.sh`, `summarize.py`, `store-cost.py`, `digest-logs.py` |
