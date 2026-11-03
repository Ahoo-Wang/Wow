# Saturation snapshot on Apple M5 Max

The latest saturation snapshot of Wow 10 on the local Apple M5 Max (decision D391 keeps one): single-node closed-loop ceilings on disk, PostgreSQL and MongoDB, with uniform, Zipf and one-hot-account keys for E1 and uniform keys for E3; then three nodes on this one host. Measured on 2026-11-02 and 03, from 23:27 to 00:52 (PST), at `b435768e49` (`main`). It replaces [snapshot 2](https://github.com/Ahoo-Wang/Wow/blob/1357642891a5a458bdb8fc7adc364af7bcb8ee21/test/benchmarks/results/reports/v10-m5max-snapshot-2.md) (`30d0c0348e`); the round of [#5334](https://github.com/Ahoo-Wang/Wow/pull/5334) (`fe6f38f224`) was closed unmerged, and its numbers appear below for reference only. The raw results and the scripts are in [`m5max-snapshot/`](m5max-snapshot/).

## Setup

| | |
| --- | --- |
| Commit measured | `b435768e4979f146e5c52142410f87a06b941861` (`main`; the runs record it with no changed paths, `environment.txt`) |
| Since the earlier rounds | 32 commits since #5334's `fe6f38f224` (`git rev-list --count fe6f38f224..b435768e49`), 66 since snapshot 2's `30d0c0348e` (`git rev-list --count 30d0c0348e..b435768e49`). Since `fe6f38f224` the runtime, the HTTP edge and the backends changed by: the internal HTTP/2 channel waiting at the peer's stream limit (X-55, #5340); a forward not sent by its deadline withdrawn or answered busy (X-61, #5355); the owner dropping a state another node made stale (X-60, #5337); a conflict in a MongoDB group append costing only its own stream (X-60, #5357); a state event carrying the aggregate's tags (PA-33, #5342); `@InternalCommand` (D388, #5353); the typed clients' system commands (D389, #5365) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB, macOS 27.2 (26B123) |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`), MongoDB 7.0 (a one-member replica set, WiredTiger cache 2 GB), Kafka 7.4.0 (KRaft); on the engine's disk, not tmpfs ([`compose.yml`](../../harness/compose.yml)) |
| Nodes | One node, or three on this host; each `-Xms1g -Xmx1g -XX:+UseG1GC` on Temurin 21.0.13+9; the server's defaults, its state stream published to Kafka |
| Data | 100,000 accounts, seeded and primed once a session (`--prime --seed-concurrency=128`) |
| Sweeps | Closed loop, 1, 4, 16, 64, 256, 1,024 and 4,096 clients (`--sweep --growth=4 --max-concurrency=4096`), 10 s of warm-up and 30 s measured a step; the load generator ends a sweep after a step that failed more than 1% of its commands. Three nodes also run E1 and E3 at a fixed 1,024 clients, 15 s of warm-up and 60 s measured |
| Past 4,096 clients | A single node's three E1 sweeps (uniform, Zipf, one hot account) go on: 4,096 clients again, then 8,192, then 12,288 if 8,192 still rose past the noise band and failed 1% or less ([`summarize.py --peak`](m5max-snapshot/scripts/summarize.py), `e1-peak.md`, `e1-zipf-peak.md`, `e1-hot-peak.md`). 12,288 is the last step that fits the client's 16,384 ephemeral ports (`net.inet.ip.portrange`) to the node's one public port, with 4,096 kept for the connections still closing. No sweep got that far |
| Load generator | Its cores (`generatorCores`, of the 18 it had) and its busiest thread's CPU, every 10 s (`host-load.tsv`): its HTTP client has one selector thread. A step at 80% of its processors, or 0.9 of a core for that thread, is generator-bound, and a peak on it a lower bound. The most it used in any step was 4.03 cores, and 0.52 of a core for its busiest thread (PostgreSQL, one hot account, 4,096 clients): no step was generator-bound |
| Host record | `uptime`, swap, the containers, the busiest other processes and the JVMs before and after each measurement (`host.txt`), and every 10 s during it (`host-load.tsv`): the CPU of the processes that are not the benchmark's, of the containers that are not the benchmark's, of every process on the host, of the load generator's busiest thread, of the JVMs allowed to run, and the pids of any other JVM |

The machine is shared with other projects' sessions and builds. The snapshot ran in a window the coordinating session cleared: CoSec and CoCache paused, `cosec-redis` idle. A step is marked **disturbed** when the other processes used 2 cores or more on average over it, or another container half a core (`summarize.py`). No step was disturbed: the busiest step for the other processes averaged 0.77 of a core (PostgreSQL's E1 uniform sweep at 16 clients), and `cosec-redis` used at most 0.8% of a core.

No JVM but the benchmark's ran, except one idle Gradle 9.8.2 daemon (pid 67826, started 18:38, about 2 GB resident) that the coordinating session could not stop. It was allowed by its pid (`ALLOW_JVM_PIDS=67826`, `environment.txt`); any other JVM in a step's window would have made the step **void**, and none appeared. Over the 211 samples taken while it ran, the daemon used 0.1% of a core on average. It stopped itself at 00:13:01, idle for 180 minutes, so the MongoDB session and the three-node sessions ran without it. Its log records no command in the window; its last build ended at 21:13.

The daemon used 5% of a core or more in one sample: 24.9% at 23:30:34, in the 16-client step of PostgreSQL's first E1 uniform sweep. Its log has eight lines in the 12 s before that sample, all routine (its health checks and the registry's locks), and no build request ([`allowed-jvms.txt`](m5max-snapshot/1-S-postgres/e1-sweep.void-1/allowed-jvms.txt)). The rule in force then made a step void at one sample of 5%, so that sweep was set aside as `e1-sweep.void-1/` and run again after 60 s of quiet; the rerun is its result. From 23:36:39 the rule is the one in `summarize.py`, agreed with the coordinating session: a step is void with two samples in a row at 5% or more, or a build request in the daemon's log in its window. A single sample over 5% is a spike, recorded and not void. Under that rule the attempt would have stood. No other measurement had a spike.

## How to read the numbers

- A **peak** is the throughput of the best step that failed 1% of its commands or less. A step that failed more is listed apart, with its answered throughput and its failures by kind, and is not a peak: 429 `wow.command.busy` is the node's admission refusing a command by its configured limits, 504 `wow.wait.timeout` a wait that ran out, 503 `wow.command.unknown` a forwarded command whose outcome did not come back, 409 `wow.command.contended` a write that lost its version slot.
- A peak is a lower bound, written `≥`, only where the measurement could not go further: the peak on a sweep's last step, on a generator-bound step, or with no ports left for one more step. A failure never makes one, and two lower bounds are not compared as a change.
- E1 on a single node is measured to its limit, with each of its three key distributions. Where the throughput was still rising at the last step that failed 1% or less, the text says so ("still rising when the failures began"), without `≥`. The noise band is the difference between the two runs of 4,096 clients that failed 1% or less, 3% at least: no A/A here measures it better.
- The **sustainable** throughput is that of the sweep's highest step with a p99 of 200 ms or less and no error. It is another number than the peak, and the tables give both.
- Each number is one run. The differences from the earlier rounds below are given without a conclusion: there is no A/A or ABAB here to say how much a single run varies.
- A cause is stated only where a controlled run showed it; elsewhere the text says "correlates with" or "inferred" and gives the evidence.

## Single node: ceilings

| Measurement | PostgreSQL peak | PostgreSQL sustainable | MongoDB peak | MongoDB sustainable |
| --- | ---: | ---: | ---: | ---: |
| E1, uniform | 32,303.0/s at 4,096 | 24,541.9/s at 1,024 (p99 95.2 ms) | 22,886.7/s at 4,096 | 19,580.4/s at 1,024 (p99 116.7 ms) |
| E1, Zipf | 15,112.4/s at 1,024 | 15,063.1/s at 256 (p99 45.1 ms) | 3,898.2/s at 256 | 2,819.9/s at 64 (p99 123.4 ms) |
| E1, one hot account | 12,351.4/s at 1,024 | 8,000.2/s at 256 (p99 155.6 ms) | 1,634.7/s at 1,024 | 1,456.7/s at 64 (p99 165.9 ms) |
| E3, uniform | 7,176.3/s at 1,024 | 3,799.1/s at 64 (p99 31.0 ms) | 7,629.5/s at 1,024 | 5,100.5/s at 256 (p99 113.2 ms) |

None of these peaks is a lower bound: each sweep went on until a step failed more than 1% of its commands, and none was generator-bound.

### E1 to its limit

| Sweep | Peak | Why it stopped | The steps that failed more than 1% (not peaks) |
| --- | ---: | --- | --- |
| PostgreSQL, uniform | 32,303.0/s at 4,096 | 8,192 clients failed 2.1%; still rising when the failures began | 8,192: 20,823.6/s answered, failed 2.1% (13,373 × 429 `wow.command.busy`) |
| PostgreSQL, Zipf | 15,112.4/s at 1,024 | 4,096 clients failed 13.8% and 13.5% in the two runs | 4,096 (sweep): 19,754.8/s answered, failed 13.8% (95,019 × 429); 4,096 (again): 17,714.5/s, failed 13.5% (82,969 × 429) |
| PostgreSQL, one hot account | 12,351.4/s at 1,024 | 4,096 clients failed 48.9% and 49.0%; still rising when the failures began | 4,096 (sweep): 23,768.6/s answered, failed 48.9% (681,803 × 429); 4,096 (again): 24,142.2/s, failed 49.0% (696,339 × 429) |
| MongoDB, uniform | 22,886.7/s at 4,096 | 8,192 clients failed 8.3%; still rising when the failures began | 8,192: 20,601.2/s answered, failed 8.3% (56,003 × 429) |
| MongoDB, Zipf | 3,898.2/s at 256 | 4,096 clients failed 13.3% in both runs | 4,096 (sweep): 6,667.6/s answered, failed 13.3% (30,749 × 429); 4,096 (again): 6,982.9/s, failed 13.3% (32,028 × 429) |
| MongoDB, one hot account | 1,634.7/s at 1,024 | 4,096 clients failed 49.7% and 57.7%; still rising when the failures began | 4,096 (sweep): 18,301.3/s answered, failed 49.7% (543,008 × 429); 4,096 (again): 16,087.4/s, failed 57.7% (656,829 × 429, 1,890 × 504 `wow.wait.timeout`) |

- Every failure of a single node's E1 is the admission's 429, apart from 1,890 waits that ran out (504) in the second run of MongoDB's hot account at 4,096 clients. The node refused what its limits did not admit, so more clients would mostly be refused too; only that run timed out.
- The two runs of 4,096 clients differ by 2.5% on PostgreSQL's uniform keys (31,484.9/s and 32,303.0/s) and 4.1% on MongoDB's (22,886.7/s and 21,949.7/s), the noise bands of those two sweeps (3% and 4.1%).
- With contended keys a step that the admission cuts back answers more than the clean steps below it: PostgreSQL's hot account 24,142.2/s at 4,096 clients against 12,351.4/s at 1,024, with half the commands refused. Inferred: the refusals come back fast, and a closed-loop client then sends its next command, mostly to another account, instead of waiting behind the hot one. That throughput is not a peak.
- No step past 4,096 clients was clean, so 12,288 clients were not measured.

## Single node: E3 past its knee

| Session | E3 at 1,024 | p99 at 1,024 | E3 at 4,096, answered | Failed at 4,096 |
| --- | ---: | ---: | ---: | --- |
| PostgreSQL | 7,176.3/s | 811.0 ms | 4,325.8/s | 75.6%: 399,748 × 429 `wow.command.busy`, 1,719 × 504 `wow.wait.timeout` |
| MongoDB | 7,629.5/s | 303.1 ms | 3,547.0/s | 74.6%: 311,424 × 429 `wow.command.busy`, 1,493 × 504 `wow.wait.timeout` |

Neither session failed a command at 1,024 clients or below. At 4,096 most of the failures are the admission's; the waits that ran out (504) are transfers whose second hop, `Entry` on the target account, was not committed within the 10 s deadline. The node logs (`log-digest.md`) count, in the 4,096-client step, 12,990 throttle warnings from the saga's subscriptions (`PartitionThrottle`, the downstream pushed back) on PostgreSQL and 12,210 on MongoDB.

## Three nodes on one host

| Backend | Measurement | Peak or result | p99 | At 4,096 clients, answered | Failed at 4,096 |
| --- | --- | ---: | ---: | ---: | --- |
| PostgreSQL | E1 sweep | 19,274.8/s at 1,024 | 143.4 ms | 9,837.1/s | 10.2%: 24,075 × 429, 9,366 × 504, 135 × 503 `wow.command.unknown` |
| PostgreSQL | E3 sweep | 6,470.6/s at 1,024 | 440.3 ms | 2,665.9/s | 1.4%: 1,123 × 504, 2 × 409 `wow.command.contended` |
| PostgreSQL | E1, 1,024 clients, 60 s | 18,319.9/s | 151.6 ms | | |
| PostgreSQL | E3, 1,024 clients, 60 s | 5,893.5/s | 663.6 ms | | |
| MongoDB | E1 sweep | 15,341.3/s at 1,024 | 194.6 ms | 6,580.8/s | 0 (p99 5,242.9 ms) |
| MongoDB | E3 sweep | 5,154.1/s at 1,024 | 528.4 ms | 2,087.7/s | 0.26%: 163 × 504 |
| MongoDB | E1, 1,024 clients, 60 s | 14,395.6/s | 216.1 ms | | |
| MongoDB | E3, 1,024 clients, 60 s | 4,892.3/s | 581.6 ms | | |

- No command failed at 1,024 clients or below in either cluster, in the sweeps or in the fixed runs of 60 s. Each sweep's peak is at 1,024 clients, and at 4,096 its throughput fell.
- The three nodes share the host with the stores and the load generator; the cluster's numbers are this host's, not a deployment's.

### The nodes' warnings

From each session's `log-digest.md` (the node logs are not committed):

- **The internal channel (X-55).** No node logged "too many concurrent streams". The internal signal sender dropped a wait's signal only when the wait was over before it was sent: two per node on PostgreSQL and one per node on MongoDB, all in E3's 4,096-client step.
- **Three nodes, PostgreSQL.** Besides those, 4 netty `exceptionCaught()` warnings in E1's 4,096-client step and 129 throttle warnings from the saga's subscriptions in E3's.
- **Three nodes, MongoDB.** 47 throttle warnings in E3's 4,096-client step, and 11 connection resets in its 1,024-client step.
- **Single node, both backends.** Connection resets (`FluxReceive`, "observed post termination") as the load generator closes its connections at a step's end, and the saga's throttle warnings in E3's top steps. MongoDB's node also logged one request that failed with a connection reset (`WowRoutes`, 00:08:00, in the 4,096-client run of E1).
- **Startup.** Each node logs the JDK 21 virtual-thread pinning warning and the anonymous caller warning once.

## Anomalies

Two findings that this snapshot does not explain. Neither was measured further in the window; each needs a controlled run.

### PostgreSQL's E1 uniform falls past its peak

At 8,192 clients a single node on PostgreSQL answered 20,823.6/s, 35.5% below its 32,303.0/s at 4,096 clients:

| | 4,096 clients | 8,192 clients |
| --- | ---: | ---: |
| Answered | 32,303.0/s | 20,823.6/s |
| Failed | 0 | 2.1%: 13,373 × 429 `wow.command.busy`, about 446/s |
| p50, p99 | 123.4 ms, 261.1 ms | 389.1 ms, 495.6 ms |
| Host CPU, every process, OrbStack's VM among them | 12.6 cores | 10.4 cores |
| Load generator, all its threads; its busiest thread | 2.73 cores; 0.36 | 2.16 cores; 0.19 |

- The p50 at 8,192 clients matches the clients over the throughput (8,192 / 20,823.6/s = 393 ms): the clients waited longer for each answer.
- The refusals were about 2% of what the node answered, and the host's CPU fell with the throughput, by about 2 cores. Inferred: the refusal path's own CPU does not account for the fall; something waited rather than computed.
- The node logged nothing in that step.
- Not measured, since a sweep records no resources (the harness records them for `bench.sh run`): the node's CPU alone, its GC, and PostgreSQL's CPU and transactions.
- MongoDB fell less, from 21,949.7/s (the 4,096-client run before it) to 20,601.2/s, with 8.3% refused and its host CPU 11.6 and 11.0 cores.
- To find out: a `bench.sh run` of E1 uniform at 4,096 and 8,192 clients with the harness's resources (the node's CPU and GC, PostgreSQL's transactions and WAL), and a profile of the node at 8,192.

### MongoDB with contended keys

A single node on MongoDB, with Zipf keys or one hot account, answered far less at its clean steps than in the earlier rounds; PostgreSQL did not. Single runs, no ABAB:

| Backend, keys | Clients | Snapshot 2 (`30d0c0348e`) | #5334 (`fe6f38f224`) | This snapshot (`b435768e49`) |
| --- | ---: | ---: | ---: | ---: |
| MongoDB, Zipf | 256 | 14,350.9/s | 13,966.5/s | 3,898.2/s |
| MongoDB, Zipf | 1,024 | 15,171.4/s | 15,296.6/s | 2,672.6/s (p99 3,096.6 ms) |
| MongoDB, one hot account | 256 | 9,642.0/s | 9,690.0/s | 1,558.3/s |
| MongoDB, one hot account | 1,024 | 9,682.2/s | 9,615.4/s | 1,634.7/s (p99 1,458.2 ms) |
| PostgreSQL, Zipf | 1,024 | 15,823.3/s | 16,275.5/s | 15,112.4/s |
| PostgreSQL, one hot account | 1,024 | 12,187.9/s | 11,054.5/s | 12,351.4/s |
| MongoDB, uniform | 4,096 | 23,382.3/s | 21,961.3/s | 22,886.7/s |

- None of those steps failed a command. From 64 to 4,096 clients the host used 2.5 to 4.0 cores in MongoDB's Zipf sweep, against 5.7 to 11.0 in PostgreSQL's at the same steps; from 64 to 1,024 clients, 1.9 to 4.2 cores in MongoDB's hot-account sweep. Inferred: the node waited rather than computed.
- The earlier rounds' numbers come from their raw `saturation.json` (#5334's at its head `3d0591e2c1`).
- MongoDB's node logged no warning in those sweeps, besides the connection resets at a step's end.
- Correlates with: the one commit since `fe6f38f224` that changes the MongoDB backend alone, [#5357](https://github.com/Ahoo-Wang/Wow/pull/5357) (`1357642891`, X-60: a conflict in a group append costs only its own stream), and the runtime's X-60 change before it, [#5337](https://github.com/Ahoo-Wang/Wow/pull/5337) (`c973fb9d4c`). No run here separates them.
- To find out: E1 Zipf on a single MongoDB node at `c973fb9d4c` (with #5337, without #5357) and at `1357642891`, alternated (ABAB).

## The earlier rounds

Single runs, no ABAB: the numbers are side by side, and no improvement or regression is read from them. The earlier reports wrote a `≥` peak on a 4,096-client step that failed more than 1% (with Zipf keys and one hot account); this table gives the same steps in each round instead, so that a step is compared with the same step.

| Measurement | Step | Snapshot 2 (`30d0c0348e`) | #5334 (`fe6f38f224`) | This snapshot (`b435768e49`) |
| --- | --- | ---: | ---: | ---: |
| PostgreSQL, E1 uniform | 4,096 | 36,388.7/s | 35,225.6/s | 31,484.9/s and 32,303.0/s |
| PostgreSQL, E1 Zipf | 4,096, answered (429) | 21,227.8/s (102,964) | 20,257.1/s (94,412) | 19,754.8/s (95,019) |
| PostgreSQL, E1 one hot account | 4,096, answered (429) | 20,184.5/s (548,717) | 20,190.9/s (565,568) | 23,768.6/s (681,803) |
| PostgreSQL, E3 uniform | 1,024 | 8,674.6/s | 9,441.7/s | 7,176.3/s |
| PostgreSQL, E3 uniform | 4,096, answered (429, 504) | 5,021.4/s (415,664, 469) | 4,821.1/s (388,629, 1,210) | 4,325.8/s (399,748, 1,719) |
| MongoDB, E1 uniform | 4,096 | 23,382.3/s | 21,961.3/s | 22,886.7/s and 21,949.7/s |
| MongoDB, E1 Zipf | 4,096, answered (429) | 18,050.6/s (80,682) | 18,194.5/s (81,388) | 6,667.6/s (30,749) |
| MongoDB, E1 one hot account | 4,096, answered (429) | 19,829.2/s (583,009) | 19,433.3/s (571,639) | 18,301.3/s (543,008) |
| MongoDB, E3 uniform | 1,024 | 6,531.7/s | 6,226.3/s | 7,629.5/s |
| MongoDB, E3 uniform | 4,096, answered (429, 504) | 4,194.6/s (292,011, 2,152) | 4,149.2/s (227,997, 2,196) | 3,547.0/s (311,424, 1,493) |
| Three nodes, PostgreSQL, E1 sweep | 1,024 (503, 504, 409) | 7,814.6/s (4,536, 408, 2) | 6,095.3/s (2,151, 1,136, 0) | 19,274.8/s (0, 0, 0) |
| Three nodes, PostgreSQL, E3 sweep | 1,024 (503, 504, 409) | 3,081.0/s (4,764, 1,835, 4) | 2,195.8/s (1,299, 1,114, 11) | 6,470.6/s (0, 0, 0) |
| Three nodes, PostgreSQL, E1 for 60 s | 1,024 (503, 504, 409) | 6,896.4/s (11,180, 1,876, 1) | 5,437.8/s (3,710, 2,727, 0) | 18,319.9/s (0, 0, 0) |
| Three nodes, PostgreSQL, E3 for 60 s | 1,024 (503, 504, 409) | 2,836.8/s (6,086, 2,096, 16) | 1,999.8/s (2,400, 2,068, 26) | 5,893.5/s (0, 0, 0) |
| Three nodes, MongoDB, E1 sweep | 1,024 (503, 504, 409) | 3,302.7/s (1,742, 351, 0) | 3,281.4/s (2,318, 947, 1) | 15,341.3/s (0, 0, 0) |
| Three nodes, MongoDB, E3 sweep | 1,024 (503, 504, 409) | 347.6/s (199, 1,388, 3) | 289.1/s (297, 1,161, 0) | 5,154.1/s (0, 0, 0) |
| Three nodes, MongoDB, E1 for 60 s | 1,024 (503, 504, 409) | 4,053.5/s (7,085, 2,337, 1) | 2,923.4/s (4,187, 2,863, 0) | 14,395.6/s (0, 0, 0) |
| Three nodes, MongoDB, E3 for 60 s | 1,024 (503, 504, 409) | 263.7/s (386, 2,637, 0) | 208.7/s (418, 2,169, 0) | 4,892.3/s (0, 0, 0) |

- The earlier rounds' three-node sessions answered 503, 504 and 409 at 1,024 clients, and their node logs had the internal channel's "too many concurrent streams" (X-55; more than 4,400 dropped signals on one PostgreSQL node in #5334). This snapshot has none of them at 1,024 clients. That correlates with the changes to the internal channel and the forwards (#5340, #5355) and to stale states (#5337, #5357) since `fe6f38f224`; no run here isolates one of them.
- The earlier rounds ran the three nodes in sessions that also reproduced X-34 and X-55; this one runs them as part of the snapshot, with the same flags.

## Limits

- One machine, one run each; no A/A pair in this snapshot. The noise band rests on one repeat of 4,096 clients a sweep.
- Three nodes share the host with the stores and the load generator, so the cluster's numbers are this host's, not a deployment's.
- Nothing checks the accounts' balances or the logs' continuity after a run; the snapshot says nothing about lost or duplicated events.
- A sweep records no resources: the node's CPU, its GC and the stores' CPU are known only as the host's total (`host-load.tsv`). The two anomalies above need a run that records them.
- Only a single node's E1 goes past 4,096 clients; E3 and the clusters stop there.
- The peak and void rules changed during the window, as the coordinating session decided (above). The verdicts are computed from the raw results after the run, with the rules in `summarize.py`. Which steps ran did not depend on the change: no sweep's 8,192-client step was clean.

## Reproduce

From a clean checkout at `b435768e49` on this machine, with the builds of `bench.sh build` (`./gradlew :benchmark-server:installDist :benchmark-load:installDist`) and no other JVM running:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/run-all.sh
```

An idle JVM that cannot be stopped is allowed by its pid, `ALLOW_JVM_PIDS="<pid>"`, as this snapshot allowed the Gradle daemon 67826. `01-single-node.sh <backend>` or `02-three-nodes.sh <backend>` reruns one session; a session is the unit of a rerun, since its sweeps run one after the other on the same primed stores. A measurement with a void or disturbed step runs again in its session once the host is quiet, and the attempt stays as `<measurement>.void-<n>/`. The scripts write the raw output under `test/benchmarks/results/e2e/m5max-snapshot/` (ignored by git); `curate` in [`lib.sh`](m5max-snapshot/scripts/lib.sh) copies into [`m5max-snapshot/`](m5max-snapshot/) what this report rests on. The node logs are not committed; each session's `log-digest.md` (`digest-logs.py`) counts their warnings and errors by message and by step.

| Path | What |
| --- | --- |
| [`SUMMARY.md`](m5max-snapshot/SUMMARY.md) | Every measurement's peak, sustainable throughput, the steps that failed more than 1%, the steps marked disturbed or void, the load before and after; the allowed JVM's CPU over the window |
| `1-S-<backend>/` | The single-node session: `up.log`, `host.txt`, `log-digest.md`, `e1-peak.md`, `e1-zipf-peak.md`, `e1-hot-peak.md`, and a directory per sweep (`saturation.json`, `summary.md`, `saturate.log`, `settle.*`, `host.txt`, `host-load.tsv`, `allowed-jvms.txt`), the steps past 4,096 clients in `<sweep>-top/`; PostgreSQL's set-aside attempt in `e1-sweep.void-1/` |
| `3-N-<backend>/` | The three-node session, likewise, with the fixed 1,024-client runs `e1-c1024/` and `e3-c1024/` |
| [`scripts/`](m5max-snapshot/scripts/) | `run-all.sh`, `01-single-node.sh`, `02-three-nodes.sh`, `lib.sh`, `summarize.py`, `digest-logs.py` |
