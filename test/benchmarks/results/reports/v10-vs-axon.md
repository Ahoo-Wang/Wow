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

The same comparison on an Apple M5 Max (18 cores, 64 GB, macOS 27.2, as the runs record them; they do not record the model, `hw.model`) at `3c138c0237`, run on 2026-10-30 ([`v10-vs-axon-m5max/`](v10-vs-axon-m5max/), `SUMMARY.md`). The container engine is OrbStack 2.2.5, as the session's summary names it; the runs record Docker engine 29.8.2 with 16 CPUs and 32,110 MiB, the store on disk. The sessions are W A A W K K, then one Axon and one Wow session with 32 connections (A32, W32), run by [`compare.sh`](v10-vs-axon-m5max/compare.sh); each runs the fixed rates, then sweeps of E1 and E3 to 4,096 clients (growth 4). Neither server's configuration changed for this machine.

W and W32 pass a blank Kafka address (`--wow.kafka.bootstrap-servers=`), as on the other machines, which at this commit selects the in-memory transport in development, whose topics keep every record: their nodes ran out of heap in their E1 sweeps (X-33, fixed in #5231, which bounds the in-memory topics and adds `wow.transport=none`), so their sweeps are void.

| Closed-loop peak | Wow, Kafka on (K) | Axon (A) | Axon, 32 connections (A32) | Wow, blank Kafka address (W, W32) |
| --- | --- | --- | --- | --- |
| E1 | 33,655, 34,256/s (top step) | 4,604, 4,712/s | 7,904/s | void |
| E3 | 10,009, 9,839/s | 1,563, 1,577/s | void | void |
| E1, one client | 1,082, 1,102/s | 942, 857/s | 942/s | void |
| E3, one client | 527, 513/s | 328, 308/s | void | void |

A32's E3 sweep is void too: its node ran out of heap after its E1 sweep (the session's summary), and the sweep's first step answered nothing (three timeouts). One 1 GB session does not tell how Axon's memory behaves; the two A sessions finished both sweeps.

- E1: K peaked at the top step, 33,655 and 34,256/s, 7.1–7.4 times Axon's peaks (4,604 and 4,712/s, at 256 clients) and 4.3 times A32's (7,904/s, at 64). K's peaks are lower bounds, and so are the ratios. At 1,024 clients K ran 25,564 and 26,136/s; the M4 Pro's K ran 18,926 and 17,231/s there, its top step.
- E3: K peaked at 1,024 clients, 10,009 and 9,839/s, 6.2–6.4 times Axon's peaks (1,563 and 1,577/s, at 64); the M4 Pro's K ran 6,879 and 6,642/s there. At 4,096 K fell to 8,406 and 8,102/s, refused 213,909 and 289,391 commands as busy (429) and timed out 2,720 and 820 waits (504). No other step of K's sweeps failed a command, and the A sessions' sweeps failed none.
- Axon's E1 stays at 4,380–4,712/s from 16 clients to 4,096. Its E3 peaks at 64 clients and falls past them, with a p99 of 5.2–8.8 s at 1,024 and 4,096. K's E1 p99 at 4,096 is 242–255 ms.
- 32 connections raise Axon's E1 peak by 68–72%, to 7,904/s, still at 64 clients.
- At one client K does 1.1–1.3 times Axon's E1 and 1.6–1.7 times its E3.

The fixed rates ran before the sweeps in every session (`compare.log`), so W's and W32's finished, without a failure, before their nodes ran out of heap. Their heap was already at 776–861 MB on average, against K's 437–490 MB and Axon's 500–643 MB. The values are in session order:

| Fixed rates | Wow, Kafka on (K) | Axon (A) | Axon, 32 connections (A32) | Wow, blank Kafka address (W) | Wow, blank Kafka address, 32 connections (W32) |
| --- | --- | --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 1.92, 1.55 ms | 1.66, 1.70 ms | 1.66 ms | 1.48, 1.47 ms | 1.52 ms |
| E1: p99 | 92.2, 4.6 ms | 18.4, 23.7 ms | 16.3 ms | 4.5, 4.5 ms | 15.7 ms |
| E1: node CPU per op | 0.546, 0.554 ms | 0.490, 0.505 ms | 0.496 ms | 0.480, 0.488 ms | 0.467 ms |
| E1: PostgreSQL CPU per op | 0.201, 0.236 ms | 0.355, 0.348 ms | 0.356 ms | 0.233, 0.232 ms | 0.222 ms |
| E1: commits per op | 1.23, 1.64 | 1.96, 1.95 | 1.97 | 1.66, 1.69 | 1.59 |
| E3 at 700/s: p50 | 2.32, 2.25 ms | 3.47, 3.52 ms | 3.58 ms | 2.21, 2.14 ms | 2.21 ms |
| E3: p99 | 7.1, 7.6 ms | 12.7, 17.7 ms | 11.6 ms | 6.8, 7.4 ms | 6.7 ms |
| E3: node CPU per op | 1.737, 1.613 ms | 1.191, 1.252 ms | 1.204 ms | 1.480, 1.537 ms | 1.492 ms |
| E3: PostgreSQL CPU per op | 0.719, 0.717 ms | 1.122, 1.112 ms | 1.145 ms | 0.726, 0.712 ms | 0.708 ms |
| E3: commits per op | 5.72, 5.76 | 6.99, 6.97 | 7.11 | 5.71, 5.75 | 5.74 |

- Wow's node costs more CPU per E3 operation at 700/s than Axon's here: 1.613–1.737 ms with Kafka on and 1.480–1.537 ms with W and W32, against Axon's 1.191–1.252 ms (A and A32). Only K publishes to Kafka. W, without it, still costs 18–29% more than Axon, but its heap was filling, so these runs do not tell Kafka's share of K's cost. With Kafka off, Axon's node cost 1.3–1.4 times Wow's locally, and Wow's was 2–7% higher on the M4 Pro.
- On E1 at 2,500/s, K's node costs 0.546–0.554 ms per operation, Axon's 0.490–0.505 ms and W's and W32's 0.467–0.488 ms.
- PostgreSQL's CPU per operation is 1.5–1.8 times Wow's for Axon on E1 (0.348–0.356 ms against 0.201–0.236 ms) and 1.5–1.6 times on E3 (1.112–1.145 ms against 0.708–0.726 ms). Axon commits 1.95–1.97 transactions per E1 and 6.97–7.11 per E3, Wow 1.23–1.69 and 5.71–5.76.
- Axon's E3 p50 is 3.47–3.58 ms and its p99 11.6–17.7 ms, against Wow's 2.14–2.32 ms and 6.7–7.6 ms. On E1 both have a p50 of 1.47–1.92 ms. K's first session had an E1 p99 of 92.2 ms, Axon's sessions 16.3–23.7 ms and W32 15.7 ms, not examined here. Where each node's time goes was not profiled.

### With an 8 GB heap

The same eight sessions, rerun with an 8 GB heap (`--heap=8g`, so `-Xms8g -Xmx8g -XX:+UseG1GC`) by [`heap-8g/compare.sh`](v10-vs-axon-m5max/heap-8g/compare.sh) ([`v10-vs-axon-m5max/heap-8g/`](v10-vs-axon-m5max/heap-8g/), `SUMMARY.md`). The runs record commit `df12780c32`, with uncommitted changes; that commit adds only the 1 GB reports to `3c138c0237`. Docker records the same 16 CPUs and 32,110 MiB, and the PostgreSQL settings are unchanged. With 1 GB, W and W32 ran out of heap in their E1 sweeps and A32 after its E1 sweep; with 8 GB every session ran every step of both sweeps.

W and W32 again pass a blank Kafka address (`--wow.kafka.bootstrap-servers=`), which at this commit selects the in-memory transport in development (X-33). Their columns measure that development transport, not Wow with Kafka off, and their ceilings, E1 and E3, are that transport's. X-33 (#5231) added `wow.transport=none`, the arm for Kafka off in a rerun; the W arm of [`v10-vs-axon/compare.sh`](v10-vs-axon/compare.sh) already uses it. The values are in session order:

| Closed-loop peak, 8 GB heap | Wow, Kafka on (K) | Axon (A) | Axon, 32 connections (A32) | Wow, in-memory development transport (W) | Wow, in-memory development transport, 32 connections (W32) |
| --- | --- | --- | --- | --- | --- |
| E1 | 36,452, 34,783/s (top step) | 4,988, 4,992/s | 8,436/s | 36,685, 36,215/s (top step) | 37,019/s (top step) |
| E3 | 9,808, 9,867/s | 1,554, 1,555/s | 2,345/s | 4,365, 4,364/s | 4,208/s |
| E1, one client | 1,104, 1,082/s | 949, 916/s | 944/s | 1,114, 1,133/s | 1,125/s |
| E3, one client | 515, 517/s | 342, 332/s | 322/s | 533, 540/s | 503/s |

- E1: K peaked at the top step, 36,452 and 34,783/s, 7.0–7.3 times Axon's peaks (4,988 and 4,992/s, at 256 and 64 clients) and 4.1–4.3 times A32's (8,436/s, at 64). K's peaks are lower bounds, and so are the ratios.
- E3: K peaked at 1,024 clients, 9,808 and 9,867/s, 6.3 times Axon's peaks (1,554 and 1,555/s, at 64) and 4.2 times A32's (2,345/s, at 64). At 4,096 K fell to 8,432 and 5,764/s, refused 186,982 and 376,881 commands as busy (429) and timed out 2,327 and 162 waits (504). No other step of K's sweeps failed a command, and the A and A32 sweeps failed none.
- A32 ran both sweeps to the end. 32 connections raise Axon's peaks by 69% on E1 and 51% on E3, both at 64 clients.
- W's and W32's E1 sweeps failed no command and reached 36,685, 36,215 and 37,019/s at 4,096 clients. Their E3 sweeps peaked at 64 clients (4,365, 4,364 and 4,208/s) and fell from 256: 2,548, 2,783 and 2,759/s there, with a p99 of 303 ms in all three, then 462, 732 and 490/s at 1,024, with a p99 of 3.8–4.8 s, and 8, 0 and 18/s at 4,096. Below 4,096 they failed one command (an `EOFException`, W's second session at 1,024). At 4,096 they refused 16,058, 34,417 and 16,320 commands as busy (429) and timed out 6,221, 5,461 and 5,138 waits (504); the two W sessions also answered 21 and 11 HTTP 500s and had 2 and 3 client exceptions (`EOFException`, `HttpTimeoutException`). The sweeps record no node signals, such as heap or GC, so these runs do not tell why that transport's E3 falls past 64 clients.
- At one client K does 1.1–1.2 times Axon's E1 and 1.5–1.6 times its E3 (A and A32).

The fixed rates ran before the sweeps in every session (`compare.log`). In them every node's heap averaged 2.5–3.1 GB of its 8 GB, with no full collection: W and W32 2.8–3.1 GB, K 2.5–2.9 GB and Axon 2.5–2.7 GB. The values are in session order:

| Fixed rates, 8 GB heap | Wow, Kafka on (K) | Axon (A) | Axon, 32 connections (A32) | Wow, in-memory development transport (W) | Wow, in-memory development transport, 32 connections (W32) |
| --- | --- | --- | --- | --- | --- |
| E1 at 2,500/s: p50 | 1.53, 1.61 ms | 1.56, 1.58 ms | 2.45 ms | 1.50, 1.53 ms | 1.53 ms |
| E1: p99 | 4.6, 53.8 ms | 5.6, 9.7 ms | 153.6 ms | 38.7, 123.4 ms | 9.5 ms |
| E1: node CPU per op | 0.559, 0.587 ms | 0.410, 0.409 ms | 0.370 ms | 0.449, 0.439 ms | 0.467 ms |
| E1: PostgreSQL CPU per op | 0.238, 0.223 ms | 0.342, 0.349 ms | 0.306 ms | 0.217, 0.214 ms | 0.232 ms |
| E1: commits per op | 1.64, 1.48 | 1.98, 1.94 | 1.78 | 1.55, 1.51 | 1.58 |
| E3 at 700/s: p50 | 2.17, 2.24 ms | 3.41, 3.39 ms | 3.41 ms | 2.04, 2.06 ms | 2.06 ms |
| E3: p99 | 7.8, 7.5 ms | 8.5, 8.1 ms | 7.5 ms | 6.4, 7.4 ms | 6.9 ms |
| E3: node CPU per op | 1.623, 1.763 ms | 1.038, 1.032 ms | 1.041 ms | 1.376, 1.361 ms | 1.395 ms |
| E3: PostgreSQL CPU per op | 0.731, 0.732 ms | 1.106, 1.111 ms | 1.117 ms | 0.700, 0.706 ms | 0.716 ms |
| E3: commits per op | 5.74, 5.72 | 7.07, 7.07 | 7.17 | 5.81, 5.75 | 5.81 |

- Wow's node costs more CPU per operation than Axon's at these rates. On E1 at 2,500/s K costs 0.559–0.587 ms, W and W32 0.439–0.467 ms and Axon 0.370–0.410 ms (A and A32); on E3 at 700/s K costs 1.623–1.763 ms, W and W32 1.361–1.395 ms and Axon 1.032–1.041 ms. W and W32 publish to the in-memory transport, not to nothing, so these runs do not give Kafka's share of K's cost.
- PostgreSQL's CPU per operation is 1.3–1.6 times Wow's for Axon on E1 (0.306–0.349 ms against 0.214–0.238 ms) and 1.5–1.6 times on E3 (1.106–1.117 ms against 0.700–0.732 ms). Axon commits 1.78–1.98 transactions per E1 and 7.07–7.17 per E3, Wow 1.48–1.64 and 5.72–5.81.
- Axon's E3 p50 is 3.39–3.41 ms, against Wow's 2.04–2.24 ms.

## On the M5 Max, at 10.0.0-beta.1

A complete rerun of the comparison on Apple M5 Max (`Mac17,14`, 18 cores: 6 Super + 12 Performance, 64 GB unified memory, macOS 27.2 (26B123)) at commit `fe6f38f224` (`fe6f38f224759890e510356ad3419a7eaf41791d`, `main`), 34 commits after snapshot 2 (`30d0c0348e`). The raw runs, logs, and scripts are in [`v10-vs-axon-m5max-2/`](v10-vs-axon-m5max-2/) (1 GB heap) and [`v10-vs-axon-m5max-2/heap-8g/`](v10-vs-axon-m5max-2/heap-8g/) (8 GB heap). Container engine: OrbStack 2.2.5, Docker engine 29.8.2 with 16 CPUs and 32,110 MiB, store on disk. JDK: Eclipse Adoptium Temurin 21.0.13+9.

Unlike the earlier M5 Max runs where W and W32 passed an empty Kafka broker string (`--wow.kafka.bootstrap-servers=`) that selected in-memory topics that leaked memory (X-33), W and W32 in this rerun pass `--server-args="--wow.transport=none"`. This cleanly disables Kafka transport without engaging in-memory queues, allowing both W and W32 to complete every step of the closed-loop sweeps without running out of heap. Axon server code and version (Axon Framework 4.13.2, Spring Boot 3.5.16) remain identical to earlier comparisons.

### Peak throughput (closed loop, 1 GB heap)

Standard sessions W A A W K K, followed by A32 and W32 (`spring.datasource.hikari.maximum-pool-size=32`), with `-Xms1g -Xmx1g -XX:+UseG1GC`:

| Closed-loop peak, 1 GB heap | Wow, Kafka off (W) | Axon (A) | Axon, 32 connections (A32) | Wow, Kafka off, 32 connections (W32) | Wow, Kafka on (K) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| E1 | ≥ 35,491, 36,366/s (step 4096) | 4,427, 4,403/s (step 256) | 7,180/s (step 1024) | ≥ 35,569/s (step 4096) | ≥ 32,762, 32,408/s (step 4096) |
| E3 | 10,373, 10,707/s (step 1024) | 1,478, 1,458/s (step 64, 16) | 2,101/s (step 64) | 10,401/s (step 1024) | 9,936, 9,761/s (step 1024) |
| E1, one client | 1,110, 1,101/s | 790, 607/s | 775/s | 1,110/s | 1,070, 1,072/s |
| E3, one client | 508, 502/s | 297, 231/s | 256/s | 510/s | 499, 420/s |

- **E1:** Wow Kafka off (W) peaked at the top step (concurrency 4,096), reaching 35,491 and 36,366/s. This is 8.0–8.2 times Axon's peak (4,427/s, at 256 clients) and 4.9–5.1 times Axon's 32-connection peak (7,180/s, at 1,024 clients), even though W ran on the default 10 connections. W32 reached ≥ 35,569/s at step 4,096. Wow Kafka on (K) reached ≥ 32,762 and 32,408/s at step 4,096 (7.3–7.4 times Axon's peak and 4.5 times A32). Because Wow's E1 peaks fall on the highest step, these throughput numbers are lower bounds (marked with `≥`).
- **E3:** Wow Kafka off (W) peaked at concurrency 1,024, reaching 10,373 and 10,707/s. This is 7.0–7.2 times Axon's peak (1,478/s, at 64 clients) and 4.9–5.1 times Axon's 32-connection peak (2,101/s, at 64 clients). At concurrency 4,096, W fell to 7,035 and 5,825/s, refusing excess commands with 429 `wow.command.busy` and timing out waits with 504 `wow.wait.timeout`. W32 reached 10,401/s at concurrency 1,024. K reached 9,936 and 9,761/s at concurrency 1,024 (6.6–6.7 times Axon and 4.6–4.7 times A32).
- **Single client:** On a single client, Wow Kafka off delivers 1.4–1.8 times Axon's E1 throughput and 1.7–2.2 times Axon's E3 throughput.
- **Connection pool impact:** Raising Axon's pool from 10 to 32 connections increased its E1 peak by 62% (from 4,427 to 7,180/s) and its E3 peak by 42% (from 1,478 to 2,101/s). For Wow, 10 connections did not constrain throughput (W and W32 both reached ~35.5–36.3k on E1 and ~10.4–10.7k on E3), as reactive pipelining multiplexes transfers effectively over Hikari's default pool.

### Fixed rates (1 GB heap)

Measured before the sweeps, in session order:

| Fixed rates, 1 GB heap | Wow, Kafka off (W) | Axon (A) | Axon, 32 connections (A32) | Wow, Kafka off, 32 connections (W32) | Wow, Kafka on (K) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| E1 at 2,500/s: p50 | 1.47, 1.50 ms | 2.21, 1.83 ms | 1.81 ms | 1.48 ms | 1.53, 1.55 ms |
| E1: p99 | 4.4, 4.9 ms | 130.0, 19.7 ms | 17.3 ms | 4.7 ms | 5.5, 4.9 ms |
| E1: node CPU per op | 0.447, 0.446 ms | 0.476, 0.515 ms | 0.515 ms | 0.449 ms | 0.559, 0.601 ms |
| E1: PostgreSQL CPU per op | 0.230, 0.234 ms | 0.285, 0.330 ms | 0.327 ms | 0.235 ms | 0.241, 0.242 ms |
| E1: commits per op | 1.66, 1.66 | 1.59, 1.89 | 1.88 | 1.65 | 1.62, 1.62 |
| E3 at 700/s: p50 | 2.22, 2.43 ms | 3.74, 3.77 ms | 3.73 ms | 2.30 ms | 2.53, 2.46 ms |
| E3: p99 | 7.4, 60.7 ms | 18.4, 19.7 ms | 17.5 ms | 6.8 ms | 8.6, 8.3 ms |
| E3: node CPU per op | 1.286, 1.273 ms | 1.251, 1.242 ms | 1.251 ms | 1.300 ms | 1.598, 1.715 ms |
| E3: PostgreSQL CPU per op | 0.694, 0.687 ms | 1.025, 1.049 ms | 1.071 ms | 0.718 ms | 0.727, 0.725 ms |
| E3: commits per op | 5.74, 5.49 | 6.67, 6.60 | 6.74 | 5.70 | 5.66, 5.65 |

- On E1 at 2,500/s, Wow Kafka off consumes 0.446–0.447 ms node CPU per operation, compared to 0.476–0.515 ms for Axon; PostgreSQL CPU per op is 0.230–0.234 ms for Wow vs 0.285–0.330 ms for Axon.
- On E3 at 700/s, Wow Kafka off consumes 1.273–1.286 ms node CPU per operation, comparable to Axon's 1.242–1.251 ms; however, PostgreSQL CPU for Axon is 1.48–1.53 times Wow's (1.025–1.049 ms vs 0.687–0.694 ms). Axon commits 6.60–6.67 transactions per E3 op vs 5.49–5.74 for Wow.
- Latency: Wow's E3 p50 is 2.22–2.43 ms vs 3.74–3.77 ms for Axon.

### With an 8 GB heap (rerun with wow.transport=none)

The same eight sessions rerun with `-Xms8g -Xmx8g -XX:+UseG1GC`, where W and W32 pass `--server-args="--wow.transport=none"`:

| Closed-loop peak, 8 GB heap | Wow, Kafka off (W) | Axon (A) | Axon, 32 connections (A32) | Wow, Kafka off, 32 connections (W32) | Wow, Kafka on (K) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| E1 | ≥ 38,877, 38,583/s (step 4096) | 4,722, 4,734/s (step 64) | 7,852/s (step 64) | ≥ 37,747/s (step 4096) | ≥ 35,594, 35,182/s (step 4096) |
| E3 | 10,501, 10,518/s (step 1024) | 1,509, 1,519/s (step 64) | 1,974/s (step 64) | 10,536/s (step 1024) | 9,944, 9,900/s (step 1024) |
| E1, one client | 1,119, 1,119/s | 684, 683/s | 902/s | 1,018/s | 1,097, 1,085/s |
| E3, one client | 517, 509/s | 214, 317/s | 309/s | 486/s | 490, 502/s |

- **E1:** Wow Kafka off (W) reached ≥ 38,877 and 38,583/s at concurrency 4,096. This is 8.2 times Axon's peak (4,734/s, at 64 clients) and 4.9 times Axon's 32-connection peak (7,852/s, at 64 clients). W32 reached ≥ 37,747/s at step 4,096. K reached ≥ 35,594 and 35,182/s.
- **E3:** Wow Kafka off (W) reached 10,501 and 10,518/s at concurrency 1,024. This is 6.9 times Axon's peak (1,519/s, at 64 clients) and 5.3 times Axon's 32-connection peak (1,974/s, at 64 clients). W32 reached 10,536/s at concurrency 1,024. K reached 9,944 and 9,900/s.
- **Overload error verification (X-40 resolution):** Across all eight 8 GB sessions, **zero HTTP 500 errors and zero unhandled exceptions occurred**. At concurrency 4,096 on E3, overloaded requests were answered cleanly with 429 `wow.command.busy` and 504 `wow.wait.timeout`. The previous HTTP 500s seen with empty Kafka strings are confirmed absent when using `--wow.transport=none`.

| Fixed rates, 8 GB heap | Wow, Kafka off (W) | Axon (A) | Axon, 32 connections (A32) | Wow, Kafka off, 32 connections (W32) | Wow, Kafka on (K) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| E1 at 2,500/s: p50 | 1.45, 1.46 ms | 1.69, 1.68 ms | 1.61 ms | 1.45 ms | 1.51, 1.50 ms |
| E1: p99 | 4.6, 5.0 ms | 5.8, 7.6 ms | 4.3 ms | 5.0 ms | 4.9, 4.4 ms |
| E1: node CPU per op | 0.433, 0.428 ms | 0.418, 0.412 ms | 0.403 ms | 0.436 ms | 0.536, 0.540 ms |
| E1: PostgreSQL CPU per op | 0.228, 0.226 ms | 0.317, 0.307 ms | 0.307 ms | 0.232 ms | 0.238, 0.234 ms |
| E1: commits per op | 1.67, 1.70 | 1.91, 1.93 | 1.96 | 1.67 | 1.63, 1.65 |
| E3 at 700/s: p50 | 2.65, 2.25 ms | 3.68, 3.71 ms | 3.62 ms | 2.48 ms | 2.64, 2.38 ms |
| E3: p99 | 172.0, 6.9 ms | 9.1, 9.1 ms | 8.4 ms | 9.0 ms | 9.5, 7.0 ms |
| E3: node CPU per op | 1.165, 1.245 ms | 1.026, 1.015 ms | 1.014 ms | 1.335 ms | 1.626, 1.558 ms |
| E3: PostgreSQL CPU per op | 0.639, 0.682 ms | 1.020, 1.010 ms | 1.018 ms | 0.782 ms | 0.805, 0.703 ms |
| E3: commits per op | 4.61, 5.75 | 6.71, 6.72 | 6.84 | 5.54 | 5.45, 5.75 |

- At fixed rates on an 8 GB heap, Wow Kafka off node CPU per op is 0.428–0.433 ms on E1 and 1.165–1.245 ms on E3, while Axon node CPU is 0.412–0.418 ms on E1 and 1.015–1.026 ms on E3.
- PostgreSQL CPU per op remains 1.36–1.40 times higher for Axon on E1 (0.307–0.317 ms vs 0.226–0.228 ms) and 1.48–1.60 times higher on E3 (1.010–1.020 ms vs 0.639–0.682 ms). Axon commits 1.91–1.93 transactions per E1 and 6.71–6.72 per E3, while Wow commits 1.67–1.70 per E1 and 4.61–5.75 per E3.
- E3 p50 latency: Wow reaches 2.25–2.65 ms vs 3.68–3.71 ms for Axon.

## Limits

- Three machines, one node each, local Docker; no server disk. The rates and sweeps are E1 and E3 only: the Axon server has no state reads (E2, E4) and no large payloads.
- Axon's configuration is its documented production one without Axon Server, with the two choices above (the tracking processor, 16 segments); Axon Server and its event store were not measured. Wow runs its defaults.
- The first W session's E1 at 2,500/s had a p99 of 69 ms (one stall in the first measured seconds after the prime); its second session and the K sessions did not.
- The first round's W arm used `wow.topics.in-memory`, a development mode whose topics keep every record in the JVM: the 1 GB node ran out of heap in its first E1 sweep, so those sessions are void, and W runs with Kafka off instead (which needed the fix that a blank broker address turns Kafka off).
- On the M5 Max with a 1 GB heap, the W and W32 sweeps and A32's E3 sweep are void (out of heap, [above](#on-the-m5-max)); W's and W32's fixed rates ran with their heap filling. With an 8 GB heap ([above](#with-an-8-gb-heap)) every sweep ran every step, but W and W32 still ran on the in-memory development transport, so no M5 Max run measures Wow with Kafka off.
