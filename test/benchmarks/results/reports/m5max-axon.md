# Axon Framework 4 comparison on Apple M5 Max

This is the latest comparison of Wow 10 with Axon Framework 4 on the local Apple M5 Max; decision D391 keeps one.

- **Arms**: Wow with no bus against Axon Framework 4, one node each, on one PostgreSQL.
- **Rules**: D392's fairness rules, through the sessions suites of [`bench.mjs`](../../../../.github/scripts/bench.mjs) that the [Actions report](actions-axon.md) runs, with this machine's settings. Each part answers one or more of three questions:
  - same rate: for the same work, whose latency and cost are lower;
  - peak: how much each handles at most;
  - own share: with the same headroom, each one's latency and cost per command.
- **Suites**:
  - `m5max-axon-peak`: E1 and E3 to their peaks, then each arm at half of its own sustainable rate;
  - `m5max-axon-same-rate`: E1 and E3 at one rate for both;
  - `m5max-axon-pool-32`: both connection pools at 32 instead of Spring Boot's 10;
  - `m5max-axon-contention`: Zipf keys and hot keys.
- **When and what**: measured on 2026-11-04 and 05, from 21:18 to 00:09 (PST), at `cc11b16aa8` (`main`).
- **Runtime**: both servers and the load generator ran on JDK 25 (D393).
- **History**: this is the first report of its kind on this machine. The first comparison round on the M5 Max ran on JDK 21 and left the tree (D391).
- **Raw results**: in [`m5max-axon/`](m5max-axon/): each part's `suite.json` and `summary.md`, every run's results, the run's combined [`report.md`](m5max-axon/report.md), and the host around each measurement ([`window.md`](m5max-axon/window.md)).

## Setup

| | |
| --- | --- |
| Commit measured | `cc11b16aa893b58f3a450c9ba62eadde16741e45` (`main`; the run records it with no changed paths, `environment.txt`) |
| Machine | Apple M5 Max (`Mac17,14`), 18 cores (6 Super + 12 Performance), 64 GB, macOS 27.2 (26B123); OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB |
| CPU layout | None: macOS has no `taskset`. The load generator, the node and OrbStack's VM, which runs PostgreSQL, see every core. No Kafka ran, as neither arm uses one (`--no-bus`) |
| JVM | Temurin 25.0.5+8 for both servers and the load generator; `-Xms1g -Xmx1g -XX:+UseG1GC` |
| Store | PostgreSQL 17-alpine on the engine's disk ([`compose.yml`](../../harness/compose.yml)), a fresh one each session; HikariCP at Spring Boot's default, 10 connections, for both, except in `m5max-axon-pool-32` |
| Wow | The head's benchmark server (Spring Boot 4.1.2, WebFlux), `--wow.transport=none`, its defaults otherwise |
| Axon | Axon Framework 4.13.2 (`axon-bom` 4.13.3) on Spring Boot 3.5.16, WebFlux, as its reference guide configures production without Axon Server: JDBC event storage engine and token store, Jackson; every account cached with strong references (131,072 entries); a snapshot every 100 events, on two threads of its own; commands sent from 200 platform threads; the transfer's reactions in a tracking processor of 16 segments and threads, retried when they fail (`PropagatingErrorHandler`). Why Axon Framework 4 and not 5: [the benchmark README](../../README.md#the-industry-comparison) |
| Data | 100,000 accounts, seeded at concurrency 128 and primed at each session's start |
| Parts | As on Actions, each sweep runs in sessions of its own, W A A W (one session per arm and round, two rounds, the arms in turn), so that no sweep runs behind the backlog an earlier one left. A part's same-rate and own-share runs share its sessions |
| Same rate | E1 at 3,850/s and E3 at 1,150/s, in open loop, 20 s of warm-up and 40 s measured: half of the lower arm's peak measured here (D392 ①), Axon's 7,721/s and 2,320/s, the means of its two sessions in `m5max-axon-peak` ([`rates.json`](m5max-axon/rates.json)) |
| Peak | Closed-loop sweeps from 1 client, doubling a step (D392 ②: a step between the last clean one and the first failed one) to 4,096, then on while the top step still gains more than 5%, up to 12,288: the most the client's 16,384 ephemeral ports to the node's one port allow, with 4,096 kept for the connections still closing. Located with 8 s steps after 3 s of warm-up, then the steps around the peak and the sustainable rate measured at full length, 30 s after 10 s. A sweep ends after a step that failed more than 1% |
| Own share | Each arm in open loop at 50% of the sustainable rate its own sweep found in the session, after the sweeps |
| Checks | Each session's store inspected once its node is quiet (correctness, storage); every run's anomalies; the host sampled every 10 s for the whole run (`host-load.tsv`), and each measurement judged by the samples of its window (`window.py`, D392 ⑧) |

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

The machine is shared with other projects' sessions; the coordinating session paused them for the window (CoSec and CoCache). Two idle JVMs were allowed by their pids (`ALLOW_JVM_PIDS`, `environment.txt`): a Gradle 9.8.2 daemon (1529) and a Kotlin compile daemon (67782), which exited before the run began. Neither used 5% of a core in any sample, and the daemon's log records no command ([`allowed-jvms.txt`](m5max-axon/allowed-jvms.txt)). No other JVM ran. None of the 81 measurements was void or disturbed: the other processes averaged at most 0.24 of a core over one, and the busiest other container (`cocache-redis`, idle) used at most 0.01 of a core.

## How to read the numbers

- **Peak**: the throughput of a sweep's best step that failed 1% of its commands or less.
  - It is a lower bound, written `≥`, at the sweep's top step or at the load generator's limit.
  - Two lower bounds do not compare.
- **Sustainable rate**: the throughput of the sweep's highest step with a p99 of 200 ms or less and no failure.
- **Ratios**: a ratio is written as a range, from the lowest Wow value over the highest Axon value to the highest over the lowest.
- **A/A spread**: each arm's spread between its own two sessions (the most over the least, less 1). The larger of the two arms' spreads is the band. A range within it is **level**; one beyond it names the arm that is higher.
- **Same-rate and own-share runs**: each is one run per session, so both values are given. They have no A/A threshold of their own, so a difference that the two sessions of one arm also show is not claimed.
- **Correctness**: a session whose store does not hold what its runs asked of it makes its arm **not comparable** in that suite (D392 ④).
- **Causes**: a cause is stated only where the evidence shows it; elsewhere the text says so.

## The comparison (`m5max-axon-peak`, `m5max-axon-same-rate`)

| Sweep | Wow | Axon | Wow/Axon | A/A spread | Verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| E1 peak | 37,311/s at 2,048; 37,344/s at 2,048 | 7,725/s at 256; 7,716/s at 256 | 4.83–4.84 | 0.1% | **Wow higher** |
| E3 peak | 12,404/s at 2,048; 12,042/s at 2,048 | 2,314/s at 64; 2,326/s at 128 | 5.18–5.36 | 3.0% | **Wow higher** |
| E1 sustainable | 37,311/s at 2,048 (p99 99.3 ms); 37,344/s at 2,048 (p99 105.5 ms) | 7,725/s at 256 (p99 126.5 ms); 7,716/s at 256 (p99 126.5 ms) | 4.83–4.84 | 0.1% | **Wow higher** |
| E3 sustainable | 11,655/s at 1,024 (p99 149.5 ms); 11,668/s at 1,024 (p99 140.3 ms) | 2,314/s at 64 (p99 100.4 ms); 2,320/s at 32 (p99 38.9 ms) | 5.02–5.04 | 0.2% | **Wow higher** |

- **E1**: Wow handles 4.8 times Axon's deposits at the peak and at the sustainable rate, beyond a 0.1% A/A spread.
- **E3**: Wow handles 5.2 to 5.4 times Axon's transfers at the peak, and 5.0 times at the sustainable rate.
- **Neither peak is a lower bound**: every sweep went on past its peak and fell or failed there. Axon's fell past 256 clients (E1) and 64 to 128 (E3). Wow's E1 fell past 2,048, and its E3 failed more than 1% at 4,096.
- **Axon's connections**: with both pools at 32 (below), Axon's peaks are 1.9 times (E1) and 1.6 times (E3) these, and Wow's move by at most 4%. Inferred: on this machine, Spring Boot's 10 connections bound Axon and not Wow.
- **The load generator** used at most 3.10 of its 18 CPUs at a peak (the report's "more than 1.6" is Actions' line, for its two load CPUs). Its busiest thread averaged at most 0.62 of a core over a sweep, under the 0.9 at which a step is generator-bound.

The same rate, both arms at one rate in sessions of their own; two values per arm in session order:

| Scenario | Metric | Wow | Axon |
| --- | --- | ---: | ---: |
| E1, 3,850/s | p50 | 1.74 ms, 1.76 ms | 1.14 ms, 1.25 ms |
| | p99 | 4.9 ms, 5.0 ms | 32.6 ms, 909.3 ms |
| | Failed | 0, 0 | 0, 7,718 (generator-overload) |
| | Node CPU ms/op | 0.385, 0.386 | 0.308, 0.306 |
| | PostgreSQL CPU ms/op | 0.185, 0.186 | 0.182, 0.170 |
| | PostgreSQL transactions/op | 1.38, 1.37 | 1.74, 1.52 |
| | PostgreSQL WAL bytes/op | 1.3 KB, 1.3 KB | 665 B, 636 B |
| | Node alloc/op | 90.9 KB, 91.2 KB | 79.0 KB, 74.5 KB |
| E3, 1,150/s | p50 | 3.34 ms, 3.36 ms | 2.85 ms, 2.85 ms |
| | p99 | 7.6 ms, 8.5 ms | 29.7 ms, 37.1 ms |
| | Failed | 0, 0 | 0, 0 |
| | Node CPU ms/op | 1.152, 1.154 | 0.854, 0.855 |
| | PostgreSQL CPU ms/op | 0.577, 0.581 | 0.666, 0.666 |
| | PostgreSQL transactions/op | 4.47, 4.43 | 5.54, 5.57 |
| | PostgreSQL WAL bytes/op | 4.3 KB, 4.3 KB | 3.0 KB, 3.1 KB |
| | Node alloc/op | 225.7 KB, 225.4 KB | 257.4 KB, 257.7 KB |

- **Latency**: Axon's p50 is lower (E1 1.1–1.3 ms against Wow's 1.7–1.8 ms, E3 2.9 against 3.3–3.4 ms) and its p99 higher (E1 33 ms, E3 30–37 ms, against Wow's 5–9 ms).
  - Axon's second E1 session stalled: p99 909.3 ms, its throughput 11.1% higher in its second half than its first (the run's anomalies). 7,718 commands (5.0%) failed there, all `generator-overload`: the load generator holds at most 2,000 commands in flight to a target, and counts a command past them as failed. So Axon did not keep up in that run. The cause of the stall is not known from this run.
- **Cost per command**:
  - The node's CPU is higher for Wow (E1 0.385 against 0.306–0.308 ms, E3 1.15 against 0.85 ms).
  - PostgreSQL's CPU is level on E1 (0.185 against 0.170–0.182 ms) and lower for Wow on E3 (0.58 against 0.67 ms).
  - Wow commits fewer transactions per command (E1 1.37–1.38 against 1.52–1.74; E3 4.43–4.47 against 5.54–5.57) and writes about twice the WAL (E1 1.3 KB against 636–665 B; E3 4.3 against 3.0–3.1 KB).
  - Wow allocates more on E1 (91 against 75–79 KB) and less on E3 (225 against 257 KB).
  - At its own share below, 18,600/s, Wow's node spent 0.21 ms of CPU and PostgreSQL 0.10 ms per E1 command, half of what they spent at 3,850/s: its appends batch more commands as the rate grows. Axon's cost per command is the same at both rates.

Own share, each arm at half of its own sustainable rate, after the sweeps:

| Scenario | Metric | Wow | Axon |
| --- | --- | ---: | ---: |
| E1 | Rate | 18,656/s, 18,672/s | 3,862/s, 3,858/s |
| | Kept up | yes, yes | yes, yes |
| | Failed | 3,887, 2,881 (generator-overload) | 0, 0 |
| | p50 | 16.06 ms, 16.51 ms | 1.18 ms, 1.22 ms |
| | p99 | 77.3 ms, 68.6 ms | 64.3 ms, 9.3 ms |
| | Node CPU ms/op | 0.207, 0.206 | 0.286, 0.301 |
| | PostgreSQL CPU ms/op | 0.096, 0.096 | 0.188, 0.188 |
| | PostgreSQL transactions/op | 0.15, 0.16 | 1.75, 1.74 |
| E3 | Rate | 5,827/s, 5,834/s | 1,157/s, 1,160/s |
| | Kept up | **no**, **no** | **no**, yes |
| | Failed | 2,985, 3,631 (generator-overload) | 2,942 (generator-overload), 0 |
| | p50 | 69.12 ms, 68.61 ms | 3.95 ms, 2.99 ms |
| | p99 | 454.7 ms, 434.2 ms | 7,340.0 ms, 38.7 ms |
| | Node CPU ms/op | 0.392, 0.386 | 0.861, 0.927 |
| | PostgreSQL CPU ms/op | 0.254, 0.251 | 0.623, 0.681 |

- **E1**: both kept up. Wow's p50 is 16 ms at its 18,600/s against Axon's 1.2 ms at its 3,860/s. Wow's two runs drove the load generator's busiest thread to 0.73 and 0.74 of a core on average (0.94 and 0.92 at their highest samples): close to the bound of its one selector thread, so part of Wow's latency there may be the client's. Its failures (0.6% and 0.5%) are the load generator's cap of 2,000 in flight.
- **E3**: half of the closed loop's sustainable rate was more than Wow kept up with in open loop. Its two runs failed 1.3% and 1.6%, all at the load generator's cap, with a p50 of 69 ms. One of Axon's two runs stalled (p99 7.3 s, 2,942 failed at the cap) and the other kept up.
- None of the failures in these runs is an answer from a server.

### Correctness, storage and startup

Every session's store held what its runs asked of it in these suites and in `m5max-axon-pool-32` ([`report.md`](m5max-axon/report.md)): the accounts opened, the deposits within the range between those that reached their completion point and those sent, and every transfer locked, entered and confirmed, with no transfer refused. In `m5max-axon-contention` one of Wow's sessions did not (below).

| | Wow | Axon |
| --- | ---: | ---: |
| Bytes per event, E1 sessions (indexes and TOAST included) | 615 | 462 |
| Bytes per event, E3 sessions | 600 | 500 |
| Snapshots | every account's (100,000), 502–553 bytes each | one per 100 events of an account: none on E1, 46–51 on E3 |
| Ready after launch | 1.8 s | 1.1–1.2 s |
| RSS when ready | 461–487 MB | 367–397 MB |
| RSS after a session's runs | 1.50 GB | 1.58–1.61 GB |

Wow's log holds a row per command with its events in one jsonb array, and its request id under a unique key (idempotency, which Axon does not do); Axon's a row per event.

## Both pools at 32 connections (`m5max-axon-pool-32`)

Spring Boot's default pool has 10 connections, which Axon's 16 processor threads and 200 command threads share. Here both servers have 32.

| Sweep | Wow | Axon | Wow/Axon | A/A spread | Verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| E1 peak | 37,065/s at 2,048; 37,554/s at 2,048 | 14,538/s at 512; 14,771/s at 512 | 2.51–2.58 | 1.6% | **Wow higher** |
| E3 peak | 12,386/s at 2,048; 12,510/s at 2,048 | 3,742/s at 128; 3,718/s at 256 | 3.31–3.36 | 1.0% | **Wow higher** |
| E1 sustainable | 37,065/s at 2,048; 37,554/s at 2,048 | 14,538/s at 512; 14,771/s at 512 | 2.51–2.58 | 1.6% | **Wow higher** |
| E3 sustainable | 11,572/s at 1,024; 11,713/s at 1,024 | 3,684/s at 64; 3,698/s at 64 | 3.13–3.18 | 1.2% | **Wow higher** |

- Axon's peaks with 32 connections are 1.9 times (E1) and 1.6 times (E3) its peaks with 10. Wow's are within 1% (E1) and 4% (E3) of its peaks with 10.
- Every session's store check passed.
- The main comparison keeps Spring Boot's default, as each server is deployed by default. This table is the same comparison with the pool that Axon's peak needs on 18 cores.

## Contention (`m5max-axon-contention`)

**Not comparable: Wow.** In one of its two E3 Zipf sessions, the store held 2,443 transfers locked but neither entered nor refused, and 3,639 entries not confirmed, once the node was quiet. By D392 ④ that makes Wow's arm not comparable in this suite. Its E1 Zipf and E1 hot sessions, and its other E3 Zipf session, passed their checks.

Why that session failed, from its node log ([`node-1.oom.txt`](m5max-axon/m5max-axon-contention-e3-zipf/1-wow-up/node-1.oom.txt), an excerpt; the node log is not committed):

- The node ran out of heap (`OutOfMemoryError: Java heap space`, 1 GB, 22 lines), first at 23:25:19. That was 84 s after the sweep's located step at 8,192 clients, as its full-length step at 4,096 clients began (after those at 32 and 64).
- Eleven times TransferSaga's subscription met the error as fatal while decoding an account's events to evolve its state, and stopped that lane: `TransferSaga.onPrepared met a fatal error handling transfer.account/(0)/acc-0000097@1396; its lane stops`. Those lanes' transfers stayed locked.
- The other Wow E3 Zipf session ran the same steps, 8,192 clients included, without running out of memory, and its store matched.
- After `bench.sh down` asked it to stop (23:26:39), the node went on logging until 23:27:50: failed reads of a store already removed. That overlapped the next session's seeding and priming, which are not timed; its first measurement began at 23:27:51.

The numbers, without a verdict:

| Sweep | Wow | Axon |
| --- | ---: | ---: |
| E1 Zipf peak | 23,812/s at 2,048; ≥ 23,352/s at 2,048 (top step) | 6,142/s at 512; 6,220/s at 256 |
| E3 Zipf peak | 2,447/s at 64; 2,562/s at 32 | 1,785/s at 32; 1,769/s at 64 |
| E1 hot peak | ≥ 23,943/s at 2,048 (top step); ≥ 23,634/s at 2,048 (top step) | 2,654/s at 256; 2,633/s at 256 |
| E1 Zipf sustainable | 19,837/s at 512; 18,781/s at 512 | 6,142/s at 512; 6,220/s at 256 |
| E3 Zipf sustainable | 2,447/s at 64; 2,562/s at 32 | 1,785/s at 32; 1,769/s at 64 |
| E1 hot sustainable | 14,274/s at 128; 13,618/s at 256 | 2,654/s at 256; 2,633/s at 256 |

## Anomalies and what the logs show

- **Wow ran out of heap under Zipf E3** (above). The saga's subscription treats the error as fatal and stops the lane, so those transfers stay locked. One of two sessions; not seen with uniform keys.
- **A sweep left a backlog for the next.** A first run of these suites ran each suite's sweeps one after another in the same sessions, unlike Actions, and is not reported; its raw results stay out of the tree. Two of its sweeps measured the backlog of the one before:
  - with 32 connections, Axon's E3 after its E1 sweep at about 14,000/s answered nothing: its tracking processor was still reading that sweep's deposits, and the store held 1 transfer entered of 7 locked;
  - Wow's E3 with Zipf keys after its E1 Zipf sweep answered about 20/s, and its store had 508 transfers not yet entered when inspected.

  Inferred: both servers' reactions read their stream in order, and a deep backlog of earlier events delays every later reaction. The parts above run each sweep in fresh sessions, as on Actions.
- **The load generator's cap of 2,000 in flight** produced every open-loop failure: in Wow's E1 and E3 own-share runs, in Axon's E3 own-share run, and in Axon's second E1 same-rate run. Each time, a server's latency kept commands in flight longer, past what the load generator holds.

## Against the Actions report

Different machines, so only the ratios are side by side; single runs on each, no conclusion drawn ([`actions-axon.md`](actions-axon.md), 4-core runners, `3fd5349050`):

| Ratio, Wow/Axon | Actions (2 server CPUs) | M5 Max (18 cores) |
| --- | ---: | ---: |
| E1 peak | 1.02–1.13 (level) | 4.83–4.84 |
| E3 peak | 1.89–2.33 | 5.18–5.36 |
| E1 sustainable | 0.92–1.07 (level) | 4.83–4.84 |
| E3 sustainable | 0.94–1.80 (level) | 5.02–5.04 |

## Limits

- One machine, two sessions per arm and part; the A/A spread rests on those two.
- No CPU sets: the load generator, the node and PostgreSQL share the 18 cores, so a ratio here includes how each server uses a machine whose other work is the load generator's.
- One node each; no multi-node, MongoDB, reads, rejections or long histories.
- Axon at its documented production settings with the default pool; a tuned Axon (`DisruptorCommandBus`, batching, thread counts, D392 ⑨) is not measured here.
- Wow's default configuration, publishing to Kafka, is not measured here (D392 ⑥).
- The window's rules came from the snapshot (`lib.sh`, `summarize.py`); each measurement is judged over its whole window, not step by step.

## Reproduce

From a clean checkout at `cc11b16aa8` on this machine, with JDK 25 installed, the builds of `bench.sh build` and the Axon server's (`./gradlew -p test/benchmarks/axon-server test installDist`), and no other JVM running:

```bash
test/benchmarks/results/reports/m5max-axon/scripts/run-all.sh
```

An idle JVM that cannot be stopped is allowed by its pid, `ALLOW_JVM_PIDS="<pid>..."`. The script waits until the machine is quiet, runs [`run.mjs`](m5max-axon/scripts/run.mjs) (which adds this machine's suites to `bench.mjs` and runs them part by part), and judges each measurement by the host's samples (`window.py`). The raw output goes to `test/benchmarks/results/e2e/m5max-axon/` (ignored by git). `node run.mjs report <that directory>` writes `report.md` again.

| Path | What |
| --- | --- |
| [`report.md`](m5max-axon/report.md) | The combined report `bench.mjs` renders: anomalies, every part's views, correctness, storage and startup |
| [`window.md`](m5max-axon/window.md) | Each measurement's window: the other processes, the containers, the allowed JVMs, the verdict |
| [`rates.json`](m5max-axon/rates.json) | The same rate and the peaks it came from |
| `m5max-axon-<suite>-<part>/` | A part's `suite.json` and `summary.md`, and a directory per run (`result.json` or `saturation.json`, `settle.json`; the sessions' `prime.json`, `startup.json`, `inspect.json`) |
| `environment.txt`, `host.txt`, `host-load.tsv`, `allowed-jvms.txt` | The machine, the host before and after, the samples, the allowed JVMs' records |
| [`scripts/`](m5max-axon/scripts/) | `run-all.sh`, `run.mjs`, `window.py`; the host helpers are the snapshot's ([`lib.sh`](m5max-snapshot/scripts/lib.sh), [`summarize.py`](m5max-snapshot/scripts/summarize.py)) |
