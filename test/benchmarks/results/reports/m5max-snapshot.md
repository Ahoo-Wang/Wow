# Saturation snapshot on Apple M5 Max

The latest saturation snapshot of Wow 10 on the local Apple M5 Max (decision D391 keeps one). It has:

- single-node closed-loop ceilings on disk, PostgreSQL and MongoDB, with uniform, Zipf and one-hot-account keys for E1 and uniform keys for E3;
- three nodes on this one host.

Measured on 2026-11-17, from 02:31 to 03:53 (PST), at `103f9b6746` (10.0.0-beta.5), the benchmark's JVMs on JDK 25. It is the head arm of beta.5's closing snapshot. In the same window the same scripts measured `v10.0.0-beta.4` first, and that arm is not part of this report. It replaces the snapshot at [`d9b0781205`](https://github.com/Ahoo-Wang/Wow/blob/e3a4e937aa/test/benchmarks/results/reports/m5max-snapshot.md); no figure here is set beside that one's. The raw results and the scripts are in [`m5max-snapshot/`](m5max-snapshot/).

## Setup

| | |
| --- | --- |
| Commit measured | `103f9b674680e7b98fbd8b934ee1947fbbe37a12` (10.0.0-beta.5); the server and the load generator are that commit's clean builds (`environment.txt`: no changed paths; each sweep's `install.txt`: `dirty=no`) |
| The JDK | Temurin 25.0.5+8 for the servers and the load generator (D393) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB, macOS 27.2.1 (26B131) |
| Container engine | OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 48,159 MiB |
| Stores | PostgreSQL 17-alpine (`max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`), MongoDB 7.0 (a one-member replica set, WiredTiger cache 2 GB), Kafka 7.4.0 (KRaft); on the engine's disk, not tmpfs ([`compose.yml`](../../harness/compose.yml)) |
| Nodes | One node, or three on this host; each `-Xms1g -Xmx1g -XX:+UseG1GC`; the server's defaults, its state stream published to Kafka |
| Data | 100,000 accounts, seeded and primed once a session (`--prime --seed-concurrency=128`) |
| Sweeps | Closed loop, 1, 4, 16, 64, 256, 1,024 and 4,096 clients (`--sweep --growth=4 --max-concurrency=4096`), 10 s of warm-up and 30 s measured a step; the load generator ends a sweep after a step that failed more than 1% of its commands. Three nodes also run E1 and E3 at a fixed 1,024 clients, 15 s of warm-up and 60 s measured |
| Past 4,096 clients | A single node's three E1 sweeps go on: 4,096 clients again, then 8,192, then 12,288 if 8,192 still rose past the noise band and failed 1% or less ([`summarize.py --peak`](m5max-snapshot/scripts/summarize.py), `e1-peak.md`, `e1-zipf-peak.md`, `e1-hot-peak.md`). No sweep got to 12,288 |
| Load generator | No step was generator-bound (80% of its processors, or 0.9 of a core for its busiest thread; `SUMMARY.md`) |
| Host record | `host.txt` before and after each measurement, `host-load.tsv` every 10 s during it; on MongoDB, `mongo-stats.tsv` beside each sweep (the journal's syncs and their time, every second) |
| Scripts | [`run-all.sh`](m5max-snapshot/scripts/run-all.sh) as committed with the last snapshot, unchanged |

### The window

The machine is shared. The coordinating session cleared it: the other Wow sessions were held or idle under the batch lock, and no other project's session ran. A step is **disturbed** when the other processes used 2 cores or more on average over it, or another container half a core. It is **void** when another JVM ran, or an allowed JVM used 5% of a core or more in two samples in a row, or an allowed Gradle daemon's log records a build request (`summarize.py`).

- **Allowed JVMs**, idle and left running (`./gradlew --stop` would stop every session's daemons): pids 2744 and 32611 (`ALLOW_JVM_PIDS`, `environment.txt`), of which only 2744, a Gradle daemon, was running. Over the 22 measurements' 385 samples the allowed JVMs used 0.1% of a core on average, 28.2% at most in one sample (`allowed-jvms.txt`).
- **No measurement was disturbed or void**, and no other JVM ran (`SUMMARY.md`).
- A dry run of one 10 s step before the window was clean.

## How to read the numbers

- A **peak** is the throughput of the best step that failed 1% of its commands or less. A step that failed more is listed apart, with its answered throughput and its failures by kind, and is not a peak:
  - 429 `wow.command.busy` is the node's admission refusing a command by its configured limits;
  - 504 `wow.wait.timeout` is a wait that ran out.
- A peak is a lower bound, written `≥`, only where the measurement could not go further: the sweep's last step, with the steps past it failed.
- E1 on a single node is measured to its limit. Where the throughput was still rising at the last step that failed 1% or less, the text says so. The noise band is the difference between the two runs of 4,096 clients that failed 1% or less, 3% at least.
- The **sustainable** throughput is that of the sweep's highest step with a p99 of 200 ms or less and no error.
- A step's p50 and p99 are of the answered commands: the load generator times only the commands that succeeded (`ClosedLoop.kt`). Past the failures, compare the answered rate, not the failed share, since a command refused at once comes back at once.
- Each number is one run. Within this snapshot, two measures of the same step differ by up to 7% (MongoDB's E1 at 4,096 clients, run twice) and by 1% to 23% between a three-node sweep's 1,024 step and its fixed 1,024-client run (next sections).
- A cause is stated only where a controlled run showed it; elsewhere the text says "correlates with" or "inferred" and gives the evidence.

## Single node: ceilings

| Measurement | PostgreSQL peak | PostgreSQL sustainable | MongoDB peak | MongoDB sustainable |
| --- | ---: | ---: | ---: | ---: |
| E1, uniform | ≥ 41,706.1/s at 4,096 | 41,706.1/s at 4,096, p99 167.9 ms | ≥ 28,609.4/s at 4,096 | 28,196.1/s at 1,024, p99 66.6 ms |
| E1, Zipf | 19,746.8/s at 256 | 19,746.8/s at 256, p99 29.6 ms | 15,957.5/s at 1,024 | 14,914.4/s at 256, p99 42.2 ms |
| E1, one hot account | 11,853.7/s at 256 | 11,853.7/s at 256, p99 76.8 ms | 9,948.1/s at 256 | 9,948.1/s at 256, p99 78.8 ms |
| E3, uniform | 8,042.7/s at 1,024 | 3,637.9/s at 64, p99 185.3 ms | 7,738.2/s at 1,024 | 5,898.6/s at 256, p99 72.2 ms |

- The uniform peaks are lower bounds: each is on the sweep's last step, and the steps past it failed (next section). None was generator-bound.
- **PostgreSQL's E3 is sustainable only up to 64 clients.** Its 256-client step answered 7,067.3/s with no error but a p99 of 643.1 ms, and its 1,024-client step 737.3 ms. MongoDB's E3 stayed under 200 ms up to 256 clients (72.2 ms).
- **Contended keys peak lower and earlier.** With Zipf keys PostgreSQL peaked at 256 clients (19,746.8/s) and its 1,024-client step answered a little less at a p99 of 337.9 ms. MongoDB's Zipf throughput was still rising at 1,024 clients when the failures began at 4,096. One hot account peaked at 256 clients on both.

### E1 to its limit

| Sweep | Peak | Why it stopped | The steps past the peak |
| --- | ---: | --- | --- |
| PostgreSQL, uniform | ≥ 41,706.1/s at 4,096 | 8,192 clients failed 2.7% | 4,096 again: 40,594.1/s; 8,192: 22,659.7/s answered (19,145 × 429) |
| PostgreSQL, Zipf | 19,746.8/s at 256 | 4,096 clients failed 14.4% and 14.3% | 4,096: 25,193.8/s answered (126,957 × 429); again: 25,574.1/s (128,487 × 429) |
| PostgreSQL, one hot account | 11,853.7/s at 256 | 4,096 clients failed 45.3% and 45.3% | 4,096: 21,540.2/s answered (535,534 × 429); again: 20,876.1/s (518,661 × 429) |
| MongoDB, uniform | ≥ 28,609.4/s at 4,096 | 8,192 clients failed 2.0% | 4,096 again: 26,615.9/s; 8,192: 20,037.3/s answered (12,468 × 429) |
| MongoDB, Zipf | 15,957.5/s at 1,024 | 4,096 clients failed 14.4% and 14.4% | 4,096: 20,449.9/s answered (102,934 × 429); again: 19,552.6/s (98,291 × 429) |
| MongoDB, one hot account | 9,948.1/s at 256 | 4,096 clients failed 47.9% and 49.2% | 4,096: 20,048.4/s answered (552,724 × 429); again: 23,132.8/s (671,697 × 429) |

- Every failure of a single node's E1 is the admission's 429. The node refused what its limits did not admit.
- The noise bands: 3% on PostgreSQL's uniform keys (41,706.1/s and 40,594.1/s at 4,096, 2.7% apart) and 7% on MongoDB's (28,609.4/s and 26,615.9/s).
- A step that the admission cuts back answers more than the clean steps below it with contended keys (both stores' hot account at 4,096 clients, about half refused). That throughput is not a peak.

## Single node: E3 past its knee

| Session | E3 at 1,024 | p99 at 1,024 | E3 at 4,096, answered, p99 | Failed at 4,096 |
| --- | ---: | ---: | ---: | --- |
| PostgreSQL | 8,042.7/s | 737.3 ms | 6,179.5/s, 3,555.3 ms | 71.7%: 467,570 × 429, 2,338 × 504 |
| MongoDB | 7,738.2/s | 440.3 ms | 3,812.2/s, 5,734.4 ms | 78.8%: 423,223 × 429, 2,707 × 504 |

- Neither session failed a command at 1,024 clients or below.
- At 4,096 clients the admission refuses about three quarters at once (429), and a few hundred waits a second run out after 10 s (504).
- The node logs (`log-digest.md`) count, in the 4,096-client step, about 6,400 throttle warnings from the saga's subscriptions (`PartitionThrottle`, the downstream pushed back) on PostgreSQL, and 3,500 on MongoDB.

## Three nodes on one host

| Store | Measurement | Peak or result, p99 | At 4,096 clients, answered, p99 | Failed at 4,096 |
| --- | --- | ---: | ---: | --- |
| PostgreSQL | E1 sweep | 25,104.3/s at 1,024, 90.6 ms | 25,482.8/s, 444.4 ms | 8.5%: 71,059 × 429 |
| PostgreSQL | E3 sweep | 8,623.0/s at 1,024, 309.2 ms | 7,339.6/s, 6,619.1 ms | 8.9%: 18,424 × 429, 3,014 × 504 |
| PostgreSQL | E1, 1,024 clients, 60 s | 24,255.1/s, 98.8 ms | | |
| PostgreSQL | E3, 1,024 clients, 60 s | 6,630.8/s, 847.9 ms | | |
| MongoDB | E1 sweep | 19,052.3/s at 1,024, 131.1 ms | 20,172.6/s, 589.8 ms | 8.8%: 58,464 × 429 |
| MongoDB | E3 sweep | 6,060.2/s at 1,024, 471.0 ms | 4,936.9/s, 6,815.7 ms | 11.9%: 16,439 × 429, 3,522 × 504 |
| MongoDB | E1, 1,024 clients, 60 s | 17,695.9/s, 152.6 ms | | |
| MongoDB | E3, 1,024 clients, 60 s | 6,015.6/s, 518.1 ms | | |

- No command failed at 1,024 clients or below in either cluster. Each sweep's peak is at 1,024 clients.
- The sweep's 1,024 step and the fixed run at 1,024 differ by 1% (MongoDB E3) to 23% (PostgreSQL E3) within one session; PostgreSQL's E3 fixed run, at 6,630.8/s against the sweep's 8,623.0/s, is the outlier, and one run cannot say which of the two is typical.
- The three nodes share the host with the stores and the load generator; the cluster's numbers are this host's, not a deployment's. They are not a measure of what a cluster gains over one node.

### The nodes' warnings

From each session's `log-digest.md` (the node logs are not committed):

- **Throttle warnings.** The saga's subscriptions throttled partitions whose downstream pushed back, in E3's 4,096-client step on every session and in its 1,024 step on three of them: about 39,000 lines across PostgreSQL's three nodes in its 4,096 step, and 32,000 on MongoDB's. On a single PostgreSQL node the transport sink's subscription throttled too, 4,551 of its 4,558 lines in the Zipf sweep's 1,024- and 4,096-client steps; three lines print the limit as `at most null handlings`, a log message with a missing value, not a measured effect.
- **The query store's admission.** A single PostgreSQL node logged three times that the query store ran as many queries as it allows and refused some (`wow.query.busy`), in the Zipf sweep and E3's 4,096 step.
- **The internal channel.** The signal sender dropped a wait's signal only when the wait was over before it was sent: once on each cluster, in E3's 4,096-client step.
- **Single node, PostgreSQL, E1's 4,096-client rerun.** 105 lines in which Netty could not set `TCP_NODELAY` on a connection being accepted (`SocketException: Invalid argument`). Inferred: the client had already closed it, since 85 of them carry no peer address (`255.255.255.255:65535`). No command failed in that step.
- **Single node, both stores.** Connection resets (`FluxReceive`, "observed post termination") as the load generator closes its connections at a step's end.
- **Startup.** Each node logs the anonymous caller warning once.

## Limits

- One machine, one run each; no A/A pair in the snapshot itself. The noise band rests on one repeat of 4,096 clients a sweep.
- **A sweep's low steps cannot carry a comparison.** Each is one 30 s step in one session, and on this machine the stores' flush runs in one of two modes that change between two steps (the last snapshot's anomaly, in the [`d9b0781205` report](https://github.com/Ahoo-Wang/Wow/blob/e3a4e937aa/test/benchmarks/results/reports/m5max-snapshot.md#two-modes-at-4-to-16-clients-in-every-build-measured-the-stores-flush)). A comparison at 1–256 clients needs each low step repeated, or arms interleaved in one window, with the stores' flush time beside it.
- Three nodes share the host with the stores and the load generator, so the cluster's numbers are this host's, not a deployment's. Scaling across separate machines is not measured.
- Nothing checks the accounts' balances or the logs' continuity after a run.
- A sweep records no resources but the host's and, on MongoDB, the store's (`mongo-stats.tsv`): the node's CPU and GC are known only as the host's total (`host-load.tsv`).
- Only a single node's E1 goes past 4,096 clients; E3 and the clusters stop there.

## Reproduce

From a clean checkout at `103f9b6746` on this machine, with the builds of `bench.sh build` (`./gradlew :benchmark-server:installDist :benchmark-load:installDist`), JDK 25 (`JAVA_HOME=$(/usr/libexec/java_home -v 25)`), and no other JVM running:

```bash
test/benchmarks/results/reports/m5max-snapshot/scripts/run-all.sh
```

An idle JVM that cannot be stopped is allowed by its pid, `ALLOW_JVM_PIDS="<pid> …"`. `01-single-node.sh <backend>` or `02-three-nodes.sh <backend>` reruns one session. A measurement with a void or disturbed step runs again in its session once the host is quiet, at most twice, and the attempt stays as `<measurement>.void-<n>/`.

The scripts write the raw output under `test/benchmarks/results/e2e/` (ignored by git); `curate` in [`lib.sh`](m5max-snapshot/scripts/lib.sh) copies into [`m5max-snapshot/`](m5max-snapshot/) what this report rests on. The node logs are not committed; each session's `log-digest.md` counts their warnings and errors by message and by step.

| Path | What |
| --- | --- |
| [`SUMMARY.md`](m5max-snapshot/SUMMARY.md) | Every measurement's peak, sustainable throughput, the steps that failed more than 1%, the steps marked disturbed or void, the load before and after; the allowed JVMs' CPU over the window |
| [`summary.json`](m5max-snapshot/summary.json) | The same, with every step, as `bench-headline.mjs` reads it for [`headline.json`](m5max-snapshot/headline.json) |
| `1-S-<backend>/` | The single-node session: `up.log`, `up-prime.json`, `up-settle.json`, `host.txt`, `log-digest.md`, the three `*-peak.md`, and a directory per sweep (`saturation.json`, `summary.md`, `saturate.log`, `settle.*`, `install.txt`, `host.txt`, `host-load.tsv`, `allowed-jvms.txt`, and on MongoDB `mongo-stats.tsv`), the steps past 4,096 clients in `<sweep>-top/` |
| `3-N-<backend>/` | The three-node session, likewise, with the fixed 1,024-client runs `e1-c1024/` and `e3-c1024/` |
| `0*-*.log`, `environment.txt` | Each script's output, and the window's record: the commit, the builds, the machine, the engine, the JDK and the allowed JVMs |
| [`scripts/`](m5max-snapshot/scripts/) | `run-all.sh`, `01-single-node.sh`, `02-three-nodes.sh`, `lib.sh`, `summarize.py`, `hostload.py`, `digest-logs.py`, and the last snapshot's `03-e1-hot-arms.sh`, `04-mongo-store-cost.sh`, `store-cost.py` and `arm-install.sh`, which this one did not run |
