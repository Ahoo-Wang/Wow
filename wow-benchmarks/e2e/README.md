# End-to-end architecture benchmark: V10 against 9.x

The benchmark of decision D224 (roadmap M7.5, B.0 to B.R): conclusions come only from requests that enter through HTTP and complete when the result the client waits for is visible. Services, stores and the bus are separate processes, each version in its recommended production topology. This folder holds the twin benchmark domain, the servers, the load generator and the harness (work package B.0); the measurements are B.1 to B.4 and the report B.R.

## Layout

| Path | Project | What it is |
| --- | --- | --- |
| `bench-9x/` | `:e2e-bench-9x` | The account and transfer saga domain, its specs and its Spring Boot server, on 9.x modules only (full build scope) |
| `bench-v10/` | `:e2e-bench-v10` | The same on V10 modules only (in the V10 region of the settings, checked by `v10Check`) |
| `load/` | `:e2e-bench-load` | The load generator: a JVM of its own on the JDK alone, no Wow module and no library |
| `harness/` | none | `compose.yml` (MongoDB, PostgreSQL, Kafka) and `bench.sh` (start, seed, check, run, collect, clean up) |

None of them is published; the root build keeps them out of the ABI checks and the coverage reports (`e2eBenchmarkProjects`). No project depends on both versions (D85).

## The twin domain

Each module has the same files with the same names and the same package, `me.ahoo.wow.benchmark.transfer`, so `diff -r bench-9x/src bench-v10/src` shows only what the frameworks make different:

| File | Concept |
| --- | --- |
| `TransferContext.kt` | The bounded context `transfer` and its aggregate `account` |
| `AccountCommands.kt` | `OpenAccount`, `Deposit`, `Withdraw`, `Prepare`, `Entry`, `Confirm`, `UnlockAmount`, `FreezeAccount` |
| `AccountEvents.kt` | `AccountOpened`, `Deposited`, `Withdrawn`, `AmountLocked`, `Prepared`, `AmountEntered`, `EntryFailed`, `Confirmed`, `AmountUnlocked`, `AccountFrozen` (identical on both) |
| `AccountRejections.kt` | The rejection codes: invalid amount, insufficient balance, frozen |
| `AccountState.kt` | Owner, balance, locked amount, frozen |
| `Account.kt` | The rules: `Withdraw` and `Prepare` read the balance and reject when it does not cover the amount; a frozen account refuses a transfer out (rejection) and an entry in (`EntryFailed`) |
| `TransferSaga.kt` | `Prepared` → `Entry` on the target; `AmountEntered` → `Confirm` on the source; `EntryFailed` → `UnlockAmount` on the source (the compensation) |
| `TransferServer.kt` | The server |

The semantics are those of the 9.x Java example `example/transfer`, ported to Kotlin, with `Deposit` and `Withdraw` added. The framework differences that remain: 9.x rejects by throwing, V10 by returning a `Decision`; 9.x's state takes its id in its constructor; 9.x's account is in the default tenant through `@StaticTenantId`, which V10's is when the caller names no tenant; 9.x's saga addresses a derived command with a command builder, V10's with the aggregate's reference; V10 requires a sourcing function for every event, so both states declare the no-op ones of `Prepared` and `EntryFailed`.

`src/test/.../TransferCases.kt` is one table of cases, byte for byte the same in both modules (`SharedCasesTest` in `:e2e-bench-v10` checks it). Each version's `AccountSpec` and `TransferSagaSpec` run every case through its own test DSL.

## Topologies

| | 9.x | V10 |
| --- | --- | --- |
| Events | MongoDB event store | MongoDB log (`mongo` profile) or PostgreSQL log (`postgres` profile) |
| Snapshots | MongoDB, strategy `all` | the same store |
| Commands | Kafka command topic (local-first on, the default) | routed between the nodes over their internal ports (the cluster router) |
| Events to other services | Kafka event and state topics | the state stream published to Kafka (`wow.transfer.account.state`) |
| Cluster of 3 | one Kafka consumer group, a CosId machine id per node | a node id and internal port per node, the cluster token, leases on the store |
| Defaults otherwise | 9.x's | V10's |

V10 on PostgreSQL is reported on its own and never compared with 9.x (D224). Both versions use the topic `wow.transfer.account.state` with different formats, so they never share a Kafka: `bench.sh` starts every run on empty containers and removes them afterwards.

Both servers run on the same JDK 21 (the 9.x modules build with the root's toolchain 21 too) with `-Xms1g -Xmx1g -XX:+UseG1GC` unless `--heap` says otherwise.

## Completion points

Each scenario's request waits for the same point on both versions; the adapter (`load/.../VersionAdapter.kt`) holds the mapping.

| Scenario | Operation | 9.x | V10 |
| --- | --- | --- | --- |
| E1 write | `Deposit` | `Command-Wait-Stage: PROCESSED` | `Wow-Wait: committed` |
| E2 read-your-write | `Deposit`, then a state read that must show its version | `SNAPSHOT`, then `GET /account/{id}/snapshot` | `snapshot`, then `GET /transfer/account/{id}/state` |
| E3 saga chain | `Prepare` from one account to another | `SAGA_HANDLED` of `TransferSaga.onPrepared`, tail `PROCESSED` | `committed > handled(TransferSaga.onPrepared) > committed` |
| E4 read by id | state of one account | `GET /account/{id}/snapshot` | `GET /transfer/account/{id}/state` |
| E4 list | ten accounts | `POST /account/snapshot/list`, condition `ALL`, limit 10 | `POST /transfer/account/snapshot/query`, `MATCH_ALL`, limit 10 |
| E5 cluster | E1 and E3 on 3 nodes, requests spread round-robin over the nodes | | |

Where the points are not exactly the same:

- **E1.** 9.x signals `PROCESSED` after the append and after handing the event stream and the state event to its buses (with local-first, the local hand-off; the Kafka copy follows). V10's `committed` is the append alone; its Kafka publication runs afterwards in its own subscription. Both mean "the events are stored"; 9.x's answer also covers the hand-off, and its command itself travels through Kafka (or the local-first path), V10's through the cluster router.
- **E3.** Both wait until the saga's `Entry` is stored on the target, the transfer "entered on both sides" of roadmap B.2. The saga's second hop, `Confirm` on the source, is awaited on neither: 9.x's waiting chain follows one saga hop only. Agreed with the user (D224 implementation note): reports state that E3 excludes the source's `Confirm`. 9.x's saga reads the event from Kafka, V10's from the log.
- **E4 list.** Neither sorts; the snapshot documents returned differ in shape (9.x's carry its snapshot metadata), not in number.
- **Bean Validation.** The 9.x server needs a Bean Validation provider to start (`spring-boot-starter-validation`, as the 9.x examples have); V10 validates only when one is on the class path, and its server has none. The commands declare no constraints. Agreed with the user (D224 implementation note): each side keeps its default, the cost counts toward its framework, and reports say so.

## Load model and recorder

- **Open loop** (`run`): operation `i` is scheduled at `start + i / rate`, whatever the earlier ones are doing, and its latency is measured from that scheduled time, so a slow server cannot slow the arrivals (no coordinated omission). A warm-up (not recorded) precedes the measured phase. At most 20,000 operations are outstanding; one beyond is counted as `generator-overload`. Errors are counted by kind and their latencies kept apart.
- **Closed loop** (`saturate`): concurrency doubling from 1 until throughput gains less than 5% twice, errors pass 1%, or `--max-concurrency`; the best throughput is the saturation point the open-loop runs take their 50% and 80% rates from. Its latencies only locate the knee.
- **Keys:** `uniform`, `zipf` (exponent 1.1) or `hot` (`--hot-fraction` of the requests to `--hot-keys` accounts, the rest uniform) over the seeded accounts, from a seeded generator, so both versions see the same key sequence.
- **Recorder:** a log-linear histogram of its own (`Histogram.kt`): exact below 256 µs, 128 buckets per power of two above (at most 0.8% wide), lock-free. A run reports p50, p95, p99, p999, max, mean, and per second of the measured phase p50, p99 and max, whose worst p99 is the worst-second p99. The non-empty buckets go into the results as raw data.

## Resource capture

Over the measured phase, beside the load:

- every server JVM: CPU time and RSS from `ps` each second; heap and collections from one `jstat -gc -t <pid> 1000` per JVM (heap used max and average, the deltas of `YGC`, `YGCT`, `FGC`, `FGCT`, `CGC`, `CGCT`, `GCT`); `--jfr` adds a flight recording per server;
- every container: CPU and memory from one `docker stats` stream;
- the stores: MongoDB's `serverStatus().opcounters`, PostgreSQL's `pg_stat_database` of `transfer_v10`, and the end offsets of every Kafka topic, before and after, and their difference.

## Running it

From the repository root, with Docker and `JAVA_HOME` set to a JDK 21:

```bash
wow-benchmarks/e2e/harness/bench.sh build
wow-benchmarks/e2e/harness/bench.sh run --version=9x  --scenario=E1 --rate=200 --warm-up=30 --measure=60
wow-benchmarks/e2e/harness/bench.sh run --version=v10 --backend=postgres --scenario=E3 --rate=200
wow-benchmarks/e2e/harness/bench.sh run --version=v10 --nodes=3 --scenario=E1 --rate=200 --distribution=zipf
wow-benchmarks/e2e/harness/bench.sh saturate --version=9x --scenario=E1 --max-concurrency=256
```

## Measurement tiers

Decision D224's implementation note of 2026-10-19 splits the measurements by what they depend on.

**Counts** (`harness/counts.sh`, `harness/counts.py`) do not depend on the machine's load, so they run at a low rate on any machine at any time:

- `counts.sh scenarios <setup> <out>`: per E1, E2 or E3 operation, the store round trips by kind (request-id check, aggregate load, log append, snapshot, state read, tail, checkpoint, lease), the store operations, the Kafka messages and the server's heap allocations. The method is B.D's: an idle window gives the periodic work's rate, which is taken off each load window (20/s, 60 s by default). MongoDB's profiler and `serverStatus` counters, or PostgreSQL's `pg_stat_statements` (`harness/compose.counts.yml`), count the store's side; the load generator's `alloc` reads each server JVM's allocated bytes over a local JMX connection.
- `counts.sh ps2 <mongo|postgres> <out>`: PS-2 (decision D240 ②), what each partition costs with no traffic and under a trickle of 10 commands/s, for P = 64, 128 and 256 and 0, 1, 5 and 10 subscriptions beyond the service's own (`--bench.extra-subscriptions`, a harness-only factory of no-op projections in `bench-v10`, `transfer/ps2/`: the one file `diff -r` shows beyond the twin domain).

**Timing** (`harness/tiers.sh`) needs a machine of its own; it runs only when the user says so:

- `tiers.sh quick`: V10 MongoDB against 9.x MongoDB, E1, uniform and Zipf keys, each side at 80% of both sides' saturation, two repetitions (about 40 minutes).
- `tiers.sh full`: E1 to E3, the three setups, uniform, Zipf and hot keys, at 50% and 80%.
- `tiers.sh night`: `full`, unattended; it refuses to start while a Gradle build or another benchmark runs and keeps the machine awake (`caffeinate`).
- `tiers.sh plan quick|full` prints the cells and an estimate; `tiers.sh status quick|full` the cells that have results.

What makes a tier cheaper than one `bench.sh run` per cell:

- **Sessions.** A setup's containers stay up across its cells. Between cells (`--isolation`), `restart` (the default) empties the stores and the topics and restarts the server JVMs; `keys` keeps the JVMs too and gives each cell an account set of its own (`--account-prefix`), at the cost of stores that grow over the cells; `fresh` starts new containers, as `bench.sh run` does. A running V10 node cannot have its store emptied under it (its state cache, partition counters and leases would outlive the data), and the framework has no hook for it, so clearing always restarts the nodes.
- **Saturation by bisection.** The finder doubles the concurrency from 4 with 5 s steps after a 2 s warm-up, then bisects between the knee and the concurrency below it (3 more steps), instead of 10 s steps from 1.
- **Adaptive repetitions.** Two runs per cell; a third only when they differ by more than 5% in p99 or throughput.
- **Shorter phases once shown stable.** The load generator records the warm-up's per-second latencies. `harness/stability.py` compares, on runs of 30 s warm-up and 60 s measured, the window a 20 s warm-up and 45 s measured phase would have used; once four or more runs differ by at most 5% in throughput, median per-second p50 and p99, `stability.json` accepts the shortening and `--timing=auto` uses it.
- **Resumable.** A cell whose result is in the report folder is skipped. Results go to `wow-benchmarks/results/reports/v10-vs-9x-e1/` (and `-e2`, `-e3`), `<setup>/<distribution>/<rate>-r<repetition>/`; `v10-vs-9x-e1/aggregate.py <folder>` makes the tables.

## Results

A run writes `result.json` (load, results, histogram, resources, machine, commit, JVM and configuration) and `summary.md` beside the servers' logs to `wow-benchmarks/results/e2e/<time>-<label>-<version>-<backend>-n<nodes>-<scenario>/`, which git ignores; a report copies the results it cites into `results/reports/`. Ports: public 18080 to 18082, V10 internal 19090 to 19092, MongoDB 27017, PostgreSQL 5432, Kafka 9092.

On one machine every service, store and the load generator share the CPU: results are directional, and the report says so (D224).
