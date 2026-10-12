# V10 Spike H6: Do Storage Leases Support Membership And Rebalancing?

Spike H6 of the V10 design (`documentation/designs/v10/decisions.md` on the `v10` branch) asks whether partition leases kept in the application's own database (PostgreSQL or MongoDB) can carry cluster membership and rebalancing. It runs the cluster code of roadmap 4.2a (`PartitionOwnership`, HRW, hand-off, `PartitionedRouter` with Local fallback) unchanged, with real lease stores in containers, under a steady write load.

In short: owners converge in about 2 s after a join or a graceful leave, and in about the lease TTL plus 1 s after a kill (10–11 s with the defaults, 6.2–6.5 s with a 5 s TTL). Every change moved exactly the HRW share of the node that joined or went, about P/N. A graceful hand-off handled no entry twice. A kill handled 0.7–1.8% of the window's entries twice. Commands kept succeeding through every change: at least 99.45% succeeded in each cell, except MongoDB with 256 partitions and the 10 s TTL. The H6 abandon criterion (convergence too slow) is **not triggered**. The spike did find a defect in `PartitionOwnership`: it renews leases one at a time. When a renewal round takes longer than the margin between the renew interval and the TTL, a healthy node loses its own leases. On MongoDB with 256 partitions, 2 of 10 repetitions turned that into a collapse that lasted until the repetition ended.

## What Is Measured

`test/lease-spike` (`:lease-spike`): `LeaseSpike`, plus `RenewalRoundSpike` for the defect. Both run only with `-Dwow.spike.h6=true`.

- **Nodes**: up to 5 in-process nodes. Each wires what a production node wires for one context:
  - its own database clients: a HikariCP pool of 16 for PostgreSQL, or a `MongoClient` with up to 32 connections;
  - its own lease store, log, checkpoint store and failure ledger on those clients;
  - its own `Kernel` with an `LruStateCache` of 10,000 states, `Executor`, `SubscriptionRuntime` (default `SubscriptionOptions`, so checkpoints are saved every second), `PartitionOwnership`, `PartitionedRouter`, `WaitCoordinator` and `ForwardEndpoint`.

  The nodes reach each other through `LoopbackClusterChannel`, which serializes every forward for real. All nodes share one database container.
- **Subscription**: one projector subscription per node over the whole context. It records every (aggregate, version) it handles across all nodes, so a second handling of the same entry counts as a duplicate.
- **Load**: an open loop of 200 deposits a second, each sent to a uniformly random live node and awaited until `COMMITTED`. Aggregates follow a Zipf distribution (s = 1.1) over 10,000 accounts, so the hottest account takes about 10% of commands and Local fallback produces real version conflicts. Counted per second:
  - commands sent, succeeded and failed, by result code;
  - version conflicts that the kernel retried;
  - commands decided on a node that did not hold their partition (Local fallback, D71);
  - forwards and the forwards that failed;
  - renewals that found their lease lost.
- **Repetition**: a fresh context, so fresh log tables and a fresh lease group, and fresh node names, so HRW draws a new assignment each time.
  1. 3 nodes start; then 3 s of load before the first change.
  2. The changes, one at a time: join 3→4, join 4→5, leave 5→4, leave 4→3, join 3→4, join 4→5, kill 5→4, kill 4→3. The node that leaves or is killed is picked at random.
  3. After each change converges, 3 s of quiet before the next one.

  A *leave* is `PartitionOwnership.stop()`: it leaves the members, then hands every partition off (stop accepting, drain, save checkpoints, release). The load balancer stops sending to the node first.

  A *kill* models a crash. The node stops receiving commands and its channel address is unbound. It saves no checkpoint and records no handling from then on. Then `PartitionOwnership.abort()` (internal, called by reflection) stops its loops without releasing anything, so its membership and leases expire on their own.
- **Convergence**: an observer polls every 50 ms, on clients of its own. A change has converged when all of these hold at once:
  - every partition has a live lease held by its HRW owner among the expected members;
  - every member holds and accepts exactly its HRW share;
  - every member's route table lists the expected members and owners.

  The time each condition first held on its own is recorded too. The step timeout is 60 s, and no step reached it.
- **Partitions moved**: lease holders before the change compared with lease holders at convergence. They are reported against the HRW share of the node that joined or went, and against the ideal P/N, where N is the larger membership.
- **Duplicates**: entries handled again between a change and the next one, out of the entries first handled in that interval. They are also counted for the partitions that moved alone.
- **Matrix**: PostgreSQL and MongoDB × two configurations × P = 64 and 256 × 10 repetitions, which is 720 measured changes:
  - *10 s*: the production defaults (D72): lease TTL 10 s, renew 3 s, member TTL 10 s, heartbeat 3 s, reconcile and route refresh 1 s.
  - *5 s*: lease TTL 5 s, renew 1.5 s, member TTL 5 s, heartbeat 1.5 s, everything else unchanged.

  Each backend starts with one discarded warm-up repetition.
- **Repetitions**: 10 per cell. That gives 20 samples for the join cells, because each repetition joins twice, and 10 for the others. The 95% bootstrap interval of the median is within ±0.2 s for every PostgreSQL and every 64-partition cell except the leaves, whose samples split between about 1.2 s and about 2.1 s (one reconcile tick). The kill cells are within ±1 s.

## Environment

- Apple M4 (10 cores), 16 GiB, macOS 27.0.1. Docker Desktop 29.8.2 (Linux VM with 10 CPUs and 7.7 GiB).
- PostgreSQL 17.11 (`postgres:17-alpine`, defaults including `fsync` and `synchronous_commit`, except `max_connections=400`).
- MongoDB 7.0.43 (`mongo:7.0`, single-member replica set, default write concern).
- JDK: Eclipse Temurin 21.0.12.1+1, 4 GiB test heap. Drivers and HikariCP from the Spring Boot BOM. Testcontainers 2.0.5.
- The machine was shared with other agents' Gradle builds during the run. That is uncontrolled background load (see the caveats).

## Results

### Owner Convergence (seconds from the change)

| Backend | TTL | P | Change | n | median | 95% CI of median | p95 | max | leases alone, median |
|---|---|---:|---|---:|---:|---|---:|---:|---:|
| PostgreSQL | 10 s | 64 | join 3→4 | 20 | 2.06 | 2.02–2.13 | 2.50 | 2.67 | 1.11 |
| PostgreSQL | 10 s | 64 | join 4→5 | 20 | 2.00 | 1.89–2.05 | 2.36 | 2.76 | 1.18 |
| PostgreSQL | 10 s | 64 | leave 5→4 | 10 | 1.27 | 1.24–1.47 | 2.14 | 2.14 | 0.29 |
| PostgreSQL | 10 s | 64 | leave 4→3 | 10 | 1.17 | 1.08–2.08 | 2.22 | 2.22 | 0.20 |
| PostgreSQL | 10 s | 64 | kill 5→4 | 10 | 9.95 | 9.24–10.97 | 11.04 | 11.04 | 9.19 |
| PostgreSQL | 10 s | 64 | kill 4→3 | 10 | 11.01 | 9.81–11.49 | 11.83 | 11.83 | 10.13 |
| PostgreSQL | 10 s | 256 | join 3→4 | 20 | 2.06 | 1.99–2.37 | 3.29 | 3.36 | 1.29 |
| PostgreSQL | 10 s | 256 | join 4→5 | 20 | 1.96 | 1.83–2.13 | 3.06 | 3.16 | 1.15 |
| PostgreSQL | 10 s | 256 | leave 5→4 | 10 | 2.15 | 1.23–2.29 | 2.33 | 2.33 | 1.21 |
| PostgreSQL | 10 s | 256 | leave 4→3 | 10 | 2.07 | 1.20–2.12 | 2.18 | 2.18 | 1.10 |
| PostgreSQL | 10 s | 256 | kill 5→4 | 10 | 10.11 | 9.77–11.21 | 11.91 | 11.91 | 9.16 |
| PostgreSQL | 10 s | 256 | kill 4→3 | 10 | 10.58 | 10.11–11.17 | 11.52 | 11.52 | 10.02 |
| PostgreSQL | 5 s | 64 | join 3→4 | 20 | 2.12 | 2.06–2.16 | 2.26 | 2.27 | 1.26 |
| PostgreSQL | 5 s | 64 | join 4→5 | 20 | 1.95 | 1.82–2.06 | 2.21 | 2.26 | 1.16 |
| PostgreSQL | 5 s | 64 | leave 5→4 | 10 | 1.20 | 1.12–1.24 | 2.05 | 2.05 | 0.21 |
| PostgreSQL | 5 s | 64 | leave 4→3 | 10 | 1.15 | 1.12–2.05 | 2.06 | 2.06 | 0.18 |
| PostgreSQL | 5 s | 64 | kill 5→4 | 10 | 6.37 | 6.18–6.58 | 6.79 | 6.79 | 5.39 |
| PostgreSQL | 5 s | 64 | kill 4→3 | 10 | 6.16 | 5.83–6.42 | 6.67 | 6.67 | 5.20 |
| PostgreSQL | 5 s | 256 | join 3→4 | 20 | 2.44 | 2.07–3.13 | 3.81 | 6.31 | 1.52 |
| PostgreSQL | 5 s | 256 | join 4→5 | 20 | 2.15 | 1.80–2.46 | 3.33 | 3.67 | 1.24 |
| PostgreSQL | 5 s | 256 | leave 5→4 | 10 | 1.32 | 1.16–2.25 | 3.15 | 3.15 | 0.46 |
| PostgreSQL | 5 s | 256 | leave 4→3 | 10 | 2.09 | 1.13–2.17 | 3.38 | 3.38 | 1.17 |
| PostgreSQL | 5 s | 256 | kill 5→4 | 10 | 6.33 | 6.17–6.82 | 7.36 | 7.36 | 5.47 |
| PostgreSQL | 5 s | 256 | kill 4→3 | 10 | 6.17 | 5.88–6.72 | 7.02 | 7.02 | 5.31 |
| MongoDB | 10 s | 64 | join 3→4 | 20 | 2.04 | 2.01–2.10 | 2.17 | 2.17 | 1.18 |
| MongoDB | 10 s | 64 | join 4→5 | 20 | 1.99 | 1.82–2.08 | 2.12 | 2.14 | 1.14 |
| MongoDB | 10 s | 64 | leave 5→4 | 10 | 1.19 | 1.08–1.30 | 2.06 | 2.06 | 0.18 |
| MongoDB | 10 s | 64 | leave 4→3 | 10 | 1.64 | 1.13–2.07 | 2.12 | 2.12 | 0.67 |
| MongoDB | 10 s | 64 | kill 5→4 | 10 | 9.96 | 9.73–11.07 | 11.80 | 11.80 | 8.99 |
| MongoDB | 10 s | 64 | kill 4→3 | 10 | 10.18 | 9.53–10.57 | 11.61 | 11.61 | 9.19 |
| MongoDB | 10 s | 256 | join 3→4 | 20 | 3.11 | 2.14–3.95 | 10.44 | 13.37 | 2.34 |
| MongoDB | 10 s | 256 | join 4→5 | 20 | 2.05 | 1.51–2.93 | 8.53 | 19.88 | 1.36 |
| MongoDB | 10 s | 256 | leave 5→4 | 10 | 2.08 | 1.39–3.34 | 4.71 | 4.71 | 1.12 |
| MongoDB | 10 s | 256 | leave 4→3 | 10 | 2.12 | 1.70–3.48 | 50.97 | 50.97 | 1.32 |
| MongoDB | 10 s | 256 | kill 5→4 | 10 | 10.95 | 10.26–11.21 | 11.98 | 11.98 | 10.18 |
| MongoDB | 10 s | 256 | kill 4→3 | 10 | 11.26 | 9.99–11.81 | 14.44 | 14.44 | 10.62 |
| MongoDB | 5 s | 64 | join 3→4 | 20 | 2.07 | 2.04–2.08 | 2.09 | 2.12 | 1.14 |
| MongoDB | 5 s | 64 | join 4→5 | 20 | 2.01 | 1.98–2.04 | 2.12 | 2.17 | 1.14 |
| MongoDB | 5 s | 64 | leave 5→4 | 10 | 1.22 | 1.09–1.52 | 2.02 | 2.02 | 0.23 |
| MongoDB | 5 s | 64 | leave 4→3 | 10 | 2.02 | 1.16–2.06 | 2.07 | 2.07 | 1.03 |
| MongoDB | 5 s | 64 | kill 5→4 | 10 | 6.45 | 6.23–6.70 | 6.93 | 6.93 | 5.46 |
| MongoDB | 5 s | 64 | kill 4→3 | 10 | 6.18 | 5.95–6.42 | 6.87 | 6.87 | 5.22 |
| MongoDB | 5 s | 256 | join 3→4 | 20 | 2.06 | 1.42–2.30 | 3.09 | 3.11 | 1.32 |
| MongoDB | 5 s | 256 | join 4→5 | 20 | 2.04 | 1.78–2.12 | 2.57 | 2.68 | 1.29 |
| MongoDB | 5 s | 256 | leave 5→4 | 10 | 1.78 | 1.21–2.14 | 2.36 | 2.36 | 0.71 |
| MongoDB | 5 s | 256 | leave 4→3 | 10 | 1.19 | 1.14–2.06 | 2.08 | 2.08 | 0.26 |
| MongoDB | 5 s | 256 | kill 5→4 | 10 | 6.18 | 6.12–6.69 | 6.85 | 6.85 | 5.32 |
| MongoDB | 5 s | 256 | kill 4→3 | 10 | 6.13 | 5.97–6.47 | 6.59 | 6.59 | 5.25 |

The first start of 3 nodes converged in 1.1–1.9 s (median) in every cell. The raw JSON has it.

### Partitions Moved Per Change (all backends and configurations)

| P | Change | n | moved, mean (min–max) | HRW share of the node, mean | ideal P/N | steps with extra moves |
|---:|---|---:|---|---:|---:|---:|
| 64 | join 3→4 | 80 | 16.4 (7–24) | 16.4 | 16.0 | 0 |
| 64 | join 4→5 | 80 | 13.4 (7–21) | 13.4 | 12.8 | 0 |
| 64 | leave 5→4 | 40 | 11.9 (5–18) | 11.9 | 12.8 | 0 |
| 64 | leave 4→3 | 40 | 15.5 (10–26) | 15.5 | 16.0 | 0 |
| 64 | kill 5→4 | 40 | 13.2 (6–21) | 13.2 | 12.8 | 0 |
| 64 | kill 4→3 | 40 | 15.6 (6–21) | 15.6 | 16.0 | 0 |
| 256 | join 3→4 | 80 | 65.3 (46–91) | 64.7 | 64.0 | 2 |
| 256 | join 4→5 | 80 | 50.5 (33–69) | 50.2 | 51.2 | 2 |
| 256 | leave 5→4 | 40 | 50.8 (42–61) | 50.8 | 51.2 | 0 |
| 256 | leave 4→3 | 40 | 65.2 (46–76) | 65.2 | 64.0 | 0 |
| 256 | kill 5→4 | 40 | 53.0 (39–64) | 53.0 | 51.2 | 0 |
| 256 | kill 4→3 | 40 | 65.0 (50–79) | 65.0 | 64.0 | 0 |

The 4 steps with extra moves all began while partitions had no live lease, because leases had just been lost (see the defect below). The comparison counts a partition that had no holder before the change as moved. No step moved a partition between two nodes that both stayed.

### Duplicate Handling And Command Availability

*Entries* and *handled twice* run from the change to the next change. *Sent* through *lost leases* count from the change until a second after convergence, summed over the repetitions. The p99 is the median over repetitions of that window's p99. The baseline is the 3 s before the change. All failures were `Contended` (a command that lost the version slot 3 times) or, in MongoDB 10 s / 256 only, commands that did not finish within the driver's 30 s timeout.

| Backend | TTL | P | Change | entries | handled twice | % of entries | % of moved partitions' entries | sent | failed | of which Contended | conflict retries | non-owner executions | failed forwards | lost leases | p99 ms (baseline) |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| PostgreSQL | 10 s | 64 | join 3→4 | 20287 | 0 | 0.00 | 0.00 | 16002 | 7 (0.04%) | 7 | 308 | 929 | 89 | 0 | 18 (19) |
| PostgreSQL | 10 s | 64 | join 4→5 | 19971 | 0 | 0.00 | 0.00 | 16003 | 1 (0.01%) | 1 | 281 | 982 | 140 | 2 | 23 (16) |
| PostgreSQL | 10 s | 64 | leave 5→4 | 8729 | 0 | 0.00 | 0.00 | 6999 | 12 (0.17%) | 12 | 152 | 293 | 41 | 0 | 15 (37) |
| PostgreSQL | 10 s | 64 | leave 4→3 | 8948 | 0 | 0.00 | 0.00 | 7199 | 0 (0.00%) | 0 | 136 | 286 | 31 | 0 | 38 (17) |
| PostgreSQL | 10 s | 64 | kill 5→4 | 26028 | 250 | 0.96 | 4.93 | 24002 | 20 (0.08%) | 20 | 1824 | 5253 | 3280 | 0 | 25 (12) |
| PostgreSQL | 10 s | 64 | kill 4→3 | 27457 | 263 | 0.96 | 4.08 | 25399 | 8 (0.03%) | 8 | 2065 | 6512 | 3884 | 0 | 15 (17) |
| PostgreSQL | 10 s | 256 | join 3→4 | 21188 | 0 | 0.00 | 0.00 | 16797 | 3 (0.02%) | 3 | 298 | 1145 | 114 | 2 | 57 (50) |
| PostgreSQL | 10 s | 256 | join 4→5 | 20284 | 0 | 0.00 | 0.00 | 16200 | 9 (0.06%) | 9 | 306 | 985 | 135 | 2 | 54 (50) |
| PostgreSQL | 10 s | 256 | leave 5→4 | 9794 | 0 | 0.00 | 0.00 | 8002 | 28 (0.35%) | 28 | 253 | 630 | 44 | 0 | 74 (52) |
| PostgreSQL | 10 s | 256 | leave 4→3 | 9665 | 0 | 0.00 | 0.00 | 7601 | 3 (0.04%) | 3 | 177 | 508 | 33 | 14 | 57 (57) |
| PostgreSQL | 10 s | 256 | kill 5→4 | 26851 | 199 | 0.74 | 3.65 | 24997 | 36 (0.14%) | 36 | 1834 | 5552 | 3405 | 0 | 55 (52) |
| PostgreSQL | 10 s | 256 | kill 4→3 | 27185 | 246 | 0.90 | 4.10 | 24997 | 21 (0.08%) | 21 | 1892 | 5964 | 3621 | 0 | 13 (18) |
| PostgreSQL | 5 s | 64 | join 3→4 | 20202 | 0 | 0.00 | 0.00 | 16401 | 1 (0.01%) | 1 | 356 | 1173 | 96 | 2 | 16 (13) |
| PostgreSQL | 5 s | 64 | join 4→5 | 19658 | 0 | 0.00 | 0.00 | 15403 | 3 (0.02%) | 3 | 278 | 1038 | 116 | 1 | 13 (13) |
| PostgreSQL | 5 s | 64 | leave 5→4 | 8523 | 0 | 0.00 | 0.00 | 6401 | 0 (0.00%) | 0 | 101 | 204 | 38 | 0 | 12 (13) |
| PostgreSQL | 5 s | 64 | leave 4→3 | 8915 | 0 | 0.00 | 0.00 | 6801 | 2 (0.03%) | 2 | 144 | 341 | 30 | 0 | 15 (12) |
| PostgreSQL | 5 s | 64 | kill 5→4 | 18702 | 254 | 1.36 | 6.04 | 16996 | 13 (0.08%) | 13 | 1119 | 3404 | 1864 | 0 | 21 (13) |
| PostgreSQL | 5 s | 64 | kill 4→3 | 18225 | 245 | 1.34 | 5.89 | 15797 | 22 (0.14%) | 22 | 1100 | 3437 | 1917 | 0 | 18 (13) |
| PostgreSQL | 5 s | 256 | join 3→4 | 22899 | 0 | 0.00 | 0.00 | 18797 | 102 (0.54%) | 102 | 801 | 1883 | 289 | 51 | 133 (44) |
| PostgreSQL | 5 s | 256 | join 4→5 | 21303 | 0 | 0.00 | 0.00 | 16401 | 27 (0.16%) | 27 | 452 | 1272 | 207 | 10 | 68 (96) |
| PostgreSQL | 5 s | 256 | leave 5→4 | 9405 | 0 | 0.00 | 0.00 | 7399 | 33 (0.45%) | 33 | 335 | 569 | 41 | 22 | 55 (99) |
| PostgreSQL | 5 s | 256 | leave 4→3 | 9931 | 0 | 0.00 | 0.00 | 8002 | 5 (0.06%) | 5 | 271 | 517 | 31 | 15 | 57 (56) |
| PostgreSQL | 5 s | 256 | kill 5→4 | 18916 | 185 | 0.98 | 4.78 | 16999 | 26 (0.15%) | 26 | 1125 | 3265 | 1686 | 0 | 24 (68) |
| PostgreSQL | 5 s | 256 | kill 4→3 | 18427 | 328 | 1.78 | 6.82 | 16599 | 37 (0.22%) | 37 | 1424 | 4063 | 1982 | 0 | 82 (19) |
| MongoDB | 10 s | 64 | join 3→4 | 19810 | 0 | 0.00 | 0.00 | 16003 | 0 (0.00%) | 0 | 336 | 1045 | 75 | 0 | 11 (10) |
| MongoDB | 10 s | 64 | join 4→5 | 19616 | 0 | 0.00 | 0.00 | 15798 | 2 (0.01%) | 2 | 352 | 1147 | 122 | 0 | 11 (11) |
| MongoDB | 10 s | 64 | leave 5→4 | 8552 | 0 | 0.00 | 0.00 | 6401 | 0 (0.00%) | 0 | 169 | 319 | 39 | 0 | 11 (10) |
| MongoDB | 10 s | 64 | leave 4→3 | 9236 | 0 | 0.00 | 0.00 | 6998 | 0 (0.00%) | 0 | 153 | 380 | 28 | 0 | 10 (9) |
| MongoDB | 10 s | 64 | kill 5→4 | 26763 | 183 | 0.68 | 3.52 | 24600 | 14 (0.06%) | 14 | 1613 | 5153 | 3250 | 0 | 12 (10) |
| MongoDB | 10 s | 64 | kill 4→3 | 26296 | 232 | 0.88 | 3.54 | 24397 | 1 (0.00%) | 1 | 1895 | 6341 | 3951 | 0 | 16 (9) |
| MongoDB | 10 s | 256 | join 3→4 | 27670 | 37 | 0.13 | 0.53 | 24397 | 1082 (4.43%) | 472 | 2720 | 4929 | 2296 | 40 | 187 (60) |
| MongoDB | 10 s | 256 | join 4→5 | 25041 | 30 | 0.12 | 0.62 | 22798 | 1465 (6.43%) | 595 | 3712 | 6020 | 2576 | 214 | 68 (75) |
| MongoDB | 10 s | 256 | leave 5→4 | 10690 | 0 | 0.00 | 0.00 | 8999 | 476 (5.29%) | 139 | 1155 | 1705 | 732 | 22 | 109 (229) |
| MongoDB | 10 s | 256 | leave 4→3 | 18341 | 0 | 0.00 | 0.00 | 18401 | 2088 (11.35%) | 355 | 3433 | 5978 | 2319 | 496 | 70 (82) |
| MongoDB | 10 s | 256 | kill 5→4 | 26687 | 369 | 1.38 | 7.25 | 25598 | 680 (2.66%) | 469 | 2988 | 6765 | 4257 | 0 | 252 (48) |
| MongoDB | 10 s | 256 | kill 4→3 | 30164 | 457 | 1.52 | 5.43 | 26401 | 924 (3.50%) | 589 | 4204 | 9528 | 5902 | 20 | 2569 (91) |
| MongoDB | 5 s | 64 | join 3→4 | 19998 | 0 | 0.00 | 0.00 | 16403 | 0 (0.00%) | 0 | 159 | 624 | 77 | 0 | 10 (10) |
| MongoDB | 5 s | 64 | join 4→5 | 19850 | 0 | 0.00 | 0.00 | 16203 | 0 (0.00%) | 0 | 167 | 722 | 112 | 0 | 10 (10) |
| MongoDB | 5 s | 64 | leave 5→4 | 8663 | 0 | 0.00 | 0.00 | 6396 | 0 (0.00%) | 0 | 94 | 170 | 37 | 0 | 10 (10) |
| MongoDB | 5 s | 64 | leave 4→3 | 9476 | 0 | 0.00 | 0.00 | 7599 | 0 (0.00%) | 0 | 128 | 351 | 27 | 3 | 10 (10) |
| MongoDB | 5 s | 64 | kill 5→4 | 18801 | 253 | 1.35 | 6.16 | 16595 | 12 (0.07%) | 12 | 1076 | 3344 | 1888 | 0 | 11 (9) |
| MongoDB | 5 s | 64 | kill 4→3 | 18270 | 231 | 1.26 | 4.92 | 16204 | 7 (0.04%) | 7 | 1343 | 3868 | 2088 | 0 | 11 (10) |
| MongoDB | 5 s | 256 | join 3→4 | 20106 | 0 | 0.00 | 0.00 | 16400 | 2 (0.01%) | 2 | 280 | 911 | 90 | 8 | 17 (13) |
| MongoDB | 5 s | 256 | join 4→5 | 20016 | 0 | 0.00 | 0.00 | 15404 | 2 (0.01%) | 2 | 283 | 922 | 112 | 1 | 12 (14) |
| MongoDB | 5 s | 256 | leave 5→4 | 9445 | 0 | 0.00 | 0.00 | 7398 | 0 (0.00%) | 0 | 205 | 358 | 40 | 0 | 16 (26) |
| MongoDB | 5 s | 256 | leave 4→3 | 8912 | 0 | 0.00 | 0.00 | 7196 | 0 (0.00%) | 0 | 166 | 292 | 31 | 0 | 12 (15) |
| MongoDB | 5 s | 256 | kill 5→4 | 18721 | 278 | 1.48 | 7.34 | 16800 | 4 (0.02%) | 4 | 984 | 3178 | 1750 | 0 | 17 (10) |
| MongoDB | 5 s | 256 | kill 4→3 | 18263 | 263 | 1.44 | 5.29 | 16398 | 22 (0.13%) | 22 | 1485 | 4333 | 2307 | 0 | 18 (17) |

### Second By Second Around A Kill (PostgreSQL, 10 s TTL, 64 partitions, 5→4, mean of 10 repetitions)

| s | sent | failed | conflict retries | non-owner executions | failed forwards |
|---:|---:|---:|---:|---:|---:|
| -1 | 200 | 0.0 | 0.2 | 0.0 | 0.0 |
| 0 | 200 | 0.2 | 9.5 | 31.1 | 22.1 |
| 1 | 200 | 0.1 | 14.9 | 55.2 | 40.3 |
| 2–7 (each) | 200 | 0.1–0.4 | 16.2–21.0 | 54.8–60.1 | 36.9–39.4 |
| 8 | 200 | 0.1 | 17.9 | 49.7 | 27.9 |
| 9 | 200 | 0.2 | 16.5 | 34.3 | 6.5 |
| 10 | 200 | 0.0 | 10.4 | 14.6 | 0.0 |
| 11 | 200 | 0.0 | 4.8 | 2.6 | 0.0 |

MongoDB with the same settings has the same shape: up to 17 conflict retries and 57 non-owner executions a second, back to zero at second 11. During a graceful leave the disturbance lasts about 2 s: at most 11 conflict retries and 20 non-owner executions a second, then 1–2. Every cell's profile is in the JSON.

### Lease Store Calls

| Backend | TTL | P | renew calls/s (all nodes) | renew p50 ms | renew p99 ms | renew max ms | acquire p99 ms | lost leases in the change windows |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| PostgreSQL | 10 s | 64 | 19 | 1.3 | 20.3 | 214 | 20.2 | 2 |
| PostgreSQL | 10 s | 256 | 68 | 1.6 | 30.0 | 243 | 27.9 | 18 |
| PostgreSQL | 5 s | 64 | 39 | 1.7 | 8.0 | 119 | 7.7 | 3 |
| PostgreSQL | 5 s | 256 | 121 | 1.2 | 64.4 | 633 | 61.4 | 118 |
| MongoDB | 10 s | 64 | 19 | 1.5 | 4.6 | 84 | 6.1 | 0 |
| MongoDB | 10 s | 256 | 52 | 5.2 | 212.5 | 886 | 288.1 | 792 |
| MongoDB | 5 s | 64 | 39 | 1.5 | 4.4 | 78 | 5.0 | 3 |
| MongoDB | 5 s | 256 | 145 | 1.2 | 11.2 | 270 | 12.6 | 9 |

With all partitions held, a 10 s TTL renews P/3 leases a second and a 5 s TTL renews twice that. MongoDB 10 s / 256 renewed 52 a second instead of 85, because its renewal rounds took seconds.

## Reading

- **A graceful change converges in about two reconcile ticks.**
  - On a join, the old owners notice the new member within a second (route refresh or reconcile) and hand off. The new node acquires on its next reconcile, within another second. The leases alone are right after a median of 1.1–1.5 s.
  - On a leave, the leaving node releases right away, so the leases are right after 0.2–1.2 s. The two clusters of leave samples, about 1.2 s and about 2.1 s, are whether the survivors' next reconcile tick came before or after the release.
  - The route tables add up to one refresh interval (1 s) on top in every case. That is why convergence medians sit at about 2 s for joins and 1.2–2.1 s for leaves on both backends.
- **A kill converges in the TTL plus about a second, and shortening the TTL shortens it one for one.**
  - The dead node's membership and leases expire 7–10 s after the kill, since they were renewed at most 3 s before it. A survivor then acquires on its next reconcile, and the routes follow within a second.
  - With the defaults: 9.95–11.26 s median, at most 14.4 s. With 5 s: 6.13–6.45 s median, at most 7.4 s.
  - Only the dead node's subscriptions wait that long. Commands do not (next point).
- **Commands keep succeeding (D71).** Except MongoDB 10 s / 256 (below), every cell had at least 99.45% of commands succeed through the change, and every failure was `Contended`.
  - The price of Local fallback is visible, though. After a kill, until the dead member expires, about a quarter of commands run on a node that does not hold their partition (about 55 a second of 200). The hot aggregates then conflict: 15–21 retries a second against 0.2 before.
  - The `Contended` failures are the commands that lost the slot three times in a row. The tail stayed within a few tens of milliseconds of the baseline.
- **Moves are exactly HRW's.** Each change moved the joining or departing node's HRW share and nothing else: on average 64.7 of 256 for 3→4 and 50.2–53.0 of 256 for 4↔5, against ideals of 64 and 51.2. No partition moved between two nodes that stayed.
- **Duplicates are bounded by the checkpoint interval.**
  - A graceful hand-off saves checkpoints before releasing, and 0 of 413,382 entries were handled twice across the 420 graceful changes outside MongoDB 10 s / 256.
  - A kill re-handles what the dead node handled after its last checkpoint save (every second): a median of 17–31 entries per kill, 0.7–1.8% of all entries in the interval, 3.5–7.3% of the moved partitions' entries. D45 tolerates this.
  - A shorter TTL does not change the count per kill. The percentage is higher with 5 s only because the interval is shorter.
- **The defect: lease renewal is sequential (described below).**
  - It shows as *lost leases*: a renewal that found the node's own lease already expired, on a healthy node.
  - On PostgreSQL it cost a few dozen `Contended` commands and some slower joins at P = 256 with the 5 s TTL.
  - On MongoDB at P = 256 with the 10 s TTL it cascaded in repetitions 3 and 7. Leases lost right after the start sent the hot partitions to Local fallback on several nodes at once. Contending MongoDB transactions slowed every call on the shared connection pools, including renewals, so more leases were lost. Commands timed out at 30 s, and one leave took 51 s to converge.
  - The other 8 repetitions of that cell look like the 5 s cell. That the 5 s cell did not cascade in 10 repetitions is not evidence that a shorter TTL protects. It renews twice as often, so each round has twice the deadline pressure.

## Defect: `PartitionOwnership` Renews Leases One At A Time

`core/runtime/.../cluster/PartitionOwnership.kt` (`:runtime`), `renew()` and `start()`. Not fixed here.

- `renew()` loops over the held partitions and awaits `leases.renew(...)` for each in turn. The loop runs every `renewInterval`, counted from the end of the previous round: `every()` is `delay(interval)` then the block. So a partition is renewed about every `renewInterval + owned × latency`. Once that exceeds the TTL, the node's own leases expire between renewals, and it stops partitions that nobody else wants. They are re-acquired on a later reconcile, after the drop.
- `start()` acquires every target partition one at a time, starting each partition's subscriptions, before it launches the renew loop. The leases acquired first are already as old as the whole initial reconcile when the first round begins.
- **Failing scenario** (`RenewalRoundSpike`, in this module): one node, 64 partitions, an in-memory lease store whose `renew` takes 150 ms, which is far below the 10 s TTL, default `ClusterOptions`. The round takes 9.6 s, more than the 7 s margin, and 89 renewals found their lease lost within 30 s. With 50 ms and 100 ms (rounds of 3.2 s and 6.4 s) none was lost.
- **Measured here**:
  - PostgreSQL with a 5 s TTL and P = 256 lost 117 leases on all three nodes in one repetition. That was 1 s after the first start, which took 4.3 s.
  - MongoDB with a 10 s TTL and P = 256 lost 792 in the change windows, with the cascade above.
  - A 10 s TTL run at P = 64 on a cold JVM (the first harness run, before the warm-up repetition was added) lost 57 within 13 s of the start, with renew p99 557 ms.
- **Expected**: a store latency far below the TTL never costs a healthy node its leases. Renewing the held leases concurrently, or in one statement per round (one `UPDATE … WHERE partition = ANY(?)` on PostgreSQL, one `updateMany` on MongoDB), would remove the dependence on the number held. Starting the renew loop before the initial acquisition would remove the cold-start case. Giving lease calls connections that the log tail cannot exhaust would help on MongoDB. A partition owner that tails P/N partitions every 50 ms shares its pool with the renewals.

## Verdict Against The H6 Row

The H6 row's abandon criterion is: *convergence too slow: shorten the lease and add active yielding; if still not enough, evaluate an external coordinator (etcd).*

1. **Does convergence with the defaults fit the 10 s TTL story? Yes.**
   - Joins and graceful leaves converge in about 2 s, independent of the TTL.
   - A crash converges in TTL + about 1 s (median 10–11 s, at most 14.4 s). During that time only the dead node's subscriptions pause, as D71 says, and commands keep succeeding through Local fallback (at least 99.77% succeeded in every kill cell outside MongoDB 10 s / 256).
   - Convergence is not too slow, so the criterion is **not triggered**.
2. **Is shortening the TTL or active yielding needed? No.**
   - Active yielding already exists: the graceful hand-off of D71, measured here at about 2 s with no duplicates.
   - A 5 s TTL cuts crash convergence by about 4 s (to about 6.2 s) and changes nothing else measured. But it doubles the renewal traffic, and with the sequential renewal it lost more leases, not fewer, on PostgreSQL at P = 256 (118 against 18).
   - The data gives no reason to leave the D72 defaults.
3. **Is there a reason to evaluate an external coordinator? No.**
   - Convergence is set by the TTL, the reconcile interval and the route refresh, not by the stores. Lease calls took a median of 1–5 ms on both, and the convergence curves of PostgreSQL and MongoDB match at P = 64.
   - The one failure seen, lost leases on healthy nodes, comes from the runtime's sequential renewal and from sharing a connection pool with the log. Both are fixable in `PartitionOwnership`, and an external coordinator would not remove the MongoDB contention that amplified it.

**Verdict: storage leases support membership and rebalancing. H6 is verified, with the defect above to fix before the cluster channel is relied on at P = 256 or on MongoDB.**

## Caveats

- **The MongoDB log needs an owner queue that the runtime does not have yet.**
  - In a first run, the MongoDB log collapsed under this load: 182 appends were stuck in aborted-and-retried transactions after the first join. The cause is that the `Executor` serializes per aggregate, not per partition, so an owner appends to one partition from many aggregates at once (H1, finding F1).
  - The harness therefore wraps each MongoDB node's log in `PartitionSerializedLog`, which lets one append per partition run at a time on each node. This stands in for the owner's append queue of H1. Appends from two nodes to one partition during Local fallback still contend, as they would in production.
  - PostgreSQL's log is used unwrapped. MongoDB results depend on that stand-in, and the cascade above shows what the D52 contention window can turn into.
- **In-process nodes.** All nodes share one JVM, its scheduler and its GC, and one machine clock, so the clock skew of D53 is absent.
  - The loopback channel refuses a forward to a dead address at once. A real network would wait up to the 1 s forward timeout (D80) for each of the about 40 failed forwards a second during a kill, before running the command locally. That adds latency to those commands but changes no convergence time.
  - A kill is simulated as described above (unbinding, no further checkpoint or handling, `abort()`). A real crash also loses the commands in flight.
- **Shared, noisy host.** Other agents' Gradle builds ran on the same machine during the run. The two MongoDB cascades fell in one 15-minute series, and that load cannot be ruled out as their trigger. The mechanism, lost renewals followed by contention, is reproduced without load by `RenewalRoundSpike`.
- **PostgreSQL defaults include `fsync`.** Docker Desktop's fsync stalls show in the renew maximum of 214–633 ms. That is closer to a durable production database than H2's settings, and harsher for the renewal defect.
- **No snapshots.** The kernel loads cold states from the log tail only. A non-owner's load of a hot aggregate replays its whole stream, which lengthens Local fallback's conflict retries.
- **One load level.** 200 commands a second, Zipf over 10,000 accounts. Convergence does not depend on it; conflicts and `Contended` scale with the load on the hot aggregates.
- **The convergence condition includes route tables.** These refresh every second, which adds up to 1 s to every change. The *leases alone* column is what subscriptions see.

## Reproduce

- **Source**: measured at `81f5e8d54d` (`v10` at `88643c2b95` plus this harness), with the harness as in this pull request. No production module changed.
- **Command**: `./gradlew :lease-spike:integrationTest -Dwow.spike.h6=true` (82 minutes, both backends). It writes `test/lease-spike/build/spike-h6.json` and `spike-h6.md`. The measured run predates the D87 rename, so it used the old project names: `:wow-lease-spike` in `test/wow-lease-spike`, on `:wow-runtime` and `:wow-postgres`. The harness code is the same.
  - `-Dwow.spike.h6.quick=true` runs one repetition at P = 64 per backend and configuration.
  - `-Dwow.spike.h6.only=PostgreSQL` (or `MongoDB`) and `-Dwow.spike.h6.repetitions=<n>` narrow a run.
  - `--tests '*RenewalRoundSpike*'` runs the defect's scenario alone, in 90 seconds, without containers.
- **Raw results**: [`v10-h6-leases.json`](v10-h6-leases.json) holds every step with its times, moves, duplicates, load window, baseline and second-by-second profile, plus the settings and the lease-call latencies. It is compacted from `spike-h6.json`: floats are rounded to 3 decimals and zero counters are left out of profile rows. `RenewalRoundSpike`'s output is quoted above.
