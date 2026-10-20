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

A run writes `result.json` (load, results, histogram, resources, machine, commit, JVM and configuration) and `summary.md` beside the servers' logs to `wow-benchmarks/results/e2e/<time>-<label>-<version>-<backend>-n<nodes>-<scenario>/`, which git ignores; a report copies the results it cites into `results/reports/`. Ports: public 18080 to 18082, V10 internal 19090 to 19092, MongoDB 27017, PostgreSQL 5432, Kafka 9092.

On one machine every service, store and the load generator share the CPU: results are directional, and the report says so (D224).

## Running it on GitHub Actions

The timed measurements need a machine to themselves for hours, so `.github/workflows/v10-e2e-bench.yml` runs them on GitHub's runners. A run starts when a branch under `v10-bench-run/` is pushed, and the V10 server is built from the pushed commit:

```bash
git push origin v10:v10-bench-run/20261020
```

The defaults are every suite, 20 s warm-up, 40 s measured, 2 rounds, 80% of saturation, and the 9.x server built from `v10-9x-baseline` (the last commit of `v10` with the 9.x code, D262). To change them, commit `wow-benchmarks/e2e/bench-run.json` on the run branch with any of these keys:

```json
{ "suites": ["e1-mongo-uniform", "e1-mongo-zipf"], "warm_up": 20, "measure": 40, "repeats": 3, "fraction": 0.8, "baseline_ref": "v10-9x-baseline" }
```

The suites are `e1-mongo-uniform`, `e1-mongo-zipf`, `e1-pg-uniform`, `e2-mongo`, `e3-mongo`, `e3-pg`, `e3-mongo-diag`, `pg-append-queue`, and the cliff suites `cliff-e3-pg`, `cliff-e3-mongo` and `cliff-e3-9x`. A cliff suite runs E3 in open loop at fixed rates (50, 100, 200, 300, 400 and 500/s, a minute each on fresh containers) until two steps in a row fail to keep up. For V10 it sets `bench.signals.enabled=true`: the benchmark server's `BenchSignals` observer (`bench-v10/.../BenchSignals.kt`, the one file without a 9.x twin, off in every other run) then logs the node's cumulative overload and fallback counts once a second, as `bench-signals {json}` in the node log. The counts cover the tails' store reads by reason, refused commands by code, timed-out waits by stage, dropped signals, failed forwards and paused partitions. The step reads the last such line, so a cliff can be told apart from plain overload. When the run has finished, delete the branch (its results stay with the run):

```bash
git push origin --delete v10-bench-run/20261020
```

The workflow can also be dispatched by hand, with the same settings as inputs, once a copy of the file is on the default branch: GitHub only dispatches workflows that are there. Runs queue one behind another.

Each suite runs in a job of its own (`.github/scripts/v10-e2e-bench.mjs` drives `bench.sh`). First it finds each arm's saturation: closed loop, 3 s warm-up, 8 s steps. The closed loop reaches its best throughput only with deep queues, so before measuring, each arm's rate is checked in open loop: a probe at `fraction` of the saturation (10 s warm-up, 20 s measured), stepping down by a quarter (at most 4 probes) while more than 1% fails or less than 97% of the rate completes. Then it runs the arms alternately at their calibrated rates for the set number of rounds, and adds one more round when two runs of an arm differ by more than 5% in throughput or p99. Every comparison is a pair measured on the same runner, so the ratios hold across runners; the absolute numbers belong to that runner. Server properties for a run reach the servers as `SPRING_APPLICATION_JSON`, which they inherit from `bench.sh`'s environment. Each job uploads its results and its summary, and the report job combines them into `report.md`.
