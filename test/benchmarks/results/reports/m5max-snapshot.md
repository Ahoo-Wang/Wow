# Saturation snapshot on Apple M5 Max

The latest saturation snapshot of Wow 10 on the local Apple M5 Max (decision D391 keeps one). It has:

- single-node closed-loop ceilings on disk, PostgreSQL and MongoDB, with uniform, Zipf and one-hot-account keys for E1 and uniform keys for E3;
- three nodes on this one host;
- the re-measurement of MongoDB's cost per commit below the knee with D420's sparse access key, against `v10.0.0-beta.1` and against D420's parent.

Measured on 2026-11-11 and 12, from 23:16 to 01:21 (PST), at `d9b0781205` (`main`), the benchmark's JVMs on JDK 25. It replaces the snapshot at [`a7c372ad3e`](https://github.com/Ahoo-Wang/Wow/blob/d9b0781205aa5749bf7e3941dfcbb622c2eaac36/test/benchmarks/results/reports/m5max-snapshot.md), whose figures stand beside each of this one's below, and that report's D420 addendum, whose method this one repeats. The raw results and the scripts are in [`m5max-snapshot/`](m5max-snapshot/).

## Setup

| | |
| --- | --- |
| Commit measured | `d9b0781205aa5749bf7e3941dfcbb622c2eaac36` (`main`; the runs record it with no changed paths, `environment.txt`) |
| Since the last snapshot | 176 commits (`git rev-list --count a7c372ad3e..d9b0781205`). Among them, on the write and subscription paths: <ul><li>a shared state's small rebuilds outside the replay gate (#6070, X-118's fix);</li><li>MongoDB's tenant-scoped sparse access key (#6105, D420);</li><li>the failure ledger's due scan by its key (#6141);</li><li>the RT-11 to RT-15 lifecycle and hand-off fixes;</li><li>every background loop, the append queue's lanes and mergers among them, run as rounds whose end is seen (#6147, #6153);</li><li>a subscription's checkpoints in one read of the key's prefix (#6149).</li></ul> |
| The JDK | Temurin 25.0.5+8 for the servers and the load generator (D393), as in the last snapshot. The code is the one variable here |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB, macOS 27.2 (26B123) |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`), MongoDB 7.0 (a one-member replica set, WiredTiger cache 2 GB), Kafka 7.4.0 (KRaft); on the engine's disk, not tmpfs ([`compose.yml`](../../harness/compose.yml)) |
| Nodes | One node, or three on this host; each `-Xms1g -Xmx1g -XX:+UseG1GC`; the server's defaults, its state stream published to Kafka |
| Data | 100,000 accounts, seeded and primed once a session (`--prime --seed-concurrency=128`) |
| Sweeps | Closed loop, 1, 4, 16, 64, 256, 1,024 and 4,096 clients (`--sweep --growth=4 --max-concurrency=4096`), 10 s of warm-up and 30 s measured a step; the load generator ends a sweep after a step that failed more than 1% of its commands. Three nodes also run E1 and E3 at a fixed 1,024 clients, 15 s of warm-up and 60 s measured |
| Past 4,096 clients | A single node's three E1 sweeps go on: 4,096 clients again, then 8,192, then 12,288 if 8,192 still rose past the noise band and failed 1% or less ([`summarize.py --peak`](m5max-snapshot/scripts/summarize.py), `e1-peak.md`, `e1-zipf-peak.md`, `e1-hot-peak.md`). No sweep got to 12,288 |
| Load generator | The most it used in any step was 3.57 cores (PostgreSQL, one hot account, 4,096 clients): no step was generator-bound (80% of its processors, or 0.9 of a core for its busiest thread) |
| Host record | As in the last snapshot: `host.txt` before and after each measurement, `host-load.tsv` every 10 s during it |
| Scripts | Unchanged since the last snapshot's D420 addendum (#6095). The window's order: the store cost's first pass (rounds 1–2, 23:16–23:39), `run-all.sh` (23:39–00:58), the store cost's second pass (rounds 3–4, 00:58–01:21) |

### The window

The machine is shared. The coordinating session cleared it: the other Wow sessions were held or idle with empty queues, and no other project's session ran. A step is **disturbed** when the other processes used 2 cores or more on average over it, or another container half a core. It is **void** when another JVM ran, or an allowed JVM used 5% of a core or more in two samples in a row, or an allowed Gradle daemon's log records a build request (`summarize.py`).

- **Allowed JVMs**, idle and left running (`./gradlew --stop` would stop every session's daemons): two Gradle daemons (pids 10510, 94957) and two Kotlin build daemons (10555, 64878) (`ALLOW_JVM_PIDS`, `environment.txt`).
  - Over the snapshot's 370 samples they used 0.5% of a core on average, 89.3% at most in one sample. That sample was in single-node PostgreSQL's E3 sweep, 1 s after its 1,024-client step ended, and the daemon's log has only routine lines around it (`allowed-jvms.txt`).
  - The first start of the window listed none of them, so its quiet check refused at once and measured nothing. The window was started again a minute later with the four allowed.
- **No measurement was disturbed or void**, in the snapshot or in the store cost, so no session was rerun for that. No other JVM ran. The busiest step for the other processes averaged 1.06 cores (MongoDB, one hot account, 4,096 clients, `SUMMARY.md`).
- **The desktop is not constant over a window.** The store cost's first pass shows it: the other processes used about 0.8 of a core (WindowServer about 45%, the Claude app) through its first four sessions and 0.2–0.4 in its last two. That moved its figures by more than the arms differ (see the store cost below), so a second pass ran with the host quiet throughout.

## How to read the numbers

- A **peak** is the throughput of the best step that failed 1% of its commands or less. A step that failed more is listed apart, with its answered throughput and its failures by kind, and is not a peak:
  - 429 `wow.command.busy` is the node's admission refusing a command by its configured limits;
  - 504 `wow.wait.timeout` is a wait that ran out;
  - 503 `wow.command.unknown` is a forwarded command whose outcome did not come back;
  - 409 `wow.command.contended` is a write that lost its version slot.
- A peak is a lower bound, written `≥`, only where the measurement could not go further.
- E1 on a single node is measured to its limit. Where the throughput was still rising at the last step that failed 1% or less, the text says so. The noise band is the difference between the two runs of 4,096 clients that failed 1% or less, 3% at least.
- The **sustainable** throughput is that of the sweep's highest step with a p99 of 200 ms or less and no error.
- A step's p50 and p99 are of the answered commands: the load generator times only the commands that succeeded (`ClosedLoop.kt`). Past the failures, compare the answered rate, not the failed share, since a command refused at once comes back at once.
- Each number is one run, except the store cost's. The differences from the last snapshot are given without a conclusion: there is no A/A or ABAB in a snapshot. Within this one, two measures of the same step differ by up to 11% (a three-node sweep's 1,024 step against its fixed 1,024-client run), and the last snapshot's five sessions of one hot account ranged ±15%.
- A cause is stated only where a controlled run showed it; elsewhere the text says "correlates with" or "inferred" and gives the evidence.

## Single node: ceilings

Each cell gives this snapshot's figure, then the last snapshot's at `a7c372ad3e` and the difference.

| Measurement | PostgreSQL peak | PostgreSQL sustainable | MongoDB peak | MongoDB sustainable |
| --- | ---: | ---: | ---: | ---: |
| E1, uniform | ≥ 37,713.6/s at 4,096 (33,938.5/s; +11.1%) | 23,030.1/s at 1,024, p99 74.8 ms (21,373.1/s; +7.8%) | ≥ 21,775.2/s at 4,096 (21,458.6/s; +1.5%) | 19,759.6/s at 1,024, p99 102.4 ms (17,946.6/s; +10.1%) |
| E1, Zipf | 17,090.6/s at 1,024 (16,139.1/s; +5.9%) | 15,386.1/s at 256, p99 52.2 ms (15,417.2/s; −0.2%) | 13,372.5/s at 1,024 (14,536.9/s; −8.0%) | 3,353.8/s at 64, p99 197.6 ms (12,643.0/s at 256; −73.5%) |
| E1, one hot account | 11,521.2/s at 1,024 (9,431.6/s at 256; +22.2%) | 8,520.7/s at 256, p99 155.6 ms (9,431.6/s; −9.7%) | 6,911.2/s at 1,024 (8,984.5/s; −23.1%) | 5,417.2/s at 64, p99 150.5 ms (8,079.4/s at 256; −32.9%) |
| E3, uniform | 7,956.2/s at 1,024 (8,298.6/s; −4.1%) | 3,687.4/s at 64, p99 41.7 ms (4,333.5/s at 256; −14.9%) | 7,341.7/s at 1,024 (7,446.5/s; −1.4%) | 4,809.4/s at 256, p99 173.1 ms (4,436.5/s; +8.4%) |

- The uniform peaks are lower bounds: each is on the sweep's last step, and the steps past it failed (next section). None was generator-bound.
- **Three sustainable figures fell by a step or two.** Each missed the 200 ms p99 at the 256 step, with no error, by more than a hair:
  - MongoDB's Zipf: 239.6 ms (68.6 ms at `a7c372ad3e`), and its 64 step passed by 2.4 ms;
  - MongoDB's hot account: 248.8 ms (147.5 ms);
  - PostgreSQL's E3: 704.5 ms (93.7 ms).

  They moved with the stores' two flush modes below, and are not a change between the snapshots.
- **The hot account moved in opposite directions,** PostgreSQL +22.2% and MongoDB −23.1%. The last snapshot's five fresh sessions of one hot account ranged 8.9–11.9 k/s on PostgreSQL. Single runs; not read as either store's change.

### E1 to its limit

| Sweep | Peak | Why it stopped | The steps past the peak |
| --- | ---: | --- | --- |
| PostgreSQL, uniform | ≥ 37,713.6/s at 4,096 | 8,192 clients failed 4.0% | 4,096 again: 36,719.5/s; 8,192: 21,273.0/s answered (26,600 × 429) |
| PostgreSQL, Zipf | 17,090.6/s at 1,024 | 4,096 clients failed 12.0% and 10.7% | 4,096: 20,308.3/s answered (83,231 × 429); again: 20,620.8/s (74,031 × 429) |
| PostgreSQL, one hot account | 11,521.2/s at 1,024 | 4,096 clients failed 48.1% and 48.1% | 4,096: 21,779.9/s answered (605,525 × 429); again: 21,789.3/s (606,290 × 429) |
| MongoDB, uniform | ≥ 21,775.2/s at 4,096 | 8,192 clients failed 16.4% | 4,096 again: 21,822.6/s; 8,192: 17,754.7/s answered (104,730 × 429) |
| MongoDB, Zipf | 13,372.5/s at 1,024 | 4,096 clients failed 13.7% and 13.1% | 4,096: 15,452.7/s answered (73,748 × 429); again: 17,511.2/s (78,858 × 429) |
| MongoDB, one hot account | 6,911.2/s at 1,024 | 4,096 clients failed 49.5% and 49.5% | 4,096: 18,645.7/s answered (548,113 × 429); again: 17,971.2/s (527,698 × 429) |

- Every failure of a single node's E1 is the admission's 429. The node refused what its limits did not admit.
- The noise bands are 3% on every sweep: 2.7% on PostgreSQL's uniform keys (37,713.6/s and 36,719.5/s at 4,096) and 0.2% on MongoDB's (21,775.2/s and 21,822.6/s).
- As in the last snapshot, a step that the admission cuts back answers more than the clean steps below it with contended keys (both backends' hot account at 4,096 clients, about half refused). That throughput is not a peak.

## Single node: E3 past its knee

| Session | E3 at 1,024 | p99 at 1,024 | E3 at 4,096, answered, p99 | Failed at 4,096 | `a7c372ad3e` at 4,096 | `b435768e49` at 4,096 |
| --- | ---: | ---: | ---: | --- | --- | --- |
| PostgreSQL | 7,956.2/s | 745.5 ms | **5,222.8/s**, 6,684.7 ms | 69.7%: 359,173 × 429, 1,066 × 504 | 1,117.9/s, 9,502.7 ms; 8,064 × 504, 11 × 429 | 4,325.8/s, 5,210.1 ms; 399,748 × 429, 1,719 × 504 |
| MongoDB | 7,341.7/s | 401.4 ms | **4,282.4/s**, 5,931.0 ms | 75.7%: 397,534 × 429, 1,989 × 504 | 3,541.6/s, 8,454.1 ms; 5,058 × 504, 4 × 429 | 3,547.0/s, 6,651.9 ms; 311,424 × 429, 1,493 × 504 |

- Neither session failed a command at 1,024 clients or below.
- **At 4,096 clients the admission refuses again, and the answered rate is back.** The last snapshot's E3 at 4,096 hardly refused. It failed by waits that ran out after 10 s, and PostgreSQL answered a quarter of `b435768e49`'s rate. That was X-118: the replay gate over the adaptive query bulkhead (#5533) held the shared states' small rebuilds, fixed by #6070. Here both backends refuse three quarters at once (429), as at `b435768e49`, and answer 21% above it. Their p99 is under the last snapshot's 8.5–9.5 s: 6.7 s on PostgreSQL (5.2 s at `b435768e49`), 5.9 s on MongoDB (6.7 s).
- The node logs (`log-digest.md`) count, in the 4,096-client step, about 14,000 throttle warnings from the saga's and the transport sink's subscriptions (`PartitionThrottle`, the downstream pushed back) on PostgreSQL, and 12,000 on MongoDB.

## Three nodes on one host

| Backend | Measurement | Peak or result, p99 | `a7c372ad3e` | At 4,096 clients, answered, p99 | Failed at 4,096 |
| --- | --- | ---: | ---: | ---: | --- |
| PostgreSQL | E1 sweep | 21,114.1/s at 1,024, 122.9 ms | 21,166.6/s, 124.9 ms (−0.2%) | 10,824.4/s, 3,227.6 ms | 2.4%: 7,681 × 504, 232 × 503, 2 × 409 (last: 0) |
| PostgreSQL | E3 sweep | 6,950.1/s at 1,024, 415.7 ms | 6,863.2/s, 446.5 ms (+1.3%) | 3,217.4/s, 6,979.6 ms | 0.1%: 66 × 504, 2 × 409 (last: 28.6%, 916.4/s answered) |
| PostgreSQL | E1, 1,024 clients, 60 s | 19,000.2/s, 180.2 ms | 20,698.7/s, 127.0 ms (−8.2%) | | |
| PostgreSQL | E3, 1,024 clients, 60 s | 5,807.8/s, 806.9 ms | 6,122.7/s, 712.7 ms (−5.1%) | | |
| MongoDB | E1 sweep | 16,657.2/s at 1,024, 178.2 ms | 14,778.8/s, 204.8 ms (+12.7%) | 7,741.7/s, 3,375.1 ms | 0 (last: 0) |
| MongoDB | E3 sweep | 5,380.1/s at 1,024, 514.0 ms | 5,332.2/s, 536.6 ms (+0.9%) | 2,495.1/s, 5,636.1 ms | 0 (last: 4.6%, 3,447 × 504) |
| MongoDB | E1, 1,024 clients, 60 s | 15,805.8/s, 192.5 ms | 15,269.0/s, 203.8 ms (+3.5%) | | |
| MongoDB | E3, 1,024 clients, 60 s | 5,273.4/s, 581.6 ms | 4,769.1/s, 626.7 ms (+10.6%) | | |

- No command failed at 1,024 clients or below in either cluster. Each sweep's peak is at 1,024 clients, and at 4,096 its throughput fell.
- E3 at 4,096 answered more on both backends than in the last snapshot (PostgreSQL 3,217.4 against 916.4/s), as on a single node.
- The sweep's 1,024 step and the fixed run at 1,024 differ by 5% (MongoDB E1) to 11% (PostgreSQL E1) within one session. The differences from the last snapshot are within that.
- The three nodes share the host with the stores and the load generator; the cluster's numbers are this host's, not a deployment's.

### The nodes' warnings

From each session's `log-digest.md` (the node logs are not committed):

- **The internal channel.**
  - In PostgreSQL's E1 4,096-client step, node-3 logged six times that an exception reached the tail of the HTTP pipeline: Netty's `Http2Exception: Maximum number of RST frames reached`. That is Netty's guard against rapid stream resets, which closes the connection. Waits that time out reset their streams.
  - Inferred: the forwards in flight on a connection it closed lost their replies, and a forward sent and not answered is reported unknown, never run elsewhere (`ClusterOptions.forwardTimeout`). That is the step's 232 × 503 `wow.command.unknown`. `b435768e49`'s same step had 135.
  - No node logged "too many concurrent streams". The internal signal sender dropped a wait's signal only when the wait was over before it was sent: twice on PostgreSQL's cluster, in E3's 4,096-client step.
- **Throttle warnings.** The saga's and the transport sink's subscriptions throttled partitions whose downstream pushed back, in the 4,096-client steps of E3.
- **Single node, both backends.** Connection resets (`FluxReceive`, "observed post termination") as the load generator closes its connections at a step's end.
- **Startup.** Each node logs the anonymous caller warning once.

## Anomalies

### Two modes at 4 to 16 clients, in every build measured: the store's flush

A single node's median at 4 and 16 clients was 1.8 to 2.9 times as high in this snapshot as in the last two, on both backends, while 1 client and 64 or more were unchanged:

| Single node, E1 uniform, p50 | `b435768e49` | `a7c372ad3e` | This snapshot |
| --- | ---: | ---: | ---: |
| PostgreSQL, 4 clients | 1.59 ms | 1.80 ms | 4.93 ms |
| PostgreSQL, 16 clients | 2.72 ms | 2.65 ms | 4.89 ms |
| MongoDB, 16 clients | 3.47 ms | 3.81 ms | 6.53 ms |

**It is not a change between the snapshots.** Four builds across the range were measured one after another, one fresh single-node PostgreSQL session each, E1 uniform at 4, 16, 4 and 16 clients (15 s measured after 5 s a step) (`low-concurrency/split/`). p50 a step, in order:

| Build | 4 clients | 16 clients | 4 clients | 16 clients |
| --- | ---: | ---: | ---: | ---: |
| `a7c372ad3e`, the last snapshot | 1.79 ms | 2.90 ms | **5.09 ms** | **4.86 ms** |
| `13835bf24a`, D420's parent | 1.99 ms | **5.05 ms** | 1.78 ms | **4.13 ms** |
| `62314e9075`, D420 | 1.77 ms | 2.64 ms | 1.81 ms | 2.64 ms |
| `d9b0781205`, this snapshot | 1.80 ms | **4.99 ms** | 1.78 ms | 2.75 ms |

Every build has fast steps (about 1.8 ms at 4 clients, 2.6–2.9 ms at 16), and three of the four have slow ones at about 5 ms, `a7c372ad3e` among them in the session where its first two steps were fast. A build flips from one mode to the other between two 20 s steps. One 30 s step of a sweep catches either: the last snapshot's 4 and 16 clients fell in the fast mode, this one's in the slow. No session was void or disturbed, and the other processes used as much in the slow steps as in the fast (0.5–1.1 cores).

**What waits is the stores' flush.** One session at `d9b0781205` ran E1 uniform at 4 clients for 12 steps (10 s after 3 s) on each backend, sampling the store every second (`low-concurrency/naming-postgres/wal.tsv`, `naming-mongo/journal.tsv`). PostgreSQL ran with `track_wal_io_timing` on, beside `pg_stat_wal`, `pg_stat_database` and `pg_stat_statements`; MongoDB with WiredTiger's journal sync count and time:

| Backend, mode | Steps | p50 | Throughput | Mean flush | Flushes a second | Also |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| PostgreSQL, fast | 1–4, 11–12 | 1.79–2.00 ms | 1,512–2,153/s | WAL sync 0.59–0.96 ms | 955–1,569 | 2.29–2.36 commits a sync; statements 0.030–0.038 ms a call |
| PostgreSQL, slow | 5–10 | 4.64–5.05 ms | 726–796/s | WAL sync **1.74–1.80 ms** | 514–578 | 2.33–2.41 commits a sync; statements 0.037–0.043 ms a call |
| MongoDB, fast | 1, 3, 5, 6, 10 | 2.35–2.65 ms | 1,319–1,432/s | journal sync 0.55–0.62 ms | 863–973 | |
| MongoDB, slow | 7–9 | 4.16–4.25 ms | 819–836/s | journal sync **1.41–1.43 ms** | 543–611 | the other steps between, 0.81–1.27 ms with p50 2.83–3.79 ms |

- The flush time itself is bimodal, on both stores, and the median follows it. Everything Wow sends executes alike in both modes: the statements' time a call, and the commits a sync (the group commit), are the same.
- At 4 clients each command waits on its commits in turn, so the PostgreSQL median's +3 ms is about three of them at the extra 1.1 ms each. Inferred from the counts.
- The two stores run in OrbStack's Linux VM on the engine's disk. A flush slower on both at once is that storage path's state, the VM's or the disk's, not either store's code and not Wow's.
- The three sustainable figures that fell above moved with this mode. They are not a change between the snapshots.
- **On GitHub Actions** the same two builds, `a7c372ad3e` against `d9b0781205`, ran paired on one Linux runner ([run 43193065391](https://github.com/Ahoo-Wang/Wow/actions/runs/43193065391), `wow-pg-same-rate-ab`, W A A W).
  - E1 on an EPYC 9V74 and E3 on an EPYC 7763, PostgreSQL, no bus: their peaks and sustainable rates are level, within the A/A spreads.
  - At E1 1,000/s the p50 of both arms shifts within a run: 1.14, 66.05, 7.49 and 2.86 ms for `a7c372ad3e`, 3.04, 9.09, 2.62 and 1.05 ms for `d9b0781205`. Seven of the eight runs are flagged unstable, their second half against their first.
  - E3 at 300/s is steady on both, p50 2.10–2.29 ms.
  - So Linux shows no difference between the two builds either, and E1's latency at a low rate moves within a run there too. The runner records no flush time, so whether its moves are the store's flush is not measured.

### PostgreSQL's E1 uniform past its peak: a refusal now

| | 4,096 clients | 8,192 clients |
| --- | ---: | ---: |
| Answered | 36,719.5/s (the second run) | 21,273.0/s |
| Failed | 0 | 4.0%: 26,600 × 429 |
| p50, p99 | 108.5 ms, 227.3 ms | 376.8 ms, 557.1 ms |

- The last two snapshots measured a clean step that answered less past the peak (−44.9% at `a7c372ad3e`, failing 0.53%). Here 8,192 clients answered 42.1% less and the admission refused 4.0%. So it is a step past the failures, not a clean fall. The fall's cause is not measured, as before.

## MongoDB's cost per commit below the knee (D420)

The last snapshot's addendum measured MongoDB's two access indexes on the log at a fixed 8,000/s: they cost 9–15% of its insert time per commit, and 7–10% of mongod's CPU on the tag's documents. D420 made them a tenant-scoped sparse key that leaves out the nulls (#6105). This measures the change with the same instrument, from this checkout's harness and load generator:

| | |
| --- | --- |
| Arms | **tag:** the server of `v10.0.0-beta.1` (`22f8ac19cb`), 3 log indexes, its documents carrying no access attributes. **before:** the server of `13835bf24a`, D420's parent, 5 log indexes, which hold a null owner and space for every document. **d420:** the server of `62314e9075`, D420's merge commit, the same 5 indexes, sparse. Each built in its own worktree and copied under `results/e2e/installs/` |
| Sessions | One fresh single-node MongoDB session each, Kafka on, 1 GB heap, 100,000 accounts seeded and primed; then E1 with uniform keys at a fixed 8,000/s open loop, 30 s of warm-up and 120 s measured (37% of this snapshot's single-node MongoDB E1 ceiling) |
| Order | Two passes of ABC CBA (tag, before, d420; then reversed), 12 sessions. The first pass ran before the snapshot, the second after it |
| What is read | As in the addendum: mongod's CPU from its container's cgroup net of its 20 s idle floor, the log's `latencyStats` insert time, the snapshot's update latency, per commit over the run's whole window, about 1.2 M commits a session; and each index's keys after the run (`index-check.json`) |

### Results

| Pass, round | Session | Other processes, % of a core | mongod CPU µs/commit, net | Log insert µs/commit | Snapshot update µs/op | Log indexes, MB |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 1, r1 | tag | 76.7 | 225.5 | 30.80 | 89.9 | 3, 296.2 |
| 1, r1 | before | 77.6 | 219.7 | 32.22 | 83.3 | 5, 354.9 |
| 1, r1 | d420 | 96.2 | 216.8 | 29.98 | 86.8 | 5, 291.6 |
| 1, r2 | d420 | 81.3 | 219.1 | 30.68 | 84.2 | 5, 295.3 |
| 1, r2 | before | 38.7 | 204.1 | 29.79 | 77.3 | 5, 357.4 |
| 1, r2 | tag | 20.2 | 183.1 | 23.85 | 75.6 | 3, 296.9 |
| 2, r3 | tag | 15.2 | **194.6** | **25.08** | 76.1 | 3, 295.6 |
| 2, r3 | before | 15.8 | **185.1** | **26.59** | 71.1 | 5, 356.6 |
| 2, r3 | d420 | 17.8 | **185.0** | **25.30** | 71.4 | 5, 291.1 |
| 2, r4 | d420 | 16.6 | **182.5** | **25.13** | 71.2 | 5, 296.9 |
| 2, r4 | before | 16.2 | **200.7** | **28.71** | 74.3 | 5, 357.4 |
| 2, r4 | tag | 19.2 | **188.9** | **24.37** | 75.7 | 3, 292.3 |

The second pass's rounds are `r1` and `r2` of `mongo-store-cost-r3-r4/`; they are r3 and r4 here.

- **The first pass ran on a changing host.** The other processes used about 0.8 of a core in its first four sessions (WindowServer, the Claude app, and a renderer spike to 2.8 cores in r1 d420), and 0.2–0.4 in its last two.
  - Its tag read 225.5 and 183.1 µs CPU, 15% apart. The addendum's tag read 193.3 and 193.6.
  - Its d420 sessions ran only in the busy part. No session was void or disturbed (the line is 2 cores on average), but the pass can't compare its arms.
  - So the second pass ran, ABC CBA again, with the host quiet throughout (15–19% of a core). **The reading rests on it.** The first pass stays as recorded.
- **Keys after the run:** d420's `access_owner` and `access_space` hold 0 keys of about 1.4 M log documents in every session. On `before` each holds every document, 30–32 MB each. d420's log indexes, 291–297 MB, are the tag's size, 292–297 MB; `before`'s are 355–357 MB.
- **The load generator dropped commands in six runs:** 200 to 1,872 each, "generator-overload", its in-flight cap reached in a stalled second. Two runs had client-side socket errors: 531 in the first pass's r1 before, 20 in the second pass's r3 before. That is under 0.2% of each run, and there was no server error in any session. They are kept.

| Second pass | Round 3 | Round 4 | Median |
| --- | ---: | ---: | ---: |
| d420 / tag, mongod CPU | 0.951 | 0.966 | 0.958 |
| d420 / tag, log insert | 1.009 | 1.031 | 1.020 |
| before / tag, log insert | 1.060 | 1.178 | 1.119 |
| d420 / before, log insert | 0.951 | 0.875 | 0.913 |

### Reading

- **D420 closes the difference against the tag.** d420's log insert time per commit is 1% and 3% over the tag's, inside the tag's own round-to-round spread of 3%. Its mongod CPU is 3–5% under the tag's, inside the spread too (the tag's rounds differ by 3%, `before`'s by 8%).
- **What D420 removed:** `before`, whose two indexes hold every document's nulls, costs 6% and 18% more insert time than the tag in the same rounds. That is the addendum's 9–15% again. d420 writes no entry to either index for an aggregate with no owner or space.
- **The snapshot's updates** don't move between the arms.

## Limits

- One machine, one run each, apart from the store cost's 12 sessions; no A/A pair in the snapshot itself. The noise band rests on one repeat of 4,096 clients a sweep, and the steps below 64 clients vary by up to 40% from run to run (the last snapshot's e1-hot check).
- 176 commits separate the two snapshots. No difference between them is laid to one commit.
- **A sweep's low steps cannot carry a comparison.** Each is one 30 s step in one session, and on this machine the stores' flush runs in one of two modes that change between two 20 s steps (the anomaly above). A comparison at 1–256 clients would need each low step repeated, or the arms' steps interleaved in one window, with the stores' flush time recorded beside it (PostgreSQL's `pg_stat_wal` with `track_wal_io_timing`, MongoDB's journal sync time). The snapshot's scripts do neither yet.
- The desktop's own CPU varies over a window by more than a store-cost arm differs. A step is disturbed only at 2 cores, so a fixed-rate comparison has to be read with each session's other processes beside it.
- Three nodes share the host with the stores and the load generator, so the cluster's numbers are this host's, not a deployment's.
- Nothing checks the accounts' balances or the logs' continuity after a run.
- A sweep records no resources but the host's: the node's CPU, its GC and the stores' CPU are known only as the host's total (`host-load.tsv`).
- Only a single node's E1 goes past 4,096 clients; E3 and the clusters stop there.

## Reproduce

From a clean checkout at `d9b0781205` on this machine, with the builds of `bench.sh build` (`./gradlew :benchmark-server:installDist :benchmark-load:installDist`), JDK 25 (`JAVA_HOME=$(/usr/libexec/java_home -v 25)`), and no other JVM running:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/run-all.sh
```

An idle JVM that cannot be stopped is allowed by its pid, `ALLOW_JVM_PIDS="<pid> …"`. `01-single-node.sh <backend>` or `02-three-nodes.sh <backend>` reruns one session. A measurement with a void or disturbed step runs again in its session once the host is quiet, at most twice, and the attempt stays as `<measurement>.void-<n>/`.

The store cost, with each arm's server built in its own worktree and copied under this checkout's `results/e2e/installs/<commit>/benchmark-server`. The arms are given in the order of round 1, and round 2 runs them reversed. `store-cost.py` writes the tables:

```bash
BENCH_OUT=test/benchmarks/results/e2e/mongo-store-cost test/benchmarks/results/reports/m5max-snapshot/scripts/04-mongo-store-cost.sh tag=<installs/22f8ac19cb> before=<installs/13835bf24a> d420=<installs/62314e9075>
python3 -I test/benchmarks/results/reports/m5max-snapshot/scripts/store-cost.py test/benchmarks/results/e2e/mongo-store-cost tag/before d420/tag d420/before
```

The scripts write the raw output under `test/benchmarks/results/e2e/` (ignored by git); `curate` in [`lib.sh`](m5max-snapshot/scripts/lib.sh) copies into [`m5max-snapshot/`](m5max-snapshot/) what this report rests on. The node logs are not committed; each session's `log-digest.md` counts their warnings and errors by message and by step.

| Path | What |
| --- | --- |
| [`SUMMARY.md`](m5max-snapshot/SUMMARY.md) | Every measurement's peak, sustainable throughput, the steps that failed more than 1%, the steps marked disturbed or void, the load before and after; the allowed JVMs' CPU over the window |
| `1-S-<backend>/` | The single-node session: `up.log`, `host.txt`, `log-digest.md`, the three `*-peak.md`, and a directory per sweep (`saturation.json`, `summary.md`, `saturate.log`, `settle.*`, `host.txt`, `host-load.tsv`, `allowed-jvms.txt`), the steps past 4,096 clients in `<sweep>-top/` |
| `3-N-<backend>/` | The three-node session, likewise, with the fixed 1,024-client runs `e1-c1024/` and `e3-c1024/` |
| `0*-*.log` | Each script's output |
| `low-concurrency/` | The two modes: `split/<n>-<build>/` (the four builds' sessions, steps 4, 16, 4, 16), `naming-postgres/` (12 steps at 4 clients, with `wal.tsv`, PostgreSQL's WAL and statements every second, and `pg-top.txt`), `naming-mongo/` (likewise, with `journal.tsv`). Each session ran `03-e1-hot-arms.sh`'s session with `SWEEP=(--sweep --concurrencies=… --step=… --warm-up=…)` and, in the naming sessions, a sampler of the store beside the sweep; their wrappers are not committed |
| `mongo-store-cost/`, `mongo-store-cost-r3-r4/` | The store cost's two passes: each with [`STORE-COST.md`](m5max-snapshot/mongo-store-cost-r3-r4/STORE-COST.md) (every session's figures, index sizes and keys, and the ratios), `SUMMARY.md` (each run's verdict and load), `verdicts.txt`, `env-*/environment.txt`, the pass's log `04-mongo-store-cost.log`, and per session `r<round>-<arm>/` with `up.log`, `indexes.log`, `index-check.json`, `idle.json`, `store-before.json`, `store-after.json`, `host.txt` and the run's `result.json`, `host-load.tsv` and `allowed-jvms.txt` |
| [`scripts/`](m5max-snapshot/scripts/) | `run-all.sh`, `01-single-node.sh`, `02-three-nodes.sh`, `03-e1-hot-arms.sh`, `04-mongo-store-cost.sh`, `lib.sh`, `summarize.py`, `store-cost.py`, `digest-logs.py` |
