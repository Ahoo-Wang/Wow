# V10 local baseline: cost per operation and where it goes

A baseline of V10 on one machine, measured on 2026-10-23, to rank the next optimizations by evidence: what one command costs on each resource, which resource limits each backend, how much of that cost each part of the node takes, and how large a difference one run can show. It measures; it changes nothing.

## Setup

| | |
| --- | --- |
| Commit | `claude/bench-session` at `b09f349a5` (`main` at `c58787552` plus the harness's session mode) |
| Machine | Apple M4, 10 cores, 16 GB; Docker Desktop with 10 CPUs and 7.9 GB; JDK 21 |
| Topology | one node, 1 GB heap, G1; PostgreSQL or MongoDB (single-node replica set) and Kafka in containers |
| Data | 100,000 accounts, seeded and primed once per session (every account touched once), uniform keys unless noted |
| Scenarios | E1 (deposit, answered once committed), E3 (transfer saga across two accounts) |

Three stages, all on sessions (`bench.sh up`, then runs with `--reuse`, then `down`), from [`scripts/baseline.sh`](v10-local-baseline/scripts/baseline.sh):

- **Stage 0, A/A.** PostgreSQL E1 at one fixed rate (2,542/s, half of a closed-loop sweep's peak), three sessions of two 60 s runs each: the spread within a session and across server restarts.
- **Stage 1, cost.** One session per backend with flight recordings (`--jfr`, JFR's `profile` settings). Per scenario a closed-loop sweep (concurrency 1 to 512), then open-loop runs at 25%, 50% and 75% of its peak, 30 s warm-up and 60 s measured each.
- **L1.** The kernel and one store in one JVM (`bench.sh component`), at concurrency 16, 64 and 256: round trips and allocation per operation without HTTP and Kafka.
- **A probe** afterwards: a MongoDB session, E1 at 1,720/s, with MongoDB's `top` read before and after, for the store's time per collection.

The raw results are in [`v10-local-baseline/`](v10-local-baseline/): `runs/<run>/` holds each run's `result.json`, `summary.md`, `saturation.json`, `component.json` and, on PostgreSQL, `postgres-stats.txt` (`pg_stat_statements` over the run); `cost-per-op.tsv`, `jfr-thread-cpu.txt` and `jfr-components.txt` are the tables below, made by the scripts in `scripts/`. The recordings themselves (5 MB each) are not kept.

## How much one run can show (stage 0)

| Session, run | p50 | p99 | Server CPU ms/op | Allocated KB/op | PostgreSQL CPU ms/op | Commits/op |
| --- | --- | --- | --- | --- | --- | --- |
| 1, 1 | 2.9 ms | 58 ms | 0.825 | 102 | 0.63 | 2.33 |
| 1, 2 | 5.5 ms | 136 ms | 0.824 | 104 | 0.70 | 2.11 |
| 2, 1 | 6.9 ms | 130 ms | 0.836 | 100 | 0.67 | 2.08 |
| 2, 2 | 154 ms | 791 ms | 0.746 | 102 | 0.96 | 1.31 |
| 3, 1 | 13.2 ms | 188 ms | 0.861 | 100 | 0.72 | 1.88 |
| 3, 2 | 12.6 ms | 167 ms | 0.850 | 101 | 0.73 | 1.91 |

- **Cost per operation is stable**: server CPU 0.82–0.86 ms/op and allocation 100–104 KB/op in five runs of six (±2.5%), within a session and across restarts alike. A difference of 5% in these is visible in one pair of runs; the same holds for counts (round trips, commits, rows).
- **Latency at half the peak is not.** p50 ranges 2.9–154 ms and p99 58–791 ms at the same rate on the same build. In the outlier (2, 2) the node queued: PostgreSQL did fewer, larger commits (1.31 per op), the WAL flushed half as often, and the node spent less CPU per operation. A latency difference measured at this load locally is noise unless many alternating rounds show it; at low concurrency (below) latency is usable.

## Throughput and latency (stage 1)

Closed-loop sweeps, throughput / p50 at each concurrency:

| Concurrency | 1 | 8 | 32 | 128 | 512 | Peak |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL E1 | 1,157/s, 0.8 ms | 1,954/s, 3.8 ms | 2,687/s, 11.7 ms | 2,812/s, 43.8 ms | 3,811/s, 134 ms | 3,811/s |
| PostgreSQL E1, zipf | 961/s, 0.9 ms | 3,956/s, 1.8 ms | 4,354/s, 2.2 ms | 4,245/s, 2.3 ms | 3,991/s, 2.5 ms | 4,354/s |
| PostgreSQL E3 | 640/s, 1.5 ms | 1,144/s, 6.8 ms | 1,400/s, 22 ms | 1,578/s, 79 ms | 1,660/s, 281 ms | 1,660/s |
| MongoDB E1 | 664/s, 1.4 ms | 1,770/s, 4.2 ms | 2,343/s, 12 ms | 3,114/s, 38 ms | 3,446/s, 142 ms | 3,446/s |
| MongoDB E1, zipf | 655/s, 1.3 ms | 2,710/s, 2.5 ms | 3,079/s, 2.9 ms | 3,069/s, 2.8 ms | 2,985/s, 3.0 ms | 3,079/s |
| MongoDB E3 | 321/s, 3.0 ms | 872/s, 8.7 ms | 1,009/s, 30 ms | 1,360/s, 92 ms | 1,337/s, 377 ms | 1,427/s |

Open loop at shares of each peak, throughput and p50 / p99:

| | 25% | 50% | 75% |
| --- | --- | --- | --- |
| PostgreSQL E1 | 953/s, 0.9 / 5.5 ms | 1,906/s, 4.4 / 106 ms | 2,763/s of 2,859, 135 ms / 3.0 s |
| PostgreSQL E1, zipf | 1,089/s, 0.7 / 1.7 ms | 2,177/s, 0.7 / 3.7 ms | 3,266/s, 1.8 / 151 ms |
| PostgreSQL E3 | 415/s, 2.5 / 975 ms | 830/s, 9.3 / 211 ms | 266/s of 1,245, collapsed |
| MongoDB E1 | 861/s, 2.3 / 15 ms | 1,723/s, 6.6 / 53 ms | 2,584/s, 40 / 184 ms |
| MongoDB E1, zipf | 770/s, 1.4 / 4.8 ms | 1,540/s, 1.3 / 9.9 ms | 2,309/s, 2.2 / 96 ms |
| MongoDB E3 | 357/s, 6.8 / 762 ms | 714/s, 20 / 154 ms | 969/s of 1,070, 1.8 / 6.3 s |

- **The closed-loop peak is not a sustainable rate.** At 75% of it, uniform keys queue (PostgreSQL E1 p50 135 ms) or collapse (E3 on both backends); at 50% p99 is already 50–210 ms. The sustainable share on this machine is about half the peak, as on the 4-core runners (47–56% for PostgreSQL in [D248 ④](v10-d248-pg-append-queue.md)). The E3 p99 at 25% (762–975 ms) is a burst within the run, not a steady tail: the median second's p99 was 9 ms on PostgreSQL and 27 ms on MongoDB, and 6 and 10 seconds of the 60 passed 200 ms, together (`perSecond` in `result.json`).
- **Hot keys cap the closed loop early.** With zipf keys, throughput stops growing from concurrency 32 while p50 stays at 2–3 ms: the commands of the hottest accounts wait in their aggregate's lane, one commit after another, and the added clients only wait there.
- **The low-concurrency latency** is the cost of one command end to end: 0.6–0.8 ms for E1 on PostgreSQL at concurrency 1, 1.3–1.4 ms on MongoDB; E3 1.5 and 3.0 ms.
- **Recordings cost throughput.** The stage 0 sweep without recordings peaked at 5,084/s, the stage 1 sweep with them at 3,811/s, and server CPU per operation at 50% was 1.03 ms with them against 0.82 without (at another rate). Costs from recorded runs compare only with recorded runs.

## Cost per operation

At 50% of each peak (from [`cost-per-op.tsv`](v10-local-baseline/cost-per-op.tsv); every run is there). CPU is in milliseconds per operation, the containers' from Docker's averages.

| | Server CPU | Allocated | Store CPU | Kafka CPU | Commits | WAL records, bytes | WAL flushes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL E1 | 1.03 | 102 KB | 0.86 | 0.39 | 2.27 | 9.9, 1.7 KB | 0.78 |
| PostgreSQL E1, zipf | 0.58 | 89 KB | 0.30 | 0.20 | 2.06 | 10.0, 1.3 KB | 1.72 |
| PostgreSQL E3 | 2.12 | 267 KB | 2.33 | 0.83 | 5.83 | 28.9, 7.2 KB | 1.92 |
| MongoDB E1 | 0.95 | 220 KB | 2.07 | 0.40 | | | |
| MongoDB E1, zipf | 0.69 | 156 KB | 0.67 | 0.23 | | | |
| MongoDB E3 | 1.99 | 535 KB | 6.92 | 0.91 | | | |

L1, the kernel and the store alone at concurrency 16 (store round trips per operation, the two largest kinds, and allocation):

| | Throughput | Round trips/op | Largest | Allocated |
| --- | --- | --- | --- | --- |
| PostgreSQL E1 | 4,000/s | 2.79 | append 1.0, snapshot with tail 1.0 | 85 KB |
| PostgreSQL E3 | 1,379/s | 5.44 | append 3.0, snapshot with tail 1.0 | 206 KB |
| MongoDB E1 | 2,038/s | 3.32 | snapshot `find` 1.0, tail `aggregate` 1.0, insert 1.0 | 224 KB |
| MongoDB E3 | 1,231/s | 5.59 | insert 3.0, snapshot `find` 1.0, tail `aggregate` 1.0 | 424 KB |

(L1 rotates through the accounts, so every load misses the state cache.)

- **Uniform keys cost the most, through the state cache.** At 100,000 uniform accounts and a 1 GB heap, about 74% of commands load their aggregate (PostgreSQL: 126,770 snapshot-with-tail reads for 171,540 commands). Against zipf keys this costs the node +0.45 ms CPU per command on PostgreSQL and +0.26 ms on MongoDB, and the store +0.56 ms (PostgreSQL) and +1.40 ms (MongoDB).
- **MongoDB loads in two round trips where PostgreSQL takes one.** D268 put the snapshot and the log's tail into one statement on PostgreSQL; on MongoDB a load is still a `find` on the snapshots and an `aggregate` on the log (L1: 3.32 round trips per E1 command against 2.79).
- **MongoDB allocates 2.6 times as much** as PostgreSQL in L1 (224 against 85 KB per E1 command), and twice as much in the server; its snapshot store and log tail take about a quarter of the allocation each (below).

## Where the node's time goes

Per-thread CPU from the recordings (`jdk.ThreadCPULoad`, [`jfr-thread-cpu.txt`](v10-local-baseline/jfr-thread-cpu.txt)), at 50%:

| | Coroutine workers | Virtual-thread carriers (JDBC, MongoDB driver) | Netty (HTTP) | Kafka producer | Total |
| --- | --- | --- | --- | --- | --- |
| PostgreSQL E1 | 0.78 cores (43%) | 0.54 (30%) | 0.31 (17%) | 0.08 (4.5%) | 1.83 |
| PostgreSQL E3 | 0.75 (47%) | 0.52 (32%) | 0.13 (8%) | 0.08 (5%) | 1.60 |
| MongoDB E1 | 0.53 (33%) | 0.64 (40%) | 0.21 (13%) | 0.05 (3.4%) | 1.59 |
| MongoDB E3 | 0.47 (37%) | 0.58 (46%) | 0.12 (9%) | 0.05 (4%) | 1.28 |

The execution samples cannot split these further on their own: JFR 21 sampled the virtual threads about five times less often than their CPU (83 samples for 30% of the CPU on PostgreSQL E1), which makes everything on platform threads look larger. The Kafka sender, for one, takes 19% of the execution samples but 4.5% of the CPU. The allocation samples do cover virtual threads; by component ([`jfr-components.txt`](v10-local-baseline/jfr-components.txt), each sample to the first matching part of its stack):

| Share of allocation | PostgreSQL E1 | PostgreSQL E3 | MongoDB E1 | MongoDB E3 |
| --- | --- | --- | --- | --- |
| HTTP, WebFlux, Netty | 22% | 9% | 11% | 5% |
| Subscriptions and sinks | 20% | 30% | 9% | 14% |
| Log tail | 10% | 13% | 25% | 23% |
| Snapshot store | 9% | 10% | 26% | 25% |
| Log append | 7% | 9% | 2% | 3% |
| Kafka producer | 7% | 8% | 3% | 3% |
| Store driver | 4% | 5% | 16% | 20% |
| Kernel, state load, JSON | 10% | 8% | 5% | 4% |

On PostgreSQL E1 the HTTP share is mostly WebFlux's routing (path parsing, `RouterFunctionMapping` attributes, predicate matching) under the filter chain.

## Where the stores' time goes

**PostgreSQL**, E1 at 50% (`pg_stat_statements` over warm-up and measurement, per command):

| Statement | Calls | Rows | Execution time |
| --- | --- | --- | --- |
| snapshot with tail (load) | 0.74 | 8.5 per call | 0.131 ms |
| append, one stream | 0.93 | 1 | 0.128 ms |
| snapshot upserts, 1–10 rows | 0.54 | 0.88 per command | 0.064 ms |
| append, several streams (queue) | 0.03 | 2–3 per call | 0.009 ms |
| checkpoint | 0.27 | 1 | 0.006 ms |

The WAL is the limit: at 50% of the peak PostgreSQL flushed it 1,496 times a second for 805 ms of each second (0.54 ms per flush); at 25% for 258 ms, at E1 zipf 75% for 847 ms. Every run from 50% up spends 80–85% of each second flushing, so the queueing at 50–75% is the WAL device of Docker Desktop's VM, the cause D267 ⑩ suspected. A load returns 8.5 rows: the snapshot, the log's tail after it, and the recent request keys before it (D268), which the node keeps for deduplication.

**MongoDB**, E1 at 1,720/s (the probe; `top`, time inside MongoDB per command):

| Collection, operation | Per command | Time each | Share |
| --- | --- | --- | --- |
| log, `aggregate` (loads and tails) | 1.48 | 258 µs | 36% |
| snapshots, `update` (upserts, statements) | 1.00 | 283 µs | 27% |
| log, `insert` | 0.99 | 276 µs | 26% |
| snapshots, `find` (loads) | 0.74 | 120 µs | 8% |
| checkpoints, `update` | 0.28 | 107 µs | 3% |

The store spends 1.06 ms per command inside these operations; its container used 2.07 ms of CPU per command at the same rate in stage 1. `top` counts statements, not requests: in a second probe at the same rate (`serverStatus`), 1.28 update statements per command arrived in 0.55 `update` requests, so the snapshots' upserts are batched (D248 ②, one unordered `bulkWrite` per round and aggregate type) at about two per request beside the checkpoints; the log's inserts were 1.00 documents in 0.99 requests, one per append, since at this load the append queue rarely holds two of one partition. A snapshot per version is the store's work by design (the snapshots are also the query model); batching saves round trips, not that work.

## Ranked candidates

By share of the limiting resource times how much of it a change can remove. Each gets its own paired measurement before it lands (one change at a time, alternating rounds, judged on the stable costs above).

| # | Candidate | Evidence | Expected effect | Size |
| --- | --- | --- | --- | --- |
| 1 | ~~**MongoDB: load the snapshot and the tail in one aggregation**, as D268 did on PostgreSQL~~ (tried and dropped, [below](#candidate-1-tried-one-aggregation-on-mongodb)) | a load is `find` + `aggregate`; L1 3.32 round trips per E1 command against PostgreSQL's 2.79; 74% of commands load | one round trip less per load (L1 about 2.3 per command), a lower p50 at low concurrency; one read point for both parts | contained in the MongoDB event log and snapshot store |
| 2 | **MongoDB: allocation in the snapshot store, log tail and driver** | 220 KB per command in the server, 2.6 times PostgreSQL in L1; snapshot store, tail and driver about two thirds of it | fewer bytes per command, less GC and carrier CPU (40% of the node's CPU) | find the decoding that dominates first |
| 3 | **PostgreSQL: fewer WAL flushes per command** | WAL 80–85% busy from 50% load; per command 0.93 append statements, each its own commit, beside 0.54 snapshot statements and 0.27 checkpoints in their own transactions | commit the derived writes (snapshots, checkpoints) without waiting for the flush, or in fewer transactions | a durability decision: needs agreement first (D267 ⑲ kept `synchronous_commit` on for the whole database) |
| 4 | **Hot aggregates: more than one command per commit** | zipf: throughput flat from concurrency 32, p50 2–3 ms; the hottest lanes commit one command at a time | the hottest aggregate's rate bounded by batches, not by one commit each | a change to the executor's lane: design first |
| 5 | **HTTP: WebFlux routing per request** | 17% of the node's CPU on Netty threads, 22% of allocation on PostgreSQL E1, mostly routing | a cheaper match for the command endpoint | medium |
| 6 | Kafka producer | 4.5% of the node's CPU | small | low priority |

## Candidate 1, tried: one aggregation on MongoDB

Measured on 2026-10-23 and dropped. The change ([`mongo-one-load.patch`](v10-local-baseline/mongo-one-load/mongo-one-load.patch), against `main` at `c58787552`) read a snapshot and its tail in one database aggregation when the snapshot store shares the log's client, database and context: the snapshot, then by `$lookup` on its version the tail from it and the request keys before it, unwound one row each, falling back to the reads apart otherwise. On MongoDB 7.0 both inner reads used the log's partial version index, examining as many keys as they returned, and the `$lookup` with its `$unwind` streamed the rows. The module's tests passed.

Four alternating rounds (A `main`, B the change, A, B; [`abab.sh`](v10-local-baseline/mongo-one-load/abab.sh), raw results beside it), each with L1 at concurrency 16, then a session on 100,000 primed accounts: closed loop at concurrency 1 to 4, and open loop at 1,720/s (E1) and 714/s (E3), about half of the baseline's peaks. Per command, the two rounds of each arm:

| | A | B |
| --- | --- | --- |
| L1 E1 round trips | 3.32, 3.33 | 2.30, 2.30 |
| L1 E3 round trips | 5.61, 5.59 | 4.59, 4.60 |
| L1 E1 allocated | 224, 225 KB | 231, 228 KB |
| E1 server CPU | 0.891, 0.904 ms | 1.008, 0.996 ms |
| E1 MongoDB CPU | 2.39, 1.65 ms | 2.53, 2.02 ms |
| E1 p50 / p99 | 9.7 / 84, 4.4 / 24 ms | 13.3 / 108, 6.0 / 48 ms |
| E3 server CPU | 2.170, 1.956 ms | 2.154, 2.026 ms |
| p50 at concurrency 1 | 1.45, 1.20 ms | 1.49, 1.43 ms |

- The round trip went as intended: the snapshot's `find` fell from 1.00 per command to none.
- Nothing it should buy followed. Server CPU on E1 rose about 11% in both pairs, beyond one run's ±2.5%; MongoDB's CPU and the latencies at 50% were worse in both pairs, though they vary widely between rounds of one arm; the p50 at concurrency 1, where a round trip saved should show first, did not fall.
- Inside MongoDB, the aggregation costs what it saves: 3,000 loads over 5,000 aggregates from `mongosh` in the container took 0.38–0.39 ms each as `find` plus `aggregate` and 0.40 ms as one aggregation. On loopback a round trip is tens of microseconds while a load is about 0.4 ms of the server's work, and the correlated `$lookup` with `$expr`, `$documents` and a nested `$unionWith` costs more to run than the two plain reads. D268 pays on PostgreSQL because one statement with a CTE is cheap there; MongoDB has no such cheap form.

Where the database is a network hop away a round trip weighs more, but nothing measured here supports the change, so it is not merged. The lever on MongoDB's loads is their execution and their number (the state cache), not their round trips.

## Candidate 2, first part: the tail read's pipeline as BSON

On MongoDB E1 at 50%, 9.7% of the node's allocation went to encoding the tail read's request ([`jfr-components.txt`](v10-local-baseline/jfr-components.txt) and the recording), most of it in the driver's rendering of the pipeline: it renders a pipeline of builders (`Aggregates`, `Filters`, `Projections`) on every call, and renders its last stage once more to look for `$out` or `$merge`, which for the tail read is the whole `$unionWith` of the request keys. Each rendering resolves codecs anew for every nested document. The fixed stages are now rendered once, and the matches and the limit built as BSON, so the driver renders nothing.

L1 on MongoDB, concurrency 16, two alternating rounds of `main` (A) and the change (C) ([`l1ab.sh`](v10-local-baseline/mongo-tail-bson/l1ab.sh), results beside it):

| Per command | A | C |
| --- | --- | --- |
| E1 allocated | 223.5, 224.3 KB | 199.5, 199.1 KB (−11%) |
| E3 allocated | 425.8, 423.4 KB | 402.5, 399.1 KB (−5.6%) |
| Round trips | 3.31–3.32 (E1), 5.59–5.60 (E3) | 3.32–3.34, 5.59–5.60 |

About 24 KB less per load, the same in E1 and E3 (one load per command in L1). The snapshot store's writes are next: their encoding was another 5.7% of the node's allocation.

## Candidate 2, second part: snapshot writes as BSON

A snapshot's save parsed its state into a JSON tree, copied it into a `Document`, wrapped each field in another `Document` for `$literal`, and left the driver to render it all through its codecs: 5.7% of the node's allocation in encoding and about as much in building. The state now streams from its JSON text into BSON (`ExactBson.bson`, by the rules of `ExactBson.document`; a test pins the two to the same bytes and the same refusals), and the filter and the update are built as BSON.

The same L1 rounds, `main` before the first part (A) against this change alone (D) ([results](v10-local-baseline/mongo-snapshot-bson/)):

| Per command | A | D |
| --- | --- | --- |
| E1 allocated | 222.6, 223.4 KB | 215.3, 216.1 KB (−3.3%) |
| E3 allocated | 424.9, 423.2 KB | 404.8, 406.1 KB (−4.5%) |
| Round trips | 3.32–3.33 (E1), 5.60 (E3) | 3.32–3.33, 5.60 |

About 7 KB less per snapshot written: E1 writes one per command, E3 three. With the tail read's change (first part) on `main` as well, L1 allocates 189.6–190.1 KB per E1 command and 379.3–380.0 KB per E3 command, against 223.5–224.3 and 423.4–425.8 KB before both: −15% and −11%.

## Candidate 2, both parts end to end

The two parts together (D2) against the baseline (A), on sessions of 100,000 primed accounts as in the one-aggregation trial: two alternating rounds, each with L1, a closed loop at concurrency 1 to 4 and open loops at 1,720/s (E1) and 714/s (E3) ([results](v10-local-baseline/mongo-allocation-e2e/)). Per command:

| | A | D2 |
| --- | --- | --- |
| E1 allocated | 216, 222 KB | 193, 190 KB (−12%) |
| E3 allocated | 517, 530 KB | 465, 457 KB (−12%) |
| E1 server CPU | 0.936, 0.946 ms | 0.884, 0.946 ms |
| E3 server CPU | 2.034, 2.111 ms | 1.952, 2.025 ms |
| E1 MongoDB CPU | 1.66, 1.60 ms | 1.60, 1.66 ms |
| E1 p50 / p99 | 4.6 / 26, 4.3 / 24 ms | 4.1 / 25, 4.5 / 25 ms |
| p50 at concurrency 1 | 1.30, 1.27 ms | 1.33, 1.35 ms |

- The allocation falls end to end as in L1, 12% per command in both scenarios and both rounds.
- Server CPU may fall with it, but not beyond doubt: E3 4% lower in both pairs, E1 6% lower in one pair and the same in the other, at the edge of one run's ±2.5%. The JVM's young collections are cheap at these rates, so bytes allocated buy little CPU here.
- MongoDB's CPU and the latencies, at 50% and at concurrency 1, do not move.

The parts stay: fewer bytes per command for the same work, from code that is no more complex. Further allocation work on MongoDB is unlikely to pay in CPU on this evidence; the next candidates go after what costs CPU.

## Candidate 2, tried: snapshot reads from raw BSON

Tried on 2026-10-24 and dropped. A snapshot's load decodes the stored document into a `Document` tree (6.8% of the node's allocation on MongoDB E1 was the driver's decoding of replies to loads), turns its state into a JSON tree and writes that as text. The change ([`mongo-snapshot-raw.patch`](v10-local-baseline/mongo-snapshot-raw/mongo-snapshot-raw.patch), with its tests) took the reply raw (`RawBsonDocument`), read it field by field in one pass and wrote the state to JSON text as it read it; tests pinned the text to the old one. The module's tests passed.

L1, `main` with the first two parts (D2) against the same with this change (E) ([results](v10-local-baseline/mongo-snapshot-raw/)):

| Per command | D2 | E |
| --- | --- | --- |
| E1 allocated | 189.6, 190.1 KB | 188.7, 190.1 KB |
| E3 allocated | 380.0, 379.3 KB | 381.6, 375.8 KB |

No difference beyond one run's noise, so it is not merged. A first comparison had shown −11% and −7%: its control was built before the first part reached `main` and the change after, so it measured the first part again; the rerun above has both arms on the same base. The tree of a snapshot's small document is a small share of what a load allocates; the reply's buffers are not, and a raw document keeps them.

## Notes on method

- Judge a change locally on the stable quantities: CPU and allocation per operation, round trips, commits, rows and WAL per operation. Throughput and latency only at low concurrency, or over many alternating rounds.
- Run cost measurements at about 50% of the closed-loop peak at most; at 75% the uniform runs queue or collapse, and their costs per operation shift with the queue (the stage 0 outlier).
- Attribute CPU by thread from `jdk.ThreadCPULoad`, not from execution samples, while the stores run on virtual threads.
- A recorded run is slower than an unrecorded one; compare recorded with recorded.
