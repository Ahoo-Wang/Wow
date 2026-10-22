# V10 Spike H2 (Phase 1): Does Affinity Routing Pay For Its Forwarding Hop?

Spike H2 of the V10 design (`documentation/designs/decisions.md` on the `v10` branch) asks whether routing each command to its partition's owner (`Partitioned`) is worth the extra in-cluster hop compared with executing it on whichever node received it (`Local`). Phase 1 simulates the cluster in one JVM against a real PostgreSQL. It does pay. Under skewed and hot load, Partitioned has the lower p99, about 45% fewer store reads per command, no version conflicts and up to 45% less CPU per command. Under uniform cold load it costs about half a millisecond at the median and stays within one forward round trip at p99. Against the pre-registered criteria the verdict is **Partitioned default**.

## What Is Measured

`test/wow-routing-spike`, `AffinityRoutingSpike` (runs only with `-Dwow.spike.h2=true`).

- **Nodes**: 3 or 5 in-process nodes. Each has its own HikariCP pool (16 connections), `PostgresEventLog` and `PostgresSnapshotStore` clients, `Kernel`, `Executor` and `LruStateCache` (10,000 states, a tenth of the aggregates). All nodes share one PostgreSQL log (64 partitions) and one snapshot store. Partition `p` belongs to node `p % N`, a fixed assignment that stands in for the leases.
- **Aggregate**: an account (owner, balance, operation count) with `OpenAccount`, `Deposit` and `Withdraw`. `Withdraw` reads the balance in its decide function. Commands are 90% deposits and 10% withdrawals, and no command is rejected.
- **Snapshots** (standing in for roadmap 4.6): a node writes the snapshot synchronously after its command commits, in the command's latency but outside the aggregate's lane. The state is serialized while the lane is still held, because mutable state (D7) may be changed by the next command. The *small state, short tail* shape writes one after every commit, which is the policy most favourable to Local, since a cold load then reads the snapshot plus a one-stream tail. The *large state, long tail* shape has a state of about 3 KB, writes a snapshot every 10 versions, and is seeded with a tail of 0 to 9 streams after the snapshot. Partitioned rarely needs the snapshot because its state is usually cached.
- **Routing variants**:
  - `Local`: a uniformly random node receives the command and executes it.
  - `Partitioned`: a random node receives it and forwards it to the owner unless it is the owner, so 2/3 of commands are forwarded with 3 nodes and 4/5 with 5. A forward serializes the command to JSON and back, waits a delay drawn uniformly from 0.3–1 ms (half on each leg), and serializes the reply the same way.
  - `Ingress-hash`: the load balancer sends the command straight to the owner, with no hop.
- **Workloads**: 100,000 seeded accounts. Access is uniform over all of them, Zipf with s = 1.1 over all of them (ranks permuted onto ids, so the top account takes about 10% of commands), or 10 extremely hot accounts. Per-aggregate concurrency is *concurrent* (every command is sent when due) or *sequential* (a client sends an aggregate's next command only after the previous one has answered).
- **Load**: saturation is first measured per variant in a closed loop (128 clients, 2 s warm-up, 3 s measured). Each cell then runs **open loop** at a fixed arrival rate of 50% and 80% of *Local's* saturation, the same absolute rate for all three variants. Latency is measured from each command's scheduled send time, so a stalled system cannot hide its queue (no coordinated omission). Each run uses fresh nodes with cold caches and runs 3 s of warm-up followed by 5 s measured. Each cell has 5 repetitions for the two cells the criteria read (uniform and Zipf, concurrent, small state, 3 nodes) and 3 for the others. The variant order rotates between repetitions, and the tables report the median across repetitions. For the concurrent hot-spot cells, a third rate of 50% of *Partitioned's* saturation probes the load the owners can actually carry, because Local's own saturation is depressed by contention there.
- **Metrics**:
  - Commits completed per second.
  - p50, p99 and p999 end to end.
  - *Steady p99*: the median over the five measured seconds of each second's p99. It is the tail without an isolated stall, and *worst-second p99* is the stalled second.
  - State-cache hit rate.
  - Store reads per command: the request-id lookup plus snapshot loads and log reads, counted by wrapping the log and the snapshot store. *State reads* exclude the request-id lookup, which every command makes.
  - Version conflicts per command.
  - The `Contended` ratio (commands that lost the version slot `maxAttempts = 3` times).
  - Forward overhead: serialization plus the achieved delay, excluding the owner's execution.
  - JVM process CPU per executed command, which covers all nodes, the driver and the JDBC client but not PostgreSQL.
  - JVM GC time.

### Pruned Matrix

The full cross product would be 3 distributions × 2 concurrencies × 2 state shapes × 2 node counts × 2 loads × 3 variants × 3 repetitions = 432 runs (about 75 minutes). The spike runs 10 of the 24 workload cells:

| Workload | 3 nodes | 5 nodes |
|---|---|---|
| uniform, concurrent, small | ✓ (criterion, 5 reps) | ✓ |
| Zipf, concurrent, small | ✓ (criterion, 5 reps) | ✓ |
| Zipf, sequential, small | ✓ | — |
| hot 10, concurrent, small | ✓ (+ probe) | ✓ (+ probe) |
| hot 10, sequential, small | ✓ | — |
| uniform, concurrent, large | ✓ | — |
| Zipf, concurrent, large | ✓ | — |

Pruned:

- Sequential uniform. With 100,000 aggregates two commands almost never meet, so it would measure the same as concurrent uniform.
- The large state on hot and sequential cells. A cached hot state is never reloaded, so its size only matters on misses, which the uniform and Zipf large cells already measure.
- 5 nodes for sequential and large-state cells. Five nodes change the forwarded share (4/5 instead of 2/3) and Local's hit rate (1/5 instead of 1/3), and the uniform, Zipf and hot cells at 5 nodes cover that.

The final run took 34 minutes.

## Results

### Saturation (closed loop, commits/s)

| Workload | Local | Partitioned | Ingress-hash |
|---|---:|---:|---:|
| uniform, concurrent, small, 3 nodes | 6,880 | 7,700 | 7,595 |
| Zipf, concurrent, small, 3 nodes | 6,102 | 7,569 | 8,098 |
| Zipf, sequential, small, 3 nodes | 4,097 | 5,047 | 6,901 |
| hot 10, concurrent, small, 3 nodes | 5,911 | 9,492 | 8,731 |
| hot 10, sequential, small, 3 nodes | 4,472 | 6,438 | 8,205 |
| uniform, concurrent, large, 3 nodes | 5,960 | 7,059 | 8,589 |
| Zipf, concurrent, large, 3 nodes | 7,178 | 9,342 | 9,227 |
| uniform, concurrent, small, 5 nodes | 8,142 | 7,430 | 7,281 |
| Zipf, concurrent, small, 5 nodes | 6,291 | 7,871 | 7,896 |
| hot 10, concurrent, small, 5 nodes | 4,699 | 9,255 | 9,624 |

Each saturation figure comes from a single 3-second run. They vary by about ±10% between runs (compare the uniform cells at 3 and 5 nodes), so only differences larger than that mean anything.

### The Cells The Criteria Read (3 nodes, small state, 5 repetitions)

| Cell | Routing | p50 ms | p99 ms | p999 ms | steady p99 ms | Hit | Reads/cmd | State reads/cmd | Conflicts/cmd | Contended | CPU µs/cmd |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Zipf, 80% (4,881/s) | Local | 2.13 | 8.95 | 31.88 | 5.31 | 0.51 | 2.44 | 1.44 | 0.468 | 0.47% | 439 |
| | **Partitioned** | **1.60** | **3.20** | **11.20** | **2.81** | 0.81 | **1.38** | **0.38** | 0 | 0 | **320** |
| | Ingress-hash | 0.91 | 1.95 | 3.80 | 1.94 | 0.81 | 1.38 | 0.38 | 0 | 0 | 275 |
| Zipf, 50% (3,051/s) | Local | 1.50 | 2.98 | 4.89 | 2.87 | 0.49 | 2.47 | 1.47 | 0.453 | 0.06% | 408 |
| | Partitioned | 1.45 | 2.52 | 3.09 | 2.50 | 0.79 | 1.43 | 0.43 | 0 | 0 | 290 |
| | Ingress-hash | 0.75 | 1.45 | 2.28 | 1.40 | 0.79 | 1.43 | 0.43 | 0 | 0 | 242 |
| uniform, 80% (5,504/s) | Local | 1.82 | 5.72 | 13.54 | 3.81 | 0.09 | 2.84 | 1.84 | 0.008 | 0 | 426 |
| | Partitioned | 2.24 | 4.80 | 9.17 | 4.46 | 0.25 | 2.50 | 1.50 | 0 | 0 | 448 |
| | Ingress-hash | 1.61 | 4.11 | 7.40 | 3.75 | 0.25 | 2.50 | 1.50 | 0 | 0 | 388 |
| uniform, 50% (3,440/s) | Local | 1.31 | 2.35 | 4.08 | 1.93 | 0.06 | 2.89 | 1.89 | 0.004 | 0 | 388 |
| | Partitioned | 1.83 | 2.95 | 4.42 | 2.76 | 0.17 | 2.65 | 1.66 | 0 | 0 | 416 |
| | Ingress-hash | 1.27 | 2.64 | 4.20 | 1.78 | 0.17 | 2.66 | 1.66 | 0 | 0 | 366 |

All three variants carried the offered rate in these cells. Local fell 0.4% short at Zipf 80%, the commands it lost as `Contended`. In the Zipf 80% cell, Partitioned's p99 beat Local's in each of the five repetitions (2.9, 3.2, 8.6, 3.4 and 2.9 ms against 26.1, 19.7, 8.9, 5.8 and 6.8 ms). The mean forward overhead was 770–820 µs: a mean injected delay of 650 µs plus JSON serialization and timer slack.

### Everything Else (median of 3 repetitions)

| Cell | Routing | p50 ms | p99 ms | steady p99 ms | worst-second p99 ms | Reads/cmd | Contended | CPU µs/cmd |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Zipf, sequential, 80% (3,277/s) | Local | 1.66 | 7.92 | 6.32 | 21.50 | 2.47 | 0 | 417 |
| | Partitioned | 1.51 | 3.93 | 3.93 | 4.53 | 1.42 | 0 | 292 |
| | Ingress-hash | 0.74 | 1.48 | 1.43 | 1.70 | 1.42 | 0 | 242 |
| Zipf, sequential, 50% (2,048/s) | Local | 1.26 | 2.68 | 2.70 | 2.95 | 2.49 | 0 | 359 |
| | Partitioned | 1.52 | 5.79 | 2.86 | 18.88 | 1.46 | 0 | 281 |
| | Ingress-hash | 0.72 | 1.31 | 1.29 | 1.39 | 1.47 | 0 | 220 |
| hot 10, concurrent, 80% (4,729/s) | Local | 2.38 | 6.69 | 6.05 | 9.98 | 2.27 | 1.36% | 456 |
| | Partitioned | 1.48 | 2.66 | 2.46 | 3.75 | 1.00 | 0 | 280 |
| | Ingress-hash | 0.78 | 1.60 | 1.45 | 1.82 | 1.00 | 0 | 235 |
| hot 10, concurrent, 50% (2,956/s) | Local | 1.73 | 3.41 | 3.30 | 3.92 | 2.30 | 0.24% | 417 |
| | Partitioned | 1.33 | 2.12 | 2.11 | 2.16 | 1.00 | 0 | 240 |
| | Ingress-hash | 0.65 | 1.08 | 0.97 | 1.70 | 1.00 | 0 | 194 |
| hot 10, sequential, 80% (3,578/s) | Local | 2.81 | 15.83 | 13.90 | 19.70 | 2.34 | 0 | 456 |
| | Partitioned | 1.57 | 4.97 | 4.83 | 5.68 | 1.00 | 0 | 268 |
| | Ingress-hash | 0.71 | 1.66 | 1.66 | 2.00 | 1.00 | 0 | 219 |
| hot 10, sequential, 50% (2,236/s) | Local | 1.63 | 5.09 | 4.17 | 6.93 | 2.34 | 0 | 402 |
| | Partitioned | 1.35 | 3.28 | 3.14 | 3.59 | 1.00 | 0 | 237 |
| | Ingress-hash | 0.66 | 1.13 | 1.15 | 1.45 | 1.00 | 0 | 197 |
| uniform, large, 80% (4,768/s) | Local | 1.40 | 6.63 | 5.47 | 13.43 | 2.85 | 0 | 434 |
| | Partitioned | 1.89 | 18.60 | 4.62 | 36.18 | 2.55 | 0 | 444 |
| | Ingress-hash | 1.28 | 54.98 | 3.95 | 73.69 | 2.55 | 0 | 397 |
| uniform, large, 50% (2,980/s) | Local | 1.15 | 92.09 | 3.25 | 123.02 | 2.91 | 0 | 418 |
| | Partitioned | 1.69 | 3.64 | 3.12 | 4.43 | 2.70 | 0 | 422 |
| | Ingress-hash | 1.08 | 2.23 | 2.07 | 2.64 | 2.71 | 0 | 363 |
| Zipf, large, 80% (5,742/s) | Local | 2.12 | 12.15 | 8.50 | 27.09 | 2.43 | 0.91% | 457 |
| | Partitioned | 1.33 | 3.80 | 2.89 | 6.22 | 1.35 | 0 | 267 |
| | Ingress-hash | 0.62 | 2.31 | 2.05 | 2.89 | 1.36 | 0 | 225 |
| Zipf, large, 50% (3,589/s) | Local | 1.50 | 22.98 | 3.59 | 47.31 | 2.46 | 0.23% | 417 |
| | Partitioned | 1.20 | 2.41 | 2.42 | 2.56 | 1.40 | 0 | 248 |
| | Ingress-hash | 0.52 | 10.18 | 1.51 | 18.84 | 1.41 | 0 | 202 |
| uniform, 5 nodes, 80% (6,514/s) | Local | 2.19 | 6.91 | 6.26 | 11.31 | 2.88 | 0 | 439 |
| | Partitioned | 2.62 | 6.33 | 6.54 | 7.44 | 2.41 | 0 | 453 |
| | Ingress-hash | 1.86 | 6.25 | 5.32 | 8.29 | 2.40 | 0 | 387 |
| uniform, 5 nodes, 50% (4,071/s) | Local | 1.46 | 2.81 | 2.71 | 3.52 | 2.92 | 0 | 405 |
| | Partitioned | 2.05 | 3.54 | 3.34 | 4.28 | 2.60 | 0 | 433 |
| | Ingress-hash | 1.39 | 2.70 | 2.36 | 4.66 | 2.60 | 0 | 382 |
| Zipf, 5 nodes, 80% (5,033/s) | Local | 2.61 | 181.52 | 8.98 | 276.66 | 2.71 | 1.47% | 495 |
| | Partitioned | 1.68 | 3.20 | 3.14 | 3.90 | 1.37 | 0 | 334 |
| | Ingress-hash | 0.92 | 2.85 | 2.81 | 4.74 | 1.37 | 0 | 280 |
| Zipf, 5 nodes, 50% (3,146/s) | Local | 1.73 | 3.87 | 3.24 | 5.15 | 2.69 | 0.13% | 450 |
| | Partitioned | 1.56 | 40.84 | 2.81 | 112.95 | 1.42 | 0 | 311 |
| | Ingress-hash | 0.76 | 1.89 | 1.64 | 4.60 | 1.43 | 0 | 249 |
| hot 10, 5 nodes, 80% (3,759/s) | Local | 2.31 | 5.83 | 5.19 | 9.89 | 2.65 | 1.31% | 502 |
| | Partitioned | 1.45 | 2.23 | 2.19 | 2.47 | 1.00 | 0 | 276 |
| | Ingress-hash | 0.71 | 1.27 | 1.21 | 1.54 | 1.00 | 0 | 225 |
| hot 10, 5 nodes, 50% (2,350/s) | Local | 1.73 | 3.02 | 2.99 | 3.38 | 2.60 | 0.17% | 456 |
| | Partitioned | 1.35 | 2.00 | 1.99 | 2.12 | 1.00 | 0 | 240 |
| | Ingress-hash | 0.63 | 0.94 | 0.91 | 1.06 | 1.00 | 0 | 196 |
| hot 10, 3 nodes, probe (4,746/s) | Local | 2.43 | 8.99 | 6.01 | 13.55 | 2.26 | 1.53% | 456 |
| | Partitioned | 1.47 | 2.53 | 2.52 | 2.77 | 1.00 | 0 | 280 |
| | Ingress-hash | 0.78 | 1.56 | 1.45 | 1.79 | 1.00 | 0 | 235 |
| hot 10, 5 nodes, probe (4,627/s) | Local | 3.21 | 102.62 | 12.58 | 150.18 | 2.75 | 5.54% | 541 |
| | Partitioned | 1.54 | 2.58 | 2.55 | 2.86 | 1.00 | 0 | 296 |
| | Ingress-hash | 0.81 | 1.71 | 1.66 | 2.01 | 1.00 | 0 | 244 |

In skewed cells Local's version conflicts run at 0.45–0.88 per command, and its hit rate is 0.45–0.61 against Partitioned's 0.77–1.00. Some p99 medians are far from their steady p99 (Local uniform large 50%, Partitioned and Ingress-hash uniform large 80%, Partitioned Zipf sequential 50% and Zipf 5 nodes 50%). In those cells one or two seconds of a run stalled. The stalls fall on the variants in no pattern: for example, Ingress-hash, which has no hop, has the worst uniform-large-80% p99. GC was 1–34 ms per measured window and does not explain them. They come from the Docker VM and PostgreSQL (see the caveats).

## Reading

- **Affinity buys reads, and reads are the cost.** On a cache hit a command makes 1 store read, the request-id lookup. On a miss it makes 3: the lookup, the snapshot, and the log's tail. The state is then evolved after decoding the snapshot and the tail. Under Zipf, Partitioned's hit rate is 0.8 against Local's 0.5, so it makes 1.38 reads per command instead of 2.44: 43% fewer reads, and 74% fewer state reads. Under hot load it makes exactly 1 read (hit rate 1.00) against 2.3–2.7.
- **Affinity removes conflicts.** Local spreads one aggregate's commands over all nodes, and only the version key orders them. Under skew, about every second command conflicts at least once, and up to 5.5% exhaust their three attempts (`Contended`). That is a client-visible failure Partitioned never produced. The conflicts are also where Local's tail comes from: each retry is another load and decide.
- **The hop costs about 0.5 ms at the median, as expected.** Two thirds of commands take a 0.77 ms forward, which adds about 0.51 ms. Uniform p50 rose 0.52 ms at 50% load and 0.42 ms at 80%. At 5 nodes, 4/5 are forwarded, which adds about 0.6 ms (measured: 0.59 and 0.43 ms). Under skew, the saved reads more than pay for it: Partitioned's p50 is lower than Local's in every Zipf and hot cell except one (Zipf sequential at 50%: 1.52 against 1.26 ms).
- **The hop costs CPU only when it saves nothing.** Serializing the command and the reply adds 4–28 µs per command under uniform load. Under skew Partitioned uses 22–45% less CPU per command than Local, because it decodes no snapshots and replays no tails.
- **Skew, not only extreme hot spots, sets Local's ceiling.** Zipf s = 1.1 puts about 10% of commands on one account, so Zipf behaves like a hot spot for Local. In closed loop, Partitioned's saturation exceeded Local's by 23–97% on every skewed workload. On uniform the difference was mixed, from −9% to +18%.
- **Ingress-hash is better still.** With no hop it keeps all of Partitioned's hit rate, reads and absence of conflicts, and has the lowest p50 and p99 in nearly every cell. A hop is only needed when the receiver is not the owner. A client or load balancer that knows the partition map makes forwarding the exception rather than the rule. Ingress-hash is not a routing mode of its own: it is Partitioned with the routing done before the first hop, which the edge or gateway can do.

## Verdict Against The Pre-Registered Criteria

The criteria are recorded in the H2 row of `decisions.md`:

1. *Under Zipf at 80% load, Partitioned's p99 is no worse than Local's and its reads per command are clearly lower.* **Met.** At 3 nodes with the small state: p99 3.20 ms against 8.95 ms (better in all 5 repetitions), steady p99 2.81 against 5.31 ms, and 1.38 against 2.44 reads per command. The large state (3.80 against 12.15 ms; 1.35 against 2.43 reads) and 5 nodes (3.20 against 181.52 ms; 1.37 against 2.71 reads) agree.
2. *Under uniform cold load, Partitioned's p99 costs no more than one forward RTT (about +1 ms).* **Met.** At 3 nodes with the small state: +0.60 ms at 50% load and −0.92 ms at 80%. On steady p99: +0.83 and +0.65 ms. At 5 nodes: +0.73 and −0.58 ms. The large-state cells are within +1 ms on steady p99 (−0.13 and −0.85 ms). Their raw p99 medians (3.64 against 92.09 ms, and 18.60 against 6.63 ms) are stalls that also hit the hopless Ingress-hash (54.98 ms), not hop cost.
3. *If Partitioned's p99 is worse under hot spots, Local is the default.* **Not triggered.** Under 10 hot accounts Partitioned's p99 is lower than Local's in all 8 hot cells (3 and 5 nodes, sequential and concurrent, all three rates).

**Verdict: Partitioned default (D6 stands).** Local stays the single-node and degraded mode. The data adds a recommendation the criteria did not ask about: route at the ingress where possible, so that only commands whose receiver is not the owner pay the hop.

## Caveats

- **In-process simulation.** All nodes share one JVM, so its CPU, JIT, GC and the virtual-thread scheduler. Nothing is isolated per node, and the CPU figures cover the whole cluster and the load driver, not PostgreSQL. Phase 2 (after 4.2, three JVMs and real forwarding) re-measures p99 and the owner-failover window.
- **Injected hop.** The network is a timer delay drawn uniformly from 0.3–1 ms, with real JSON serialization. A real cluster channel adds connection handling, backpressure and occasional loss. The delay is uniform, so a real network's long tail is not represented, and that tail would fall on Partitioned's forwarded commands only.
- **Synchronous commit off.** In a first, aborted run with PostgreSQL defaults, fsync and checkpoint stalls of Docker Desktop's VM moved p99 between 15 ms and 4 s from one repetition to the next, which drowns a 1 ms effect. The final run sets `synchronous_commit=off`, `shared_buffers=1GB`, `max_wal_size=8GB` and `checkpoint_timeout=30min`. That makes appends cheaper than on a durable production database. Because appends cost the same in all variants, this narrows the gap between variants rather than widening it. Isolated one-second stalls remain, which is why the steady p99 is reported beside the raw p99.
- **A previous complete run disagrees on one cell.** A complete run with these database settings, before the per-second diagnostics were added (`v10-h2-routing-first-run.json`), overloaded the Zipf 80% cell for all three variants: p99 between 0.2 and 8.9 s, with some runs falling up to 48% short of the offered rate. Local had 282–1,403 `Contended` commands. That cell decides nothing on its own in that run. Elsewhere the run agrees in direction: the same hit rates and reads, Local's conflicts, and a hop cost under 1 ms. The final run is the evidence; its Zipf 80% cell was stable in all five repetitions.
- **Saturation from a single short closed-loop run.** The open-loop rates are 50% and 80% of one 3-second measurement of Local, which varies by about ±10%. At 80%, some cells run close to Local's real capacity, and Local's 80% results are the first to degrade.
- **Fixed ownership, no rebalancing.** The partition→node map is static and every node is always up. The window when an owner is lost, and leases moving, are phase 2's.
- **No production data.** No 9.x workload traces exist. The access distributions (uniform over 100,000, Zipf s = 1.1, 10 hot accounts), the cache size (10% of the aggregates per node) and the snapshot policies are the hypotheses the H2 row names, not measured traffic.
- **One machine.** Numbers from a laptop's Docker VM are lower bounds and vary between runs. Compare variants within one cell, not across machines.

## Reproduce

- Source: measured on the `v10` branch at `d99df25ee8` plus the pull request that adds `test/wow-routing-spike` and this report; the pull request was then rebased onto `32d61c1600` (roadmap 4.2a's cluster channel port, which the harness does not use) without changes to the harness. No production module changed: the harness builds the nodes, the counting wrappers, the snapshot stand-in and the cluster channel itself.
- Command: `./gradlew :wow-routing-spike:integrationTest -Dwow.spike.h2=true` (34 minutes). It writes `test/wow-routing-spike/build/spike-h2.json` and `spike-h2.md`. Adding `-Dwow.spike.h2.quick=true` runs a two-cell, one-repetition smoke check in under two minutes.
- Environment: Apple M4 Pro, 14 cores, 24 GiB, macOS 27.0.1. Docker Desktop 29.8.2 (a Linux VM with 4 CPUs and 5.8 GiB, which bounds every number here). PostgreSQL 17 (`postgres:17-alpine`, `max_connections=300`, `synchronous_commit=off`, `shared_buffers=1GB`, `max_wal_size=8GB`, `checkpoint_timeout=30min`). Amazon Corretto 21.0.12.1 with a 4 GiB test heap. PostgreSQL JDBC driver and HikariCP from the Spring Boot BOM.
- Raw results: [`v10-h2-routing.json`](v10-h2-routing.json) has every run, saturation and setting. [`v10-h2-routing-first-run.json`](v10-h2-routing-first-run.json) is the earlier complete run.
