# Axon Framework 4 comparison on GitHub Actions

This is the latest comparison of Wow 10 with Axon Framework 4 on GitHub Actions; decision D391 keeps one.

- **Arms**: Wow with no bus against Axon Framework 4, one node each, on one PostgreSQL.
- **Rules**: D392's fairness rules. Each session answers three questions:
  - same rate: for the same work, whose latency and cost are lower;
  - peak: how much each handles at most;
  - own share: with the same headroom, each one's latency and cost per command.
- **Suites**:
  - `axon-pg`: the main comparison;
  - `axon-pg-contention`: contended keys;
  - `axon-pg-segments`: Axon's processor at 16 segments against 2.
- **When and what**: measured on 2026-11-03 from 17:12 to 18:21 (UTC), at `3fd5349050` (`main`), in bench run [41681347500](https://github.com/Ahoo-Wang/Wow/actions/runs/41681347500).
- **Runtime**: both servers and the load generator ran on JDK 25 (D393).
- **History**: this is the first report of its kind. Earlier runs of these suites verified them on JDK 21 (#5363) and are not reports.
- **Raw results**: in [`actions-axon/`](actions-axon/): each suite's `suite.json` and `summary.md`, every run's results, and the run's combined [`report.md`](actions-axon/report.md).

## Setup

| | |
| --- | --- |
| Commit measured | `3fd53490508888f6cbdca9535b7c82b919fdf0b5` (`main`, with D393: the benchmark on JDK 25) |
| Run | `suites: axon`: `axon-pg`, `axon-pg-contention` and `axon-pg-segments`, each in a job of its own on a runner of its own |
| Runners | GitHub's `ubuntu-latest`, 4 cores and 16 GB each: `axon-pg` on an AMD EPYC 9V74, `axon-pg-contention` on an EPYC 9V45, `axon-pg-segments` on an EPYC 7763. Absolute numbers are their runner's; only ratios compare across suites |
| CPU layout | The load generator on CPUs 2–3; the node and PostgreSQL's container on 0–1; Kafka, which neither arm uses, idle on 2–3 |
| JVM | Temurin 25.0.5+8 for both servers and the load generator (each result's `meta`: `serverJvm`, `loadJvm`); `-Xms1g -Xmx1g -XX:+UseG1GC` |
| Store | PostgreSQL 17-alpine on the runner's disk ([`compose.yml`](../../harness/compose.yml)), a fresh one each session; HikariCP at Spring Boot's default, 10 connections, for both |
| Wow | The head's benchmark server (Spring Boot 4.1.2, WebFlux), `--wow.transport=none`, its defaults otherwise |
| Axon | See the next table for what it does per command. Axon Framework 4.13.2 (`axon-bom` 4.13.3) on Spring Boot 3.5.16, WebFlux, as its reference guide configures production without Axon Server, with these settings: |
| | • JDBC event storage engine and token store; Jackson |
| | • every account cached with strong references (131,072 entries) |
| | • a snapshot every 100 events, on two threads of its own |
| | • commands sent from 200 platform threads |
| | • the transfer's reactions in a tracking processor of 16 segments and threads, retried when they fail (`PropagatingErrorHandler`) |
| Data | 100,000 accounts, seeded at concurrency 128 and primed at each session's start |
| Sessions | One per arm and round, two rounds: W A A W (`axon-pg-segments`: axon-16, axon-2, axon-2, axon-16) |
| Same rate | E1 at 1,000/s and E3 at 300/s, in open loop, 20 s of warm-up and 40 s measured, before the sweeps |
| Peak | Closed-loop sweeps of 1, 4, 16, 64, 256, 1,024 and 4,096 clients, 10 s of warm-up and 30 s measured a step. The top step goes on, one step at a time, while it still gains more than 5%, up to 16,384. A sweep ends after a step that failed more than 1% |
| Own share | Each arm in open loop at 50% of the sustainable rate its own sweep found in the session, after the sweeps |
| Checks | Each session's store inspected once its node is quiet (correctness, storage); every run's anomalies; the load generator's cores at the peaks, with idle Kafka's added, flagged past 1.6 |

What each server does for a command, as the run's report states it (`AXON_PARITY` in `bench.mjs`):

| | Wow | Axon |
| --- | --- | --- |
| Event store | Its PostgreSQL log: a row per command, its events in one jsonb array, 64 partitions | The JDBC event storage engine: a row per event (domainevententry), the payload JSON in bytea |
| Unit of work | A command's events committed with one append; an aggregate's commands queued behind it committed with it (up to 32), and appends of several partitions merged into one statement | A unit of work and a Spring transaction per command, an INSERT per event; the transfer handler's command joins the processor's unit of work |
| Idempotency | Each command stored with its request id under a unique key (tenant, request id), a new id per request here | None: a command sent again is handled again |
| Aggregate cache | The state cache: strong references, a tenth of the heap in estimated bytes (about 105,000 accounts at 1 GB) | BoundedCache: strong references, 131,072 aggregates, the first put out first |
| Snapshots | The built-in snapshot subscription keeps every account's latest state, on its partition's owner, after the commit | Every 100 events of an account, on two snapshotter threads, after the commit |
| Concurrency of commands | Coroutines: one lane per aggregate in arrival order, a bound on the commands waiting in a lane and on the node's commands in flight (429 past them) | The command bus handles a command on its sender's thread, one of 200 platform threads (Tomcat's default), an aggregate locked while it is handled; past them commands queue, no bound |
| Transfer (E3) | TransferSaga, the built-in subscription of the account's state events, on the partition owners, from the log's tail | An event handler in a tracking processor of 16 segments and threads, its tokens in PostgreSQL; a reaction that fails is retried |
| Answer | The wait of the request (`Wow-Wait`): E1 `committed`, E3 the target's entry committed; 504 after 10 s | The command's result: E1 once its unit of work committed, E3 once the target's entry committed with the processor's unit of work; 504 after 10 s |
| Serialization | Jackson, events and snapshots as JSON (jsonb) | Jackson for every message and snapshot (JSON in bytea) |
| Metrics and tracing | None on the server's classpath | None: its monitors and spans are no-ops |
| HTTP | Spring Boot 4.1.2, WebFlux on Reactor Netty | Spring Boot 3.5.16, WebFlux on Reactor Netty: other Spring, Reactor and Netty versions, the one difference that stays (Axon Framework 4 runs on Spring Boot 3) |

## How to read the numbers

- **Peak**: the throughput of a sweep's best step that failed 1% of its commands or less.
  - It is a lower bound, written `≥`, at the sweep's top step or at the load generator's limit.
  - Two lower bounds do not compare.
- **Sustainable rate**: the throughput of the sweep's highest step with a p99 of 200 ms or less and no failure.
- **Ratios**: a ratio is written as a range, from the lowest Wow value over the highest Axon value to the highest over the lowest.
- **A/A spread**: each arm's spread between its own two sessions on the runner (the most over the least, less 1). The larger of the two arms' spreads is the band. A range within it is **level**; one beyond it names the arm that is higher.
- **Same-rate and own-share runs**: each is one run per session, so both values are given. They have no A/A threshold of their own, so a difference that the two sessions of one arm also show is not claimed.
- **Causes**: a cause is stated only where the evidence shows it; elsewhere the text says so.

## The comparison (`axon-pg`)

| Sweep | Wow | Axon | Wow/Axon | A/A spread | Verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| E1 peak | 3,971/s at 4,096; 3,824/s at 1,024 | 3,521/s at 256; 3,746/s at 4,096 | 1.02–1.13 | 6.4% | level |
| E3 peak | 1,549/s at 1,024; 1,413/s at 1,024 | 665/s at 16; 746/s at 256 | 1.89–2.33 | 12.2% | **Wow higher** |
| E1 sustainable | 3,014/s at 64; 3,360/s at 256 | 3,140/s at 64; 3,293/s at 64 | 0.92–1.07 | 11.5% | level |
| E3 sustainable | 737/s at 16; 627/s at 16 | 665/s at 16; 410/s at 4 | 0.94–1.80 | 61.9% | level |

- **E3**: Wow handles 1.9 to 2.3 times Axon's transfers at the peak, beyond the 12.2% A/A spread.
- **E1**: the two are level, at the peak and at the sustainable rate.
- **E3's sustainable rate**: level, but Axon's two sessions found 665/s and 410/s, a 61.9% spread, so this comparison says little.
- **The load generator** used at most 0.51 of its 2 CPUs at a peak, and idle Kafka at most 0.54 cores: 1.06 together, under the 1.6 line.

The same rate, both arms at one rate before the sweeps; two values per arm in session order:

| Scenario | Metric | Wow | Axon |
| --- | --- | ---: | ---: |
| E1, 1,000/s | p50 | 17.54 ms, 3.71 ms | 1.19 ms, 1.51 ms |
| | p99 | 364.5 ms, 376.8 ms | 130.6 ms, 344.1 ms |
| | Node CPU ms/op | 0.559, 0.600 | 0.669, 0.599 |
| | PostgreSQL CPU ms/op | 0.470, 0.512 | 0.694, 0.591 |
| | PostgreSQL transactions/op | 0.98, 1.26 | 2.34, 2.07 |
| | PostgreSQL WAL bytes/op | 1.3 KB, 1.3 KB | 660 B, 673 B |
| | Node alloc/op | 78.4 KB, 79.2 KB | 81.9 KB, 80.5 KB |
| E3, 300/s | p50 | 16.64 ms, 2.21 ms | 3.38 ms, 3.82 ms |
| | p99 | 448.5 ms, 121.3 ms | 177.2 ms, 536.6 ms |
| | Node CPU ms/op | 1.388, 1.977 | 1.955, 1.806 |
| | PostgreSQL CPU ms/op | 1.376, 1.748 | 2.340, 2.131 |
| | PostgreSQL transactions/op | 3.47, 5.60 | 7.12, 6.55 |
| | PostgreSQL WAL bytes/op | 4.4 KB, 4.4 KB | 3.1 KB, 3.1 KB |
| | Node alloc/op | 194.4 KB, 200.3 KB | 266.0 KB, 263.6 KB |

- **PostgreSQL's work per command**: Wow costs PostgreSQL less CPU and fewer transactions per command in both scenarios, but more WAL.
  - CPU and transactions in E3: 1.38–1.75 against 2.13–2.34 ms of CPU, 3.5–5.6 against 6.6–7.1 transactions.
  - WAL: 1.3 KB against 0.66 KB per E1 command, 4.4 KB against 3.1 KB per E3 command.
- **The node's CPU per command** overlaps between the two arms in E3. In E1, Wow's is 0.56–0.60 ms against Axon's 0.60–0.67.
- **Allocation per command**: E3's is a quarter lower on Wow, 194–200 KB against 264–266 KB. E1's is about the same.
- **Latency**: these runs decide no latency difference.
  - Wow's two sessions differ by 4.7 times in E1's p50 (17.54 and 3.71 ms) and by 7.5 times in E3's (16.64 and 2.21 ms).
  - The anomaly check marks most same-rate runs of both arms unstable (their halves differ), or with a p99 over 10 times their p50 ([`report.md`](actions-axon/report.md#anomalies)).
  - Axon's p50 was lower in E1 in both sessions.

Own share, each arm at half of its own sustainable rate:

| Scenario | Metric | Wow | Axon |
| --- | --- | ---: | ---: |
| E1 | Rate | 1,507/s, 1,680/s | 1,570/s, 1,647/s |
| | p50; p99 | 7.65 ms, 85.50 ms; 276.5 ms, 729.1 ms | 1.53 ms, 2.00 ms; 108.5 ms, 557.1 ms |
| | Node CPU ms/op | 0.495, 0.384 | 0.520, 0.476 |
| E3 | Rate | 369/s, 313/s | 332/s, 205/s |
| | p50; p99 | 24.19 ms, 5.05 ms; 446.5 ms, 276.5 ms | 4.03 ms, 3.10 ms; 235.5 ms, 152.6 ms |
| | Node CPU ms/op | 1.295, 1.684 | 1.658, 2.257 |
| | Node alloc/op | 196.0 KB, 199.7 KB | 262.6 KB, 275.0 KB |

These rates follow each arm's own sweep, so they differ between the arms, and Axon's E3 rates (332/s and 205/s) inherit its sweeps' 61.9% spread. As at the same rate, Wow's latencies are the less stable, and they decide nothing. E3's allocation per command is again a quarter lower on Wow.

**Correctness**: every session's store held exactly what its runs asked of it.
- Every account was opened.
- The deposits and transfers stored are within the range of those sent.
- Every transfer was locked, entered and confirmed, and none was refused.

**Storage and startup**:
- **Storage**: Wow stored 626 bytes per event against Axon's 477–482. Wow also keeps every account's snapshot (100,000, 42 MB); no Axon account reached 100 events in this suite.
- **Startup**: Axon answered 3.8–4.0 s after its launch, with 286–289 MB resident; Wow took 7.1–10.8 s, with 401–408 MB. After the runs, both held 1.31–1.34 GB.

## Contention (`axon-pg-contention`)

| Sweep | Wow | Axon | Wow/Axon | A/A spread | Verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| E1 Zipf peak | ≥ 5,175/s at 4,096; ≥ 5,008/s at 4,096 | 5,064/s at 1,024; ≥ 4,893/s at 4,096 | ~ 0.99–1.06 | 3.5% | undetermined (lower bounds) |
| E3 Zipf peak | 1,207/s at 64; 1,216/s at 64 | 1,188/s at 16; 1,151/s at 64 | 1.02–1.06 | 3.2% | level |
| E1 hot peak | 5,196/s at 256; 4,693/s at 1,024 | 3,019/s at 256; 3,162/s at 256 | 1.48–1.72 | 10.7% | **Wow higher** |
| E1 Zipf sustainable | 4,861/s at 256; 4,945/s at 256 | 4,492/s at 64; 4,247/s at 64 | 1.08–1.16 | 5.8% | **Wow higher** |
| E3 Zipf sustainable | 1,178/s at 16; 1,133/s at 16 | 1,188/s at 16; 1,130/s at 16 | 0.95–1.04 | 5.2% | level |
| E1 hot sustainable | 5,196/s at 256; 4,664/s at 256 | 2,896/s at 64; 3,123/s at 64 | 1.49–1.79 | 11.4% | **Wow higher** |

- **E1 on the harness's hot keys**: Wow handles 1.5 to 1.8 times Axon's commands, beyond the A/A spread, at the peak and at the sustainable rate.
- **E1 with Zipf keys**:
  - Wow's sustainable rate is 1.08–1.16 times Axon's.
  - The peaks are lower bounds on both sides and do not compare.
- **E3 with Zipf keys**: level.
- **Axon's snapshots and cache**:
  - Its snapshots ran on its two snapshotter threads, 18,752 and 19,303 a session.
  - Its cache answered 2.69 and 2.74 million loads, with no miss in one session and one in the other (below).
- **Correctness**: exact in every session.

## Axon's segments (`axon-pg-segments`)

| Sweep | axon-16 | axon-2 | 16/2 | A/A spread | Verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| E1 peak | ≥ 3,089/s at 4,096; 3,040/s at 1,024 | 2,994/s at 256; 3,071/s at 1,024 | ≥ 0.99–1.03 | 2.6% | undetermined (a lower bound) |
| E3 peak | 798/s at 256; 797/s at 64 | 680/s at 4,096; 688/s at 4,096 | 1.16–1.17 | 1.2% | **16 higher** |
| E1 sustainable | 2,843/s at 64; 2,781/s at 64 | 2,827/s at 64; 2,949/s at 64 | 0.94–1.01 | 4.3% | level |
| E3 sustainable | 760/s at 16; 751/s at 16 | 617/s at 16; 567/s at 16 | 1.22–1.34 | 8.8% | **16 higher** |

- **E3's throughput**: Axon's tracking processor reaches 16–17% more at the peak with 16 segments than with 2, and 22–34% more at the sustainable rate, beyond the A/A spreads. The README gives the reason it is sized so: each transfer reaction waits on a PostgreSQL round trip.
- **The cost at E3's 300/s**: 2 segments cost less per command: 1.87 against 2.40–2.43 ms of the node's CPU, and 196 against 263 KB allocated.
- **Why the comparison uses 16**: under D392 Axon runs at its best, so the comparison runs it at 16 and gives the cheaper figure here beside it.
- **Against the earlier run**: on JDK 21 the same suite found 1.12–1.17 at E3's peak (run [41619207524](https://github.com/Ahoo-Wang/Wow/actions/runs/41619207524)). Those numbers are given for reference only; that run was on another runner and another JDK.

## Anomalies and what the logs show

- **The anomaly check** marks most same-rate and own-share runs of both arms in `axon-pg`: unstable, or a p99 over 10 times the p50. The list is in [`report.md`](actions-axon/report.md#anomalies).
- **No stall**: no Axon log has a handler timeout, a retry of a reaction, or a virtual thread pinned to its carrier. Axon's command threads are platform threads.
- **Wow's throttling**: each Wow session of `axon-pg` logged its transfer saga's subscription throttling partitions while their downstream pushed back, 10,003 and 11,379 warnings (`PartitionThrottle`).
  - They all fall within one 40 s stretch a session, the length of a sweep step.
  - That stretch lines up with the E3 sweep's 4,096-client step, which answered 94,366–98,284 commands with 429 and failed the sweep.
  - This is the overload the sweep ends on, not a measured step.
- **One Axon error**: one Axon session of `axon-pg-contention` logged one error ([`node-1.log`](actions-axon/axon-pg-contention.100k/1-axon-up/node-1.log), 17:29:49).
  - The thread of Axon's embedded event store failed to read events. HikariCP threw an `ArrayIndexOutOfBoundsException` while Axon's `JdbcUtils.closeQuietly` closed a statement. That exception is raised when the statement is no longer tracked by its connection, so it replaced whatever ended the query; the first failure is not in the log.
  - It happened during the session's untimed prime, while 60–78 threads waited for one of the 10 connections.
  - In the same seconds, one prime deposit failed, and Axon's cache dropped one account. Axon evicts an aggregate when its unit of work rolls back, which is the one miss above.
  - The embedded store went on reading. The session's correctness check matched exactly, and its measured sweeps followed the prime.
  - The cause is not established. It did not appear in the other three Axon sessions of this run, nor in the 13 Axon sessions of the JDK 21 runs.

## Against the earlier runs

| | `axon-pg`, JDK 21 (run [41642375633](https://github.com/Ahoo-Wang/Wow/actions/runs/41642375633), `331f3dbef9`, EPYC 7763) | This run, JDK 25 (EPYC 9V74) |
| --- | --- | --- |
| E1 peak, Wow/Axon | ≥ 1.04–1.10 | 1.02–1.13 |
| E3 peak, Wow/Axon | 1.55–1.78 | 1.89–2.33 |
| E3 at 300/s, node alloc/op | 198–199 KB against 263–264 KB | 194–200 KB against 264–266 KB |

These are given without a conclusion. The runner, the JDK and the code all changed between the two: 25 commits (`git rev-list --count 331f3dbef9..3fd5349050`), among them every record now carrying the aggregate's access attributes in place of tags (#5381), and a snapshot index for each access attribute (#5382). The JDK's own share is what D393's calibration measures: on PostgreSQL, its first closed-loop samples put JDK 25 below JDK 21, which remains open (#5391). It does not bear on this comparison, which runs both arms on JDK 25.

## Reproduce

```bash
gh workflow run bench.yml --ref main -f head_ref=3fd53490508888f6cbdca9535b7c82b919fdf0b5 -f suites=axon
```

The run's report is the `e2e-bench-report` artifact. Each suite's results are the `bench-result-<suite>.100k` artifacts, kept here without the node logs except the one above.
