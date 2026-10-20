# PS-2: what a partition costs when there is no traffic (D240 ②)

**Work package PS-2 of decision D240, a count-based measurement (D224's tiers): store round trips, not timings.** It measures what each partition of a V10 log costs with no traffic, on PostgreSQL and MongoDB, for P = 64, 128 and 256 partitions and 0, 1, 5 and 10 subscriptions beyond the service's own, to decide whether the default P should rise from 64.

## Recommendation

**Keep the default at P = 64.** Raise it per service (`wow.log.partitions`, 128 or 256) only for a service that needs more than 64 nodes or whose per-partition append rate the time-based tier shows to be the limit.

The numbers behind it:

- **The idle cost is linear in P and the same on both stores: 1.34 to 1.43 round trips a second per partition.** One node, no traffic: 90 round trips/s at P = 64, 176 at 128, 346 at 256. A tail poll per partition per second (the tail backs off to `wow.log.max-poll-interval`, 1 s) is 0.99 of it; the partition's lease renewal (every 3 s) 0.35; membership, timers, the parked-entry scan and the snapshot table about 2 a second for the whole node, whatever P.
- **Subscriptions do not change it.** Each subscription beyond the service's 8 adds 0.1 round trips a second to the node (its parked-entry scan, every 10 s), whatever P: 2.0/s with none, 3.0/s with 10 more. A subscription has no per-partition idle work: its checkpoints only move with entries, and the tail is shared.
- **Under traffic the per-command cost does not depend on P.** At a trickle of 10 commands/s, V10 MongoDB costs 6.4 to 6.8 round trips per command and PostgreSQL 3.5 to 3.6 at every P (one run each above: 7.65 and 4.15, both at P = 256, within the noise of 300 commands against an idle rate of 350/s). Checkpoint writes grow with the subscriptions, not P, at that rate: about 61 documents/s with the 8 subscriptions and 7.7 more per extra subscription, all in one round trip a second. Their ceiling under heavy traffic is subscriptions × P documents a second: 512 at the defaults (8 × 64), 4,608 at 18 × 256, still one round trip.
- **On MongoDB the idle cost is also memory churn**: about 70 KiB/s allocated per partition (4.6 MiB/s at 64, 8.6 at 128, 17 at 256), against 13 KiB/s on PostgreSQL. At 256 partitions that is 17 MiB/s of garbage on an idle node with a 1 GiB heap.
- **What a larger P buys is not needed by default.** P bounds the nodes that can share the work (64 already exceeds any cluster the benchmark or the examples run) and spreads appends over more partition sequences, a throughput question for the time-based tier. D240 ① keeps a later doubling cheap: P must divide 4,096 slots, so 64 → 128 → 256 moves no history, and the online split comes in 10.x.

If the idle cost of a larger P ever matters, two levers are cheaper than a smaller P, both framework changes outside this measurement: a longer `wow.log.max-poll-interval` (the tail polls are 70% of the idle cost; 2 s halves them, at up to 2 s for another node's entries to be read, finding A2-14), and renewing a node's leases in one round trip per round instead of one per partition (B.D's class (c) item, 25% of the idle cost).

## Results

One V10 node, measured after seeding 1,000 accounts (so every partition has entries) and 15 s of settling: an **idle** window of 60 s with no requests, then a **trickle** window of E1 at 10 commands/s for 30 s (300 commands, all completed) plus 5 s. Round trips per second; "per partition" is the idle total divided by P.

### MongoDB

| P | Extra subscriptions | Idle round trips/s | Per partition | Lease | Tail | Other periodic | Idle alloc MiB/s | Trickle checkpoint docs/s | Trickle round trips/s | Round trips per trickle command |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 64 | 0 | 89.7 | 1.40 | 24.8 | 62.9 | 2.0 | 4.57 | 61.4 | 145.3 | 6.81 |
| 64 | 1 | 91.3 | 1.43 | 24.8 | 63.7 | 2.1 | 4.53 | 68.1 | 145.7 | 6.65 |
| 64 | 5 | 90.2 | 1.41 | 24.8 | 62.9 | 2.5 | 4.57 | 99.8 | 145.8 | 6.81 |
| 64 | 10 | 91.5 | 1.43 | 24.9 | 63.7 | 3.0 | 4.78 | 138.9 | 144.7 | 6.50 |
| 128 | 0 | 176.4 | 1.38 | 47.1 | 127.4 | 2.0 | 8.55 | 62.6 | 228.2 | 6.37 |
| 128 | 1 | 175.1 | 1.37 | 47.1 | 125.8 | 2.1 | 8.81 | 71.0 | 229.2 | 6.63 |
| 128 | 5 | 175.5 | 1.37 | 47.2 | 125.8 | 2.5 | 8.78 | 102.2 | 229.1 | 6.54 |
| 128 | 10 | 175.9 | 1.37 | 47.1 | 125.8 | 3.0 | 8.78 | 141.7 | 229.9 | 6.59 |
| 256 | 0 | 343.5 | 1.34 | 89.8 | 251.7 | 2.0 | 16.94 | 64.3 | 398.7 | 6.73 |
| 256 | 1 | 343.9 | 1.34 | 89.8 | 252.1 | 2.1 | 17.09 | 72.2 | 406.5 | 7.65 |
| 256 | 5 | 343.8 | 1.34 | 89.7 | 251.6 | 2.5 | 16.94 | 104.6 | 399.1 | 6.75 |
| 256 | 10 | 344.6 | 1.35 | 89.7 | 251.9 | 3.0 | 16.93 | 144.9 | 399.8 | 6.74 |

### PostgreSQL

| P | Extra subscriptions | Idle round trips/s | Per partition | Lease | Tail | Other periodic | Idle alloc MiB/s | Trickle checkpoint statements/s | Trickle round trips/s | Round trips per trickle command |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 64 | 0 | 90.7 | 1.42 | 24.8 | 63.9 | 2.0 | 0.88 | 60.4 | 120.2 | 3.60 |
| 64 | 1 | 90.9 | 1.42 | 24.9 | 64.0 | 2.1 | 0.89 | 68.5 | 119.6 | 3.51 |
| 64 | 5 | 91.2 | 1.43 | 24.8 | 63.9 | 2.5 | 0.89 | 99.5 | 120.2 | 3.54 |
| 64 | 10 | 90.8 | 1.42 | 24.8 | 63.0 | 3.0 | 0.98 | 137.5 | 120.4 | 3.62 |
| 128 | 0 | 176.0 | 1.38 | 46.2 | 127.9 | 2.0 | 1.67 | 62.5 | 204.6 | 3.51 |
| 128 | 1 | 176.1 | 1.38 | 46.1 | 127.9 | 2.1 | 1.63 | 70.1 | 204.3 | 3.45 |
| 128 | 5 | 176.5 | 1.38 | 46.1 | 127.9 | 2.5 | 1.65 | 103.2 | 204.9 | 3.47 |
| 128 | 10 | 176.1 | 1.38 | 46.2 | 126.9 | 3.0 | 1.56 | 141.9 | 205.6 | 3.60 |
| 256 | 0 | 346.5 | 1.35 | 88.8 | 255.7 | 2.0 | 3.20 | 63.8 | 375.0 | 3.48 |
| 256 | 1 | 346.6 | 1.35 | 88.8 | 255.7 | 2.1 | 3.25 | 72.3 | 375.7 | 3.55 |
| 256 | 5 | 345.5 | 1.35 | 88.8 | 254.2 | 2.5 | 3.15 | 104.8 | 373.8 | 3.46 |
| 256 | 10 | 347.6 | 1.36 | 88.8 | 255.9 | 3.0 | 3.17 | 142.6 | 381.2 | 4.15 |

The PostgreSQL trickle round trips count each checkpoint statement; a tick's statements are one JDBC batch, so the round trips are lower by about the checkpoint column, less one a second.

## Setup

- 2026-10-19, Apple M4 Pro, 14 cores, macOS 27; Docker Desktop with the harness's `mongo:7.0` (one-node replica set), `postgres:17-alpine` with `pg_stat_statements` and `confluentinc/cp-kafka:7.4.0`. One node, Corretto 21.0.12.1, `-Xms1g -Xmx1g -XX:+UseG1GC`, V10's defaults otherwise.
- V10: `origin/v10` `4251e3463`, merged into branch `v10-bench-b1`; the framework code is unchanged by the branch. Harness commits `e2e4ffe77` (MongoDB) and `7fd6dfe1f` (PostgreSQL), in each run's `config.txt`.
- The service's own 8 subscriptions: `snapshot`, `reservation`, `timers`, `wow.snapshot.rebuild`, `kafka-sink.account` and the saga's `TransferSaga.onPrepared`, `onAmountEntered` and `onEntryFailed`. The extra ones are no-op projections over the context's log (`--bench.extra-subscriptions`, `bench-v10`'s `transfer/ps2/IdleSubscriptions.kt`, harness-only). The PostgreSQL runs list the subscriptions in `subscriptions.txt`; on MongoDB the harness's query of them was wrong during the run (fixed since) and the list was checked by hand at P = 64 with one extra.
- Each configuration empties the stores and the topics and restarts the node (the containers stay), so each P starts on an empty log. Counting: MongoDB's `serverStatus` command counters and its profiler, PostgreSQL's `pg_stat_statements`; allocations from the node's `ThreadMXBean`.
- Reproduce: `wow-benchmarks/e2e/harness/counts.sh ps2 mongo <out>` and `... ps2 postgres <out>`, then `counts.py ps2 <out>`. Raw windows in [`v10-ps2-partition-idle/raw/`](v10-ps2-partition-idle/raw/), the computed numbers in [`ps2.json`](v10-ps2-partition-idle/ps2.json).
