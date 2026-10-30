# V10 against Axon Framework on PostgreSQL

The industry comparison before GA (user 2026-10-25): Wow against Axon Framework 4, the most used event-sourcing framework on the JVM, on the same machine, the same PostgreSQL, the same domain and the same load generator. Measured on the local machine of the [local baseline](v10-local-baseline.md) (Apple M4, 10 cores, Docker Desktop, the store on disk) on 2026-10-25 at `be2ccecc1`; the scripts and the runs are in [`v10-vs-axon/`](v10-vs-axon/).

## The two servers

| | Wow | Axon |
| --- | --- | --- |
| Version | 10.0.0-alpha.4 at `be2ccecc1`, Spring Boot 4 | Axon Framework 4.13.2, Spring Boot 3.5.16 |
| Domain | `test/benchmarks/server` | [`test/benchmarks/axon-server`](../../axon-server/): the same account and transfer rules, the same HTTP routes for E1 and E3 |
| Event store | Wow's PostgreSQL log, 64 partitions | Axon's JDBC event storage engine (`domainevententry`), no Axon Server |
| Aggregates | Wow's state cache and snapshots (defaults) | Axon's production configuration: a `WeakReferenceCache`, a snapshot every 100 events, Jackson for every message and snapshot |
| Transfer saga (E3) | `TransferSaga`, a built-in subscription | An event handler in a tracking processor of 16 segments, 16 threads, its tokens in PostgreSQL; stateless, as Wow's saga is (an Axon saga would store an instance per transfer) |
| Commands | Wow's executor | Axon's command bus, each command sent from a virtual thread, never from Netty's event loop |
| HTTP | Spring WebFlux on Reactor Netty | the same |
| Connections | HikariCP, Spring Boot's default 10 | the same; a 32-connection run below |
| Bus | W: Kafka off (`--wow.kafka.bootstrap-servers=`); K: the state stream published to Kafka, as the other reports run | none |

Both servers ran with `-Xms1g -Xmx1g -XX:+UseG1GC` on JDK 21. The completion points are the same: E1 answers once the deposit's events are committed; E3 once the transfer's entry is committed on the target, after the saga (Wow) or the tracking processor (Axon) handled `Prepared`.

**Not the pooled streaming processor.** Axon recommends its pooled streaming processor since 4.5, so it was the first choice. On an event store without Axon Server, the embedded store over JDBC, its event stream has no availability callback, and the processor's coordinator then polls for new events every 500 ms (`Coordinator`, "no availability callback"). E3 at 300/s answered at a p50 of 303–319 ms with 1, 4 or 16 threads, against 2.1–2.2 ms with a tracking processor, which waits on the embedded store's notice of each local append. The comparison uses the tracking processor; with Axon Server the pooled processor would not poll.

## Sessions

Fresh sessions W A A W on PostgreSQL, one node, 100,000 accounts seeded and primed, then K K: E1 at 2,500/s and E3 at 700/s (20 s warm-up, 40 s measured), then closed-loop sweeps of E1 and E3 to 1,024 clients (growth 4, 30 s a step). [`compare.sh`](v10-vs-axon/compare.sh) runs them; [`summarize.py`](v10-vs-axon/summarize.py) gives the tables below from [`sessions/`](v10-vs-axon/sessions/). Kafka runs, idle, in every session.

## Peak throughput (closed loop)

| Clients | Wow, Kafka off (W) | Axon (A) | Wow, Kafka on (K) |
| --- | --- | --- | --- |
| E1, 1 | 2,076, 2,027/s | 979, 1,004/s | 2,016, 1,926/s |
| E1, 16 | 6,132, 6,268/s | 3,395, 3,412/s | 5,382, 5,591/s |
| E1, 64 | 10,956, 11,137/s | 3,524, 3,444/s | 9,031, 9,247/s |
| E1, 1,024 | **14,656, 15,648/s** | 3,418, 3,505/s | 12,253, 12,076/s |
| E3, 1 | 835, 858/s | 330, 360/s | 752, 754/s |
| E3, 16 | 2,515, 2,501/s | 1,117, 1,129/s | 2,136, 2,181/s |
| E3, 64 | 3,450, 3,636/s | 1,132, 1,122/s | 2,947, 3,021/s |
| E3, 1,024 | **5,613, 5,795/s** | 916, 926/s | 4,560, 4,666/s |

- **E1:** Axon levels off at about 3,500/s from 16 clients on; Wow keeps scaling, to 4.2–4.5 times Axon's peak with Kafka off and 3.4–3.5 times with Kafka on.
- **E3:** Axon peaks at about 1,130/s at 16–64 clients and falls as the backlog grows (p99 5.7–6.3 s at 1,024); Wow reaches about five times that peak with Kafka off.
- **One client:** Wow does twice Axon's E1 and 2.4–2.5 times its E3, so the gap is in the cost of each command, not only in concurrency.
- **Kafka's cost to Wow:** 16–23% of the peak, 0.07–0.13 ms of node CPU per E1 command at 2,500/s.

## Fixed rates

| | Wow, Kafka off | Axon | Wow, Kafka on |
| --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 0.55, 0.50 ms | 2.06, 2.06 ms | 0.53, 0.67 ms |
| E1: p99 | 69.1 (first session), 2.8 ms | 20.4, 10.9 ms | 4.4, 5.1 ms |
| E1: node CPU per op | 0.406, 0.395 ms | 0.545, 0.516 ms | 0.477, 0.527 ms |
| E1: PostgreSQL CPU per op | 0.261, 0.231 ms | 0.406, 0.401 ms | 0.241, 0.276 ms |
| E1: commits per op | 1.91, 2.00 | 1.57, 1.59 | 1.99, 1.95 |
| E3 at 700/s: p50 | 1.01, 0.98 ms | 4.25, 3.84 ms | 1.07, 1.06 ms |
| E3: p99 | 3.7, 4.0 ms | 20.2, 9.7 ms | 27.6, 5.7 ms |
| E3: node CPU per op | 1.023, 1.032 ms | 1.459, 1.331 ms | 1.253, 1.328 ms |
| E3: PostgreSQL CPU per op | 0.675, 0.692 ms | 1.211, 1.141 ms | 0.703, 0.765 ms |

At the same rate, Axon's node spends 1.3–1.4 times Wow's CPU per command (Kafka off) and PostgreSQL 1.6–1.8 times, though Axon commits fewer transactions per operation; its p50 is about four times Wow's. Where Axon's time goes was not profiled.

## More connections for Axon

Axon's E1 plateau, about 3,500/s, is close to what 10 connections give at about 2.9 ms a transaction. One A session with `spring.datasource.hikari.maximum-pool-size=32` ([`pool-32/`](v10-vs-axon/pool-32/)):

| Clients | E1 | E3 |
| --- | --- | --- |
| 16 | 3,735/s | 1,252/s |
| 64 | 4,474/s | 1,357/s |
| 1,024 | 4,571/s | 1,144/s |

The pool raises Axon's peaks by 31% (E1) and 20% (E3); Wow, on 10 connections, still does 3.2–3.4 and 4.1–4.3 times them.

## On the M4 Pro

The same sessions, W A A W, K K and one Axon session with 32 connections, on the M4 Pro of the [milestone snapshots](v10-m4pro-snapshot-2.md) (10 performance and 4 efficiency cores, Docker with 12 CPUs, the store on disk) at `c48c5d519`, run on 2026-10-25 ([`v10-vs-axon-m4pro/`](v10-vs-axon-m4pro/), `SUMMARY.md`). No session failed a command.

| Closed-loop peak | Wow, Kafka off | Axon | Wow, Kafka on | Axon, 32 connections |
| --- | --- | --- | --- | --- |
| E1 | 22,212, 20,971/s (top step) | 4,544, 4,847/s | 18,926, 17,231/s (top step) | 6,770/s |
| E3 | 7,427, 7,885/s (top step) | 1,414, 1,642/s | 6,879, 6,642/s (top step) | 1,920/s |
| E1, one client | 1,902, 1,635/s | 941, 984/s | 1,776, 1,603/s | 1,037/s |
| E3, one client | 903, 744/s | 360, 369/s | 721, 832/s | 367/s |

- Wow's E1 peak is 4.3–4.9 times Axon's with Kafka off and 3.6–4.2 times with Kafka on; its E3 peak 4.5–5.6 times. Wow's peaks are at the top step, so the ratios are lower bounds; Axon's E3 falls past 16–64 clients (p99 5.9–8.7 s at 1,024), as on the local machine.
- 32 connections raise Axon's peaks by 40–49% (E1) and 17–36% (E3); Wow on 10 still does 3.1–3.3 and 3.9–4.1 times them.

| Fixed rates | Wow, Kafka off | Axon | Wow, Kafka on |
| --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 0.57, 0.57 ms | 1.38, 1.34 ms | 0.61, 0.61 ms |
| E1: node CPU per op | 0.554, 0.557 ms | 0.549, 0.544 ms | 0.691, 0.715 ms |
| E1: PostgreSQL CPU per op | 0.201, 0.208 ms | 0.285, 0.284 ms | 0.224, 0.230 ms |
| E3 at 700/s: p50 | 1.02, 1.07 ms | 3.04, 3.18 ms | 1.11, 1.11 ms |
| E3: node CPU per op | 1.377, 1.353 ms | 1.324, 1.286 ms | 1.765, 1.842 ms |
| E3: PostgreSQL CPU per op | 0.592, 0.620 ms | 1.001, 0.959 ms | 0.652, 0.662 ms |

**Unlike the local machine, the node's CPU per command at these rates is the same** (E1) or 2–7% higher for Wow (E3), where locally Axon's was 1.3–1.4 times Wow's; PostgreSQL's stays 1.4–1.7 times higher for Axon, and Axon's p50 2.4–3 times Wow's. So the peaks' gap on the M4 Pro is not the node's cost per command at a moderate rate: it is how each scales with concurrency (Axon levels off at 16–64 clients) and the store's cost. Where each node's time goes was not profiled.

Both servers had tails at the fixed rates in some sessions (Axon's E1 p99 541 and 75 ms; Wow's E3 p99 86–408 ms in three of four), not examined here; this machine's disk image stalls on fsync, as its [snapshot](v10-m4pro-snapshot-2.md#the-disks-stalls) found.

## On the M5 Max

The same comparison on the Apple M5 Max (`Mac17,14`: 6 Super cores and 12 Performance cores, 64 GB unified memory; Docker with 16 CPUs and 32 GB RAM, the store on disk via VirtioFS) at `3c138c0237`, run on 2026-10-30 ([`v10-vs-axon-m5max/`](v10-vs-axon-m5max/), `SUMMARY.md`). Sessions include the standard sequence (W A A W K K) followed by 32-connection sessions for both Axon (A32) and Wow (W32) to compare symmetrical pool sizing. Sweeps ran closed-loop concurrency steps of 1, 4, 16, 64, 256, 1,024, and 4,096 clients (growth 4). No configuration changes were made to Wow or Axon; Axon passed the harness check (`--mix=E1,E3`) without requiring any HTTP wiring adjustments.

| Closed-loop peak | Wow, Kafka on (K) | Axon (A) | Axon, 32 connections (A32) | Wow, Kafka off (W) |
| --- | --- | --- | --- | --- |
| E1 | **33,655, 34,256/s** (top step) | 4,604, 4,712/s | **7,904/s** | 2,393, 2,398/s* |
| E3 | **10,009, 9,839/s** | 1,563, 1,577/s | 0/s (OOM)* | 0/s (OOM)* |
| E1, one client | 1,082, 1,102/s | 942, 857/s | 942/s | 1,076, 1,113/s |
| E3, one client | 527, 513/s | 328, 308/s | 0/s* | 0/s* |

\* *Footnote on OOM:* Under the high event rates of M5 Max, W sessions running with `--wow.kafka.bootstrap-servers=` derived development in-memory transport (`wow.transport=memory`), whose unbounded in-memory message list exhausted the 1 GB heap at concurrency 256; subsequent E3 sweeps could not connect. In the A32 session, after completing 1.16 million E1 writes at up to 4,096 clients, the 1 GB JVM ran out of heap on tracking processor worker threads before E3 sweep began.

- **E1 peak:** Wow with Kafka on reaches **34,256/s** at 4,096 clients, **7.3 times** Axon's 10-connection peak (4,712/s) and **4.3 times** Axon's 32-connection peak (7,904/s). Compared to the M4 Pro peak (18,926/s), Wow on Kafka gains **+81%** on M5 Max.
- **E3 peak:** Wow with Kafka on reaches **10,009/s** at 1,024 clients, **6.3 times** Axon's peak (1,577/s). Compared to the M4 Pro peak (6,879/s), Wow on Kafka gains **+45%**.
- **Concurrency scaling:** Axon plateaus between 16 and 64 clients (E1 4,406–4,712/s; E3 1,542–1,577/s) and degrades sharply in tail latency past 256 clients (E3 p99 reaches 5.3–8.8 s at 1,024–4,096 clients). Wow on Kafka scales linearly through 1,024 clients and sustains 34k/s at 4,096 clients with a p99 of 242–255 ms.
- **Connection pool scaling:** 32 connections increase Axon's E1 peak by **68%** (from 4,712/s to 7,904/s), but its ceiling still hits at 64 clients. Wow on standard 10 connections still outperforms 32-connection Axon by **4.3 times**.
- **Single-client throughput:** At one client, Wow on Kafka achieves 1,102/s on E1 and 513–527/s on E3, 1.2–1.3 times and 1.6–1.7 times Axon's single-client throughput, confirming an efficiency advantage even in serial execution.

| Fixed rates | Wow, Kafka on | Axon | Axon, 32 conn | Wow, Kafka off | Wow, pool 32 (Kafka off) |
| --- | --- | --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 1.55, 1.92 ms | 1.66, 1.70 ms | 1.66 ms | 1.47, 1.48 ms | 1.52 ms |
| E1: p99 | 4.6, 92.2 ms | 18.4, 23.7 ms | 16.3 ms | 4.5, 4.5 ms | 15.7 ms |
| E1: node CPU per op | 0.546, 0.554 ms | 0.490, 0.505 ms | 0.496 ms | 0.480, 0.488 ms | 0.467 ms |
| E1: PostgreSQL CPU per op | 0.201, 0.236 ms | 0.348, 0.355 ms | 0.356 ms | 0.232, 0.233 ms | 0.222 ms |
| E1: commits per op | 1.23, 1.64 | 1.95, 1.96 | 1.97 | 1.66, 1.69 | 1.59 |
| E3 at 700/s: p50 | 2.25, 2.32 ms | 3.47, 3.52 ms | 3.58 ms | 2.14, 2.21 ms | 2.21 ms |
| E3: p99 | 7.1, 7.6 ms | 12.7, 17.7 ms | 11.6 ms | 6.8, 7.4 ms | 6.7 ms |
| E3: node CPU per op | 1.613, 1.737 ms | 1.191, 1.252 ms | 1.204 ms | 1.480, 1.537 ms | 1.492 ms |
| E3: PostgreSQL CPU per op | 0.717, 0.719 ms | 1.112, 1.122 ms | 1.145 ms | 0.712, 0.726 ms | 0.708 ms |
| E3: commits per op | 5.72, 5.76 | 6.97, 6.99 | 7.11 | 5.71, 5.75 | 5.74 |

**Comparison with M4 Pro and local baseline:**
- **Node CPU per operation** at fixed rate remains closely matched between the frameworks on E1 (~0.49–0.55 ms). On E3, Axon's node uses ~1.2 ms while Wow uses ~1.5–1.7 ms (which includes state stream serialization and Kafka publishing).
- **PostgreSQL resource cost:** Axon continues to demand substantially more database CPU: **1.5 times** Wow's on E1 (0.35 ms vs 0.23 ms) and **1.55 times** Wow's on E3 (1.12 ms vs 0.72 ms), and issues 18–21% more database commits per operation.
- **Latency profile:** At moderate fixed arrival rates, Axon's p50 latency is 1.5–1.6 times Wow's on E3 (3.5 ms vs 2.25 ms), and its p99 is 1.8–2.4 times higher.

## Limits

- Two machines, one node each, local Docker; no server disk. The rates and sweeps are E1 and E3 only: the Axon server has no state reads (E2, E4) and no large payloads.
- Axon's configuration is its documented production one without Axon Server, with the two choices above (the tracking processor, 16 segments); Axon Server and its event store were not measured. Wow runs its defaults.
- The first W session's E1 at 2,500/s had a p99 of 69 ms (one stall in the first measured seconds after the prime); its second session and the K sessions did not.
- The first round's W arm used `wow.topics.in-memory`, a development mode whose topics keep every record in the JVM: the 1 GB node ran out of heap in its first E1 sweep, so those sessions are void, and W runs with Kafka off instead (which needed the fix that a blank broker address turns Kafka off).
- On the M5 Max, sweeps up to 4,096 concurrency tested memory bounds: running Wow with `--wow.kafka.bootstrap-servers=` derived `wow.transport=memory`, which accumulates in-memory messages without retention and exhausted the 1 GB heap at concurrency 256; Wow with Kafka enabled operated stably across all concurrency steps up to 4,096. In Axon's 32-connection run, sustaining 1.16 million writes during E1 sweep pushed heap usage to the limit, causing subsequent E3 worker threads to run out of memory.
