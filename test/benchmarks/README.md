# Benchmarks

The end-to-end architecture benchmark of decision D224 (roadmap M7.5, B.0 to B.R) and the performance evidence of the project. Conclusions come only from requests that enter through HTTP and complete when the result the client waits for is visible. Services, stores and the bus are separate processes, each version in its recommended production topology. This folder holds the benchmark domain and its server, the load generator, the harness and the committed results.

## Layout

| Path | Project | What it is |
| --- | --- | --- |
| `server/` | `:benchmark-server` | The account and transfer saga domain, its specs and its Spring Boot server, on V10 modules only (in the V10 region of the settings, checked by `:check`) |
| `load/` | `:benchmark-load` | The load generator: a JVM of its own on the JDK alone, no Wow module and no library |
| `harness/` | none | `compose.yml` (MongoDB, PostgreSQL, Kafka) and `bench.sh` (start, seed, check, run, collect, clean up) |
| `results/` | none | The committed performance evidence: every report with the commit, command, environment and raw results that reproduce it |
| `bench-9x/` (9.x tree) | `:e2e-bench-9x` | The same domain and server on 9.x modules only, at `wow-benchmarks/e2e/bench-9x/` of the side branch `v10-9x-baseline` (decision D262) |

None of them is published; the root build keeps them out of the ABI checks and the coverage reports (`e2eBenchmarkProjects`). No project depends on both versions (D85).

The 9.x code, `bench-9x/` with it, left this branch early (decision D262). The 9.x side runs from a worktree of the side branch `v10-9x-baseline`, the last commit that had it, as its own processes and containers like before: `git worktree add ../wow-9x origin/v10-9x-baseline`, then `WOW_BENCH_9X_REPO=../wow-9x bench.sh build` builds both servers and `--version=9x` runs the 9.x one from there. 9.x's JMH benchmarks (`:wow-benchmarks`, the benchmark plugin of `build-logic`, `benchmark-smoke.yml` and `benchmark-ab.yml`) left with it (D244, D262) and stay on main and `v10-9x-baseline`; V10 has no JMH level. The 9.x-era reports in `results/` remain evidence under the conditions they record, reproduced from `v10-9x-baseline` or main, and keep the paths and project names of their time.

## The twin domain

Each module has the same files with the same names and the same package, `me.ahoo.wow.benchmark.transfer`, so `diff -r "$WOW_BENCH_9X_REPO/wow-benchmarks/e2e/bench-9x/src" server/src` shows only what the frameworks make different:

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

`src/test/.../TransferCases.kt` is one table of cases, byte for byte the same in both modules (`SharedCasesTest` in `:benchmark-server` checks it against the SHA-256 of the 9.x file at `v10-9x-baseline`). Each version's `AccountSpec` and `TransferSagaSpec` run every case through its own test DSL.

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

- every server JVM: CPU time and RSS from `ps` each second; heap and collections from one `jstat -gc -t <pid> 1000` per JVM (heap used max and average, the deltas of `YGC`, `YGCT`, `FGC`, `FGCT`, `CGC`, `CGCT`, `GCT`); `--jfr` adds a flight recording per server; the bytes it allocated (`totalThreadAllocatedBytes` of its `ThreadMXBean`, read over the Attach API at start and at stop, `Allocations.kt`), which the report divides by the run's operations as Alloc/op beside CPU ms/op;
- every container: CPU and memory from one `docker stats` stream;
- the stores: MongoDB's `serverStatus().opcounters`, PostgreSQL's `pg_stat_database` of `transfer`, and the end offsets of every Kafka topic, before and after, and their difference.

## Running it

From the repository root, with Docker and `JAVA_HOME` set to a JDK 21:

```bash
test/benchmarks/harness/bench.sh build
test/benchmarks/harness/bench.sh run --version=9x  --scenario=E1 --rate=200 --warm-up=30 --measure=60
test/benchmarks/harness/bench.sh run --version=v10 --backend=postgres --scenario=E3 --rate=200
test/benchmarks/harness/bench.sh run --version=v10 --nodes=3 --scenario=E1 --rate=200 --distribution=zipf
test/benchmarks/harness/bench.sh saturate --version=9x --scenario=E1 --max-concurrency=256
```

`--accounts=<n>` with `--seed-concurrency=<n>` sizes the data set; seeding opens the accounts over HTTP, about 3,000 a second at a concurrency of 128 on a laptop. `--mix=E1:50,E4-state:40` runs a weighted mix of scenarios instead of one. On Linux, `--load-cpus` and `--server-cpus` pin the load generator, and the servers with the containers, to separate CPUs. `--install=<dir>` runs another build of the same version, for instance a baseline's `installDist` output (`<dir>` is the distribution's root, the one holding `bin/`); by default the harness runs the build in this tree.

A run writes `result.json` (load, results, histogram, resources, machine, commit, JVM and configuration) and `summary.md` beside the servers' logs to `test/benchmarks/results/e2e/<time>-<label>-<version>-<backend>-n<nodes>-<scenario>/`, which git ignores; a report copies the results it cites into `results/reports/`. Ports: public 18080 to 18082, V10 internal 19090 to 19092, MongoDB 27017, PostgreSQL 5432, Kafka 9092.

On one machine every service, store and the load generator share the CPU: results are directional, and the report says so (D224).

## Running it on GitHub Actions

The timed measurements need a machine to themselves for hours, so `.github/workflows/bench.yml` (Bench) runs them on GitHub's runners. The benchmark serves four purposes: it guards V10 against regressions (the head against a V10 baseline), gives capacity guidance (the sustainable rate of each scenario), tests behaviour under overload and through a hand-over, and finds defects, because every run is checked for anomalies.

A run starts when a branch under `bench-run/` is pushed. The head is the pushed commit:

```bash
git push origin v10:bench-run/20261020
```

Runs follow D224's tiers: run the smallest one that answers the question. V10 has no L0: the JMH microbenchmarks and their `benchmark-ab.yml` left with 9.x (D262). The tiers are:

- **L1**: component counts, `component-mongo` and `component-pg`, at 1k. They take the head against the baseline when there is one.
- **L2** (the default): end-to-end points, head against baseline. They are `e1-mongo-uniform`, `e1-pg-uniform`, `e3-mongo` and `e3-pg`.
- **L3**: the end-to-end grid, every `ab` suite, run before an rc or after a major merge.
- **L4**: the special runs: the sustainable rates, cliffs, the cluster, and `cold-*` at a million accounts.

Named `suites` replace the tier's list. Each suite runs at the tier's data sets:

- `1k` accounts, which fit every cache (hot);
- `100k`, the default from L2 up;
- `1M`, cold.

L3 runs its grid at 1k and 100k; `dataset` replaces the tier's sets.

From 100k accounts up, every run is **primed** (`bench.sh --prime`, D224 note ⑤): before its warm-up, an untimed closed-loop pass touches each seeded account once with a deposit, whose snapshot write also warms the pages the reads hit. Otherwise the measured window would still be filling the caches, because at uniform keys and a few hundred operations a second a warm-up touches only a small share of the aggregates. The report records whether each suite was primed and how long priming took. The cold suites are never primed, and the report labels them cold. Above the node's state cache (V10's default `wow.state-cache.capacity`, 4096 aggregates per context), the steady state is a full cache with evictions, which priming reaches too; the report says so. If a primed run still trips the stability check, that is a finding. The other defaults are 30 s warm-up (at least 30 s from L2 up, D224 note ⑤), 40 s measured, 2 rounds, 80% of saturation before calibration, and a p99 limit of 200 ms for the sustainable rates. The baseline is the newest `v10.*` release tag that has the benchmark server (`test/benchmarks/server/`, or `wow-benchmarks/e2e/bench-v10/` before the move); without one, and without `base_ref`, the baseline suites are skipped and the report says so. To change any of this, commit `test/benchmarks/bench-run.json` on the run branch:

```json
{ "tier": "L4", "base_ref": "v10.0.0-beta.1", "p99_limit_ms": 500 }
```

The keys are `tier`, `suites`, `dataset`, `aa`, `profile`, `warm_up`, `measure`, `repeats`, `fraction`, `p99_limit_ms`, `properties`, `base_ref` and `nine_x_ref`. `suites` takes suite names and the group names below (`all` is every group but `vs-9x`). `properties`, a JSON object of server properties, goes to the last arm of every paired suite: the head, or with `"aa": true` the second of the two head arms, so an A/A run with properties measures the head's defaults against the changed head on one runner (a probe; its ratios are not written to `noise.json`):

```json
{ "suites": ["e1-pg-uniform"], "aa": true, "properties": { "wow.snapshot.interval": "PT10S" } }
```

When the run has finished, delete the branch; its results stay with the run:

```bash
git push origin --delete bench-run/20261020
```

The workflow can also be dispatched by hand, with the same settings as inputs plus `head_ref`, once a copy of the file is on the default branch: GitHub only dispatches workflows that are there. Two runs of one ref queue; runs of different refs run side by side.

| Group | Suite | What it answers |
| --- | --- | --- |
| `ab` | `e1-mongo-uniform`, `e1-mongo-zipf`, `e1-pg-uniform`, `e2-mongo`, `e3-mongo`, `e3-pg` | Head against baseline, paired on one runner: throughput and CPU per operation at each arm's calibrated rate, latency at one rate for both |
| `component` | `component-mongo`, `component-pg` | L1: the domain on the kernel and one store in one JVM, no HTTP (`bench.sh component`, `ComponentBench` in `server/`), E1, E3 and E1-large. Each operation's store round trips by command (MongoDB driver commands; PostgreSQL statements, commits and rollbacks) and bytes allocated, and an indicative closed-loop timing. A head above the baseline by more than 5% in round trips or allocation is flagged. A baseline older than ComponentBench reports `unavailable` |
| `ab` | `large-mongo`, `large-pg` | E1 with a large payload (D224 note ⑥): `Annotate`, five ~2 KB events per command. It exists in V10's domain only; a baseline older than it fails its runs |
| `head` | `cold-mongo`, `cold-pg` | A million accounts (cold caches): E1 at 200/s and E4 point reads at 500/s |
| `ab` | `mixed-mongo`, `mixed-pg` | Reads and writes together (`--mix=E1:50,E4-state:40,E4-list:10`): whether the query bulkheads keep the writes fast; each scenario's latency is reported apart |
| `head` | `sustainable-mongo` (E1, E2, E3), `sustainable-pg` (E1, E3) | The sustainable rate of each scenario: the highest open-loop rate with at most 1% errors, at least 97% completed and p99 within the limit, found by bisection below the closed-loop saturation |
| `head` | `e5-mongo`, `e5-pg` | Three nodes, requests round-robin, E1 at 150/s and E3 at 50/s for 90 s, once steady and once with node 2 stopped 25 s into the measured phase (SIGTERM, so its partitions hand over) and restarted 20 s later with its own command line |
| `head` | `cliff-e3-pg`, `cliff-e3-mongo` | E3 in open loop at 50, 100, 200, 300, 400 and 500/s, a minute each on fresh containers, until two steps in a row fail to keep up |
| `head` | `e3-mongo-diag`, `pg-append-queue` | The E3 latency rate sweep and toggles on MongoDB; PostgreSQL's append queue off against on (D248) |
| `vs-9x` | `vs-9x-e1`, `vs-9x-e3`, `vs-9x-cliff-e3` | The head against 9.x, built from `nine_x_ref` (`v10-9x-baseline`, the last commit of `v10` with the 9.x code, D262). Opt-in, and removed at GA |

Each suite runs in a job of its own; `.github/scripts/bench.mjs` drives `bench.sh`, and the baseline's and the 9.x server are passed as `--install`. A baseline from before the move to `test/benchmarks/` builds from its own layout (`:e2e-bench-v10`).

A paired suite first finds each arm's saturation in closed loop: 3 s warm-up, 8 s steps, concurrency doubling up to 4096. A saturation found at the cap is a lower bound, marked `≥`.

The closed loop reaches its best throughput only with deep queues, so before measuring, each arm's rate is checked in open loop. A probe runs at `fraction` of the saturation (10 s warm-up, 20 s measured) and steps down by a quarter, at most 4 probes, while more than 1% fails or less than 97% of the rate completes.

The arms then run alternately at their calibrated rates, plus one more round when two runs of an arm differ by more than 5% in throughput or p99. These calibrated rounds give throughput and CPU per operation; their latencies are at unequal loads. The same number of equal-rate rounds follows, with both arms at the lower calibrated rate, and the latency ratios come from those.

Every comparison is a pair measured on the same runner, so the ratios hold across runners; the absolute numbers belong to that runner. A run's server properties reach the servers as `SPRING_APPLICATION_JSON`, which they inherit from `bench.sh`'s environment.

Every V10 run sets `bench.signals.enabled=true`. With it, the benchmark server's `BenchSignals` observer (`server/.../BenchSignals.kt`, the one file without a 9.x twin) logs the node's cumulative overload and fallback counts once a second, as `bench-signals {json}` in the node log. It counts the tails' store reads by reason, refused commands by code, timed-out waits by stage, dropped signals, failed forwards, and paused and resumed partitions.

p99.9 is reported only when a run has at least 1000 samples beyond it (a million operations); otherwise it shows `n/a`.

The report counts what these lines grew by over each run's measured phase, summed over the nodes (a restarted node's log included): a node's counts are cumulative from its start, so its last line alone also holds the seeding and priming, which run flat out. A failed run, which has no measured phase, shows each node's last line. It flags these anomalies:

- a partition paused (D243);
- a tail read the store after a gap or an overflow (D249);
- more than 1% errors;
- a measured second below half the rate;
- p99 above 10× p50, when p99 is at least 50 ms;
- the head allocating more than 5% more bytes per operation than the baseline, in the median of the equal-rate pairs (bytes per operation fall as the rate rises, since a node's background allocation is spread over more operations).

Two more anomalies are flagged:

- **Unstable:** the measured phase's halves differ in mean throughput or in median per-second p50 by more than the suite's A/A noise (from `noise.json`; without it 5% and 25%). p99 is compared too, against its A/A noise (default 50%), but only when each half has at least 1000 samples beyond it; with fewer, half a run's tail is noise, and a stable system would trip the check.
- **Load generator saturated:** the generator used 90% of its CPUs, so the run measured the generator rather than the servers.

Where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up), only the signals are flagged.

On a runner of 4 or more CPUs, the load generator gets the last CPU to itself, and the servers and containers get the others (`bench.sh --load-cpus`, `--server-cpus`; D224 note ③). The run then records the load generator's own CPU and names the busiest consumer, the servers, a container or the generator, as the bottleneck.

With `"profile": true`, every paired suite adds one more run per arm after its timed rounds, never inside them (D224 note ⑧). This run takes a flight recording (`--jfr`, settings `profile`) at the rate the latencies compare at. The recording uses the JDK's `profile` settings plus safepoint events, and socket reads, writes and thread parks from 1 ms (`jfr configure`, passed as `bench.sh --jfr-settings`). The summary shows the first lines of `jfr view` hot-methods, allocation-by-class, contention-by-site, gc-pauses, safepoints, latencies-by-type and socket-reads-by-host, so a tail can be attributed to GC pauses, safepoints, store latency or lock and pool waits. On PostgreSQL the profile run also loads `pg_stat_statements` and dumps the top statements, the checkpoints and the waits after the run (`postgres-stats.txt`). The run's directory keeps the full views and the recordings.

Significance (D224 note ②) works in two steps:

1. An A/A run (`"aa": true`) measures the head against itself in the baseline suites, with at least 4 equal-rate rounds. For each metric (throughput, p50, p99), the half-width of the 95% bootstrap interval of its A/A ratio, at least 2%, becomes the suite's noise threshold. The report writes these thresholds as `noise.json`; committed as `test/benchmarks/noise.json`, they apply to later runs.
2. The report's Significance table gives each ratio a 95% bootstrap interval over the measured seconds: throughput at the calibrated rates, p50 and p99 at the equal rate. A latency's point estimate is the median of the rounds' own percentiles, each from the run's whole histogram. A ratio counts as higher or lower only when its interval clears the threshold; without a threshold it is marked `uncalibrated`. Each job uploads its results, server logs and summary, and the report job combines them into `report.md`, anomalies first.
