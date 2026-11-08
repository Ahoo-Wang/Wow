/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { spawnSync } from 'node:child_process';
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

// The steps of bench.yml, the timed end-to-end benchmark of decision
// D224 (test/benchmarks/README.md) on GitHub's runners:
//
//   node .github/scripts/bench.mjs plan            (plan job: the settings)
//   node .github/scripts/bench.mjs matrix          (plan job: the suites and builds that can run)
//   node .github/scripts/bench.mjs suite <name>    (one matrix job per suite)
//   node .github/scripts/bench.mjs report <dir>    (report job)
//
// `plan` validates the dispatch inputs and lists the suites to run. `suite`
// drives test/benchmarks/harness/bench.sh on one runner: it finds each
// arm's saturation point (closed loop, short steps), then runs the arms
// alternately, open loop at `fraction` of their saturation, so that every
// comparison is a pair measured on the same machine minutes apart; the
// relative result survives the variance between runners, the absolute numbers
// do not. Rounds alternate which arm goes first (ABBA), so drift over the job
// cancels too. When two runs of one arm differ by more than DRIFT in
// throughput or p99, one more round runs. bench.sh starts every run on fresh
// containers and servers. `report` combines the suites' results.

// The arms: a build (`install`: `head`, the pushed or dispatched V10 ref; `base`, the V10 baseline ref) on a backend,
// and the server properties it runs with (handed to the servers as SPRING_APPLICATION_JSON, which bench.sh's servers
// inherit). Every run also turns on the benchmark server's BenchSignals (SIGNALS), which count the node's overload and
// fallback signals for the anomaly checks.
const arm = (install, backend) => ({ id: install, backend, install });
const HEAD_MONGO = arm('head', 'mongo');
const BASE_MONGO = arm('base', 'mongo');
const HEAD_PG = arm('head', 'postgres');
const BASE_PG = arm('base', 'postgres');
const withProps = (arm, id, props) => ({ ...arm, id, props });
export const SIGNALS = { 'bench.signals.enabled': true };
// The industry comparison's arms (test/benchmarks/README.md): Wow with no bus (`--wow.transport=none`, X-33), as the
// Axon server has none, and Axon Framework 4 (`test/benchmarks/axon-server`), each on the one PostgreSQL. The `axon`
// build is made by the suite's own job (axonInstall). `sql`: what the store holds after a session (bench.sh inspect).
const WOW_NO_BUS = {
  id: 'wow',
  backend: 'postgres',
  install: 'head',
  serverArgs: '--wow.transport=none',
  sql: 'test/benchmarks/server/inspect.sql',
};
// The same server before and after a change, on one runner (a sessions suite of the two): the baseline's build and
// the head's (wow-pg-same-rate-ab).
const WOW_BASE_NO_BUS = { ...WOW_NO_BUS, id: 'base', install: 'base' };
const WOW_HEAD_NO_BUS = { ...WOW_NO_BUS, id: 'head' };
const AXON = {
  id: 'axon',
  backend: 'postgres',
  install: 'axon',
  sql: 'test/benchmarks/axon-server/inspect.sql',
};
/**
 * Whether an arm's servers use Kafka: not Axon's, which has no bus, nor a Wow node without a topic transport
 * (`--wow.transport=none`). bench.sh starts Kafka only for an arm that does (`--no-bus` otherwise).
 */
export const usesBus = arm =>
  arm.install !== 'axon' &&
  !/(^|\s)--wow\.transport=none(\s|$)/.test(arm.serverArgs ?? '');
// Axon's transfer reactions in its tracking processor of 16 segments and threads, axon-server's own, against 2, the
// server's CPUs on a runner, set when the session starts: the segments suite.
const AXON_16 = {
  ...AXON,
  id: 'axon-16',
  props: { 'benchmark.axon.segments': 16 },
};
const AXON_2 = {
  ...AXON,
  id: 'axon-2',
  props: { 'benchmark.axon.segments': 2 },
};
// The diagnostics of Axon's E3 under closed loop (axon-diag): its 10 connections (Spring Boot's) against 32, and the
// transfer's reactions nested in the processor's unit of work against units of work of their own.
const AXON_POOL_32 = {
  ...AXON,
  id: 'axon-pool-32',
  props: { 'spring.datasource.hikari.maximum-pool-size': 32 },
};
const AXON_SEPARATE = {
  ...AXON,
  id: 'axon-separate',
  props: { 'benchmark.axon.reactions': 'separate' },
};
// The runtime calibration (D393): the head's server on JDK 21 against JDK 25 on one runner, the load generator on 25
// in both, so a ratio is the runtime's alone. `java`: the server's JDK, by its major version (javaHome, bench.sh
// --server-java).
const onJava = (arm, java) => ({ ...arm, id: `jdk${java}`, java });
const JDK21_PG = onJava(HEAD_PG, 21);
const JDK25_PG = onJava(HEAD_PG, 25);
const JDK21_MONGO = onJava(HEAD_MONGO, 21);
const JDK25_MONGO = onJava(HEAD_MONGO, 25);
// The write path's cost under an access policy: the head's server with every request a teller's caller
// (`benchmark.access`), without a policy of the account and with one the teller passes, paired on one runner.
const ACCESS_CLAIMS_PG = withProps(HEAD_PG, 'claims', {
  'benchmark.access': 'claims',
});
const ACCESS_POLICY_PG = withProps(HEAD_PG, 'policy', {
  'benchmark.access': 'policy',
});
const ACCESS_CLAIMS_MONGO = withProps(HEAD_MONGO, 'claims', {
  'benchmark.access': 'claims',
});
const ACCESS_POLICY_MONGO = withProps(HEAD_MONGO, 'policy', {
  'benchmark.access': 'policy',
});
// The dataset read path's cost under an access policy (bench.sh measure): with one an analyst's claims pass for a tenth
// of the accounts, whose dataset rows ClickHouse then reads by a semi-join on the accounts' current access records.
const ACCESS_DATASET_PG = withProps(HEAD_PG, 'policy', {
  'benchmark.access': 'dataset',
});
// The `jdk-saturation` suites' closed-loop searches an arm, in turn (ABBA), each from empty stores: the arm's
// saturation is their median, and their spread its A/A band on the runner (saturationSamples).
const SATURATION_SAMPLES = 4;

// The mixed suites' operations: writes while point reads and lists run (the query bulkheads, the write priority).
const MIX = 'E1:50,E4-state:40,E4-list:10';

// The data set sizes: how many accounts a run seeds and draws its keys from. 1k fits every cache (hot); 100k, the
// default from L2 up, does not; 1M is cold. Seeding opens the accounts over HTTP, more at once for the larger sets.
export const DATASETS = {
  '1k': { accounts: 1000, seedConcurrency: 32 },
  '100k': { accounts: 100000, seedConcurrency: 256 },
  '1M': { accounts: 1000000, seedConcurrency: 512 },
};

// Priming (D224 note ⑤): from PRIME_FROM accounts up, every run touches each seeded account once before its warm-up
// (bench.sh --prime), untimed, so the measured window is not still filling the caches; at uniform keys and a few
// hundred operations a second, a warm-up alone touches only a small share of 100k aggregates. A suite marked `cold`
// is never primed. After the prime, bench.sh waits until the nodes are quiet again (`settle`): the closed-loop prime
// leaves them a backlog that a 10 s warm-up does not absorb on a 4-core runner (roadmap §6, performance item 2).
// Unless `wow.state-cache.capacity` is set, the node's state caches hold a tenth of its heap in estimated bytes, a
// state of few recent requests at about 1 KB (B.O-4): STATE_CACHE states at the harness's 1 GB heap, fewer as their
// rings grow. Above that, the steady state a run reaches, primed or not, is a full cache with evictions, which the
// report says.
export const PRIME_FROM = 100000;
export const STATE_CACHE = 104857;

// The E3 latency suspects on MongoDB (H1 to H5 of the E3 analysis): a rate
// sweep, then one key toggled at a time at the middle rate.
const DIAG_RATE = 20;

// Suite groups: `ab` compares the V10 baseline with the head (they need a baseline ref, and are skipped without one),
// `head` measures the head alone, `axon` the head against Axon Framework 4, `jdk` the head's server on JDK 21 against
// JDK 25 (the runtime's calibration, D393), `jdk-saturation` their closed-loop saturations alone, several an arm (a
// paired suite's `saturations`). `noiseFrom`: the suite whose A/A thresholds (noise.json) a suite takes, a
// `jdk` suite its `ab` twin's (the same scenario, backend and layout, paired on the same kind of runner).
//
// kind `paired`: saturation of each arm, open-loop calibration, then rounds of one run per arm; the ratio is
// arms[1] / arms[0] (head/base).
// kind `fixed`: the listed runs once each, at fixed rates. kind `steps`: the cliff steps (CLIFF). kind `capacity`:
// the sustainable rate of each scenario by bisection (CAPACITY). kind `cluster`: three nodes, steady and with a node
// stopped and restarted mid-measurement (CLUSTER). kind `sessions`: the arms in sessions ABBA, each with fixed rates
// and closed-loop sweeps (SESSIONS).
// The CPUs of a suite's processes (D224 note ③): the load generator on the last CPUs of the runner, the servers and
// their containers on the others. Both arms of a paired suite get the same layout, so its ratios compare.
// - A paired PostgreSQL suite gives the generator two: on one core it reached 0.91–0.98 of it before the servers
//   saturated (2026-10-27, ABAB runs 40469058083 and 40486803393), so those suites measured the generator. The Axon
//   comparison (kind `sessions`, on PostgreSQL) takes the same layout.
// - A paired MongoDB suite gives the generator, Kafka, the store and the node one CPU each. On a shared two (the
//   generator on the other two, 2026-10-28, A/A runs 40721764669 and 40743082112) the node, mongod and Kafka used all
//   of them (about 0.6 + 0.95 + 0.45 cores), so a ratio measured whichever took the CPU first; the generator, its
//   samplers counted, used at most 0.78 of a core.
// - The other suites give the generator one and the servers the rest.
export const PAIRED_LOAD_CPUS = 2;

/**
 * The CPU lists of a runner of `total` CPUs: the last `load` to the load generator, the others to the servers and
 * the containers, as taskset lists; none below four CPUs, where a split would starve one side.
 */
export function cpuSplit(total, load = 1) {
  if (!(total >= 4) || !(load >= 1) || load > total - 2) return {};
  const first = total - load;
  return {
    load: load === 1 ? `${first}` : `${first}-${total - 1}`,
    server: `0-${first - 1}`,
  };
}

/** A suite's CPU layout in words: `generator 3 · node 0 · store 1 · Kafka 2`, or `unpinned`. */
export function layoutOf(cpus) {
  const parts = [
    ['generator', cpus?.load],
    ['node', cpus?.server],
    ['store', cpus?.store],
    ['Kafka', cpus?.bus],
  ].filter(([, list]) => list);
  return parts.length > 0
    ? parts.map(([who, list]) => `${who} ${list}`).join(' · ')
    : 'unpinned';
}

/**
 * The CPU layout of a suite of `definition` on a runner of `total` CPUs: `load` and `server`, and for a paired
 * MongoDB suite `bus` (Kafka) and `store` (mongod) on CPUs of their own; none below four CPUs. A `sessions` suite
 * is laid out as a paired PostgreSQL one, but with Kafka (`bus`) on the generator's CPUs.
 */
export function cpuLayout(definition, total) {
  if (!(total >= 4)) return {};
  if (!['paired', 'sessions'].includes(definition.kind))
    return cpuSplit(total, 1);
  // Kafka, when an arm uses it, on the generator's CPUs rather than the servers'; neither server of the Axon comparison
  // uses it, so it does not run there.
  if (definition.kind === 'sessions') {
    const split = cpuSplit(total, PAIRED_LOAD_CPUS);
    return split.load && definition.arms.some(usesBus)
      ? { ...split, bus: split.load }
      : split;
  }
  if (!definition.arms.every(arm => arm.backend === 'mongo'))
    return cpuSplit(total, PAIRED_LOAD_CPUS);
  const servers = total - 3;
  return {
    load: `${total - 1}`,
    bus: `${total - 2}`,
    store: `${total - 3}`,
    server: servers === 1 ? '0' : `0-${servers - 1}`,
  };
}

export const SUITES = {
  'e1-mongo-uniform': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'E1 write on MongoDB, uniform keys: the head against the V10 baseline (regression guard), throughput and CPU at each calibrated rate, latency at one rate.',
  },
  'e1-mongo-zipf': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'zipf',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'E1 write on MongoDB, Zipf keys (hot accounts, lane and cache contention): head against baseline.',
  },
  'e1-pg-uniform': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'E1 write on PostgreSQL: head against baseline.',
  },
  'e2-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E2',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers: 'E2 read-your-write on MongoDB: head against baseline.',
  },
  'e3-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      "E3 saga chain on MongoDB (completion: the target's Entry stored): head against baseline.",
  },
  'e3-pg': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'E3 saga chain on PostgreSQL: head against baseline.',
  },
  'mixed-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'mixed',
    mix: MIX,
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'Reads and writes together on MongoDB (E1 deposits 50%, E4 point reads 40%, E4 lists 10%): whether the query bulkheads keep the writes fast. Head against baseline.',
  },
  'mixed-pg': {
    group: 'ab',
    kind: 'paired',
    scenario: 'mixed',
    mix: MIX,
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'Reads and writes together on PostgreSQL, as mixed-mongo.',
  },
  'large-mongo': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1-large',
    distribution: 'uniform',
    arms: [BASE_MONGO, HEAD_MONGO],
    answers:
      'E1 with a large payload on MongoDB (D224 note ⑥): five events of about 2 KB per command. Head against baseline.',
  },
  'large-pg': {
    group: 'ab',
    kind: 'paired',
    scenario: 'E1-large',
    distribution: 'uniform',
    arms: [BASE_PG, HEAD_PG],
    answers: 'E1 with a large payload on PostgreSQL, as large-mongo.',
  },
  'component-mongo': {
    group: 'component',
    kind: 'component',
    arm: HEAD_MONGO,
    baseArm: BASE_MONGO,
    scenarios: ['E1', 'E3', 'E1-large'],
    answers:
      "L1 on MongoDB: the domain on the kernel and the store in one JVM, no HTTP; each operation's store round trips by command and bytes allocated, head against baseline when there is one.",
  },
  'component-pg': {
    group: 'component',
    kind: 'component',
    arm: HEAD_PG,
    baseArm: BASE_PG,
    scenarios: ['E1', 'E3', 'E1-large'],
    answers:
      'L1 on PostgreSQL, as component-mongo (statements, commits and rollbacks).',
  },
  'cold-mongo': {
    group: 'head',
    kind: 'fixed',
    dataset: '1M',
    cold: true,
    distribution: 'uniform',
    runs: [
      { arm: HEAD_MONGO, rate: 200, scenario: 'E1' },
      { arm: HEAD_MONGO, rate: 500, scenario: 'E4-state' },
    ],
    answers:
      'A million accounts on MongoDB, uniform keys, so the caches miss (cold): E1 writes and E4 point reads at fixed rates.',
  },
  'cold-pg': {
    group: 'head',
    kind: 'fixed',
    dataset: '1M',
    cold: true,
    distribution: 'uniform',
    runs: [
      { arm: HEAD_PG, rate: 200, scenario: 'E1' },
      { arm: HEAD_PG, rate: 500, scenario: 'E4-state' },
    ],
    answers: 'A million accounts on PostgreSQL, as cold-mongo.',
  },
  'sustainable-mongo': {
    group: 'head',
    kind: 'capacity',
    distribution: 'uniform',
    arm: HEAD_MONGO,
    scenarios: ['E1', 'E2', 'E3'],
    answers:
      'Capacity guidance on MongoDB: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.',
  },
  'sustainable-pg': {
    group: 'head',
    kind: 'capacity',
    distribution: 'uniform',
    arm: HEAD_PG,
    scenarios: ['E1', 'E3'],
    answers:
      'Capacity guidance on PostgreSQL: per scenario, the highest open-loop rate with at most 1% errors and p99 within the limit, by bisection.',
  },
  'e5-mongo': {
    group: 'head',
    kind: 'cluster',
    distribution: 'uniform',
    arm: HEAD_MONGO,
    answers:
      'E5 cluster of three nodes on MongoDB, requests round-robin: E1 and E3 steady, then with one node stopped and restarted mid-measurement (its partitions handed over and back).',
  },
  'e5-pg': {
    group: 'head',
    kind: 'cluster',
    distribution: 'uniform',
    arm: HEAD_PG,
    answers: 'E5 cluster of three nodes on PostgreSQL, as e5-mongo.',
  },
  'e3-mongo-diag': {
    group: 'head',
    kind: 'fixed',
    scenario: 'E3',
    distribution: 'uniform',
    runs: [
      { arm: HEAD_MONGO, rate: 5 },
      { arm: HEAD_MONGO, rate: DIAG_RATE },
      { arm: HEAD_MONGO, rate: 40 },
      {
        arm: withProps(HEAD_MONGO, 'head-max-poll-5s', {
          'wow.log.max-poll-interval': '5s',
        }),
        rate: DIAG_RATE,
      },
    ],
    answers:
      'E3 latency on MongoDB: a rate sweep (a median falling at 5/s means load, a flat one a fixed delay), then a 5 s longest poll at 20/s.',
  },
  'cliff-e3-pg': cliff(
    HEAD_PG,
    'E3 on PostgreSQL in fixed open-loop steps: where completions stop following the rate, and whether the tails fell back to the store (a cliff) or the node refused work (overload).',
  ),
  'cliff-e3-mongo': cliff(
    HEAD_MONGO,
    'E3 on MongoDB in fixed open-loop steps, as cliff-e3-pg.',
  ),
  // A sessions suite's plan: the same-rate runs (`rates`), the closed-loop sweeps (`sweeps`, each a scenario and its
  // keys) and whether the own-share runs follow them (`own`); `scenarios` are those its sessions check.
  'axon-pg': {
    group: 'axon',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    // As the local compare.sh seeds and primes.
    seedConcurrency: 128,
    arms: [WOW_NO_BUS, AXON],
    // About half of the slower arm's sustainable rate on a runner (SESSIONS).
    rates: [
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
    ],
    sweeps: [{ scenario: 'E1' }, { scenario: 'E3' }],
    own: true,
    answers:
      'The industry comparison: Wow with no bus (wow.transport=none) against Axon Framework 4 on one PostgreSQL, one node each, as the local compare.sh runs it: per session E1 and E3 at one rate for both, closed-loop sweeps of E1 and E3 to their peaks, then each at half of its own sustainable rate.',
  },
  'axon-pg-contention': {
    group: 'axon',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [WOW_NO_BUS, AXON],
    rates: [],
    sweeps: [
      { scenario: 'E1', distribution: 'zipf' },
      { scenario: 'E3', distribution: 'zipf' },
      { scenario: 'E1', distribution: 'hot' },
    ],
    own: false,
    answers:
      "Contention in the industry comparison: closed-loop sweeps to their peaks with Zipf keys (E1, E3) and the harness's hot keys (E1), Wow with no bus against Axon Framework 4, as axon-pg.",
  },
  'axon-pg-segments': {
    group: 'axon',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [AXON_16, AXON_2],
    rates: [{ scenario: 'E3', rate: 300 }],
    sweeps: [{ scenario: 'E1' }, { scenario: 'E3' }],
    own: false,
    answers:
      "Axon's tracking processor on a runner: 16 segments and threads, axon-server's own, against 2, the server's CPUs, by their peaks and E3 at one rate.",
  },
  // A diagnostic suite (`dumpEvery`) dumps each sweep's threads and PostgreSQL sessions every so many seconds.
  'axon-pg-e3-pool': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [AXON, AXON_POOL_32],
    rates: [],
    sweeps: [{ scenario: 'E3' }],
    own: false,
    dumpEvery: 15,
    answers:
      "A diagnostic of Axon's E3 in closed loop: its 10 connections (Spring Boot's) against 32, each sweep's threads and PostgreSQL sessions dumped every 15 s.",
  },
  'axon-pg-e3-reactions': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [AXON, AXON_SEPARATE],
    rates: [],
    sweeps: [{ scenario: 'E3' }],
    own: false,
    dumpEvery: 15,
    answers:
      "A diagnostic of Axon's E3 in closed loop: the transfer's reactions nested in the processor's unit of work (axon-server's) against units of work of their own, each sweep's threads and PostgreSQL sessions dumped every 15 s.",
  },
  // The same-rate view's latency alone (X-72): in axon-pg on a runner, Wow's p50 at E1 1,000/s and E3 300/s went from
  // 1 ms to 177 ms second by second and differed by session (17.5 against 3.7 ms), Axon's stayed at 1–4 ms. Each
  // session runs both rates twice, so its runs compare with each other as well as with the other sessions'. With
  // `profile`, every server records a flight recording, and each run keeps its servers' recording and PostgreSQL's
  // statements and checkpoints (bench.sh up --jfr).
  'axon-pg-same-rate': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [WOW_NO_BUS, AXON],
    rates: [
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
    ],
    sweeps: [],
    own: false,
    answers:
      "A diagnostic of the same-rate view's latency: Wow with no bus and Axon at E1 1,000/s and E3 300/s, each twice a session, nothing else; with profile, every run's flight recording and PostgreSQL's statements.",
  },
  // The same on a slower disk: PostgreSQL's writes capped at 1,400 a second (bench.sh --store-write-iops). Each WAL
  // flush is a write of its page; on a runner whose flushes took 0.15–0.2 ms, Wow flushed about 1,800 times a second
  // at E1 1,000/s (its append and its snapshot, each a transaction) and Axon about 1,000 (its one write), so the cap
  // binds one and leaves the other headroom, as the runner of axon-pg's slow sessions did with 0.5–0.7 ms flushes.
  'axon-pg-same-rate-slow-disk': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [WOW_NO_BUS, AXON],
    rates: [
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
    ],
    sweeps: [],
    own: false,
    storeWriteIops: 1400,
    answers:
      "axon-pg-same-rate with PostgreSQL's disk writes capped at 1,400 a second: whether the same-rate latency follows the store's flushes.",
  },
  // A change of the same-rate view's latency, before against after on one runner, W A A W (X-72): Wow with no bus,
  // the baseline's build against the head's, at E1 1,000/s and E3 300/s twice a session, then each sweep to its peak,
  // each sweep's part on a runner of its own; so the same rates and the peaks compare with their A/A spreads.
  'wow-pg-same-rate-ab': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [WOW_BASE_NO_BUS, WOW_HEAD_NO_BUS],
    rates: [
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
    ],
    sweeps: [{ scenario: 'E1' }, { scenario: 'E3' }],
    own: false,
    answers:
      "Wow with no bus before and after a change on one runner: the baseline's build against the head's at E1 1,000/s and E3 300/s, each twice a session, then each sweep to its peak.",
  },
  // The same rates on a slower disk, PostgreSQL's writes capped at 1,200 a second: before X-72's fix Wow flushed its
  // WAL about 1,800 times a second at E1 1,000/s, after it about 900, so the cap binds the one and not the other.
  'wow-pg-same-rate-ab-slow-disk': {
    group: 'axon-diag',
    kind: 'sessions',
    scenarios: ['E1', 'E3'],
    distribution: 'uniform',
    seedConcurrency: 128,
    arms: [WOW_BASE_NO_BUS, WOW_HEAD_NO_BUS],
    rates: [
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
      { scenario: 'E1', rate: 1000 },
      { scenario: 'E3', rate: 300 },
    ],
    sweeps: [],
    own: false,
    storeWriteIops: 1200,
    answers:
      "wow-pg-same-rate-ab's same rates with PostgreSQL's disk writes capped at 1,200 a second, no sweep.",
  },
  'jdk-e1-pg': {
    group: 'jdk',
    kind: 'paired',
    noiseFrom: 'e1-pg-uniform',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [JDK21_PG, JDK25_PG],
    answers:
      "The runtime's share of a change (D393): E1 on PostgreSQL, the head's server on JDK 21 against JDK 25.",
  },
  'jdk-e3-pg': {
    group: 'jdk',
    kind: 'paired',
    noiseFrom: 'e3-pg',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [JDK21_PG, JDK25_PG],
    answers: 'E3 on PostgreSQL, as jdk-e1-pg.',
  },
  'jdk-e1-mongo': {
    group: 'jdk',
    kind: 'paired',
    noiseFrom: 'e1-mongo-uniform',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [JDK21_MONGO, JDK25_MONGO],
    answers: 'E1 on MongoDB, as jdk-e1-pg.',
  },
  'access-e1-pg': {
    group: 'access',
    kind: 'paired',
    noiseFrom: 'e1-pg-uniform',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [ACCESS_CLAIMS_PG, ACCESS_POLICY_PG],
    answers:
      "What an access policy costs a command (D390): E1 on PostgreSQL, the head's server with every request a teller's caller, without a policy of the account and with one the teller passes.",
  },
  'access-e3-pg': {
    group: 'access',
    kind: 'paired',
    noiseFrom: 'e3-pg',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [ACCESS_CLAIMS_PG, ACCESS_POLICY_PG],
    answers:
      "E3 on PostgreSQL, as access-e1-pg: the root command is checked, the saga's derived ones are trusted.",
  },
  'access-e1-mongo': {
    group: 'access',
    kind: 'paired',
    noiseFrom: 'e1-mongo-uniform',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [ACCESS_CLAIMS_MONGO, ACCESS_POLICY_MONGO],
    answers: 'E1 on MongoDB, as access-e1-pg.',
  },
  'access-e3-mongo': {
    group: 'access',
    kind: 'paired',
    noiseFrom: 'e3-mongo',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [ACCESS_CLAIMS_MONGO, ACCESS_POLICY_MONGO],
    answers: 'E3 on MongoDB, as access-e3-pg.',
  },
  'access-dataset-ch': {
    group: 'access-dataset',
    kind: 'measure',
    measure: 'dataset-access',
    arms: [ACCESS_CLAIMS_PG, ACCESS_DATASET_PG],
    answers:
      "What an access policy costs a dataset query (D390 C4): one node's list page, count and aggregation of a dataset of 1,000,000 rows over 100,000 accounts on ClickHouse, without a policy of the account and with one whose predicate compares each account's current owner with an analyst's claims (a tenth of them), read by a semi-join on the Sink's `_state`.",
  },
  'jdk-e1-pg-saturation': {
    group: 'jdk-saturation',
    kind: 'paired',
    scenario: 'E1',
    distribution: 'uniform',
    arms: [JDK21_PG, JDK25_PG],
    saturations: SATURATION_SAMPLES,
    answers:
      "Whether jdk-e1-pg's closed-loop saturation differs between JDK 21 and 25 beyond its A/A band: four searches an arm, ABBA, nothing else.",
  },
  'jdk-e3-pg-saturation': {
    group: 'jdk-saturation',
    kind: 'paired',
    scenario: 'E3',
    distribution: 'uniform',
    arms: [JDK21_PG, JDK25_PG],
    saturations: SATURATION_SAMPLES,
    answers: 'E3 on PostgreSQL, as jdk-e1-pg-saturation.',
  },
};

/** The suites of a group. */
export const suitesOf = group =>
  Object.keys(SUITES).filter(name => SUITES[name].group === group);

// The tiers of D224's benchmark ladder that this workflow runs: run the smallest one that answers the question. L2, an
// end-to-end point (one scenario on one backend, head against baseline), is the default and never the whole grid;
// L3 is the end-to-end grid (before an rc or after a major merge); L4 the special runs: sustainable rates, overload
// cliffs and the three-node cluster. V10 has no L0 (in-process JMH) level. `suites` given by name replaces the tier's list.
// The data sets each tier runs its suites at (the `dataset` setting replaces them).
export const TIER_DATASETS = {
  L1: ['1k'],
  L2: ['100k'],
  L3: ['1k', '100k'],
  L4: ['100k'],
};

export const TIERS = {
  L1: ['component-mongo', 'component-pg'],
  L2: ['e1-mongo-uniform', 'e1-pg-uniform', 'e3-mongo', 'e3-pg'],
  L3: suitesOf('ab'),
  L4: [
    'sustainable-mongo',
    'sustainable-pg',
    'cliff-e3-mongo',
    'cliff-e3-pg',
    'e5-mongo',
    'e5-pg',
    'cold-mongo',
    'cold-pg',
  ],
};

// kind `steps` (the cliff suites): one arm in open loop at rising fixed rates, each step on fresh containers, a
// minute measured, until two steps in a row are not sustained.
export const CLIFF = {
  rates: [50, 100, 200, 300, 400, 500],
  warmUp: 10,
  measure: 60,
};

function cliff(arm, answers) {
  return {
    group: 'head',
    kind: 'steps',
    scenario: 'E3',
    distribution: 'uniform',
    arm,
    answers,
  };
}

// kind `component` (L1): per scenario, one run of each arm through bench.sh's `component` command.
export const COMPONENT = { ops: 20000, warmUpOps: 2000, concurrency: 64 };

// kind `measure`: rounds of one run of each arm, ABBA, through bench.sh's `measure` command, each sending every query
// `warmUpRuns` times untimed, then `runs` times timed; a difference within the A/A band (the spread of an arm's two
// runs) is no result (D392 ③).
export const MEASURE = { runs: 200, warmUpRuns: 20, rounds: 2 };

// The views of a flight recording the profile summarizes, and how many of each view's lines it keeps.
export const JFR_VIEWS = [
  'hot-methods',
  'allocation-by-class',
  'contention-by-site',
  'gc-pauses',
  'safepoints',
  'latencies-by-type',
  'socket-reads-by-host',
];

// The recording's settings: the JDK's `profile`, plus the safepoint events (off there) and socket reads, writes and
// thread parks from 1 ms, so a tail can be told apart as GC pauses, safepoints, store latency or lock and pool waits.
const JFR_SETTINGS = [
  '+jdk.SafepointBegin#enabled=true',
  '+jdk.SafepointEnd#enabled=true',
  '+jdk.SocketRead#threshold=1 ms',
  '+jdk.SocketWrite#threshold=1 ms',
  '+jdk.ThreadPark#threshold=1 ms',
];

/** Writes the profile's .jfc into `dir` with the JDK's `jfr configure`; its path, or undefined when that fails. */
export function jfrSettings(dir) {
  const file = join(dir, 'profile.jfc');
  const jfr = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', 'jfr')
    : 'jfr';
  const result = spawnSync(
    jfr,
    ['configure', '--input', 'profile', ...JFR_SETTINGS, '--output', file],
    {
      encoding: 'utf8',
    },
  );
  return result.status === 0 && existsSync(file) ? file : undefined;
}
const JFR_LINES = 16;

/** The JDK's `jfr view` of each JFR_VIEWS over node-1.jfr in `dir`, its first lines each; empty without a recording. */
export function jfrViews(dir) {
  const recording = join(dir, 'node-1.jfr');
  if (!existsSync(recording)) return {};
  const jfr = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', 'jfr')
    : 'jfr';
  const views = Object.fromEntries(
    JFR_VIEWS.map(view => {
      const result = spawnSync(
        jfr,
        ['view', '--width', '160', view, recording],
        { encoding: 'utf8' },
      );
      const text =
        result.status === 0
          ? result.stdout
          : `jfr view ${view} failed: ${result.stderr}`;
      writeFileSync(join(dir, `jfr-${view}.txt`), text);
      return [
        view,
        text
          .split('\n')
          .filter(line => line.trim())
          .slice(0, JFR_LINES)
          .join('\n'),
      ];
    }),
  );
  // A PostgreSQL run's store side, when bench.sh dumped it (top statements, checkpoints, waits).
  const postgres = join(dir, 'postgres-stats.txt');
  if (existsSync(postgres)) {
    views['postgres-stats'] = readFileSync(postgres, 'utf8')
      .split('\n')
      .slice(0, 40)
      .join('\n');
  }
  return views;
}

// kind `capacity`: per scenario, the closed-loop saturation bounds a bisection over the open-loop rate; a probe holds
// when at most 1% fails, at least 97% of the rate completes and p99 is within `p99_limit_ms`. The bisection stops
// when the bracket is within 5% of its top or after `probes` probes; the capacity is the highest rate that held. Each
// probe warms up 30 s, as a timed run does: near the limit a 10 s warm-up left a queue the measured phase began with
// (roadmap §6 performance items 2 and 6).
export const CAPACITY = { warmUp: 30, measure: 30, probes: 6, tolerance: 0.05 };

// kind `cluster`: three nodes (bench.sh --nodes=3), requests round-robin, each scenario at a fixed rate a minute and
// a half, once steady and once with node 2 stopped (SIGTERM, its partitions hand over) `stopAfter` seconds into the
// measured phase and restarted `down` seconds later with its own command line.
export const CLUSTER = {
  nodes: 3,
  warmUp: 15,
  measure: 90,
  stopAfter: 25,
  down: 20,
  nodePort: 18081,
  scenarios: [
    { scenario: 'E1', rate: 150 },
    { scenario: 'E3', rate: 50 },
  ],
};

/** The last `bench-signals {json}` line of a server log: the node's cumulative signal counts, or undefined. */
export function lastSignals(log) {
  const lines = log
    .split('\n')
    .filter(line => line.includes('bench-signals {'));
  const last = lines.at(-1);
  if (!last) return undefined;
  try {
    return JSON.parse(
      last.slice(last.indexOf('bench-signals {') + 'bench-signals '.length),
    );
  } catch {
    return undefined;
  }
}

/** Every `bench-signals {json}` line of a server log with its log time (epoch milliseconds), in order. */
function signalLines(log) {
  const lines = [];
  for (const line of log.split('\n')) {
    const at = line.indexOf('bench-signals {');
    const time =
      /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:?\d\d))/.exec(
        line,
      );
    if (at < 0 || !time) continue;
    try {
      lines.push({
        time: Date.parse(time[1]),
        signals: JSON.parse(line.slice(at + 'bench-signals '.length)),
      });
    } catch {
      // A line cut short: the next second's line counts again.
    }
  }
  return lines;
}

/**
 * The signals a server log counted in [from, to] (epoch milliseconds): its last line by `to` less its last line before
 * `from`, per kind and key, nothing that did not grow. A node's counts are cumulative from its start, so its last line
 * alone holds the seeding and priming too, which run flat out. A node started within the window (restarted in a
 * hand-over) counts from zero; one stopped within it, up to its last line. Undefined when no line falls by `to`.
 */
export function signalsWithin(log, from, to) {
  const lines = signalLines(log);
  const end = lines.filter(line => line.time <= to).at(-1);
  if (!end) return undefined;
  const base = lines.filter(line => line.time < from).at(-1)?.signals ?? {};
  const within = { t: end.signals.t };
  for (const [kind, counts] of Object.entries(end.signals)) {
    if (kind === 't' || typeof counts !== 'object') continue;
    for (const [key, count] of Object.entries(counts)) {
      const grown = count - (base[kind]?.[key] ?? 0);
      if (grown > 0) {
        within[kind] ??= {};
        within[kind][key] = grown;
      }
    }
  }
  return within;
}

/** The measured phase of a load generator's `result.json`, in epoch milliseconds; undefined without its times. */
export function measuredWindow(result) {
  const started = Date.parse(result?.startedAt ?? '');
  const warmUp = result?.load?.warmUpSeconds;
  const measure = result?.load?.measureSeconds;
  if (Number.isNaN(started) || warmUp === undefined || measure === undefined)
    return undefined;
  const from = started + warmUp * 1000;
  // The node logs its counts once a second: a second's grace takes the line that closes the phase.
  return { from, to: from + measure * 1000 + 1000 };
}

/** The signals of several nodes' logs, summed per kind and key (a cluster's nodes, a restarted node's log). */
export function mergeSignals(all) {
  const present = all.filter(Boolean);
  if (present.length === 0) return undefined;
  const merged = { t: Math.max(...present.map(signals => signals.t ?? 0)) };
  for (const signals of present) {
    for (const [kind, counts] of Object.entries(signals)) {
      if (kind === 't') continue;
      merged[kind] ??= {};
      for (const [key, count] of Object.entries(counts)) {
        merged[kind][key] = (merged[kind][key] ?? 0) + count;
      }
    }
  }
  return merged;
}

// A query its store's bulkhead refused: answered 429 `wow.query.busy`, whatever the adaptive bound was then. The load
// generator's error kind ends with the code (`E4-list:read-http-429-wow.query.busy`); the report counts these on
// their own, beside the bound that refused them, never as a silent error count.
export const BUSY = 'wow.query.busy';

/** How many of `errors` (kind → count) a store's bulkhead refused. */
export function busyOf(errors) {
  return Object.entries(errors ?? {})
    .filter(([kind]) => kind.endsWith(`-${BUSY}`))
    .reduce((sum, [, count]) => sum + count, 0);
}

/**
 * The query bounds a server log's `bench-query-limits {json}` lines show in [from, to] (epoch milliseconds), per
 * store: the lowest and highest bound, the ceiling, how often the adaptation lowered it, and the seconds it pressed.
 * Undefined when no line falls in the window.
 */
export function queryLimitsWithin(log, from, to) {
  const lines = [];
  let before;
  for (const line of log.split('\n')) {
    const at = line.indexOf('bench-query-limits {');
    const time =
      /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:?\d\d))/.exec(
        line,
      );
    if (at < 0 || !time) continue;
    let limits;
    try {
      limits = JSON.parse(line.slice(at + 'bench-query-limits '.length));
    } catch {
      continue;
    }
    const when = Date.parse(time[1]);
    if (when < from) before = limits;
    else if (when <= to) lines.push(limits);
  }
  if (lines.length === 0) return undefined;
  const stores = {};
  for (const store of Object.keys(lines.at(-1)).filter(key => key !== 't')) {
    const seen = lines.map(limits => limits[store]).filter(Boolean);
    const bounds = seen.map(limit => limit.limit);
    const decreases = seen.at(-1).decreases;
    stores[store] = {
      min: Math.min(...bounds),
      max: Math.max(...bounds),
      ceiling: seen.at(-1).ceiling,
      ...(decreases === undefined
        ? {}
        : {
            lowered:
              decreases - (before?.[store]?.decreases ?? seen[0].decreases),
            pressedSeconds: seen.filter(limit => limit.pressure).length,
          }),
    };
  }
  return stores;
}

/** The query bounds of a run in words: `mongo 12–25 of 50, lowered 3, pressed 8 s`, or `-`. */
export function queryLimitsText(stores) {
  const parts = Object.entries(stores ?? {}).map(([store, it]) => {
    const bound = it.min === it.max ? `${it.min}` : `${it.min}–${it.max}`;
    const adaptive =
      it.lowered === undefined
        ? ''
        : `, lowered ${it.lowered}, pressed ${it.pressedSeconds} s`;
    return `${store} ${bound} of ${it.ceiling}${adaptive}`;
  });
  return parts.join(' · ') || '-';
}

// The anomaly checks of a run, so a run surfaces defects and not only numbers. `overload` marks a run that went past
// what the node keeps up with by design (a calibration or capacity probe, a cliff step that did not keep up), where
// errors are expected; signals are flagged there too, since a pause or a store fallback is worth a look either way.
/** The error kind of an operation the load generator did not send: its in-flight cap was full. */
export const OVERLOAD = 'generator-overload';

export const ANOMALY = {
  errorPercent: 1,
  tailToMedian: 10,
  // Unstable: the halves of the measured phase differ by more than the suite's A/A noise (noise.json) in throughput,
  // p50 or, with enough tail samples, p99; these defaults stand in for a suite without a threshold.
  stability: { throughput: 0.05, p50: 0.25, p99: 0.5 },
  // The load generator used this share of the CPUs it had: the run measured the generator, not the servers.
  generatorBusy: 0.9,
  // A component count of the head above the baseline's by more than this.
  componentGrowth: 0.05,
  // p99 > 10× p50 is flagged only above this p99, so a 2 ms median with a 30 ms p99 does not count.
  minTailMs: 50,
};

/** The anomalies of one run: `{ kind, detail }` each; `noise` is its suite's A/A thresholds, when there are any. */
export function anomaliesOf(run, { overload = false, noise } = {}) {
  const found = [];
  const s = run.signals ?? {};
  const sum = counts => Object.values(counts ?? {}).reduce((a, b) => a + b, 0);
  if (sum(s.partitionPaused) > 0) {
    found.push({
      kind: 'partition paused',
      detail: Object.entries(s.partitionPaused)
        .map(([key, count]) => `${key} ×${count}`)
        .join(', '),
    });
  }
  const fallback = ['gap', 'overflow']
    .filter(reason => s.tailRead?.[reason] > 0)
    .map(reason => `${reason}=${s.tailRead[reason]}`);
  if (fallback.length > 0) {
    found.push({ kind: 'tail read the store', detail: fallback.join(', ') });
  }
  if (run.status !== 'ok') {
    found.push({
      kind: 'run failed',
      detail: 'bench.sh failed; see the job log',
    });
    return found;
  }
  if (overload) return found;
  const m = run.metrics;
  if (
    m.loadGeneratorCpus > 0 &&
    m.loadGeneratorCores >= ANOMALY.generatorBusy * m.loadGeneratorCpus
  ) {
    found.push({
      kind: 'load generator saturated',
      detail: `${num(m.loadGeneratorCores, 2)} of ${m.loadGeneratorCpus} cores: the run measured the generator`,
    });
  }
  const st = m.stability;
  if (st) {
    const limit = metric => noise?.[metric] ?? ANOMALY.stability[metric];
    const drifted = [
      ['throughput', st.throughputChange],
      ['p50', Number.isFinite(st.p50Ratio) ? st.p50Ratio - 1 : undefined],
      ['p99', Number.isFinite(st.p99Ratio) ? st.p99Ratio - 1 : undefined],
    ].filter(
      ([metric, change]) =>
        Number.isFinite(change) && Math.abs(change) > limit(metric),
    );
    if (drifted.length > 0) {
      found.push({
        kind: 'unstable',
        detail: `second half against first, beyond ${noise ? 'the A/A noise' : 'the default limits'}: ${drifted
          .map(
            ([metric, change]) =>
              `${metric} ${change >= 0 ? '+' : ''}${num(change * 100)}% (±${num(limit(metric) * 100)}%)`,
          )
          .join(', ')}`,
      });
    }
  }
  const total = m.ok + m.failed;
  if (total > 0 && (m.failed * 100) / total > ANOMALY.errorPercent) {
    const overloaded = Object.keys(m.errors ?? {}).some(kind =>
      kind.endsWith(OVERLOAD),
    );
    const cap =
      overloaded && Number.isFinite(m.inFlightCap)
        ? `; ${OVERLOAD}: ${m.inFlightCap} in flight at most (${m.inFlightBoundBy})`
        : '';
    // The bulkhead's refusals are named as such, beside the bound that refused them: not the adaptive limit shedding
    // unless the bound shows it lowered.
    const busy =
      m.busy > 0
        ? `; ${m.busy} ${BUSY} (bulkhead), bound ${queryLimitsText(run.queryLimits)}`
        : '';
    found.push({
      kind: 'error spike',
      detail: `${m.failed} of ${total} failed: ${errorsText(m.errors)}${cap}${busy}`,
    });
  }
  if (m.weakSeconds > 0) {
    found.push({
      kind: 'throughput dip',
      detail: `${m.weakSeconds} s below half the rate (lowest ${m.minSecondOk}/s)`,
    });
  }
  if (
    m.p99 >= ANOMALY.minTailMs &&
    m.p50 > 0 &&
    m.p99 > ANOMALY.tailToMedian * m.p50
  ) {
    found.push({
      kind: 'p99 > 10× p50',
      detail: `p50 ${ms(m.p50)}, p99 ${ms(m.p99)}`,
    });
  }
  return found;
}

/** The anomalies of a suite: every run's and probe's, with where they were found; `noise` from noise.json. */
export function anomalies(suite, noise = {}) {
  const thresholds = noise[noiseLabel(suite)];
  const rows = [];
  if (suite.kind === 'measure') {
    // A query answered other than 200: its times are not the query's, which the tables leave out.
    for (const run of suite.runs.filter(it => it.status === 'ok')) {
      for (const [shape, measured] of Object.entries(run.measure.shapes)) {
        if (measured.failed > 0) {
          rows.push({
            suite: labelOf(suite),
            run: run.name,
            context: shape,
            kind: 'failed queries',
            detail: `${measured.failed} of ${measured.timed} answered other than 200`,
          });
        }
      }
    }
    return rows;
  }
  if (suite.kind === 'component') {
    // More store round trips or allocations per operation than the baseline: a regression of the core path's cost.
    for (const head of suite.runs.filter(
      run => run.arm === 'head' && run.status === 'ok',
    )) {
      const base = suite.runs.find(
        run =>
          run.arm === 'base' &&
          run.scenario === head.scenario &&
          run.status === 'ok',
      );
      if (!base) continue;
      for (const [key, label] of [
        ['storeRoundTripsPerOp', 'more store round trips'],
        ['allocatedBytesPerOp', 'more allocation'],
      ]) {
        const ratioValue = head.component[key] / base.component[key];
        if (ratioValue > 1 + ANOMALY.componentGrowth) {
          rows.push({
            suite: labelOf(suite),
            run: head.name,
            context: 'counts',
            kind: label,
            detail: `${num(head.component[key], 2)} against ${num(base.component[key], 2)} per operation (×${num(ratioValue, 2)})`,
          });
        }
      }
    }
    return rows;
  }
  if (suite.kind === 'sessions') {
    // A session whose store does not hold what its runs asked of it: that arm's numbers do not compare.
    for (const session of suite.sessions ?? []) {
      if (session.status === 'ok' && session.inspected === 'failed') {
        rows.push({
          suite: labelOf(suite),
          run: session.name,
          context: 'store',
          kind: 'not inspected',
          detail: 'bench.sh inspect failed; see the job log',
        });
      }
      for (const check of session.correctness?.checks ?? []) {
        if (check.ok !== false) continue;
        rows.push({
          suite: labelOf(suite),
          run: session.name,
          context: 'store',
          kind: 'store does not match the runs',
          detail: `${check.name}: ${check.stored} stored, ${check.expected} expected`,
        });
      }
    }
  }
  if (suite.kind === 'paired' && suite.arms?.length === 2 && !suite.aa) {
    // The second arm (the head) allocating more per operation than the first, in the median of the equal-rate pairs:
    // a node's background allocation per second is spread over the operations, so bytes per operation fall as the
    // rate rises, and pairs at unequal rates would compare the rates. Suites without equal rounds use the calibrated.
    const equal = pairs(suite, EQUAL);
    const mode = equal.length > 0 ? EQUAL : CALIBRATED;
    const growth = median(
      (equal.length > 0 ? equal : pairs(suite)).map(
        pair => pair.ratios.allocatedBytesPerOp,
      ),
    );
    if (growth > 1 + ANOMALY.componentGrowth) {
      const [a, b] = suite.arms.map(arm => arm.id);
      rows.push({
        suite: labelOf(suite),
        run: `${b} against ${a}`,
        context: 'cost',
        kind: 'more allocation',
        detail: `${b} allocates ×${num(growth, 2)} the bytes per operation of ${a} (median of the ${mode === EQUAL ? 'equal-rate' : 'calibrated'} pairs)`,
      });
    }
  }
  for (const run of suite.runs ?? []) {
    const overload = suite.kind === 'steps' && run.sustained === false;
    const context = overload
      ? 'overload (cliff step)'
      : run.handover
        ? 'hand-over'
        : 'steady';
    for (const found of anomaliesOf(run, { overload, noise: thresholds })) {
      rows.push({ suite: labelOf(suite), run: run.name, context, ...found });
    }
  }
  for (const probe of suite.probes ?? []) {
    for (const found of anomaliesOf(probe, { overload: true })) {
      rows.push({
        suite: labelOf(suite),
        run: probe.name,
        context: 'overload (probe)',
        ...found,
      });
    }
  }
  return rows;
}

export const DRIFT = 0.05;
// The closed loop reaches its best throughput only with deep queues, so `fraction` of it can be more than a minute
// of open loop sustains (the first runs on GitHub's 4-vCPU runners collapsed into timeouts at 80%). Each arm's
// rate is therefore calibrated in open loop first: a short probe at the rate, and while it is not sustainable
// (more than 1% failed, or less than 97% of the rate completed) the rate steps down by a quarter, at most 4 probes.
export const PROBE = { warmUp: 10, measure: 20, attempts: 4, step: 0.75 };

/** Whether an open-loop run at `rate` kept up: at most 1% failed and at least 97% of the rate completed. */
export function sustainable(m, rate) {
  const total = (m?.ok ?? 0) + (m?.failed ?? 0);
  return total > 0 && m.failed / total <= 0.01 && m.throughput >= 0.97 * rate;
}

// bench.sh's default cap of 256 workers stopped the closed loop before V10 flattened, and so did 1024 on GitHub's
// runners (E1, E2 and E3), so the cap is 4096; the doubling stops on its own after two steps that gain less than 5%.
// A saturation found at the cap is a lower bound, marked `≥` (`capped`).
// Two arms whose saturations differ more than `outlier` times are probed again, the lower once: a search can stop
// early on a stalled step (large-mongo arm b found 202/s at concurrency 1 against arm a's 990/s at 128, 2026-10-28,
// run 40721764669, an A/A run of one build). The second search stands, and the first is kept beside it.
export const SATURATION = {
  warmUp: 3,
  step: 8,
  maxConcurrency: 4096,
  outlier: 3,
};

// kind `sessions` (the Axon comparison), as the local compare.sh runs it: one session per arm and round, ABBA (W A A W
// for two rounds). `bench.sh up` seeds and primes the data set, and the session answers three questions, in this order:
// - same rate: both arms at one fixed rate in open loop (20 s warm-up, 40 s measured), about half of the slower arm's
//   sustainable rate on a runner's two server CPUs, so both run loaded (the suite's `rates`): for the same work, whose
//   latency and cost are lower. Axon's sweeps there kept p99 within 200 ms with no failure up to 1,776–1,969/s (E1) and
//   515–655/s (E3) (EPYC 9V45, run 41568172064, whose fixed rates were 500/s and 150/s, a quarter of that).
// - peak: each scenario's closed-loop sweep, the concurrency growing fourfold from 1 to the paired suites' cap (V10
//   still gained past 1,024 on GitHub's 4-core runners), then on a step at a time while the top step still rises
//   (sweepNext): how much each handles at most. The sweep also gives the arm's sustainable rate, the most a step did
//   with p99 within the limit and no failure (sustainableStep). Coarse then fine: short steps (`locate`, the paired
//   suites' search's 3 s warm-up and 8 s measured) locate the peak and the sustainable rate, then full-length steps,
//   10 s warm-up and 30 s measured, measure the steps around them (fineSteps, fineMore), which are what the views
//   report. A failing full-length step ends once it has measured `failAfter` s (the load generator's stopsEarly).
// - own share: each arm in open loop at `share` of its own sustainable rate from this session's sweep, as long as the
//   same-rate runs: with the same headroom, each one's latency and cost per command.
// then `down`.
export const SESSIONS = {
  share: 0.5,
  warmUp: 20,
  measure: 40,
  sweep: {
    warmUp: 10,
    step: 30,
    locate: { warmUp: SATURATION.warmUp, step: SATURATION.step },
    failAfter: 10,
    growth: 4,
    maxConcurrency: SATURATION.maxConcurrency,
    // A step gaining at most this over the one before has levelled off: the closed loop's own minimum gain.
    gain: 0.05,
    // Each client of a step holds a connection to the one node: 16,384 stay within Linux's ephemeral ports to one
    // address (about 28,000).
    limit: 16384,
  },
};
/**
 * A sessions suite's sweeps, each with its keys (the suite's unless named), its label in the report (`E1`, `E1 zipf`)
 * and its key in run names (`e1`, `e1-zipf`).
 */
export function sweepPlanOf(definition) {
  return definition.sweeps.map(({ scenario, distribution }) => {
    const keys = distribution ?? definition.distribution;
    const own = keys === definition.distribution;
    return {
      scenario,
      distribution: keys,
      label: own ? scenario : `${scenario} ${keys}`,
      key: own ? scenario.toLowerCase() : `${scenario.toLowerCase()}-${keys}`,
    };
  });
}

/**
 * A sessions suite's parts, one a sweep, each in a job of its own on a runner of its own, in fresh sessions, the full
 * ABBA: its sweep (`key`, `label`: sweepPlanOf's), the same-rate runs of its scenario (with the first sweep of that
 * scenario) and the own-share run it sets. So each sweep's ratios and A/A spread are one runner's, and its views run
 * in parallel with the others'. Undefined for a suite of one sweep or of another kind.
 */
export function partsOf(definition) {
  if (definition?.kind !== 'sessions' || definition.sweeps.length < 2)
    return undefined;
  const plans = sweepPlanOf(definition);
  return plans.map(plan => ({
    key: plan.key,
    label: plan.label,
    sweeps: [plan],
    rates: (definition.rates ?? []).filter(
      ({ scenario }) => plans.find(p => p.scenario === scenario) === plan,
    ),
  }));
}

/**
 * The parts of one sessions suite (partsOf), each a suite.json of its own job, as one suite: their sessions, runs and
 * sweeps in the parts' order, each session named with its part's sweep, and each part's runner (`parts`).
 */
export function mergeParts(parts) {
  const order = (partsOf(SUITES[parts[0].name]) ?? []).map(part => part.key);
  const sorted = [...parts].sort(
    (a, b) => order.indexOf(a.part) - order.indexOf(b.part),
  );
  const labelOfPart = suite => suite.sweepPlan?.[0]?.label ?? suite.part;
  const times = key =>
    sorted
      .map(suite => suite[key])
      .filter(Boolean)
      .sort();
  return {
    ...sorted[0],
    part: undefined,
    parts: sorted.map(suite => ({
      part: suite.part,
      label: labelOfPart(suite),
      runner: suite.runner,
      cpus: suite.cpus,
      failures: suite.failures,
    })),
    fixedRates: sorted.flatMap(suite => suite.fixedRates ?? []),
    sweepPlan: sorted.flatMap(suite => suite.sweepPlan ?? []),
    sessions: sorted.flatMap(suite =>
      (suite.sessions ?? []).map(session => ({
        ...session,
        name: `${session.name} (${labelOfPart(suite)})`,
      })),
    ),
    runs: sorted.flatMap(suite => suite.runs ?? []),
    sweeps: sorted.flatMap(suite => suite.sweeps ?? []),
    failures: sorted.reduce((sum, suite) => sum + (suite.failures ?? 0), 0),
    startedAt: times('startedAt')[0],
    finishedAt: times('finishedAt').at(-1),
  };
}

/** The views of a sessions suite's open-loop runs: one rate for both arms, or each at a share of its own. */
export const SAME = 'same-rate';
export const OWN = 'own-share';

/** The load generator's searches of one sweep (saturation.json, the cap's and the steps past it) as one. */
export function mergeSaturations(parts) {
  const steps = parts.flatMap(part => part.steps ?? []);
  const best = steps.reduce((a, b) => (b.throughput > a.throughput ? b : a));
  return {
    steps,
    saturationThroughput: best.throughput,
    saturationConcurrency: best.concurrency,
    generatorCores: best.generatorCores,
    availableProcessors: parts[0].availableProcessors,
  };
}

/**
 * Whether a sweep (sweepOf's steps) goes on past its top step: `{ next }`, the concurrency of the step to run, or
 * `{ end }`, why it stops. It stops when the top step failed more than 1% (`errors`), when the load generator, on
 * `cpus` CPUs, bound it (`generator`), when its peak was at an earlier step (`peaked`), when it gained at most
 * `gain` over the step before (`levelled`), or at `limit` (`limit`).
 */
export function sweepNext(steps, cpus, sweep = SESSIONS.sweep) {
  const last = steps.at(-1);
  const before = steps.at(-2);
  if (!last) return { end: 'failed' };
  if (last.errors > (ANOMALY.errorPercent / 100) * (last.ok + last.errors))
    return { end: 'errors' };
  if (generatorBound(last.generatorCores, cpus)) return { end: 'generator' };
  if (steps.some(step => step.throughput > last.throughput))
    return { end: 'peaked' };
  if (before && last.throughput <= before.throughput * (1 + sweep.gain))
    return { end: 'levelled' };
  const next = last.concurrency * sweep.growth;
  return next <= sweep.limit ? { next } : { end: 'limit' };
}

/**
 * An arm's sustainable rate from its sweep: the step with the most throughput among those with p99 within `p99LimitMs`
 * and no failure, or undefined when none. At the sweep's top step (`top`, its steps' highest unless given) it is a
 * lower bound.
 */
export function sustainableStep(
  steps,
  p99LimitMs,
  top = Math.max(...steps.map(step => step.concurrency)),
) {
  const held = steps.filter(step => holds(step, p99LimitMs));
  if (held.length === 0) return undefined;
  const best = held.reduce((a, b) => (b.throughput > a.throughput ? b : a));
  return {
    throughput: best.throughput,
    concurrency: best.concurrency,
    p99: best.p99,
    lowerBound: best.concurrency >= top,
  };
}

/** Whether a sweep's step held: no failure and p99 within `p99LimitMs`. */
const holds = (step, p99LimitMs) => step.errors === 0 && step.p99 <= p99LimitMs;

/**
 * Coarse then fine, the full-length steps a sweep measures once its short steps (`located`, sweepOf's) found its peak
 * and sustainable rate, in order: the located peak's concurrency and its better neighbour's, so a flat top is measured
 * on both sides of the knee; the located sustainable step's and the one above it, so the rate is the most a full-length
 * step held. A located sweep that held nowhere measures its lowest step for the sustainable rate.
 */
export function fineSteps(located, p99LimitMs) {
  const grid = [...new Set(located.map(step => step.concurrency))].sort(
    (a, b) => a - b,
  );
  const at = concurrency =>
    located.find(step => step.concurrency === concurrency);
  const peak = located.reduce((a, b) => (b.throughput > a.throughput ? b : a));
  const index = grid.indexOf(peak.concurrency);
  const better = [grid[index - 1], grid[index + 1]]
    .filter(concurrency => concurrency !== undefined)
    .map(at)
    .reduce(
      (a, b) => (a === undefined || b.throughput > a.throughput ? b : a),
      undefined,
    );
  const sustainable = sustainableStep(located, p99LimitMs);
  const held = sustainable ? grid.indexOf(sustainable.concurrency) : 0;
  return [
    ...new Set(
      [
        peak.concurrency,
        better?.concurrency,
        grid[held],
        sustainable ? grid[held + 1] : undefined,
      ].filter(concurrency => concurrency !== undefined),
    ),
  ].sort((a, b) => a - b);
}

/**
 * Coarse then fine, once the full-length steps (`measured`) ran: `{ next }`, one more full-length step the sweep
 * needs, `{ end }` when it stops past its located top (sweepNext's why), or `{}` when it has all it needs. The
 * sustainable rate goes down the located steps while no full-length step held, and up while the highest that held has
 * one above it not measured. The peak goes on past the located top a step at a time while a full-length step there
 * still rises (sweepNext, on `cpus` CPUs).
 */
export function fineMore(
  located,
  measured,
  p99LimitMs,
  cpus,
  sweep = SESSIONS.sweep,
) {
  const grid = [...new Set(located.map(step => step.concurrency))].sort(
    (a, b) => a - b,
  );
  const steps = [...measured].sort((a, b) => a.concurrency - b.concurrency);
  const done = new Set(steps.map(step => step.concurrency));
  const held = steps.filter(step => holds(step, p99LimitMs));
  if (held.length === 0) {
    const below = grid.filter(c => c < steps[0].concurrency).at(-1);
    if (below !== undefined) return { next: below };
  } else {
    const above = grid.find(c => c > held.at(-1).concurrency);
    if (
      above !== undefined &&
      !done.has(above) &&
      above < steps.at(-1).concurrency
    )
      return { next: above };
  }
  const peak = steps.reduce((a, b) => (b.throughput > a.throughput ? b : a));
  if (peak !== steps.at(-1) || peak.concurrency < grid.at(-1)) return {};
  return sweepNext(steps, cpus, sweep);
}

/**
 * A coarse-then-fine sweep from its short steps (`located`, sweepOf's) and its full-length ones (`swept`): the
 * full-length steps and their peak, the short steps beside them, and the sweep's top step, the highest of either. A
 * peak there, or one the load generator bound, is a lower bound.
 */
export function fineOf(located, swept) {
  const top = Math.max(
    ...[...located.steps, ...swept.steps].map(step => step.concurrency),
  );
  const peak = { ...swept.peak, top: swept.peak.concurrency >= top };
  return {
    steps: swept.steps,
    located: located.steps,
    top,
    peak: {
      ...peak,
      lowerBound:
        peak.top || generatorBound(peak.generatorCores, peak.generatorCpus),
    },
  };
}

/**
 * A sweep's steps and its peak from the load generator's saturation.json. A peak at the highest step the sweep ran
 * (`top`) or one the load generator bound is a lower bound.
 */
export function sweepOf(saturation) {
  const steps = (saturation.steps ?? []).map(step => ({
    concurrency: step.concurrency,
    // Short of the step's length when a failing step ended early (the load generator's --fail-after).
    seconds: step.seconds,
    throughput: step.throughput,
    ok: step.ok,
    errors: Object.values(step.errors ?? {}).reduce((a, b) => a + b, 0),
    sentOk: step.sentOk,
    sentFailed: step.sentFailed,
    p50: step.p50Ms,
    p99: step.p99Ms,
    generatorCores: step.generatorCores,
  }));
  const top = Math.max(...steps.map(step => step.concurrency));
  const peak = {
    throughput: saturation.saturationThroughput,
    concurrency: saturation.saturationConcurrency,
    top: saturation.saturationConcurrency >= top,
    generatorCores: saturation.generatorCores,
    generatorCpus: saturation.availableProcessors,
  };
  return {
    steps,
    peak: {
      ...peak,
      lowerBound:
        peak.top || generatorBound(peak.generatorCores, peak.generatorCpus),
    },
  };
}

/**
 * The ratio of a sessions suite's first arm's closed-loop peaks in sweep `label` (`E1`, `E1 zipf`) to its second's (`key` `sustainable`:
 * their sustainable rates): from the lowest first over the highest second to the highest over the lowest. A ratio
 * from a lower-bound peak of the first arm is a lower bound too (`≥`), from one of the second an upper bound (`≤`),
 * and from both neither (`~`).
 */
export function peakRatio(suite, label, key = 'peak') {
  const peaksOf = id =>
    (suite.sweeps ?? [])
      .filter(
        run =>
          run.arm === id &&
          (run.label ?? run.scenario) === label &&
          run.status === 'ok' &&
          run[key],
      )
      .map(run => run[key]);
  const [first, second] = suite.arms.map(arm => peaksOf(arm.id));
  if (first.length === 0 || second.length === 0) return undefined;
  const values = peaks => peaks.map(peak => peak.throughput);
  const lower = first.some(peak => peak.lowerBound);
  const upper = second.some(peak => peak.lowerBound);
  return {
    lo: Math.min(...values(first)) / Math.max(...values(second)),
    hi: Math.max(...values(first)) / Math.min(...values(second)),
    bound: lower && upper ? '~' : lower ? '≥' : upper ? '≤' : '',
  };
}

/**
 * The A/A spread of a sessions suite's sweep `label` (`key`: `peak` or `sustainable`): each arm's sessions against
 * each other (the most over the least, less 1), the larger of the two arms'. A job runs on one runner, and the next on
 * another, so the spread holds within its job alone. Undefined unless each arm has two sessions or more.
 */
export function aaSpread(suite, label, key = 'peak') {
  const spreads = suite.arms.map(arm => {
    const values = (suite.sweeps ?? [])
      .filter(
        run =>
          run.arm === arm.id &&
          (run.label ?? run.scenario) === label &&
          run.status === 'ok' &&
          run[key],
      )
      .map(run => run[key].throughput);
    return values.length >= 2 ? spread(values) : undefined;
  });
  return spreads.every(Number.isFinite) ? Math.max(...spreads) : undefined;
}

/**
 * A paired suite's closed-loop samples (`saturations`), the second arm's against the first's: each arm's throughputs in
 * order, the ratio of their medians, and from the lowest second over the highest first to the highest over the lowest.
 * The A/A spread is the larger of the two arms' (spread); a range within it is level (ratioVerdict).
 */
export function saturationSamples(suite) {
  if (!suite.saturations || suite.arms.length !== 2) return undefined;
  const [a, b] = suite.arms.map(arm => arm.id);
  const values = id =>
    (suite.saturations[id] ?? [])
      .filter(sample => sample.status === 'ok')
      .map(sample => sample.throughput);
  const [first, second] = [values(a), values(b)];
  if (first.length === 0 || second.length === 0) return undefined;
  const noise =
    first.length >= 2 && second.length >= 2
      ? Math.max(spread(first), spread(second))
      : undefined;
  const range = {
    lo: Math.min(...second) / Math.max(...first),
    hi: Math.max(...second) / Math.min(...first),
    bound: '',
  };
  return {
    ids: [a, b],
    first,
    second,
    median: median(second) / median(first),
    range,
    noise,
    verdict: ratioVerdict(range, noise, [b, a]),
  };
}

/**
 * What a ratio of two arms' peaks (peakRatio) says beyond the A/A spread `noise`: one arm higher, level, or
 * undetermined. A lower-bound ratio (`≥`) can show the first arm higher only, an upper bound (`≤`) the second.
 */
export function ratioVerdict(ratio, noise, ids) {
  if (!ratio) return '-';
  if (!Number.isFinite(noise)) return 'no A/A (one session an arm)';
  const first = ratio.lo > 1 + noise;
  const second = ratio.hi < 1 - noise;
  if (ratio.bound === '~') return 'undetermined (both lower bounds)';
  if (ratio.bound === '≥')
    return first ? `${ids[0]} higher` : 'undetermined (a lower bound)';
  if (ratio.bound === '≤')
    return second ? `${ids[1]} higher` : 'undetermined (an upper bound)';
  if (first) return `${ids[0]} higher`;
  if (second) return `${ids[1]} higher`;
  return 'level (within the A/A spread)';
}

/**
 * Whether a session's store holds what its runs asked of it, from the events it stored once the nodes were quiet
 * (bench.sh inspect): every seeded account opened; between the deposits (E1 and the prime) that reached their
 * completion point and those sent, as many stored, since one that timed out may still commit, and the same for the
 * transfers (E3); every transfer's amount locked; and every transfer through, entered or refused, an entered one
 * confirmed and a refused one unlocked. `up`'s check sent one of each, and `prime` is its prime.json. A count the
 * runs do not give (a failed run, a load generator without them) leaves its check unknown. `comparable` is false when a
 * check failed: that arm's numbers do not compare.
 */
export function correctnessOf({
  accounts,
  events = {},
  prime,
  runs = [],
  sweeps = [],
}) {
  const sentOf = scenario => {
    const sends = [
      ...runs
        .filter(run => run.scenario === scenario)
        .map(run => [run.metrics?.sentOk, run.metrics?.sentFailed]),
      ...sweeps
        .filter(run => run.scenario === scenario)
        .flatMap(run =>
          run.status === 'ok'
            ? [...run.steps, ...(run.located ?? [])].map(step => [
                step.sentOk,
                step.sentFailed,
              ])
            : [[undefined, undefined]],
        ),
    ];
    if (!sends.every(pair => pair.every(Number.isFinite))) return undefined;
    // `up`'s check: one of each, and it passed.
    return sends.reduce(
      (sum, [ok, failed]) => ({ ok: sum.ok + ok, failed: sum.failed + failed }),
      { ok: 1, failed: 0 },
    );
  };
  const stored = name => events[name] ?? 0;
  const deposits = sentOf('E1');
  const primed =
    deposits && Number.isFinite(prime?.failed)
      ? {
          ok: deposits.ok + accounts - prime.failed,
          failed: deposits.failed + prime.failed,
        }
      : undefined;
  const transfers = sentOf('E3');
  const within = (sent, name) => ({
    expected: sent ? `${sent.ok}–${sent.ok + sent.failed}` : 'unknown',
    stored: stored(name),
    ok: sent
      ? stored(name) >= sent.ok && stored(name) <= sent.ok + sent.failed
      : undefined,
  });
  const equal = (expected, name) => ({
    expected: `${expected}`,
    stored: stored(name),
    ok: stored(name) === expected,
  });
  const checks = [
    { name: 'accounts opened', ...equal(accounts, 'AccountOpened') },
    { name: 'deposits stored', ...within(primed, 'Deposited') },
    { name: 'transfers stored', ...within(transfers, 'Prepared') },
    {
      name: 'transfer amounts locked',
      ...equal(stored('Prepared'), 'AmountLocked'),
    },
    {
      name: 'transfers entered or refused',
      expected: `${stored('Prepared')}`,
      stored: stored('AmountEntered') + stored('EntryFailed'),
      ok:
        stored('AmountEntered') + stored('EntryFailed') === stored('Prepared'),
    },
    {
      name: 'entries confirmed',
      ...equal(stored('AmountEntered'), 'Confirmed'),
    },
    {
      name: 'refusals unlocked',
      ...equal(stored('EntryFailed'), 'AmountUnlocked'),
    },
  ];
  return { checks, comparable: !checks.some(check => check.ok === false) };
}

/**
 * What each server of the Axon comparison does for a command, side by side (the report's feature parity): Wow's
 * benchmark server (`test/benchmarks/server`, `--wow.transport=none`) and the Axon server (`test/benchmarks/axon-server`),
 * as their code configures them. What a run measures of it, such as PostgreSQL's transactions per command, is in the
 * tables.
 */
export const AXON_PARITY = [
  [
    'Event store',
    'Its PostgreSQL log: a row per command, its events in one jsonb array, 64 partitions',
    'The JDBC event storage engine: a row per event (domainevententry), the payload JSON in bytea',
  ],
  [
    'Unit of work',
    "A command's events committed with one append; an aggregate's commands queued behind it committed with it (up to 32), and appends of several partitions merged into one statement",
    "A unit of work and a Spring transaction per command, an INSERT per event; the transfer handler's command joins the processor's unit of work",
  ],
  [
    'Idempotency',
    'Each command stored with its request id under a unique key (tenant, request id), a new id per request here',
    'None: a command sent again is handled again',
  ],
  [
    'Aggregate cache',
    'The state cache: strong references, a tenth of the heap in estimated bytes (about 105,000 accounts at 1 GB)',
    'BoundedCache: strong references, 131,072 aggregates, the first put out first',
  ],
  [
    'Snapshots',
    "The built-in snapshot subscription keeps every account's latest state, on its partition's owner, after the commit",
    'Every 100 events of an account, on two snapshotter threads, after the commit',
  ],
  [
    'Concurrency of commands',
    "Coroutines: one lane per aggregate in arrival order, a bound on the commands waiting in a lane and on the node's commands in flight (429 past them)",
    "The command bus handles a command on its sender's thread, one of 200 platform threads (Tomcat's default), an aggregate locked while it is handled; past them commands queue, no bound",
  ],
  [
    'Transfer (E3)',
    "TransferSaga, the built-in subscription of the account's state events, on the partition owners, from the log's tail",
    "An event handler in a tracking processor of 16 segments and threads, sized by its reactions' PostgreSQL round trips, not by the CPUs (axon-pg-segments compares 2), its tokens in PostgreSQL; a reaction that fails is retried (PropagatingErrorHandler)",
  ],
  [
    'Answer',
    "The wait of the request (`Wow-Wait`): E1 `committed`, E3 the target's entry committed; 504 after 10 s",
    "The command's result: E1 once its unit of work committed, E3 once the target's entry committed with the processor's unit of work; 504 after 10 s",
  ],
  [
    'Serialization',
    'Jackson, events and snapshots as JSON (jsonb)',
    'Jackson for every message and snapshot (JSON in bytea)',
  ],
  [
    'Connections',
    "HikariCP at Spring Boot's default, 10",
    "HikariCP at Spring Boot's default, 10",
  ],
  [
    'Metrics and tracing',
    "None: wow-observability is not on the server's classpath (D174)",
    "None: neither axon-micrometer nor Axon's tracing modules are on its classpath, so its monitors and spans are no-ops",
  ],
  ['Bus', 'None (wow.transport=none)', 'None (no Axon Server)'],
  [
    'HTTP',
    'Spring Boot 4.1.2, WebFlux on Reactor Netty',
    'Spring Boot 3.5.16, WebFlux on Reactor Netty (Axon Framework 4 runs on Spring Boot 3): other Spring, Reactor and Netty versions',
  ],
];

/**
 * What a sessions suite's same-rate runs were set against: each rate beside the lowest sustainable rate a sweep of its
 * scenario found in the suite, the slower arm's, and whether the rate is above it. A rate is fixed beforehand, from an
 * earlier run (the suite's `rates`), so this tells whether this runner held it as that run did.
 */
export function sameRateBasis(suite) {
  return (suite.fixedRates ?? []).map(({ scenario, rate }) => {
    const held = (suite.sweeps ?? [])
      .filter(
        run =>
          run.status === 'ok' &&
          (run.label ?? run.scenario) === scenario &&
          run.sustainable,
      )
      .map(run => ({ arm: run.arm, throughput: run.sustainable.throughput }));
    if (held.length === 0) return { scenario, rate };
    const lowest = held.reduce((a, b) => (b.throughput < a.throughput ? b : a));
    return {
      scenario,
      rate,
      lowest,
      share: rate / lowest.throughput,
      above: rate > lowest.throughput,
    };
  });
}

/** The load generator's cores and Kafka's together past which a sessions suite's report flags them (2 CPUs). */
export const GENERATOR_WITH_KAFKA = 1.6;

/**
 * A sessions suite's load generator at its sweeps' peaks with Kafka on the same CPUs: the generator's most cores at
 * a peak, Kafka's most in an open-loop run (no sweep measures it), and their sum, or no Kafka when no open-loop run ran.
 * Without a bus (`bus` false: no arm uses Kafka, usesBus) the generator alone.
 */
export function generatorWithKafka(suite) {
  const peaks = (suite.sweeps ?? []).filter(run => run.status === 'ok');
  if (peaks.length === 0) return undefined;
  const generator = Math.max(...peaks.map(run => run.peak.generatorCores ?? 0));
  if (!suite.arms.some(usesBus)) {
    return {
      generator,
      cpus: peaks[0].peak.generatorCpus,
      bus: false,
      total: generator,
      over: generator > GENERATOR_WITH_KAFKA,
    };
  }
  const runs = (suite.runs ?? []).filter(run => run.status === 'ok');
  const kafka =
    runs.length > 0
      ? Math.max(...runs.map(run => (run.metrics.containers?.kafka ?? 0) / 100))
      : undefined;
  const total = generator + (kafka ?? 0);
  return {
    generator,
    cpus: peaks[0].peak.generatorCpus,
    kafka,
    total: kafka === undefined ? undefined : total,
    over: kafka !== undefined && total > GENERATOR_WITH_KAFKA,
  };
}

/** The arm of two saturations (id → `{throughput}`) to probe again: the lower, when they differ past the outlier. */
export function saturationOutlier(saturations, ids) {
  const [a, b] = ids.map(id => saturations[id]?.throughput);
  if (!(a > 0) || !(b > 0)) return undefined;
  if (Math.max(a, b) / Math.min(a, b) <= SATURATION.outlier) return undefined;
  return a < b ? ids[0] : ids[1];
}

/** What a row shows instead of a ratio that measured the load generator rather than the servers. */
export const GENERATOR_BOUND = 'invalid: generator-bound';

/**
 * Whether a load generator that used `cores` of its `cpus` was the bottleneck: past ANOMALY.generatorBusy of them,
 * what was measured is the generator.
 */
export function generatorBound(cores, cpus) {
  return (
    cpus > 0 && Number.isFinite(cores) && cores >= ANOMALY.generatorBusy * cpus
  );
}

/** Whether an arm's closed-loop saturation step was generator-bound. */
export function saturationGeneratorBound(suite, arm) {
  const s = suite.saturation?.[arm];
  return generatorBound(s?.generatorCores, s?.generatorCpus);
}

/** Whether either arm's saturation was generator-bound: the saturation ratio is then not the servers'. */
export function saturationRatioBound(suite) {
  return (suite.arms ?? []).some(arm =>
    saturationGeneratorBound(suite, arm.id),
  );
}

/**
 * Whether a run of either arm at the calibrated rates was generator-bound: its rates came from saturations or probes
 * the generator capped, so the calibrated pairs compare the generator, not the arms.
 */
export function calibratedGeneratorBound(suite) {
  return (suite.runs ?? []).some(
    run =>
      run.status === 'ok' &&
      (run.mode ?? 'calibrated') === 'calibrated' &&
      generatorBound(
        run.metrics?.loadGeneratorCores,
        run.metrics?.loadGeneratorCpus,
      ),
  );
}

const saturationText = s =>
  s?.throughput
    ? `${s.capped ? '≥ ' : ''}${num(s.throughput)}/s${generatorBound(s.generatorCores, s.generatorCpus) ? ` (${GENERATOR_BOUND}, ${num(s.generatorCores, 2)} of ${s.generatorCpus} cores)` : ''}${againText(s)}`
    : `**${s?.status ?? 'missing'}**`;

/** A saturation probed again: what the first search found, or that the second failed. */
const againText = s => {
  if (s.again === 'failed') return ' (probed again: failed)';
  if (!s.first) return '';
  return ` (probed again; first ${num(s.first.throughput)}/s at concurrency ${s.first.concurrency})`;
};

const integer = (name, value, min, max) => {
  const text = String(value ?? '').trim();
  const number = Number(text);
  if (!/^\d+$/.test(text) || number < min || number > max) {
    throw new Error(
      `${name} must be an integer from ${min} to ${max}: ${value}`,
    );
  }
  return number;
};

// The run's settings when a push to `bench-run/**` starts it: the defaults, over which
// test/benchmarks/bench-run.json at the pushed commit may set any of these keys.
export const DEFAULTS = {
  tier: 'L2',
  suites: '',
  dataset: '',
  aa: 'false',
  profile: 'false',
  warm_up: '30',
  measure: '40',
  repeats: '2',
  fraction: '0.8',
  p99_limit_ms: '200',
  base_ref: '',
  // Server properties (a JSON object) for the last arm of every paired suite: the head, or in an A/A run its
  // second arm, so `"aa": true` with properties puts the head's defaults against the changed head, paired.
  properties: '',
};

/** The settings of a run: the dispatch inputs, or for a push the defaults with the run file's keys over them. */
export function runSettings({ event, inputs = {}, config }) {
  if (event !== 'push') {
    const given = Object.entries(inputs).filter(
      ([, value]) => value !== undefined && value !== '',
    );
    return { ...DEFAULTS, ...Object.fromEntries(given) };
  }
  const unknown = Object.keys(config ?? {}).filter(key => !(key in DEFAULTS));
  if (unknown.length > 0) {
    throw new Error(
      `Unknown key(s) in bench-run.json: ${unknown.join(', ')}; known: ${Object.keys(DEFAULTS).join(', ')}`,
    );
  }
  const settings = { ...DEFAULTS };
  for (const [key, value] of Object.entries(config ?? {})) {
    settings[key] = Array.isArray(value)
      ? value.join(',')
      : value !== null && typeof value === 'object'
        ? JSON.stringify(value)
        : String(value);
  }
  return settings;
}

/**
 * The settings, checked. `suites` is `all` (every suite), names separated by commas or spaces, or the group names
 * `ab`, `head`, `component`, `axon` and `axon-diag` among them.
 */
export function resolveInputs({
  tier = DEFAULTS.tier,
  suites,
  dataset,
  aa = DEFAULTS.aa,
  profile = DEFAULTS.profile,
  warmUp,
  measure,
  repeats,
  fraction,
  p99LimitMs = DEFAULTS.p99_limit_ms,
  properties,
}) {
  const groups = {
    all: [
      'ab',
      'head',
      'component',
      'axon',
      'axon-diag',
      'jdk',
      'jdk-saturation',
      'access',
      'access-dataset',
    ],
    ab: ['ab'],
    head: ['head'],
    component: ['component'],
    axon: ['axon'],
    'axon-diag': ['axon-diag'],
    jdk: ['jdk'],
    'jdk-saturation': ['jdk-saturation'],
    access: ['access'],
    'access-dataset': ['access-dataset'],
  };
  if (!suites?.trim() && !TIERS[tier]) {
    throw new Error(
      `Unknown tier: ${tier}; known: ${Object.keys(TIERS).join(', ')}`,
    );
  }
  const names = [
    ...new Set(
      (suites?.trim() ? suites : TIERS[tier].join(','))
        .split(/[\s,]+/)
        .filter(Boolean)
        .flatMap(name =>
          groups[name] ? groups[name].flatMap(suitesOf) : [name],
        ),
    ),
  ];
  const unknown = names.filter(name => !(name in SUITES));
  if (unknown.length > 0 || names.length === 0) {
    throw new Error(
      `Unknown suite(s): ${unknown.join(', ') || '(none)'}; known: ${Object.keys(SUITES).join(', ')}`,
    );
  }
  const share = Number(fraction);
  if (
    !/^\d*\.?\d+$/.test(String(fraction).trim()) ||
    !(share > 0 && share <= 1)
  ) {
    throw new Error(`fraction must be in (0, 1]: ${fraction}`);
  }
  return {
    suites: names,
    warmUp: integer('warm_up', warmUp, 0, 600),
    measure: integer('measure', measure, 5, 600),
    repeats: integer('repeats', repeats, 1, 5),
    fraction: share,
    p99LimitMs: integer('p99_limit_ms', p99LimitMs, 1, 60000),
    tier: suites?.trim() ? 'custom' : tier,
    datasets: datasetsOf(dataset, suites?.trim() ? undefined : tier),
    aa: String(aa) === 'true',
    profile: String(profile) === 'true',
    ...(propertiesOf(properties)
      ? { properties: propertiesOf(properties) }
      : {}),
  };
}

/** The `properties` setting: a JSON object of server properties, or none. */
export function propertiesOf(text) {
  if (!String(text ?? '').trim()) return undefined;
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error(`properties must be a JSON object: ${text}`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`properties must be a JSON object: ${text}`);
  }
  return Object.keys(value).length > 0 ? value : undefined;
}

/** The data sets: `dataset` (names separated by commas or spaces), else the tier's, else 100k. */
function datasetsOf(dataset, tier) {
  const names = dataset?.trim()
    ? [...new Set(dataset.split(/[\s,]+/).filter(Boolean))]
    : (TIER_DATASETS[tier] ?? ['100k']);
  const unknown = names.filter(name => !(name in DATASETS));
  if (unknown.length > 0) {
    throw new Error(
      `Unknown data set(s): ${unknown.join(', ')}; known: ${Object.keys(DATASETS).join(', ')}`,
    );
  }
  return names;
}

/** A suite's name with its data set, as the report shows it: `e1-mongo-uniform@100k`. */
export const labelOf = suite => `${suite.name}@${suite.dataset ?? '100k'}`;
/** The key of a suite's A/A thresholds in noise.json: its own label, or its `noiseFrom` suite's. */
export const noiseLabel = suite =>
  labelOf({ ...suite, name: SUITES[suite.name]?.noiseFrom ?? suite.name });

/** A job of the matrix: a suite at a data set, `e1-mongo-uniform.100k`, and one part of it (partsOf), `axon-pg.100k.e1`. */
export const jobId = (suite, dataset, part) =>
  `${suite}.${dataset}${part ? `.${part}` : ''}`;
export function parseJob(id) {
  const [suite, dataset = '100k', part] = id.split('.');
  return part ? { suite, dataset, part } : { suite, dataset };
}

/**
 * Whether a sessions suite runs the baseline's build beside the head's: an arm of it. A baseline suite (group `ab`)
 * has its own rule, its A/A runs needing none.
 */
export const usesBase = name =>
  SUITES[name]?.kind === 'sessions' &&
  SUITES[name].arms.some(arm => arm.install === 'base');

/**
 * The jobs of a run: the suites that can run (those of group `ab`, and those with an arm of the baseline's build
 * (usesBase), need a baseline commit), the ones skipped and why, and the builds they need, one per side.
 */
export function planMatrix({
  suites,
  datasets = ['100k'],
  aa = false,
  headSha,
  baseSha,
}) {
  const skipped = [];
  const runnable = suites.filter(name => {
    const group = SUITES[name].group;
    // An A/A run measures the head against itself: it needs no baseline.
    if (group === 'ab' && !baseSha && !aa) {
      skipped.push(
        `${name} (no V10 baseline: set base_ref; no v10.* release tag has the benchmark yet)`,
      );
      return false;
    }
    if (usesBase(name) && !baseSha) {
      skipped.push(`${name} (an arm runs the baseline's build: set base_ref)`);
      return false;
    }
    return true;
  });
  const groups = new Set(runnable.map(name => SUITES[name].group));
  const builds = [
    { side: 'head', sha: headSha },
    // The baseline build serves the baseline suites, and the component suites when a baseline is given.
    ...((groups.has('ab') && !aa) ||
    (groups.has('component') && baseSha && !aa) ||
    runnable.some(usesBase)
      ? [{ side: 'base', sha: baseSha }]
      : []),
  ];
  // A sessions suite of several sweeps runs a job a sweep (partsOf).
  const jobs = runnable.flatMap(name =>
    datasets.flatMap(dataset =>
      (partsOf(SUITES[name]) ?? [undefined]).map(part =>
        jobId(name, dataset, part?.key),
      ),
    ),
  );
  return { suites: runnable, jobs, skipped, builds };
}

/** The spread of values: max / min - 1, 0 for fewer than two. */
export function spread(values) {
  const present = values.filter(value => Number.isFinite(value) && value > 0);
  if (present.length < 2) return 0;
  return Math.max(...present) / Math.min(...present) - 1;
}

/** The numbers of one run's result.json that the summaries use. */
export function metrics(result) {
  const results = result?.results ?? {};
  const latency = results.latencyMs ?? {};
  const resources = result?.resources ?? {};
  const serverCores = Object.values(resources.processes ?? {}).reduce(
    (sum, process) => sum + (process.averageCores ?? 0),
    0,
  );
  const containers = Object.fromEntries(
    Object.entries(resources.containers ?? {}).map(([name, container]) => [
      name.replace(/^wow-e2e-bench-/, ''),
      container.cpuPercentAverage ?? 0,
    ]),
  );
  const containerCores =
    Object.values(containers).reduce((sum, percent) => sum + percent, 0) / 100;
  const throughput = results.throughput ?? 0;
  const rate = result?.load?.rate;
  const perSecond = results.perSecond ?? [];
  const seconds = perSecond.map(second => second.ok ?? 0);
  const p99s = perSecond.map(second => second.p99Ms);
  const loadGenerator = resources.loadGenerator;
  // Who worked hardest: the servers, a container, or the load generator (in cores).
  const consumers = {
    server: serverCores,
    ...Object.fromEntries(
      Object.entries(resources.containers ?? {}).map(([name, container]) => [
        name.replace(/^wow-e2e-bench-/, ''),
        (container.cpuPercentAverage ?? 0) / 100,
      ]),
    ),
    ...(loadGenerator ? { 'load generator': loadGenerator.averageCores } : {}),
  };
  const bottleneck = Object.entries(consumers).sort((a, b) => b[1] - a[1])[0];
  // The servers' heap allocation per operation, when every server JVM's counter was read at start and stop.
  const allocated = Object.values(resources.processes ?? {}).map(
    process => process.allocatedBytes,
  );
  const operations = (results.ok ?? 0) + (results.failed ?? 0);
  const allocatedBytesPerOp =
    allocated.length > 0 && allocated.every(Number.isFinite) && operations > 0
      ? allocated.reduce((a, b) => a + b, 0) / operations
      : undefined;
  // PostgreSQL's transactions committed and WAL bytes written over the measured phase, per operation.
  const commits = resources.stores?.postgres?.delta?.xact_commit;
  const walBytes = resources.stores?.postgres?.delta?.wal_bytes;
  // The servers' collection time over the measured phase (jstat's GCT), as a share of it, and their largest RSS.
  const processes = Object.values(resources.processes ?? {});
  const gcSeconds = processes.map(process => process.gc?.delta?.GCT);
  return {
    rate,
    throughput,
    // The per-second series the bootstrap resamples, and the halves the stability check compares.
    okSeries: seconds,
    p50Series: perSecond.map(second => second.p50Ms),
    p99Series: p99s,
    stability: halves(
      seconds,
      p99s,
      perSecond.map(second => second.p50Ms),
    ),
    loadGeneratorCores: loadGenerator?.averageCores,
    loadGeneratorCpus: loadGenerator?.availableProcessors,
    bottleneck: bottleneck
      ? { side: bottleneck[0], cores: bottleneck[1] }
      : undefined,
    byScenario: results.byScenario
      ? Object.fromEntries(
          Object.entries(results.byScenario).map(([label, scenario]) => [
            label,
            {
              throughput: scenario.throughput,
              failed: scenario.failed,
              p50: scenario.latencyMs?.p50,
              p99: scenario.latencyMs?.p99,
            },
          ]),
        )
      : undefined,
    // The measured seconds that completed less than half the rate: a dip (a stall, a hand-over).
    weakSeconds: Number.isFinite(rate)
      ? seconds.filter(ok => ok < rate / 2).length
      : 0,
    minSecondOk: seconds.length > 0 ? Math.min(...seconds) : undefined,
    ok: results.ok ?? 0,
    failed: results.failed ?? 0,
    errors: results.errors ?? {},
    busy: busyOf(results.errors),
    // The bound on the operations outstanding, and what set it: a run counting `generator-overload` hit it.
    inFlightCap: result?.load?.inFlightCap,
    inFlightBoundBy: result?.load?.inFlightBoundBy,
    p50: latency.p50,
    p95: latency.p95,
    p99: latency.p99,
    // p99.9 only with at least P999_TAIL samples beyond it, else undefined (shown n/a).
    p999: (results.ok ?? 0) * 0.001 >= P999_TAIL ? latency.p999 : undefined,
    primed: result?.meta?.primed === 'true',
    primeSeconds: Number(result?.meta?.primeSeconds) || undefined,
    settleSeconds: Number(result?.meta?.settleSeconds) || undefined,
    worstSecondP99: results.worstSecondP99Ms,
    serverCores,
    containers,
    containerCores,
    cpuMsPerOp:
      throughput > 0
        ? ((serverCores + containerCores) * 1000) / throughput
        : undefined,
    allocatedBytesPerOp,
    commitsPerOp:
      Number.isFinite(commits) && operations > 0
        ? commits / operations
        : undefined,
    // Every operation the run sent, its warm-up's too: those that reached their completion point and those that failed.
    sentOk: results.sent?.ok,
    sentFailed: results.sent?.failed,
    walBytesPerOp:
      Number.isFinite(walBytes) && operations > 0
        ? walBytes / operations
        : undefined,
    gcShare:
      processes.length > 0 &&
      gcSeconds.every(Number.isFinite) &&
      resources.seconds > 0
        ? gcSeconds.reduce((a, b) => a + b, 0) /
          (resources.seconds * processes.length)
        : undefined,
    rssMaxMb:
      processes.length > 0
        ? Math.max(...processes.map(process => process.rssMaxMb ?? 0)) ||
          undefined
        : undefined,
  };
}

/**
 * The measured phase's first half against its second: the change in mean throughput, and the ratios of the halves'
 * median per-second p50 and p99. p99 is compared only when each half has at least P99_TAIL samples beyond it (a
 * hundred thousand operations a half): with fewer, half a run's tail is noise, and a stable system would trip it.
 */
export function halves(okSeries, p99Series, p50Series = []) {
  const half = Math.floor(okSeries.length / 2);
  if (half < 2) return undefined;
  const sum = values => values.reduce((a, b) => a + b, 0);
  const first = sum(okSeries.slice(0, half)) / half;
  const second = sum(okSeries.slice(half)) / (okSeries.length - half);
  const ratioOf = series => {
    const a = median(series.slice(0, half));
    const b = median(series.slice(half));
    return a > 0 ? b / a : undefined;
  };
  const tailed =
    Math.min(sum(okSeries.slice(0, half)), sum(okSeries.slice(half))) * 0.01 >=
    P99_TAIL;
  return {
    throughputChange: first > 0 ? second / first - 1 : undefined,
    p50Ratio:
      p50Series.length === okSeries.length ? ratioOf(p50Series) : undefined,
    p99Ratio: tailed ? ratioOf(p99Series) : undefined,
  };
}

/** The tail samples a p99 comparison of two halves needs: a thousand beyond it in each. */
export const P99_TAIL = 1000;

/** The tail samples p99.9 needs to be reported: a thousand beyond it, so a run of a million operations. */
export const P999_TAIL = 1000;

/** The metrics compared between the two arms of a pair, and whether higher is better. */
export const RATIO_METRICS = [
  ['throughput', 'Throughput', true],
  ['p50', 'p50', false],
  ['p95', 'p95', false],
  ['p99', 'p99', false],
  ['p999', 'p999', false],
  ['worstSecondP99', 'Worst-second p99', false],
  ['cpuMsPerOp', 'CPU ms/op', false],
  ['allocatedBytesPerOp', 'Alloc/op', false],
];

/** b / a of every ratio metric. */
export function ratios(a, b) {
  return Object.fromEntries(
    RATIO_METRICS.map(([key]) => [
      key,
      Number.isFinite(a?.[key]) && Number.isFinite(b?.[key]) && a[key] > 0
        ? b[key] / a[key]
        : undefined,
    ]),
  );
}

/** Whether another round is due: two runs of one arm differ by more than DRIFT in throughput or p99. */
// The rounds of a paired suite: `calibrated`, each arm at its own calibrated rate (throughput and cost compare;
// latencies are at unequal loads), then `equal`, both arms at the lower of the two calibrated rates (latencies
// compare).
export const CALIBRATED = 'calibrated';
export const EQUAL = 'equal';
const modeOf = run => run.mode ?? CALIBRATED;

/** The lower of the arms' calibrated rates: the rate of the equal-rate rounds. */
export function equalRate(rates, arms) {
  const values = arms.map(arm => rates[arm.id]).filter(Number.isFinite);
  return values.length === arms.length ? Math.min(...values) : undefined;
}

export function drifted(runs, arms) {
  return arms.some(arm => {
    const own = runs.filter(
      run =>
        run.arm === arm.id && run.status === 'ok' && modeOf(run) === CALIBRATED,
    );
    return (
      spread(own.map(run => run.metrics.throughput)) > DRIFT ||
      spread(own.map(run => run.metrics.p99)) > DRIFT
    );
  });
}

/** The arms of round `round` (0-based) in their order: ABBA. */
export function roundOrder(arms, round) {
  return round % 2 === 0 ? arms : [...arms].reverse();
}

/** The pairs of a paired suite: per round, the two arms' runs and their ratios. */
export function pairs(suite, mode = CALIBRATED) {
  // A component suite has a head and a baseline too, but no rounds, and a sessions suite runs several scenarios a
  // round: each compares in a table of its own.
  if (
    ['component', 'sessions', 'measure'].includes(suite.kind) ||
    suite.arms.length < 2
  )
    return [];
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(run => modeOf(run) === mode);
  const rounds = [...new Set(runs.map(run => run.round))].sort((x, y) => x - y);
  return rounds.flatMap(round => {
    const first = runs.find(run => run.round === round && run.arm === a);
    const second = runs.find(run => run.round === round && run.arm === b);
    if (first?.status !== 'ok' || second?.status !== 'ok') return [];
    return [{ round, ratios: ratios(first.metrics, second.metrics) }];
  });
}

const median = values => {
  const sorted = values.filter(Number.isFinite).sort((x, y) => x - y);
  if (sorted.length === 0) return undefined;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};

// Significance (D224 note ②): a ratio counts only beyond the suite's noise. An A/A run (`aa`) puts the head against
// itself; the largest deviation of its pair ratios from 1 is the suite's threshold per metric (at least NOISE_FLOOR),
// which the report writes as noise.json, to commit as test/benchmarks/noise.json for later runs to read. The
// confidence interval of a ratio comes from a bootstrap over the measured seconds of both arms' runs in that mode:
// the mean per-second throughput, the median per-second p50 and p99.
export const NOISE_FLOOR = 0.02;
export const AA_ROUNDS = 4;
export const BOOTSTRAP = { resamples: 1000, seed: 20261020 };
export const SIGNIFICANCE_METRICS = [
  ['throughput', 'okSeries', 'mean'],
  ['p50', 'p50Series', 'median'],
  ['p99', 'p99Series', 'median'],
];

/** A small seeded generator (mulberry32), so a report's intervals are the same every time it is rendered. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The 95% bootstrap interval of statistic(b) / statistic(a) over the samples, with its point value. */
export function bootstrapRatio(a, b, statistic) {
  const xs = a.filter(Number.isFinite);
  const ys = b.filter(Number.isFinite);
  if (xs.length < 2 || ys.length < 2) return undefined;
  const of = values =>
    statistic === 'mean'
      ? values.reduce((sum, v) => sum + v, 0) / values.length
      : median(values);
  const next = random(BOOTSTRAP.seed);
  const draw = values =>
    Array.from(
      { length: values.length },
      () => values[Math.floor(next() * values.length)],
    );
  const ratios = [];
  for (let i = 0; i < BOOTSTRAP.resamples; i++) {
    const x = of(draw(xs));
    if (x > 0) ratios.push(of(draw(ys)) / x);
  }
  ratios.sort((p, q) => p - q);
  const at = q =>
    ratios[Math.min(ratios.length - 1, Math.floor(q * ratios.length))];
  const point = of(xs) > 0 ? of(ys) / of(xs) : undefined;
  return { point, lo: at(0.025), hi: at(0.975) };
}

/** The bootstrap intervals of a paired suite's ratios in one mode, per significance metric. */
export function confidence(suite, mode) {
  if (suite.arms?.length !== 2) return {};
  const [a, b] = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(
    run => modeOf(run) === mode && run.status === 'ok',
  );
  const pool = (id, key) =>
    runs.filter(run => run.arm === id).flatMap(run => run.metrics[key] ?? []);
  // A latency's point estimate is the median of the rounds' own percentiles (each from the run's whole histogram);
  // its interval comes from the bootstrap over the measured seconds.
  const perRound = (id, metric) =>
    median(runs.filter(run => run.arm === id).map(run => run.metrics[metric]));
  return Object.fromEntries(
    SIGNIFICANCE_METRICS.map(([metric, key, statistic]) => {
      const interval = bootstrapRatio(pool(a, key), pool(b, key), statistic);
      if (!interval || metric === 'throughput') return [metric, interval];
      const pa = perRound(a, metric);
      const pb = perRound(b, metric);
      return [
        metric,
        { ...interval, point: pa > 0 ? pb / pa : interval.point },
      ];
    }),
  );
}

/**
 * An A/A suite's noise per significance metric: the half-width of the 95% bootstrap interval of its equal-rate
 * ratio (the head against itself at one rate, AA_ROUNDS rounds), at least NOISE_FLOOR. Null when the suite measured
 * no equal-rate pair: a failed suite has no noise, and the floor would make every later ratio significant.
 */
export function noiseOf(suite) {
  const intervals = confidence(suite, EQUAL);
  if (!SIGNIFICANCE_METRICS.some(([metric]) => intervals[metric])) return null;
  return Object.fromEntries(
    SIGNIFICANCE_METRICS.map(([metric]) => {
      const interval = intervals[metric];
      const half = interval ? (interval.hi - interval.lo) / 2 : NaN;
      return [metric, Math.max(NOISE_FLOOR, Number.isFinite(half) ? half : 0)];
    }),
  );
}

/** Whether a ratio's interval clears the noise threshold: `higher`, `lower`, `within noise` or `uncalibrated`. */
export function verdict(interval, threshold) {
  if (!interval) return 'no data';
  if (!Number.isFinite(threshold)) return 'uncalibrated';
  if (interval.lo > 1 + threshold) return 'higher';
  if (interval.hi < 1 - threshold) return 'lower';
  return 'within noise';
}

/** min, median and max of finite values, or undefined. */
export function range(values) {
  const finite = values.filter(Number.isFinite);
  if (finite.length === 0) return undefined;
  return {
    min: Math.min(...finite),
    median: median(finite),
    max: Math.max(...finite),
    n: finite.length,
  };
}

const ms = value => (Number.isFinite(value) ? `${value.toFixed(1)} ms` : '-');
/** Bytes in KB from 1 KB up. */
const bytes = value =>
  Number.isFinite(value)
    ? value >= 1024
      ? `${(value / 1024).toFixed(1)} KB`
      : `${value.toFixed(0)} B`
    : '-';
const num = (value, digits = 1) =>
  Number.isFinite(value) ? value.toFixed(digits) : '-';
const ratio = value => (Number.isFinite(value) ? value.toFixed(3) : '-');
const ratioRange = r =>
  r ? `${ratio(r.median)} (${ratio(r.min)}–${ratio(r.max)}, n=${r.n})` : '-';
const propsText = props =>
  props && Object.keys(props).length > 0
    ? Object.entries(props)
        .map(([key, value]) => `${key}=${value}`)
        .join(', ')
    : 'defaults';

/** The errors of a run other than the bulkhead's refusals, which a column of their own shows. */
const otherErrors = errors =>
  Object.fromEntries(
    Object.entries(errors ?? {}).filter(([kind]) => !kind.endsWith(`-${BUSY}`)),
  );

const errorsText = errors =>
  Object.entries(errors ?? {})
    .sort((a, b) => b[1] - a[1])
    .map(([kind, count]) => `${kind} ${count}`)
    .join(' · ') || '-';

/** An arm's open-loop rate as a share of its saturation, flagged when no probe sustained it. */
const shareText = (suite, id) => {
  const c = suite.calibration?.[id];
  if (!c) return '-';
  return `${(c.share * 100).toFixed(0)}%${c.sustainable ? '' : ' (**not sustained**)'}`;
};

const signalsText = signals =>
  signals
    ? Object.entries(signals)
        .filter(([kind]) => kind !== 't')
        .map(
          ([kind, counts]) =>
            `${kind} ${Object.entries(counts)
              .map(([key, count]) => `${key}=${count}`)
              .join(',')}`,
        )
        .join(' · ') || 'none'
    : '-';

/** The steps of a cliff suite: completions against the rate, errors and the node's signals. */
function cliffTable(suite) {
  const lines = [
    '| Step | Rate | Throughput | Kept up | Failed | p50 | p99 | Worst-second p99 | Errors | Node signals (measured phase) |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    const m = run.metrics;
    lines.push(
      run.status === 'ok'
        ? `| ${run.name} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${run.sustained ? 'yes' : '**no**'} | ${m.failed} | ${ms(m.p50)} | ${ms(m.p99)} | ${ms(m.worstSecondP99)} | ${errorsText(m.errors)} | ${signalsText(run.signals)} |`
        : `| ${run.name} | ${num(run.rate)}/s | **${run.status}** | | | | | | | |`,
    );
  }
  return lines;
}

/** The anomalies of suites as a table, or nothing. */
function anomalyTable(rows) {
  if (rows.length === 0) return [];
  return [
    '| Suite | Run | Context | Anomaly | Detail |',
    '| --- | --- | --- | --- | --- |',
    ...rows.map(
      row =>
        `| ${row.suite} | ${row.run} | ${row.context} | **${row.kind}** | ${row.detail} |`,
    ),
  ];
}

/** A component suite's counts per scenario and arm, and the head's against the baseline's. */
function componentTable(suite) {
  const lines = [
    '| Suite | Scenario | Arm | Store round trips/op | By command | Allocated/op | Throughput (indicative) | p50 | p99 |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    const c = run.component;
    lines.push(
      run.status === 'ok'
        ? `| ${labelOf(suite)} | ${run.scenario} | ${run.arm} | ${num(c.storeRoundTripsPerOp, 2)} | ${Object.entries(
            c.storeRoundTripsByCommand ?? {},
          )
            .map(([command, count]) => `${command} ${num(count, 2)}`)
            .join(
              ' · ',
            )} | ${num((c.allocatedBytesPerOp ?? 0) / 1024, 0)} KiB | ${num(c.throughput, 0)}/s | ${ms(c.latencyMs?.p50)} | ${ms(c.latencyMs?.p99)} |`
        : `| ${labelOf(suite)} | ${run.scenario} | ${run.arm} | **${run.status}** | | | | | |`,
    );
  }
  return lines;
}

/** The sustainable rate of each scenario of a capacity suite. */
function capacityTable(suite) {
  const lines = [
    `| Scenario | Sustainable rate (≤1% errors, p99 ≤ ${suite.p99LimitMs} ms) | p50 | p99 | CPU ms/op | Saturation (closed loop) | Bracket |`,
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const [scenario, c] of Object.entries(suite.capacity ?? {})) {
    lines.push(
      `| ${scenario} | ${c.rate ? `**${num(c.rate)}/s**` : '**none held**'} | ${ms(c.p50)} | ${ms(c.p99)} | ${num(c.cpuMsPerOp, 2)} | ${saturationText(suite.saturation?.[scenario])} | ${num(c.rate ?? 0)}–${num(c.upper)}/s |`,
    );
  }
  return lines;
}

/** The runs of a cluster suite: steady against hand-over. */
function clusterTable(suite) {
  const lines = [
    '| Run | Rate | Throughput | Failed | p50 | p99 | Worst-second p99 | Seconds below half the rate | Lowest second | Errors | Node signals (all nodes, measured phase) |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    const m = run.metrics;
    lines.push(
      run.status === 'ok'
        ? `| ${run.name} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.failed} | ${ms(m.p50)} | ${ms(m.p99)} | ${ms(m.worstSecondP99)} | ${m.weakSeconds} | ${m.minSecondOk ?? '-'}/s | ${errorsText(m.errors)} | ${signalsText(run.signals)} |`
        : `| ${run.name} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | ${signalsText(run.signals)} |`,
    );
  }
  return lines;
}

function runTable(runs) {
  const lines = [
    `| Run | Arm | Rate | Throughput | OK | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | Server CPU | Container CPU | Load generator | Busiest | CPU ms/op | Alloc/op | ${BUSY} (bulkhead) | Query bound | Errors |`,
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of runs) {
    if (run.status !== 'ok') {
      lines.push(
        `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | | | | | | | |`,
      );
      continue;
    }
    const m = run.metrics;
    const containers = Object.entries(m.containers)
      .map(([name, percent]) => `${name} ${percent.toFixed(0)}%`)
      .join(' · ');
    lines.push(
      `| ${run.name} | ${run.arm} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.ok} | ${m.failed} | ` +
        `${ms(m.p50)} | ${ms(m.p95)} | ${ms(m.p99)} | ${Number.isFinite(m.p999) ? ms(m.p999) : 'n/a'} | ${ms(m.worstSecondP99)} | ` +
        // A hand-over run's server CPU is not measured: the sampler's ps fails once the stopped node is gone.
        `${run.handover ? '-' : `${num(m.serverCores, 2)} cores`} | ${containers || '-'} | ` +
        `${Number.isFinite(m.loadGeneratorCores) ? `${num(m.loadGeneratorCores, 2)} of ${m.loadGeneratorCpus}` : '-'} | ` +
        `${m.bottleneck && !run.handover ? `${m.bottleneck.side} ${num(m.bottleneck.cores, 2)}` : '-'} | ` +
        `${run.handover ? '-' : num(m.cpuMsPerOp, 2)} | ${bytes(m.allocatedBytesPerOp)} | ${m.busy || '-'} | ` +
        `${queryLimitsText(run.queryLimits)} | ${errorsText(otherErrors(m.errors))} |`,
    );
  }
  return lines;
}

/** A sweep's peak, `≥` when it is a lower bound, and why it is one. */
const peakText = peak =>
  `${peak.lowerBound ? '≥ ' : ''}${num(peak.throughput, 0)}/s at ${peak.concurrency}` +
  (peak.top ? ' (top step)' : '') +
  (generatorBound(peak.generatorCores, peak.generatorCpus)
    ? ` (generator-bound, ${num(peak.generatorCores, 2)} of ${peak.generatorCpus} cores)`
    : '');

/** A sweep's sustainable rate: its step's throughput, clients and p99, `≥` at the sweep's top step. */
const sustainableText = s =>
  s
    ? `${s.lowerBound ? '≥ ' : ''}${num(s.throughput, 0)}/s at ${s.concurrency} (p99 ${ms(s.p99)})`
    : '**none within the limits**';

const ratioText = r =>
  r ? `${r.bound ? `${r.bound} ` : ''}${num(r.lo, 2)}–${num(r.hi, 2)}` : '-';

// Why a sweep stopped where it did (sweepNext).
const SWEEP_ENDS = {
  peaked: 'peaked below its top step',
  levelled: 'levelled off',
  errors: 'more than 1% failed',
  generator: 'the load generator bound it',
  limit: 'the concurrency limit',
  failed: 'a step past the cap failed',
};

/** Milliseconds of CPU per operation from the average cores a run used at its throughput. */
const perOp = (cores, throughput) =>
  throughput > 0 && Number.isFinite(cores)
    ? (cores * 1000) / throughput
    : undefined;

// What a sessions suite compares in open loop, from each run's metrics and the run.
const SESSION_METRICS = [
  ['Rate', (m, run) => `${num(run.rate, 0)}/s`],
  ['Throughput', m => `${num(m.throughput)}/s`],
  ['Kept up', (m, run) => (sustainable(m, run.rate) ? 'yes' : '**no**')],
  ['Failed', m => `${m.failed}`],
  ['p50', m => `${num(m.p50, 2)} ms`],
  ['p99', m => ms(m.p99)],
  ['Node CPU ms/op', m => num(perOp(m.serverCores, m.throughput), 3)],
  [
    'PostgreSQL CPU ms/op',
    m => num(perOp(m.containers?.postgres / 100, m.throughput), 3),
  ],
  ['PostgreSQL transactions/op', m => num(m.commitsPerOp, 2)],
  ['PostgreSQL WAL bytes/op', m => bytes(m.walBytesPerOp)],
  ['Node alloc/op', m => bytes(m.allocatedBytesPerOp)],
  [
    'Node GC time',
    m => (Number.isFinite(m.gcShare) ? `${num(m.gcShare * 100)}%` : '-'),
  ],
  [
    'Node RSS max',
    m => (Number.isFinite(m.rssMaxMb) ? `${num(m.rssMaxMb, 0)} MB` : '-'),
  ],
];

/** A sessions suite's open-loop runs of one view, per scenario and metric, the arms side by side in session order. */
function viewTable(suite, view) {
  const ids = suite.arms.map(arm => arm.id);
  const runs = suite.runs.filter(run => run.view === view);
  if (runs.length === 0) return ['None ran.'];
  const lines = [
    `| Scenario | Metric | ${ids.join(' | ')} |`,
    `| --- | --- | ${ids.map(() => '---').join(' | ')} |`,
  ];
  for (const scenario of [...new Set(runs.map(run => run.scenario))]) {
    const values = (id, format) =>
      runs
        .filter(run => run.arm === id && run.scenario === scenario)
        .map(run =>
          run.status === 'ok' ? format(run.metrics, run) : `**${run.status}**`,
        )
        .join(', ') || '-';
    for (const [label, format] of SESSION_METRICS) {
      lines.push(
        `| ${scenario} | ${label} | ${ids.map(id => values(id, format)).join(' | ')} |`,
      );
    }
  }
  return lines;
}

/**
 * A sessions suite's views: the peaks and sustainable rates of its sweeps, the first arm's over the second's with
 * what they say beyond the A/A spread, then its open-loop runs at one rate for both and at each arm's own share.
 */
function sessionTables(suite) {
  const ids = suite.arms.map(arm => arm.id);
  const labels = (suite.sweepPlan ?? []).map(plan => plan.label);
  const header = [
    `| Sweep | ${ids.join(' | ')} | ${ids[0]}/${ids[1]} | A/A spread | Verdict |`,
    `| --- | ${ids.map(() => '---').join(' | ')} | --- | --- | --- |`,
  ];
  const row = (label, key, text) => {
    const cells = ids.map(
      id =>
        (suite.sweeps ?? [])
          .filter(
            run => run.arm === id && (run.label ?? run.scenario) === label,
          )
          .map(run =>
            run.status === 'ok' ? text(run[key]) : `**${run.status}**`,
          )
          .join('; ') || '-',
    );
    const ratio = peakRatio(suite, label, key);
    const noise = aaSpread(suite, label, key);
    const verdict =
      incomparable.length > 0
        ? `not comparable (${incomparable.join(', ')}: the store check)`
        : ratioVerdict(ratio, noise, ids);
    return `| ${label} | ${cells.join(' | ')} | ${ratioText(ratio)} | ${Number.isFinite(noise) ? `${num(noise * 100)}%` : '-'} | ${verdict} |`;
  };
  const incomparable = incomparableArms(suite);
  const generator = generatorWithKafka(suite);
  const lines = [
    `**Peak**: how much each handles at most. Each sweep's closed-loop peak and the clients it was at, in session order; ${ids[0]}/${ids[1]} from the lowest ${ids[0]} peak over the highest ${ids[1]} peak to the highest over the lowest. \`≥\` marks a lower bound: a peak at the sweep's top step or at the load generator's limit, and a ratio from such a peak. The A/A spread is the larger of the two arms' spreads between their own sessions on this runner; a ratio within it is level.`,
    '',
    ...header,
    ...labels.map(label => row(label, 'peak', peakText)),
    '',
  ];
  if (generator) {
    lines.push(
      generator.bus === false
        ? `The load generator used at most ${num(generator.generator, 2)} of its ${generator.cpus} CPUs at a peak${generator.over ? `, **more than ${GENERATOR_WITH_KAFKA}**` : ''}; no Kafka ran, as neither server uses one.`
        : generator.kafka === undefined
          ? `The load generator used at most ${num(generator.generator, 2)} of its ${generator.cpus} CPUs at a peak; Kafka, on the same CPUs, was not measured (no open-loop run in this suite).`
          : `The load generator used at most ${num(generator.generator, 2)} of its ${generator.cpus} CPUs at a peak, and Kafka, idle on the same CPUs, at most ${num(generator.kafka, 2)} cores in an open-loop run: ${num(generator.total, 2)} cores together${generator.over ? `, **more than ${GENERATOR_WITH_KAFKA}**` : ''}.`,
      '',
    );
  }
  lines.push(
    `Sustainable rates, apart from the peaks: the most a sweep's step did with p99 within ${suite.p99LimitMs} ms and no failure (\`≥\` at the sweep's top step).`,
    '',
    ...header,
    ...labels.map(label => row(label, 'sustainable', sustainableText)),
  );
  if ((suite.fixedRates ?? []).length > 0) {
    lines.push(
      '',
      `**Same rate**: for the same work, whose latency and cost are lower. Both arms at one rate, before the sweeps (${suite.warmUp} s warm-up, ${suite.measure} s measured), in session order; kept up: at most 1% failed and at least 97% of the rate completed.`,
      '',
      ...sameRateBasis(suite).map(basis =>
        basis.lowest
          ? `- ${basis.scenario} at ${num(basis.rate, 0)}/s: ${num(basis.share * 100, 0)}% of the lowest sustainable rate a sweep found here (${basis.lowest.arm}, ${num(basis.lowest.throughput, 0)}/s)${basis.above ? ', **above it**' : ''}.`
          : `- ${basis.scenario} at ${num(basis.rate, 0)}/s: no sweep here found a sustainable rate to set it against.`,
      ),
      '',
      ...viewTable(suite, SAME),
    );
  }
  if (suite.own) {
    lines.push(
      '',
      `**Own share**: with the same headroom, each one's latency and cost per command. Each arm at ${num((suite.share ?? 0) * 100, 0)}% of the sustainable rate its own sweep found in the session, after the sweeps, in session order.`,
      '',
      ...viewTable(suite, OWN),
    );
  }
  lines.push(
    '',
    `**Correctness**: what each session's store held once its runs were done and the nodes quiet, against what the runs asked of it (stored, then expected: a range between the operations that reached their completion point and those sent, as one that timed out may still commit).${incomparable.length > 0 ? ` **Not comparable: ${incomparable.join(', ')}.**` : ''}`,
    '',
    ...correctnessTable(suite),
    '',
    "**Storage**: the store's tables after each session, their indexes and TOAST included.",
    '',
    ...storageTable(suite),
    '',
    "**Startup**: each node from its launch to its first answer (the harness probes five times a second), its RSS then, and after the session's runs.",
    '',
    ...startupTable(suite),
  );
  return lines;
}

/** The arms of a sessions suite with a session whose store did not hold what its runs asked of it. */
export function incomparableArms(suite) {
  return [
    ...new Set(
      (suite.sessions ?? [])
        .filter(session => session.correctness?.comparable === false)
        .map(session => session.arm),
    ),
  ];
}

const megabytes = value =>
  Number.isFinite(value) ? `${(value / 1024 / 1024).toFixed(1)} MB` : '-';

/** Each session's store checks (correctnessOf): stored, and what the runs make expected. */
function correctnessTable(suite) {
  const sessions = (suite.sessions ?? []).filter(s => s.status === 'ok');
  const names =
    sessions.find(s => s.correctness)?.correctness.checks.map(c => c.name) ??
    [];
  if (names.length === 0) return ['No session was inspected.'];
  return [
    `| Session | ${names.join(' | ')} |`,
    `| --- | ${names.map(() => '---').join(' | ')} |`,
    ...sessions.map(session => {
      if (!session.correctness) {
        return `| ${session.name} | ${names.map(() => '**not inspected**').join(' | ')} |`;
      }
      const cells = session.correctness.checks.map(check => {
        const text = `${check.stored} (${check.expected})`;
        return check.ok === false ? `**${text}**` : text;
      });
      return `| ${session.name} | ${cells.join(' | ')} |`;
    }),
  ];
}

/** Each session's store: its event and snapshot tables, per event and per snapshot, and the rest. */
function storageTable(suite) {
  const sessions = (suite.sessions ?? []).filter(s => s.store?.tables);
  if (sessions.length === 0) return ['No session was inspected.'];
  const sum = (tables, kind) =>
    tables
      .filter(table => table.kind === kind)
      .reduce((total, table) => total + table.totalBytes, 0);
  return [
    '| Session | Event rows | Events | Event table | Bytes per event | Snapshots | Snapshot table | Bytes per snapshot | Other tables |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ...sessions.map(session => {
      const { tables, records, snapshots } = session.store;
      const events = Object.values(session.store.events ?? {}).reduce(
        (a, b) => a + b,
        0,
      );
      const eventBytes = sum(tables, 'events');
      const snapshotBytes = sum(tables, 'snapshots');
      return `| ${session.name} | ${records} | ${events} | ${megabytes(eventBytes)} | ${events > 0 ? num(eventBytes / events, 0) : '-'} | ${snapshots} | ${megabytes(snapshotBytes)} | ${snapshots > 0 ? num(snapshotBytes / snapshots, 0) : '-'} | ${megabytes(sum(tables, 'other'))} |`;
    }),
  ];
}

/** Each session's node: how long it took to answer, and its RSS then and after the session's runs. */
function startupTable(suite) {
  const sessions = (suite.sessions ?? []).filter(s => s.status === 'ok');
  if (sessions.length === 0) return ['No session started.'];
  return [
    '| Session | Ready after | RSS at ready | RSS after the runs |',
    '| --- | --- | --- | --- |',
    ...sessions.map(session => {
      const node = session.startup?.[0];
      const after = session.rssKb?.[0];
      return `| ${session.name} | ${Number.isFinite(node?.readyMs) ? `${num(node.readyMs / 1000, 1)} s` : '-'} | ${megabytes(node?.rssKb * 1024)} | ${megabytes(after * 1024)} |`;
    }),
  ];
}

/**
 * A sessions suite's sweeps step by step: each step's throughput and p99, or its errors when it failed some (and how
 * long it measured when it ended early). `located`: the short steps that located them instead (coarse then fine).
 */
function sweepTable(suite, located = false) {
  const sweeps = suite.sweeps ?? [];
  const stepsOf = run => (located ? run.located : run.steps) ?? [];
  const concurrencies = [
    ...new Set(
      sweeps.flatMap(run => stepsOf(run).map(step => step.concurrency)),
    ),
  ].sort((a, b) => a - b);
  if (concurrencies.length === 0) return [];
  const head = located ? '' : ' Peak | Stopped |';
  return [
    `| Sweep | Arm |${head} ${concurrencies.map(c => `${c} clients`).join(' | ')} |`,
    `| --- | --- |${located ? '' : ' --- | --- |'} ${concurrencies.map(() => '---').join(' | ')} |`,
    ...sweeps.map(run => {
      if (run.status !== 'ok') {
        return `| ${run.name} | ${run.arm} |${located ? '' : ` **${run.status}** | |`} ${concurrencies.map(() => '').join(' | ')} |`;
      }
      const cells = concurrencies.map(concurrency => {
        const step = stepsOf(run).find(s => s.concurrency === concurrency);
        if (!step) return '-';
        const early =
          !located && step.seconds < suite.sweep?.step
            ? ` in ${step.seconds} s`
            : '';
        return `${num(step.throughput, 0)}/s, ${step.errors > 0 ? `${step.errors} errors${early}` : `p99 ${ms(step.p99)}`}`;
      });
      return `| ${run.name} | ${run.arm} |${located ? '' : ` ${peakText(run.peak)} | ${SWEEP_ENDS[run.end] ?? '-'} |`} ${cells.join(' | ')} |`;
    }),
  ];
}

/** The closed-loop samples of the suites that took them (saturationSamples), a row a suite. */
function samplesTable(suites) {
  return [
    '| Suite | b/a | a, in order | b, in order | Median ratio | Range | A/A spread | Verdict |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ...suites.flatMap(suite => {
      const samples = saturationSamples(suite);
      if (!samples) return [];
      const [a, b] = samples.ids;
      const list = values => values.map(value => `${num(value)}/s`).join(', ');
      return [
        `| ${labelOf(suite)} | ${b}/${a} | ${list(samples.first)} | ${list(samples.second)} | ${ratio(samples.median)} | ` +
          `${ratio(samples.range.lo)}–${ratio(samples.range.hi)} | ${Number.isFinite(samples.noise) ? `${num(samples.noise * 100)}%` : '-'} | ${samples.verdict} |`,
      ];
    }),
  ];
}

function saturationTable(suite) {
  if (!suite.saturation || Object.keys(suite.saturation).length === 0)
    return [];
  const lines = [
    '| Arm | Saturation (closed loop) | Concurrency | Open-loop rate | Share of saturation |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const arm of suite.arms) {
    const s = suite.saturation[arm.id] ?? {};
    lines.push(
      `| ${arm.id} | ${saturationText(s)} | ${s.concurrency ?? '-'} | ${num(suite.rates?.[arm.id])}/s | ${shareText(suite, arm.id)} |`,
    );
  }
  return lines;
}

function pairTable(suite, mode) {
  const rows = pairs(suite, mode);
  if (rows.length === 0) return [];
  const [a, b] = suite.arms.map(arm => arm.id);
  return [
    `| Round | ${RATIO_METRICS.map(([, label]) => `${label} ${b}/${a}`).join(' | ')} |`,
    `| --- | ${RATIO_METRICS.map(() => '---').join(' | ')} |`,
    ...rows.map(
      row =>
        `| ${row.round + 1} | ${RATIO_METRICS.map(([key]) => ratio(row.ratios[key])).join(' | ')} |`,
    ),
  ];
}

/** What a suite's runs started from: primed or not, and whether its accounts fit the node's state cache. */
function primingLines(suite) {
  if (!suite.accounts || suite.kind === 'component') return [];
  const runs = [...(suite.runs ?? []), ...(suite.probes ?? [])];
  const seconds = range(runs.map(run => run.metrics?.primeSeconds));
  const settled = range(runs.map(run => run.metrics?.settleSeconds));
  const primedText = seconds
    ? ` (${num(seconds.min, 0)}–${num(seconds.max, 0)} s)`
    : '';
  const settledText = settled
    ? ` (${num(settled.min, 0)}–${num(settled.max, 0)} s)`
    : '';
  const lines = [
    suite.primed && suite.kind === 'sessions'
      ? `Primed: each session touched each of the ${suite.accounts} accounts once after seeding, untimed${primedText}; every run on it first waited until the nodes were quiet${settledText}.`
      : suite.primed
        ? `Primed: every run touched each of the ${suite.accounts} accounts once before its warm-up, untimed${primedText}, then waited until the nodes were quiet again${settledText}.`
        : suite.cold
          ? `Cold: not primed on purpose; the caches start empty and the keys are uniform over ${suite.accounts} accounts.`
          : `Not primed: ${suite.accounts} accounts, below ${PRIME_FROM}.`,
  ];
  if (suite.storeWriteIops) {
    lines.push(
      `The store's disk writes capped at ${suite.storeWriteIops} a second (\`bench.sh --store-write-iops\`): a diagnostic of a slower disk.`,
    );
  }
  if (suite.profiled) {
    lines.push(
      "Profiled: every session's servers ran with a flight recording (`up --jfr`), so the runs carry its cost; each run's recording and PostgreSQL's statements are beside its result.",
    );
  }
  if (suite.accounts > STATE_CACHE) {
    lines.push(
      `The accounts exceed the node's state cache (a tenth of the 1 GB heap, about ${STATE_CACHE} states of few requests): the steady state is a full cache with evictions.`,
    );
  }
  return [...lines, ''];
}

/**
 * A `measure` run's results from bench.sh's --out [dir] (measure.json and the files it names): per query, each timed
 * answer's milliseconds and HTTP status, the answer's size (rows, the count, groups), and ClickHouse's query log of
 * the timed queries; undefined when measure.json is missing.
 */
export function readMeasure(dir) {
  const manifest = readJsonIfThere(join(dir, 'measure.json'));
  if (!manifest) return undefined;
  const shapes = {};
  for (const shape of manifest.shapes) {
    const lines = existsSync(join(dir, `${shape}.times`))
      ? readFileSync(join(dir, `${shape}.times`), 'utf8')
          .split('\n')
          .filter(Boolean)
      : [];
    const timed = lines.map(line => {
      const [code, seconds] = line.trim().split(/\s+/);
      return { code: Number(code), ms: Number(seconds) * 1000 };
    });
    const ok = timed.filter(it => it.code === 200).map(it => it.ms);
    const answer = readJsonIfThere(join(dir, `${shape}.json`));
    const log = existsSync(join(dir, `${shape}.log.jsonl`))
      ? readFileSync(join(dir, `${shape}.log.jsonl`), 'utf8')
          .split('\n')
          .filter(Boolean)
          .map(line => JSON.parse(line))
      : [];
    const median = values => quantile(values, 0.5);
    shapes[shape] = {
      timed: timed.length,
      failed: timed.length - ok.length,
      p50: quantile(ok, 0.5),
      p95: quantile(ok, 0.95),
      p99: quantile(ok, 0.99),
      answer: Array.isArray(answer)
        ? answer.length
        : (answer?.count ?? answer?.list?.length ?? null),
      clickhouse: {
        queries: log.length,
        durationMs: median(log.map(it => Number(it.query_duration_ms))),
        readRows: median(log.map(it => Number(it.read_rows))),
        readBytes: median(log.map(it => Number(it.read_bytes))),
        memory: median(log.map(it => Number(it.memory_usage))),
      },
    };
  }
  return { runs: manifest.runs, warmUpRuns: manifest.warmUpRuns, shapes };
}

/** The [q] quantile of [values] (nearest rank), null for none. */
export function quantile(values, q) {
  const finite = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (finite.length === 0) return null;
  return finite[
    Math.min(finite.length - 1, Math.max(0, Math.ceil(q * finite.length) - 1))
  ];
}

/**
 * A `measure` suite's tables: per query, each arm's p50, p95 and p99 (the median of its runs'), its A/A band (the
 * spread of its runs' p50s), the second arm's p50 against the first's, and ClickHouse's work for one query.
 */
export function measureTables(suite) {
  const [a, b] = suite.arms.map(arm => arm.id);
  const ok = suite.runs.filter(run => run.status === 'ok');
  const shapes = [
    ...new Set(ok.flatMap(run => Object.keys(run.measure.shapes))),
  ];
  const of = (arm, shape, key) =>
    ok
      .filter(run => run.arm === arm)
      .map(run => run.measure.shapes[shape]?.[key])
      .filter(Number.isFinite);
  const med = values => quantile(values, 0.5);
  const lines = [
    `Each query sent ${suite.warmUpRuns ?? '?'} times untimed, then ${suite.measureRuns ?? '?'} times timed (curl's total time), a run of each arm a round, ABBA.`,
    '',
    `| Query | Arm | p50 | p95 | p99 | A/A band | Failed | Answer |`,
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const shape of shapes) {
    for (const arm of [a, b]) {
      const p50s = of(arm, shape, 'p50');
      const answer = ok.find(run => run.arm === arm)?.measure.shapes[shape]
        ?.answer;
      lines.push(
        `| ${shape} | ${arm} | ${ms(med(p50s))} | ${ms(med(of(arm, shape, 'p95')))} | ${ms(med(of(arm, shape, 'p99')))} | ${p50s.length > 1 ? `${num(spread(p50s) * 100)}%` : '-'} | ${of(arm, shape, 'failed').reduce((x, y) => x + y, 0)} | ${answer ?? '-'} |`,
      );
    }
  }
  lines.push(
    '',
    `| Query | ${b} p50 / ${a} p50 | Within the A/A band | ClickHouse ${a}: ms, rows, bytes, memory | ClickHouse ${b}: ms, rows, bytes, memory |`,
    '| --- | --- | --- | --- | --- |',
  );
  for (const shape of shapes) {
    const first = of(a, shape, 'p50');
    const second = of(b, shape, 'p50');
    const ratio = med(second) / med(first);
    const band = Math.max(spread(first), spread(second));
    const clickhouse = arm => {
      const work = ok
        .filter(run => run.arm === arm)
        .map(run => run.measure.shapes[shape]?.clickhouse)
        .filter(Boolean);
      const m = key => med(work.map(it => it[key]).filter(Number.isFinite));
      return `${num(m('durationMs'), 0)} ms, ${num(m('readRows'), 0)}, ${num(m('readBytes'), 0)}, ${num(m('memory'), 0)}`;
    };
    lines.push(
      `| ${shape} | ${Number.isFinite(ratio) ? `×${num(ratio, 2)}` : '-'} | ${Number.isFinite(ratio) ? (Math.abs(ratio - 1) <= band ? 'yes: no conclusion' : 'no') : '-'} | ${clickhouse(a)} | ${clickhouse(b)} |`,
    );
  }
  return lines;
}

/** The Markdown summary of one suite. */
export function renderSuite(suite) {
  const definition = SUITES[suite.name];
  if (suite.kind === 'measure') {
    const lines = [
      `## ${labelOf(suite)}`,
      '',
      definition?.answers ?? '',
      '',
      `Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`,
      '',
      ...suite.arms.map(
        arm =>
          `- \`${arm.id}\`: ${arm.props ? propsText(arm.props) : 'as built'}`,
      ),
      '',
      ...measureTables(suite),
      '',
    ];
    const found = anomalyTable(anomalies(suite));
    if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
    if (suite.failures > 0)
      lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
    return `${lines.join('\n')}\n`;
  }
  const lines = [
    `## ${labelOf(suite)}${suite.aa ? ' (A/A: head against head)' : ''}`,
    '',
    definition?.answers ?? '',
    '',
    `${suite.scenario ?? suite.scenarios?.join(' and ') ?? 'scenarios below'}${definition?.mix ? ` (${definition.mix})` : ''}, ${suite.distribution} keys over ${suite.accounts ?? '?'} accounts; warm-up ${suite.warmUp} s, measured ${suite.measure} s` +
      (suite.kind === 'paired' && !suite.saturations
        ? `, ${suite.fraction * 100}% of saturation, ${suite.repeats} round(s)`
        : '') +
      (suite.saturations
        ? `; closed-loop searches alone, ${SUITES[suite.name]?.saturations ?? '?'} an arm in turn (ABBA), each from empty stores`
        : '') +
      (suite.kind === 'sessions'
        ? ` in open loop, sweeps of ${suite.sweep.step} s steps after ${suite.sweep.warmUp} s, the concurrency ×${suite.sweep.growth} a step to ${suite.sweep.maxConcurrency} and on while it rises, to ${suite.sweep.limit} at most` +
          (suite.sweep.locate
            ? `, located first with ${suite.sweep.locate.step} s steps after ${suite.sweep.locate.warmUp} s and measured at full length around the peak and the sustainable rate, a failing step ending after ${suite.sweep.failAfter} s`
            : '') +
          `; ${suite.repeats} round(s) of one session per arm, ABBA`
        : '') +
      (suite.extraRound
        ? ' + 1 (two runs of one arm differed by more than 5%)'
        : '') +
      (suite.parts
        ? `. Each sweep in fresh sessions on a runner of its own, the full ABBA: ${suite.parts.map(part => `${part.label} on ${part.runner?.cpu ?? '?'}, ${part.runner?.cores ?? '?'} cores`).join('; ')}.`
        : `. Runner: ${suite.runner?.cpu ?? '?'}, ${suite.runner?.cores ?? '?'} cores.`),
    '',
    ...primingLines(suite),
  ];
  const arms = suite.arms.filter(arm => arm.props || arm.jdk);
  if (arms.length > 0) {
    lines.push(
      ...arms.map(
        arm =>
          `- \`${arm.id}\`: ${[arm.jdk ? `the server on JDK ${arm.jdk}` : '', arm.props ? propsText(arm.props) : ''].filter(Boolean).join('; ')}`,
      ),
      '',
    );
  }
  const saturation = saturationTable(suite);
  if (saturation.length > 0) lines.push(...saturation, '');
  const samples = saturationSamples(suite);
  if (samples) lines.push(...samplesTable([suite]), '');
  if (suite.probes?.length > 0) {
    lines.push(
      'Open-loop calibration probes (not part of the results):',
      '',
      ...runTable(suite.probes),
      '',
    );
  }
  if (suite.kind === 'steps') lines.push(...cliffTable(suite), '');
  if (suite.kind === 'capacity') lines.push(...capacityTable(suite), '');
  if (suite.kind === 'component') lines.push(...componentTable(suite), '');
  for (const run of suite.profiles ?? []) {
    lines.push(`Profile of ${run.arm} (${run.name}, not a timed round):`, '');
    for (const [view, text] of Object.entries(run.profile ?? {})) {
      lines.push(`\`${view}\`:`, '', '```', text, '```', '');
    }
  }
  if (suite.kind === 'cluster') {
    lines.push(
      `Three nodes, requests round-robin, ${CLUSTER.measure} s measured; in the hand-over runs node 2 is stopped ${CLUSTER.stopAfter} s into the measured phase and restarted ${CLUSTER.down} s later.`,
      '',
      ...clusterTable(suite),
      '',
    );
  }
  if (suite.kind === 'sessions') {
    lines.push(
      `Sessions, in order: ${(suite.sessions ?? []).map(s => (s.status === 'ok' ? s.name : `${s.name} (**up ${s.status}**)`)).join(', ') || 'none'}.`,
      '',
      ...sessionTables(suite),
      '',
      "The sweeps' steps (throughput, and p99 or the errors):",
      '',
      ...sweepTable(suite),
      '',
    );
    const located = sweepTable(suite, true);
    if (located.length > 0) {
      lines.push(
        `The short steps that located them (${suite.sweep.locate.warmUp} s warm-up, ${suite.sweep.locate.step} s measured), not part of the results:`,
        '',
        ...located,
        '',
      );
    }
  }
  if (suite.kind !== 'component' && !suite.saturations)
    lines.push(...runTable(suite.runs), '');
  const mixed = suite.runs.filter(run => run.metrics?.byScenario);
  if (mixed.length > 0) {
    const labels = Object.keys(mixed[0].metrics.byScenario);
    lines.push(
      'Each scenario of the mix (the writes under the reads):',
      '',
      `| Run | Arm | ${labels.map(label => `${label} p50 | ${label} p99 | ${label} failed`).join(' | ')} |`,
      `| --- | --- | ${labels.map(() => '--- | --- | ---').join(' | ')} |`,
      ...mixed.map(
        run =>
          `| ${run.name} | ${run.arm} | ${labels
            .map(label => {
              const x = run.metrics.byScenario[label] ?? {};
              return `${ms(x.p50)} | ${ms(x.p99)} | ${x.failed ?? '-'}`;
            })
            .join(' | ')} |`,
      ),
      '',
    );
  }
  const calibratedPairs = pairTable(suite, CALIBRATED);
  if (calibratedPairs.length > 0 && calibratedGeneratorBound(suite)) {
    lines.push(
      `Pairs at the calibrated rates: **${GENERATOR_BOUND}**: a run of an arm used nearly all of the load generator's cores (see the runs), so its rate and its throughput were the generator's.`,
      '',
    );
  } else if (calibratedPairs.length > 0) {
    lines.push(
      'Pairs at the calibrated rates (throughput and CPU compare; the latencies are at unequal loads):',
      '',
      ...calibratedPairs,
      '',
    );
  }
  const equalPairs = pairTable(suite, EQUAL);
  if (equalPairs.length > 0) {
    lines.push(
      `Pairs at one rate for both arms${suite.equalRate ? `, ${num(suite.equalRate)}/s` : ''} (latencies compare; lower is better):`,
      '',
      ...equalPairs,
      '',
    );
  }
  const found = anomalyTable(anomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} step(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}

/**
 * The JDK at `home` as its release file names it, its vendor and full runtime version (`Eclipse Adoptium
 * 25.0.1+8-LTS`); undefined without one.
 */
export function javaRelease(home) {
  const path = home ? join(home, 'release') : undefined;
  if (!path || !existsSync(path)) return undefined;
  const release = readFileSync(path, 'utf8');
  const value = key => release.match(new RegExp(`^${key}="(.*)"$`, 'm'))?.[1];
  const version = value('JAVA_RUNTIME_VERSION') ?? value('JAVA_VERSION');
  return version
    ? [value('IMPLEMENTOR'), version].filter(Boolean).join(' ')
    : undefined;
}

/**
 * The home of the JDK of major version `major` (21, 25): setup-java's `JAVA_HOME_<major>_X64` (or `_ARM64`) on a
 * runner, else `JAVA_HOME_<major>`.
 */
export function javaHome(major, env = process.env) {
  const home = [
    `JAVA_HOME_${major}_X64`,
    `JAVA_HOME_${major}_ARM64`,
    `JAVA_HOME_${major}`,
  ]
    .map(name => env[name])
    .find(Boolean);
  if (!home) {
    throw new Error(
      `No JDK ${major}: set JAVA_HOME_${major} (bench.yml's measure job installs 21 and 25)`,
    );
  }
  return home;
}

function runnerInfo() {
  const read = path => (existsSync(path) ? readFileSync(path, 'utf8') : '');
  const cpuinfo = read('/proc/cpuinfo');
  const meminfo = read('/proc/meminfo');
  const cpu = cpuinfo.match(/^model name\s*:\s*(.+)$/m)?.[1]?.trim();
  const cores = (cpuinfo.match(/^processor\s*:/gm) ?? []).length || undefined;
  const memKb = Number(meminfo.match(/^MemTotal:\s*(\d+)/m)?.[1]);
  return {
    cpu: cpu ?? process.env.RUNNER_CPU ?? 'unknown',
    cores,
    memoryGb: memKb ? Math.round(memKb / 1024 / 1024) : undefined,
    runner: process.env.RUNNER_NAME,
    // The JDK of the load generator and, unless an arm names its own, the servers (D393).
    jdk: javaRelease(process.env.JAVA_HOME),
  };
}

// What the hand-over helper does to the host's processes, as shell functions: `node_pid <port>` prints the pid of the
// node serving the port, `node_argv <pid>` reads its command line into ARGV, `node_cwd <pid>` prints its working
// directory, `signal <TERM|0> <pid>` signals it (0: whether it is still there). These are the real ones (Linux,
// /proc); a test passes its own to runSuite (`processes`), and hostProcesses refuses to give these to one.
export const HOST_PROCESSES = `
node_pid() { pgrep -f -- "--server.port=$1" | head -1; }
node_argv() { mapfile -d '' ARGV < "/proc/$1/cmdline"; }
node_cwd() { readlink "/proc/$1/cwd"; }
signal() { kill "-$1" "$2"; }
`;

/**
 * The host's processes for the hand-over helper (HOST_PROCESSES), refused under Node's test runner, which sets
 * NODE_TEST_CONTEXT for every test process and its children: a test that ran the real ones on a shared machine
 * stopped a node another measurement was running on port 18081 (X-66).
 */
export function hostProcesses(env = process.env) {
  if (env.NODE_TEST_CONTEXT) {
    throw new Error(
      "A test must not find or signal the host's processes: give runSuite `processes` of its own (X-66).",
    );
  }
  return HOST_PROCESSES;
}

// A cluster run with a hand-over: bench.sh runs with its output teed to a log, and a helper waits for the load
// generator's measured phase, stops the node on NODE_PORT (SIGTERM: a graceful stop, its partitions hand over),
// and after DOWN seconds starts it again with its own command line, working directory and environment. The
// restarted node is stopped once bench.sh is done (bench.sh only knows the pids it started). Every dealing with
// the node goes through the process functions (HOST_PROCESSES) run before it; the script stops only its own helper
// itself.
export const HANDOVER_SCRIPT = `
set -uo pipefail
bench=$1; shift
mkdir -p "$RUN_DIR"
log="$RUN_DIR/bench-output.log"; : > "$log"
handover() {
  for _ in $(seq 1 900); do grep -q ': measure$' "$log" && break; sleep 1; done
  grep -q ': measure$' "$log" || return 0
  sleep "$STOP_AFTER"
  pid=$(node_pid "$NODE_PORT")
  if [ -z "$pid" ]; then echo "[handover] no node on port $NODE_PORT"; return 0; fi
  node_argv "$pid"
  cwd=$(node_cwd "$pid")
  echo "[handover] $(date +%s.%N) stopping the node on port $NODE_PORT (pid $pid)"
  signal TERM "$pid"
  for _ in $(seq 1 300); do signal 0 "$pid" 2>/dev/null || break; sleep 0.1; done
  echo "[handover] $(date +%s.%N) stopped; down for $DOWN s"
  sleep "$DOWN"
  (cd "$cwd" && exec "\${ARGV[@]}") >> "$RUN_DIR/node-restarted.log" 2>&1 &
  echo $! > "$RUN_DIR/node-restarted.pid"
  echo "[handover] $(date +%s.%N) restarted (pid $!)"
}
handover >> "$RUN_DIR/handover.log" 2>&1 &
helper=$!
"$bench" "$@" 2>&1 | tee -a "$log"
status=\${PIPESTATUS[0]}
kill "$helper" 2>/dev/null; wait "$helper" 2>/dev/null
if [ -f "$RUN_DIR/node-restarted.pid" ]; then
  restarted=$(cat "$RUN_DIR/node-restarted.pid")
  signal TERM "$restarted" 2>/dev/null
  for _ in $(seq 1 150); do signal 0 "$restarted" 2>/dev/null || break; sleep 0.2; done
fi
exit "$status"
`;

/**
 * Runs bench.sh with `args`, the arm's properties in SPRING_APPLICATION_JSON; true when it succeeded. With `handover`
 * (`{ dir, stopAfter, down, nodePort, processes }`) it runs through HANDOVER_SCRIPT, after the process functions
 * `processes`.
 */
function bench(benchPath, args, props, handover) {
  const env = { ...process.env };
  delete env.SPRING_APPLICATION_JSON;
  if (props && Object.keys(props).length > 0)
    env.SPRING_APPLICATION_JSON = JSON.stringify(props);
  console.log(
    `::group::bench.sh ${args.join(' ')}${props ? ` (${propsText(props)})` : ''}${handover ? ' (hand-over)' : ''}`,
  );
  const result = handover
    ? spawnSync(
        'bash',
        [
          '-c',
          `${handover.processes}\n${HANDOVER_SCRIPT}`,
          'handover',
          benchPath,
          ...args,
        ],
        {
          stdio: 'inherit',
          env: {
            ...env,
            RUN_DIR: handover.dir,
            STOP_AFTER: String(handover.stopAfter),
            DOWN: String(handover.down),
            NODE_PORT: String(handover.nodePort),
          },
        },
      )
    : spawnSync(benchPath, args, { stdio: 'inherit', env });
  console.log('::endgroup::');
  if (result.status !== 0) {
    console.log(
      `::warning::bench.sh ${args[0]} failed (${result.status ?? result.signal}): ${args.join(' ')}`,
    );
  }
  return result.status ?? -1;
}

const readJson = path => JSON.parse(readFileSync(path, 'utf8'));

/** A JSON file a step may not have written, or written partly (a store query that failed): its value, or undefined. */
function readJsonIfThere(path) {
  try {
    return existsSync(path) ? readJson(path) : undefined;
  } catch {
    return undefined;
  }
}

/** Where `installDist` puts the Axon server, under the repository. */
export const AXON_INSTALL =
  'test/benchmarks/axon-server/build/install/axon-server';

/**
 * The Axon server's distribution in `repo`, built unless it is there. bench.yml's build job makes Wow's server and the
 * load generator; the Axon server is a build of its own (Axon Framework 4 runs on Spring Boot 3), so a suite that runs
 * it builds it in its own job, before it measures anything, with the repository's Gradle wrapper, its unit tests
 * first (no other job builds it). The measure job checks out `test/benchmarks` alone, sparse and with its other
 * files fetched when wanted, so the wrapper's directory is checked out first. No Gradle or Kotlin daemon outlives the
 * build to take the runner's CPUs. `run` runs a command in `repo`.
 */
export function axonInstall(
  repo,
  run = command =>
    spawnSync(command[0], command.slice(1), {
      cwd: repo,
      stdio: 'inherit',
      // Built on JDK 21, as the build job builds Wow's server: the measure job's own JDK is 25, the runtime (D393).
      env: {
        ...process.env,
        JAVA_HOME: process.env.JAVA_HOME_21_X64 ?? process.env.JAVA_HOME,
      },
    }),
) {
  const install = join(repo, AXON_INSTALL);
  if (existsSync(join(install, 'bin', 'axon-server'))) return install;
  const commands = [
    ...(existsSync(join(repo, 'gradle', 'wrapper'))
      ? []
      : [['git', 'sparse-checkout', 'add', 'gradle']]),
    [
      './gradlew',
      '--no-daemon',
      '--console=plain',
      '-Pkotlin.compiler.execution.strategy=in-process',
      '-p',
      'test/benchmarks/axon-server',
      'test',
      'installDist',
    ],
  ];
  for (const command of commands) {
    const result = run(command);
    if (result.status !== 0) {
      throw new Error(
        `Building the Axon server failed: ${command.join(' ')} (${result.status ?? result.signal})`,
      );
    }
  }
  return install;
}

/** Drives one suite through bench.sh; writes suite.json and summary.md to `out`. */
export function runSuite(name, options) {
  const definition = SUITES[name];
  if (!definition) throw new Error(`Unknown suite: ${name}`);
  const {
    benchPath,
    out,
    warmUp,
    measure,
    repeats,
    fraction,
    p99LimitMs = Number(DEFAULTS.p99_limit_ms),
    installs = {},
    refs = {},
    cluster = CLUSTER,
    aa = false,
    cpus = {},
    profile = false,
    properties,
    // The hand-over helper's process functions (HOST_PROCESSES); a test passes its own.
    processes,
    // Where an arm's JDK is found (javaHome).
    env = process.env,
    // A sessions suite's part (partsOf), its key: that sweep alone, with its same-rate and own-share runs.
    part: partKey,
  } = options;
  const part = partKey
    ? partsOf(definition)?.find(candidate => candidate.key === partKey)
    : undefined;
  if (partKey && !part) throw new Error(`${name} has no part ${partKey}`);
  // Before anything runs: a cluster suite's hand-overs signal the node they stop.
  const handoverProcesses =
    definition.kind === 'cluster' ? (processes ?? hostProcesses()) : undefined;
  // A suite with a data set of its own (the cold suites) runs at it whatever the job's.
  // The CPUs of the generator, the servers and the containers: the suite's own layout on a runner of `cpus.total`,
  // else those given.
  const cpuset = cpus.total ? cpuLayout(definition, cpus.total) : cpus;
  const dataset = definition.dataset ?? options.dataset ?? '100k';
  mkdirSync(out, { recursive: true });
  const data = DATASETS[dataset];
  if (!data) throw new Error(`Unknown data set: ${dataset}`);
  // An A/A run puts the head against itself in a baseline suite, at least AA_ROUNDS rounds: the width of its
  // equal-rate ratios' intervals is the suite's noise.
  const aaRun = aa && definition.group === 'ab';
  const rounds = aaRun ? Math.max(repeats, AA_ROUNDS) : repeats;
  const primed = data.accounts >= PRIME_FROM && !definition.cold;
  const pairedArms = aaRun
    ? [
        { ...definition.arms[1], id: 'a' },
        { ...definition.arms[1], id: 'b' },
      ]
    : definition.arms;
  const arms =
    definition.kind === 'paired'
      ? properties
        ? [
            pairedArms[0],
            {
              ...pairedArms[1],
              props: { ...pairedArms[1].props, ...properties },
            },
          ]
        : pairedArms
      : definition.kind === 'sessions' || definition.kind === 'measure'
        ? definition.arms
        : definition.kind === 'component'
          ? installs.base && !aa
            ? [definition.baseArm, definition.arm]
            : [definition.arm]
          : ['steps', 'capacity', 'cluster'].includes(definition.kind)
            ? [definition.arm]
            : [
                ...new Map(
                  definition.runs.map(run => [run.arm.id, run.arm]),
                ).values(),
              ];
  const sessions = definition.kind === 'sessions';
  const suite = {
    schema: 'wow-e2e-bench/suite/1',
    name,
    kind: definition.kind,
    scenario: definition.scenario,
    ...(sessions ? { scenarios: definition.scenarios } : {}),
    distribution: definition.distribution,
    // A sessions suite's fixed rates run as compare.sh's, its sweeps as SESSIONS.sweep says.
    warmUp: sessions ? SESSIONS.warmUp : warmUp,
    measure: sessions ? SESSIONS.measure : measure,
    ...(part ? { part: part.key } : {}),
    ...(sessions
      ? {
          fixedRates: part ? part.rates : definition.rates,
          sweepPlan: part ? part.sweeps : sweepPlanOf(definition),
          own: definition.own,
          share: SESSIONS.share,
          sweep: SESSIONS.sweep,
          sessions: [],
        }
      : {}),
    repeats,
    fraction,
    p99LimitMs,
    dataset,
    accounts: data.accounts,
    primed,
    cold: definition.cold === true,
    aa: aaRun,
    ...(properties && definition.kind === 'paired' ? { properties } : {}),
    // An arm on a JDK of its own records which (its release; the major version without one).
    arms: arms.map(arm =>
      arm.java
        ? { ...arm, jdk: javaRelease(javaHome(arm.java, env)) ?? `${arm.java}` }
        : arm,
    ),
    refs,
    runner: runnerInfo(),
    // The taskset lists the processes ran on: load, server, and for a paired MongoDB suite store and bus.
    ...(Object.keys(cpuset).length > 0 ? { cpus: cpuset } : {}),
    saturation: {},
    rates: {},
    runs: [],
    failures: 0,
    startedAt: new Date().toISOString(),
  };
  const save = () => {
    writeFileSync(
      join(out, 'suite.json'),
      `${JSON.stringify(suite, null, 2)}\n`,
    );
    writeFileSync(join(out, 'summary.md'), renderSuite(suite));
  };
  // An arm's servers: its backend, its build (`--install`, when the build is not the one in the harness's own tree),
  // its server arguments and its JDK (`--server-java`, when it names one), which a run on a session names again
  // (bench.sh refuses one that differs from `up`'s).
  const server = arm => [
    `--backend=${arm.backend}`,
    ...(installs[arm.install] ? [`--install=${installs[arm.install]}`] : []),
    ...(arm.serverArgs ? [`--server-args=${arm.serverArgs}`] : []),
    ...(arm.java ? [`--server-java=${javaHome(arm.java, env)}`] : []),
    ...(usesBus(arm) ? [] : ['--no-bus']),
  ];
  const seedConcurrency = definition.seedConcurrency ?? data.seedConcurrency;
  const pins = [
    ...(cpuset.load ? [`--load-cpus=${cpuset.load}`] : []),
    ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
    ...(cpuset.store ? [`--store-cpus=${cpuset.store}`] : []),
    ...(cpuset.bus ? [`--bus-cpus=${cpuset.bus}`] : []),
  ];
  // The options every bench.sh call of an arm takes: its servers, the scenario and the keys, and the seeding unless
  // it runs on a session (`reuse`), which seeded and primed.
  // A mixed suite names its scenarios in --mix (the --scenario only labels the run's directory).
  const common = (
    arm,
    scenario = definition.scenario,
    reuse = false,
    distribution = definition.distribution,
  ) => [
    ...server(arm),
    ...(reuse ? ['--reuse'] : []),
    `--scenario=${scenario}`,
    ...(definition.mix && scenario === definition.scenario
      ? [`--mix=${definition.mix}`]
      : []),
    `--distribution=${distribution}`,
    `--accounts=${data.accounts}`,
    ...(reuse
      ? []
      : [
          `--seed-concurrency=${seedConcurrency}`,
          ...(primed ? ['--prime'] : []),
        ]),
    ...pins,
  ];
  const propsOf = arm => ({ ...SIGNALS, ...arm.props });
  const measureRun = (
    runName,
    arm,
    rate,
    round,
    phase = { warmUp, measure, into: suite.runs, mode: CALIBRATED },
  ) => {
    const dir = join(out, runName);
    const ok =
      bench(
        benchPath,
        [
          'run',
          ...common(arm, phase.scenario, phase.reuse),
          ...(phase.extra ?? []),
          `--rate=${rate.toFixed(1)}`,
          `--warm-up=${phase.warmUp}`,
          `--measure=${phase.measure}`,
          `--label=${name}-${runName}`,
          `--out=${dir}`,
        ],
        // A run on a session starts no server: the session's took their properties at `up`.
        phase.reuse ? undefined : propsOf(arm),
        phase.handover && { ...phase.handover, dir },
      ) === 0;
    const file = join(dir, 'result.json');
    const run = {
      name: runName,
      arm: arm.id,
      round,
      rate,
      dir: runName,
      status: 'failed',
      ...(phase.mode === EQUAL ? { mode: EQUAL } : {}),
      ...(phase.view ? { view: phase.view } : {}),
      ...(phase.scenario ? { scenario: phase.scenario } : {}),
      ...(phase.handover ? { handover: true } : {}),
    };
    if (ok && existsSync(file)) {
      run.status = 'ok';
      run.metrics = metrics(readJson(file));
    } else {
      suite.failures++;
    }
    // Every node's signals, a restarted node's included, summed: what the measured phase counted when the run has
    // its result, else each node's last line (a failed run: its logs stay), which also holds the seeding and priming.
    if (existsSync(dir)) {
      const window =
        run.status === 'ok' ? measuredWindow(readJson(file)) : undefined;
      const signals = mergeSignals(
        readdirSync(dir)
          .filter(file => /^node-.*\.log$/.test(file))
          .map(file => readFileSync(join(dir, file), 'utf8'))
          .map(log =>
            window
              ? signalsWithin(log, window.from, window.to)
              : lastSignals(log),
          ),
      );
      if (signals) run.signals = signals;
      // The stores' query bounds over the measured phase, by the first node that logged them.
      const limits = window
        ? readdirSync(dir)
            .filter(file => /^node-.*\.log$/.test(file))
            .sort()
            .map(file =>
              queryLimitsWithin(
                readFileSync(join(dir, file), 'utf8'),
                window.from,
                window.to,
              ),
            )
            .find(Boolean)
        : undefined;
      if (limits) run.queryLimits = limits;
    }
    phase.into.push(run);
    save();
    return run;
  };

  // The closed-loop saturation of an arm in a scenario, recorded under `key`.
  const saturate = (arm, key, scenario, again = false) => {
    const dir = join(out, `saturation-${key}${again ? '-again' : ''}`);
    const ok =
      bench(
        benchPath,
        [
          'saturate',
          ...common(arm, scenario),
          `--warm-up=${SATURATION.warmUp}`,
          `--step=${SATURATION.step}`,
          `--max-concurrency=${SATURATION.maxConcurrency}`,
          `--label=${name}-saturation-${key}${again ? '-again' : ''}`,
          `--out=${dir}`,
        ],
        propsOf(arm),
      ) === 0;
    const file = join(dir, 'saturation.json');
    const saturation = ok && existsSync(file) ? readJson(file) : undefined;
    const first = again ? suite.saturation[key] : undefined;
    if (saturation?.saturationThroughput > 0) {
      suite.saturation[key] = {
        status: 'ok',
        ...(first ? { first } : {}),
        throughput: saturation.saturationThroughput,
        concurrency: saturation.saturationConcurrency,
        capped: saturation.saturationConcurrency >= SATURATION.maxConcurrency,
        // The load generator's cores at the saturation step, of those it had.
        generatorCores: saturation.generatorCores,
        generatorCpus: saturation.availableProcessors,
      };
    } else if (first) {
      // A second search that failed leaves the first standing.
      suite.saturation[key] = { ...first, again: 'failed' };
    } else {
      suite.saturation[key] = { status: 'failed' };
      suite.failures++;
    }
    save();
    return suite.saturation[key];
  };

  if (definition.kind === 'paired' && definition.saturations) {
    // Closed-loop samples alone: `saturations` searches an arm in turn (ABBA); the arm's saturation is their median.
    suite.saturations = Object.fromEntries(arms.map(arm => [arm.id, []]));
    for (let index = 0; index < definition.saturations; index++) {
      for (const arm of roundOrder(arms, index)) {
        const key = `${arm.id}-${index + 1}`;
        suite.saturations[arm.id].push(saturate(arm, key));
        delete suite.saturation[key];
      }
    }
    for (const arm of arms) {
      const ok = suite.saturations[arm.id].filter(
        sample => sample.status === 'ok',
      );
      if (ok.length > 0) {
        suite.saturation[arm.id] = {
          status: 'ok',
          throughput: median(ok.map(sample => sample.throughput)),
          samples: ok.length,
          capped: ok.some(sample => sample.capped),
        };
      }
    }
    save();
  } else if (definition.kind === 'paired') {
    for (const arm of arms) saturate(arm, arm.id);
    const outlier = saturationOutlier(
      suite.saturation,
      arms.map(arm => arm.id),
    );
    if (outlier)
      saturate(
        arms.find(arm => arm.id === outlier),
        outlier,
        undefined,
        true,
      );
    suite.probes = [];
    suite.calibration = {};
    for (const arm of arms) {
      const saturation = suite.saturation[arm.id]?.throughput;
      if (!saturation) continue;
      let rate = saturation * fraction;
      let kept = false;
      for (let attempt = 1; attempt <= PROBE.attempts; attempt++) {
        const probe = measureRun(`probe${attempt}-${arm.id}`, arm, rate, 0, {
          warmUp: PROBE.warmUp,
          measure: PROBE.measure,
          into: suite.probes,
        });
        if (probe.status === 'ok' && sustainable(probe.metrics, rate)) {
          kept = true;
          break;
        }
        if (attempt < PROBE.attempts) rate *= PROBE.step;
      }
      suite.rates[arm.id] = rate;
      suite.calibration[arm.id] = {
        sustainable: kept,
        share: rate / saturation,
      };
      save();
    }
    const runnable = arms.filter(arm => suite.rates[arm.id]);
    const round = (index, mode, rateOf, prefix) => {
      for (const arm of roundOrder(runnable, index)) {
        measureRun(`${prefix}${index + 1}-${arm.id}`, arm, rateOf(arm), index, {
          warmUp,
          measure,
          into: suite.runs,
          mode,
        });
      }
    };
    const own = arm => suite.rates[arm.id];
    if (runnable.length > 0) {
      for (let index = 0; index < repeats; index++)
        round(index, CALIBRATED, own, 'r');
      if (repeats >= 2 && !aaRun && drifted(suite.runs, runnable)) {
        suite.extraRound = true;
        round(repeats, CALIBRATED, own, 'r');
      }
    }
    // The equal-rate rounds: both arms at the lower calibrated rate, so their latencies compare.
    const equal = equalRate(suite.rates, arms);
    if (arms.length === 2 && equal) {
      suite.equalRate = equal;
      save();
      for (let index = 0; index < rounds; index++)
        round(index, EQUAL, () => equal, 'e');
    }
    // Profiling (D224 note ⑧: never in a timed round): one more run per arm with a flight recording, at the rate the
    // latencies compare at, summarized with the JDK's `jfr view`.
    if (profile) {
      suite.profiles = [];
      const settings = jfrSettings(out);
      for (const arm of runnable) {
        const run = measureRun(
          `profile-${arm.id}`,
          arm,
          suite.equalRate ?? suite.rates[arm.id],
          0,
          {
            warmUp,
            measure,
            into: suite.profiles,
            extra: [
              '--jfr',
              ...(settings ? [`--jfr-settings=${settings}`] : []),
            ],
          },
        );
        run.profile = jfrViews(join(out, run.dir));
        save();
      }
    }
  } else if (definition.kind === 'measure') {
    suite.measureRuns = MEASURE.runs;
    suite.warmUpRuns = MEASURE.warmUpRuns;
    for (let round = 0; round < Math.max(rounds, MEASURE.rounds); round++) {
      for (const arm of roundOrder(arms, round)) {
        const runName = `${round + 1}-${arm.id}`;
        const dir = join(out, runName);
        const status = bench(
          benchPath,
          [
            'measure',
            `--backend=${arm.backend}`,
            `--runs=${MEASURE.runs}`,
            `--warm-up-runs=${MEASURE.warmUpRuns}`,
            ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
            `--out=${dir}`,
          ],
          arm.props,
        );
        const run = { name: runName, arm: arm.id, round, status: 'failed' };
        const measured = status === 0 ? readMeasure(dir) : undefined;
        if (measured) {
          run.status = 'ok';
          run.measure = measured;
        } else {
          suite.failures++;
        }
        suite.runs.push(run);
        save();
      }
    }
  } else if (definition.kind === 'component') {
    for (const [index, scenario] of definition.scenarios.entries()) {
      for (const arm of roundOrder(arms, index)) {
        const runName = `${scenario.toLowerCase()}-${arm.id}`;
        const dir = join(out, runName);
        const status = bench(
          benchPath,
          [
            'component',
            `--backend=${arm.backend}`,
            ...(installs[arm.install]
              ? [`--install=${installs[arm.install]}`]
              : []),
            `--scenario=${scenario}`,
            `--accounts=${data.accounts}`,
            `--ops=${COMPONENT.ops}`,
            `--warm-up-ops=${COMPONENT.warmUpOps}`,
            `--concurrency=${COMPONENT.concurrency}`,
            ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
            `--out=${dir}`,
          ],
          arm.props,
        );
        const file = join(dir, 'component.json');
        const run = { name: runName, arm: arm.id, scenario, status: 'failed' };
        if (status === 0 && existsSync(file)) {
          run.status = 'ok';
          run.component = readJson(file);
        } else if (status === 3) {
          // A build older than ComponentBench: nothing to compare, not a failure.
          run.status = 'unavailable';
        } else {
          suite.failures++;
        }
        suite.runs.push(run);
        save();
      }
    }
  } else if (definition.kind === 'capacity') {
    suite.probes = [];
    suite.capacity = {};
    for (const scenario of definition.scenarios) {
      const top = saturate(definition.arm, scenario, scenario)?.throughput;
      if (!top) continue;
      let lo = 0;
      let hi = top;
      let best;
      for (let probe = 1; probe <= CAPACITY.probes; probe++) {
        const rate = (lo + hi) / 2;
        const run = measureRun(
          `${scenario.toLowerCase()}-probe${probe}`,
          definition.arm,
          rate,
          probe - 1,
          {
            warmUp: CAPACITY.warmUp,
            measure: CAPACITY.measure,
            into: suite.probes,
            scenario,
          },
        );
        run.held =
          run.status === 'ok' &&
          sustainable(run.metrics, rate) &&
          run.metrics.p99 <= p99LimitMs;
        if (run.held) {
          lo = rate;
          best = run;
        } else {
          hi = rate;
        }
        save();
        if ((hi - lo) / top <= CAPACITY.tolerance) break;
      }
      suite.capacity[scenario] = {
        saturation: top,
        rate: best ? lo : undefined,
        run: best?.name,
        p50: best?.metrics.p50,
        p99: best?.metrics.p99,
        cpuMsPerOp: best?.metrics.cpuMsPerOp,
        upper: hi,
      };
      save();
    }
  } else if (definition.kind === 'cluster') {
    for (const { scenario, rate } of cluster.scenarios) {
      for (const handover of [false, true]) {
        measureRun(
          `${scenario.toLowerCase()}-${handover ? 'handover' : 'steady'}`,
          definition.arm,
          rate,
          0,
          {
            warmUp: cluster.warmUp,
            measure: cluster.measure,
            into: suite.runs,
            scenario,
            extra: [`--nodes=${cluster.nodes}`],
            handover: handover && {
              stopAfter: cluster.stopAfter,
              down: cluster.down,
              nodePort: cluster.nodePort,
              processes: handoverProcesses,
            },
          },
        );
      }
    }
  } else if (definition.kind === 'steps') {
    let unsustained = 0;
    for (const [index, rate] of CLIFF.rates.entries()) {
      const run = measureRun(
        `s${index + 1}-${rate}`,
        definition.arm,
        rate,
        index,
        {
          warmUp: CLIFF.warmUp,
          measure: CLIFF.measure,
          into: suite.runs,
        },
      );
      run.sustained = run.status === 'ok' && sustainable(run.metrics, rate);
      save();
      unsustained = run.sustained ? 0 : unsustained + 1;
      if (unsustained >= 2) break;
    }
  } else if (sessions) {
    suite.sweeps = [];
    // With `profile`, every session's servers record (bench.sh up --jfr), the profile's settings with the safepoints
    // and the socket reads and writes (jfrSettings): each run on the session keeps their recording over its warm-up
    // and measured phase and, on PostgreSQL, its statements and checkpoints. The runs then carry the recording's cost.
    const settings = profile ? jfrSettings(out) : undefined;
    const recording = profile
      ? ['--jfr', ...(settings ? [`--jfr-settings=${settings}`] : [])]
      : [];
    if (profile) suite.profiled = true;
    if (definition.storeWriteIops)
      suite.storeWriteIops = definition.storeWriteIops;
    // One closed-loop search on the session: every step from 1 (or `from` alone) to the cap (or `from`), or the steps
    // `concurrencies`, short ones when `locate` (SESSIONS.sweep.locate) and full-length ones otherwise, a failing one
    // ending once it measured `failAfter` s.
    const saturateOn = (
      arm,
      plan,
      runName,
      { from, concurrencies, locate },
    ) => {
      const dir = join(out, runName);
      const lengths = locate ? SESSIONS.sweep.locate : SESSIONS.sweep;
      const ok =
        bench(benchPath, [
          'saturate',
          ...common(arm, plan.scenario, true, plan.distribution),
          ...(concurrencies
            ? [`--concurrencies=${concurrencies.join(',')}`]
            : [
                ...(from ? [`--start-concurrency=${from}`] : []),
                `--max-concurrency=${from ?? SESSIONS.sweep.maxConcurrency}`,
                '--sweep',
                `--growth=${SESSIONS.sweep.growth}`,
              ]),
          ...(definition.dumpEvery
            ? [`--dump-every=${definition.dumpEvery}`]
            : []),
          `--step=${lengths.step}`,
          `--warm-up=${lengths.warmUp}`,
          ...(locate ? [] : [`--fail-after=${SESSIONS.sweep.failAfter}`]),
          `--label=${name}-${runName}`,
          `--out=${dir}`,
        ]) === 0;
      const file = join(dir, 'saturation.json');
      const saturation = ok && existsSync(file) ? readJson(file) : undefined;
      return saturation?.steps?.length > 0 ? saturation : undefined;
    };
    // Coarse then fine. Short steps locate the peak and the sustainable rate: the sweep to the cap, then a step at a
    // time past it while it still rises (sweepNext). Full-length steps then measure around them (fineSteps), and on
    // as each needs (fineMore): those are the sweep's steps, its peak and its sustainable rate.
    const sweep = (arm, session, plan, round) => {
      const runName = `${session}-${plan.key}-sweep`;
      const run = {
        name: runName,
        arm: arm.id,
        round,
        scenario: plan.scenario,
        distribution: plan.distribution,
        label: plan.label,
        dir: runName,
      };
      const failed = () => {
        run.status = 'failed';
        suite.failures++;
      };
      const locating = `${runName}-locate`;
      const parts = [saturateOn(arm, plan, locating, { locate: true })];
      let located;
      let end;
      while (parts[0]) {
        located = sweepOf(mergeSaturations(parts));
        const decision = sweepNext(located.steps, located.peak.generatorCpus);
        end = decision.end;
        const next = decision.next;
        if (!next) break;
        const more = saturateOn(arm, plan, `${locating}-${next}`, {
          from: next,
          locate: true,
        });
        if (!more) {
          end = 'failed';
          suite.failures++;
          break;
        }
        parts.push(more);
        run.locatedPast = [...(run.locatedPast ?? []), `${locating}-${next}`];
      }
      const fine = located && [
        saturateOn(arm, plan, runName, {
          concurrencies: fineSteps(located.steps, p99LimitMs),
        }),
      ];
      if (!fine?.[0]) {
        failed();
      } else {
        for (;;) {
          const swept = sweepOf(mergeSaturations(fine));
          const more = fineMore(
            located.steps,
            swept.steps,
            p99LimitMs,
            swept.peak.generatorCpus,
          );
          const extra =
            more.next &&
            saturateOn(arm, plan, `${runName}-${more.next}`, {
              from: more.next,
            });
          if (extra) {
            fine.push(extra);
            run.extended = [...(run.extended ?? []), `${runName}-${more.next}`];
            continue;
          }
          if (more.next) suite.failures++;
          Object.assign(
            run,
            {
              status: 'ok',
              end: more.next ? 'failed' : (more.end ?? end),
            },
            fineOf(located, swept),
          );
          run.sustainable = sustainableStep(run.steps, p99LimitMs, run.top);
          break;
        }
      }
      suite.sweeps.push(run);
      save();
      return run;
    };
    // A session once its runs are done (bench.sh inspect, after the nodes are quiet): how it started, the servers' RSS,
    // the store's tables and events, and whether the store holds what the runs asked of it (correctnessOf).
    const repo = join(dirname(benchPath), '..', '..', '..');
    const inspect = (arm, record) => {
      const dir = `${record.name}-inspect`;
      const ok =
        bench(benchPath, [
          'inspect',
          ...server(arm),
          '--reuse',
          `--accounts=${data.accounts}`,
          ...(arm.sql ? [`--sql=${join(repo, arm.sql)}`] : []),
          `--out=${join(out, dir)}`,
        ]) === 0;
      const up = join(out, record.dir);
      record.startup = readJsonIfThere(join(up, 'startup.json'))?.nodes;
      const inspection = ok
        ? readJsonIfThere(join(out, dir, 'inspect.json'))
        : undefined;
      if (!inspection) suite.failures++;
      record.inspected = inspection ? 'ok' : 'failed';
      record.rssKb = inspection?.rssKb;
      record.store = inspection?.store ?? undefined;
      if (record.store) {
        record.correctness = correctnessOf({
          accounts: data.accounts,
          events: record.store.events ?? {},
          prime: readJsonIfThere(join(up, 'prime.json')),
          runs: suite.runs.filter(
            run => run.round === record.round && run.arm === arm.id,
          ),
          sweeps: suite.sweeps.filter(
            run => run.round === record.round && run.arm === arm.id,
          ),
        });
      }
      save();
    };
    for (let index = 0; index < repeats; index++) {
      for (const arm of roundOrder(arms, index)) {
        const session = `${index + 1}-${arm.id}`;
        // `up` checks the suite's scenarios alone: the Axon server serves no other.
        const up =
          bench(
            benchPath,
            [
              'up',
              ...server(arm),
              `--mix=${definition.scenarios.join(',')}`,
              `--accounts=${data.accounts}`,
              `--seed-concurrency=${seedConcurrency}`,
              ...(primed ? ['--prime'] : []),
              ...recording,
              ...(definition.storeWriteIops
                ? [`--store-write-iops=${definition.storeWriteIops}`]
                : []),
              ...pins,
              `--out=${join(out, `${session}-up`)}`,
            ],
            arm.props,
          ) === 0;
        const record = {
          name: session,
          arm: arm.id,
          round: index,
          dir: `${session}-up`,
          status: up ? 'ok' : 'failed',
        };
        suite.sessions.push(record);
        if (!up) {
          // A failed `up` stops what it started.
          suite.failures++;
          save();
          continue;
        }
        const fixed = (runName, scenario, rate, view) =>
          measureRun(runName, arm, rate, index, {
            warmUp: SESSIONS.warmUp,
            measure: SESSIONS.measure,
            into: suite.runs,
            scenario,
            reuse: true,
            view,
          });
        try {
          // The same-rate view: both arms at one rate; a rate given twice runs twice (`-e1`, `-e1-2`).
          const times = {};
          for (const { scenario, rate } of suite.fixedRates) {
            const key = scenario.toLowerCase();
            times[key] = (times[key] ?? 0) + 1;
            fixed(
              `${session}-${key}${times[key] > 1 ? `-${times[key]}` : ''}`,
              scenario,
              rate,
              SAME,
            );
          }
          const swept = suite.sweepPlan.map(plan =>
            sweep(arm, session, plan, index),
          );
          // The own-share view: each arm at a share of the sustainable rate its own sweep in this session found (a
          // sweep with no step within the limits has none, which its table says).
          for (const run of swept.filter(
            run => definition.own && run.sustainable,
          )) {
            fixed(
              `${session}-${run.scenario.toLowerCase()}-own`,
              run.scenario,
              SESSIONS.share * run.sustainable.throughput,
              OWN,
            );
          }
          inspect(arm, record);
        } finally {
          bench(benchPath, ['down']);
        }
      }
    }
  } else {
    definition.runs.forEach((run, index) => {
      suite.rates[`${run.arm.id}@${run.rate}`] = run.rate;
      measureRun(
        `d${index + 1}-${run.arm.id}-${run.scenario ? `${run.scenario.toLowerCase()}-` : ''}${run.rate}`,
        run.arm,
        run.rate,
        index,
        { warmUp, measure, into: suite.runs, scenario: run.scenario },
      );
    });
  }
  suite.finishedAt = new Date().toISOString();
  save();
  return suite;
}

/** Every suite.json under `dir` (the report job's downloaded artifacts). */
export function readSuites(dir) {
  const suites = [];
  const walk = (path, depth) => {
    if (depth > 3 || !existsSync(path)) return;
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const child = join(path, entry.name);
      if (entry.isDirectory()) walk(child, depth + 1);
      else if (entry.name === 'suite.json') suites.push(readJson(child));
    }
  };
  walk(dir, 0);
  // The parts of a sessions suite (partsOf), one job each, as one suite.
  const parts = new Map();
  const whole = suites.filter(suite => {
    if (!suite.part) return true;
    const key = labelOf(suite);
    parts.set(key, [...(parts.get(key) ?? []), suite]);
    return false;
  });
  const order = Object.keys(SUITES);
  return [...whole, ...[...parts.values()].map(mergeParts)].sort(
    (a, b) => order.indexOf(a.name) - order.indexOf(b.name),
  );
}

/** The noise thresholds of a run's A/A suites, keyed by `suite@dataset`, merged over `previous`. */
export function noiseFile(suites, previous = {}) {
  const next = { ...previous };
  // An A/A run whose second arm ran with changed properties measured the change, not the noise.
  // A suite that measured nothing keeps the thresholds it had.
  for (const suite of suites.filter(s => s.aa && !s.properties)) {
    const noise = noiseOf(suite);
    if (noise) next[labelOf(suite)] = noise;
  }
  return next;
}

/** The combined report of every suite. */
export function renderReport(suites, meta = {}) {
  const lines = [
    '# V10 end-to-end benchmark (D224) on GitHub Actions',
    '',
    `Head: \`${meta.headRef ?? '?'}\` ${meta.headSha ?? ''}; V10 baseline: ${meta.baseSha ? `\`${meta.baseRef}\` ${meta.baseSha}` : 'none'}; ` +
      `harness and load generator from ${meta.harness ?? '?'}.`,
    '',
    `Tier: ${meta.tier ?? '?'}; coverage: ${suites.map(labelOf).join(', ') || 'none'}.`,
    '',
    "Each suite ran on one runner of its own (a sessions suite of several sweeps, each sweep in fresh sessions on one of its own), so only its ratios compare across suites; absolute numbers are that runner's. Ratios are b/a per pair (head/base): median (min–max, n pairs). Lower is better except throughput.",
    '',
  ];
  if (meta.skipped?.length > 0) {
    lines.push(`**Skipped:** ${meta.skipped.join('; ')}.`, '');
  }
  if (suites.length === 0) {
    lines.push('No suite left results; see the jobs.', '');
    return `${lines.join('\n')}\n`;
  }
  const found = suites.flatMap(suite => anomalies(suite, meta.noise ?? {}));
  lines.push('## Anomalies', '');
  if (found.length === 0) {
    lines.push(
      'None: no partition paused, no tail read the store after a gap or an overflow, no error spike, dip or p99 above 10× p50, no head allocating more than its baseline.',
      '',
    );
  } else {
    lines.push(
      `Checks: a partition paused (D243), a tail read the store after a gap or an overflow (D249), more than ${ANOMALY.errorPercent}% errors, a measured second below half the rate, p99 above ${ANOMALY.tailToMedian}× p50 (when p99 ≥ ${ANOMALY.minTailMs} ms), a head allocating more than ${num(ANOMALY.componentGrowth * 100)}% more bytes per operation than its baseline. Errors, dips and tails are not checked where a run went past what the node keeps up with by design (probes, cliff steps that did not keep up).`,
      '',
      ...anomalyTable(found),
      '',
    );
  }
  const noise = meta.noise ?? {};
  const significant = suites.filter(
    s => s.kind === 'paired' && s.arms.length === 2 && !s.aa,
  );
  if (significant.length > 0) {
    const rows = [];
    let beyond = 0;
    let calibrated = 0;
    for (const suite of significant) {
      const thresholds = noise[noiseLabel(suite)];
      if (thresholds) calibrated++;
      const [a, b] = suite.arms.map(arm => arm.id);
      // Throughput and CPU compare at the calibrated rates, latencies at the equal rate.
      for (const [metric, mode] of [
        ['throughput', CALIBRATED],
        ['p50', EQUAL],
        ['p99', EQUAL],
      ]) {
        const interval = confidence(suite, mode)[metric];
        const result =
          mode === CALIBRATED && calibratedGeneratorBound(suite)
            ? GENERATOR_BOUND
            : verdict(interval, thresholds?.[metric]);
        if (result === 'higher' || result === 'lower') beyond++;
        rows.push(
          `| ${labelOf(suite)} | ${b}/${a} | ${metric} | ${mode} | ${interval ? `${ratio(interval.point)} [${ratio(interval.lo)}–${ratio(interval.hi)}]` : '-'} | ` +
            `${thresholds?.[metric] ? `±${num(thresholds[metric] * 100)}%` : '-'} | ${result === 'higher' || result === 'lower' ? `**${result}**` : result} |`,
        );
      }
    }
    lines.push(
      '## Significance',
      '',
      `Beyond noise: ${beyond} of ${rows.length} ratios; ${calibrated} of ${significant.length} suites have an A/A noise threshold (test/benchmarks/noise.json). A ratio counts when its 95% bootstrap interval (over the measured seconds) clears the suite's threshold; without one it is \`uncalibrated\`.`,
      '',
      '| Suite | b/a | Metric | Rounds | Ratio [95% CI] | Noise | Verdict |',
      '| --- | --- | --- | --- | --- | --- | --- |',
      ...rows,
      '',
    );
  }
  const aaSuites = suites.filter(s => s.aa);
  if (aaSuites.length > 0) {
    lines.push(
      '## A/A noise',
      '',
      `The head against itself at one rate, ${AA_ROUNDS} rounds or more: each metric's threshold is the half-width of the 95% bootstrap interval of its ratio, at least 2%. The report writes these as noise.json; commit it as test/benchmarks/noise.json to make them the thresholds of later runs.`,
      '',
      `| Suite | ${SIGNIFICANCE_METRICS.map(([metric]) => metric).join(' | ')} |`,
      `| --- | ${SIGNIFICANCE_METRICS.map(() => '---').join(' | ')} |`,
      ...aaSuites.map(suite => {
        const n = noiseOf(suite);
        return `| ${labelOf(suite)} | ${SIGNIFICANCE_METRICS.map(([metric]) => (n ? `±${num(n[metric] * 100)}%` : 'no data')).join(' | ')} |`;
      }),
      '',
    );
  }
  const components = suites.filter(s => s.kind === 'component');
  if (components.length > 0) {
    lines.push(
      '## L1 component counts',
      '',
      "The domain on the kernel and one store in one JVM, no HTTP: each operation's store round trips (the node's background work over the measured phase included) and bytes allocated. Counts compare anywhere; the timings only indicate.",
      '',
      ...components
        .flatMap(componentTable)
        .filter(
          (line, index) =>
            index < 2 ||
            (!line.startsWith('| Suite') && !line.startsWith('| ---')),
        ),
      '',
    );
  }
  const comparisons = suites.filter(s => s.kind === 'sessions');
  if (comparisons.length > 0) {
    lines.push(
      '## The industry comparison',
      '',
      'What each server does for a command:',
      '',
      '| | Wow | Axon |',
      '| --- | --- | --- |',
      ...AXON_PARITY.map(
        ([what, wow, axon]) => `| ${what} | ${wow} | ${axon} |`,
      ),
      '',
    );
    for (const suite of comparisons) {
      lines.push(
        `### ${labelOf(suite)}`,
        '',
        SUITES[suite.name]?.answers ?? '',
        '',
        ...sessionTables(suite),
        '',
      );
    }
  }
  const capacities = suites.filter(s => s.kind === 'capacity');
  if (capacities.length > 0) {
    lines.push('## Sustainable rates (capacity guidance)', '');
    for (const suite of capacities) {
      lines.push(`### ${labelOf(suite)}`, '', ...capacityTable(suite), '');
    }
  }
  const clusters = suites.filter(s => s.kind === 'cluster');
  if (clusters.length > 0) {
    lines.push('## E5 cluster: steady and hand-over', '');
    for (const suite of clusters) {
      lines.push(`### ${labelOf(suite)}`, '', ...clusterTable(suite), '');
    }
  }
  lines.push(
    '## Runners',
    '',
    '| Suite | CPU | Cores | Memory | JDK | CPU layout |',
    '| --- | --- | --- | --- | --- | --- |',
  );
  // A suite of parts (mergeParts): a runner a sweep.
  for (const suite of suites) {
    for (const { label, runner, cpus } of suite.parts ?? [
      { runner: suite.runner, cpus: suite.cpus },
    ]) {
      lines.push(
        `| ${labelOf(suite)}${label ? ` ${label}` : ''} | ${runner?.cpu ?? '?'} | ${runner?.cores ?? '?'} | ${runner?.memoryGb ?? '?'} GB | ${runner?.jdk ?? '?'} | ${layoutOf(cpus)} |`,
      );
    }
  }
  const sampled = suites.filter(suite => saturationSamples(suite));
  if (sampled.length > 0) {
    lines.push(
      '',
      '## Saturation samples (closed loop)',
      '',
      "Closed-loop searches alone, several an arm in turn (ABBA), each from empty stores. The range runs from the lowest b over the highest a to the highest over the lowest; the A/A spread is the larger of the two arms' (their most over their least, less 1), and a range within it is level.",
      '',
      ...samplesTable(sampled),
    );
  }
  lines.push(
    '',
    '## Saturation points (closed loop)',
    '',
    '| Suite | Arm | Saturation | Concurrency | Open-loop rate | Share of saturation |',
    '| --- | --- | --- | --- | --- | --- |',
  );
  for (const suite of suites.filter(s => s.kind === 'paired')) {
    for (const arm of suite.arms) {
      const s = suite.saturation?.[arm.id] ?? {};
      lines.push(
        `| ${labelOf(suite)} | ${arm.id} | ${saturationText(s)} | ${s.concurrency ?? '-'} | ${num(suite.rates?.[arm.id])}/s | ${shareText(suite, arm.id)} |`,
      );
    }
  }
  const compared = suites.filter(
    s => s.kind === 'paired' && s.arms.length === 2,
  );
  if (compared.length > 0) {
    lines.push(
      '',
      '## Paired ratios',
      '',
      '`calibrated`: each arm at its own calibrated rate, so throughput and CPU per operation compare but the latencies are at **unequal loads**. `equal rate`: both arms at the lower of the two calibrated rates; read the latency ratios from these rows.',
      '',
      `| Suite | b/a | Rounds | Saturation | ${RATIO_METRICS.map(([, label]) => label).join(' | ')} |`,
      `| --- | --- | --- | --- | ${RATIO_METRICS.map(() => '---').join(' | ')} |`,
    );
    for (const suite of compared) {
      const [a, b] = suite.arms.map(arm => arm.id);
      const sa = suite.saturation?.[a];
      const sb = suite.saturation?.[b];
      const saturation = saturationRatioBound(suite)
        ? GENERATOR_BOUND
        : sa?.throughput && sb?.throughput
          ? `${sb.capped || sa.capped ? '~' : ''}${ratio(sb.throughput / sa.throughput)}`
          : '-';
      for (const [mode, label] of [
        [CALIBRATED, 'calibrated (unequal load)'],
        [
          EQUAL,
          `equal rate${suite.equalRate ? ` ${num(suite.equalRate)}/s` : ''}`,
        ],
      ]) {
        const rows = pairs(suite, mode);
        if (rows.length === 0) continue;
        const bound = mode === CALIBRATED && calibratedGeneratorBound(suite);
        lines.push(
          `| ${labelOf(suite)} | ${b}/${a} | ${label} | ${saturation} | ` +
            `${RATIO_METRICS.map(([key]) => (bound ? GENERATOR_BOUND : ratioRange(range(rows.map(row => row.ratios[key]))))).join(' | ')} |`,
        );
      }
    }
  }
  const solo = suites.filter(s => s.kind === 'paired' && s.arms.length === 1);
  if (solo.length > 0) {
    lines.push(
      '',
      '## Reported on their own (V10 on PostgreSQL)',
      '',
      '| Suite | Runs | Throughput | p50 | p99 | p999 | Worst-second p99 | CPU ms/op | Alloc/op |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    for (const suite of solo) {
      const runs = suite.runs
        .filter(run => run.status === 'ok')
        .map(run => run.metrics);
      const cell = (key, format) => {
        const r = range(runs.map(m => m[key]));
        return r
          ? `${format(r.median)} (${format(r.min)}–${format(r.max)})`
          : '-';
      };
      lines.push(
        `| ${labelOf(suite)} | ${runs.length} | ${cell('throughput', v => `${num(v)}/s`)} | ${cell('p50', ms)} | ${cell('p99', ms)} | ` +
          `${cell('p999', ms)} | ${cell('worstSecondP99', ms)} | ${cell('cpuMsPerOp', v => num(v, 2))} | ${cell('allocatedBytesPerOp', bytes)} |`,
      );
    }
  }
  const diag = suites.find(s => s.name === 'e3-mongo-diag');
  if (diag) {
    const armOf = new Map(diag.arms.map(arm => [arm.id, arm]));
    lines.push(
      '',
      '## E3 on MongoDB: diagnostics',
      '',
      SUITES['e3-mongo-diag'].answers,
      '',
      '| Run | Arm | Properties | Rate | Throughput | Failed | p50 | p95 | p99 | p999 | Worst-second p99 | MongoDB CPU | Kafka CPU | Server CPU |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    );
    for (const run of diag.runs) {
      const arm = armOf.get(run.arm) ?? {};
      const m = run.metrics;
      lines.push(
        run.status === 'ok'
          ? `| ${run.name} | ${arm.id} | ${propsText(arm.props)} | ${num(run.rate)}/s | ${num(m.throughput)}/s | ${m.failed} | ${ms(m.p50)} | ${ms(m.p95)} | ` +
              `${ms(m.p99)} | ${ms(m.p999)} | ${ms(m.worstSecondP99)} | ${num(m.containers.mongo, 0)}% | ${num(m.containers.kafka, 0)}% | ${num(m.serverCores, 2)} cores |`
          : `| ${run.name} | ${arm.id} | ${propsText(arm.props)} | ${num(run.rate)}/s | **${run.status}** | | | | | | | | | |`,
      );
    }
  }
  const cliffs = suites.filter(s => s.kind === 'steps');
  if (cliffs.length > 0) {
    lines.push(
      '',
      '## E3 cliff steps',
      '',
      'Fixed open-loop steps, a minute each on fresh containers. "Kept up": at most 1% failed and at least 97% of the rate completed. V10 signals count from the server start (seeding included): `tailRead` reasons other than `start` mean the tails fell back to the store, `refused wow.command.busy` that the node shed load.',
    );
    for (const suite of cliffs)
      lines.push('', `### ${labelOf(suite)}`, '', ...cliffTable(suite));
  }
  const failed = suites.filter(s => s.failures > 0);
  if (failed.length > 0) {
    lines.push(
      '',
      `**Failed steps:** ${failed.map(s => `${s.name} (${s.failures})`).join(', ')}.`,
    );
  }
  lines.push(
    '',
    '---',
    '',
    ...suites.map(suite => renderSuite(suite).replace(/^## /m, '### ')),
  );
  return `${lines.join('\n')}\n`;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const command = process.argv[2];
  const env = process.env;
  const output = pairsToWrite => {
    const text = Object.entries(pairsToWrite)
      .map(([key, value]) => `${key}=${value}`)
      .join('\n');
    console.log(text);
    if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, `${text}\n`);
  };
  const settingsOf = () => {
    const config =
      env.CONFIG && existsSync(env.CONFIG) ? readJson(env.CONFIG) : undefined;
    if (config && env.EVENT === 'push')
      console.log(`bench-run.json: ${JSON.stringify(config)}`);
    return runSettings({
      event: env.EVENT,
      config,
      inputs: {
        tier: env.TIER,
        suites: env.SUITES,
        dataset: env.DATASET,
        aa: env.AA,
        profile: env.PROFILE,
        warm_up: env.WARM_UP,
        measure: env.MEASURE,
        repeats: env.REPEATS,
        fraction: env.FRACTION,
        p99_limit_ms: env.P99_LIMIT_MS,
        base_ref: env.BASE_REF,
        properties: env.PROPERTIES,
      },
    });
  };
  try {
    if (command === 'plan') {
      // The settings, checked, and which commits the suites need resolved.
      const settings = settingsOf();
      const inputs = resolveInputs({
        tier: settings.tier,
        suites: settings.suites,
        dataset: settings.dataset,
        aa: settings.aa,
        profile: settings.profile,
        warmUp: settings.warm_up,
        measure: settings.measure,
        repeats: settings.repeats,
        fraction: settings.fraction,
        p99LimitMs: settings.p99_limit_ms,
        properties: settings.properties,
      });
      const groups = new Set(inputs.suites.map(name => SUITES[name].group));
      output({
        suites: JSON.stringify(inputs.suites),
        'warm-up': inputs.warmUp,
        measure: inputs.measure,
        repeats: inputs.repeats,
        fraction: inputs.fraction,
        'p99-limit-ms': inputs.p99LimitMs,
        tier: inputs.tier,
        datasets: JSON.stringify(inputs.datasets),
        aa: inputs.aa,
        profile: inputs.profile,
        properties: inputs.properties ? JSON.stringify(inputs.properties) : '',
        'base-ref': settings.base_ref,
        // The component suites take a baseline when there is one, and run the head alone otherwise.
        'needs-base':
          ((groups.has('ab') || groups.has('component')) && !inputs.aa) ||
          inputs.suites.some(usesBase),
      });
    } else if (command === 'matrix') {
      const plan = planMatrix({
        suites: JSON.parse(env.SUITES_JSON),
        datasets: JSON.parse(env.DATASETS_JSON ?? '["100k"]'),
        aa: env.AA === 'true',
        headSha: env.HEAD_SHA,
        baseSha: env.BASE_SHA,
      });
      for (const skipped of plan.skipped)
        console.log(`::warning::Skipped ${skipped}`);
      if (plan.suites.length === 0) throw new Error('No suite can run.');
      output({
        suites: JSON.stringify(plan.suites),
        jobs: JSON.stringify(plan.jobs),
        builds: JSON.stringify(plan.builds),
        skipped: JSON.stringify(plan.skipped),
      });
    } else if (command === 'suite') {
      const job = parseJob(process.argv[3]);
      const inputs = resolveInputs({
        suites: job.suite,
        dataset: job.dataset,
        aa: env.AA,
        profile: env.PROFILE,
        warmUp: env.WARM_UP,
        measure: env.MEASURE,
        repeats: env.REPEATS,
        fraction: env.FRACTION,
        p99LimitMs: env.P99_LIMIT_MS,
        properties: env.PROPERTIES,
      });
      // A suite with an Axon arm builds the Axon server from the measured head, the harness's repository.
      const axon = SUITES[inputs.suites[0]].arms?.some(
        arm => arm.install === 'axon',
      )
        ? env.INSTALL_AXON || axonInstall(join(dirname(env.BENCH), '../../..'))
        : undefined;
      const suite = runSuite(inputs.suites[0], {
        benchPath: env.BENCH,
        out: env.OUT,
        ...inputs,
        dataset: job.dataset,
        part: job.part,
        cpus: { total: Number(env.CPUS) || undefined },
        installs: Object.fromEntries(
          [
            ['head', env.INSTALL_HEAD],
            ['base', env.INSTALL_BASE],
            ['axon', axon],
          ].filter(([, dir]) => dir),
        ),
        refs: {
          headRef: env.HEAD_REF,
          headSha: env.HEAD_SHA,
          baseRef: env.BASE_REF,
          baseSha: env.BASE_SHA,
          harness: env.HARNESS,
          tier: env.TIER,
        },
      });
      process.exitCode = suite.failures > 0 ? 1 : 0;
    } else if (command === 'report') {
      const suites = readSuites(process.argv[3]);
      const previous =
        env.NOISE && existsSync(env.NOISE) ? readJson(env.NOISE) : {};
      const meta = {
        ...(suites[0]?.refs ?? {}),
        skipped: env.SKIPPED ? JSON.parse(env.SKIPPED) : [],
        noise: previous,
      };
      const report = renderReport(suites, meta);
      const file = process.argv[4] ?? 'report.md';
      writeFileSync(file, report);
      if (suites.some(suite => suite.aa)) {
        writeFileSync(
          join(dirname(file), 'noise.json'),
          `${JSON.stringify(noiseFile(suites, previous), null, 2)}\n`,
        );
      }
      if (env.GITHUB_STEP_SUMMARY)
        appendFileSync(env.GITHUB_STEP_SUMMARY, report);
    } else {
      throw new Error(
        `usage: bench.mjs plan | matrix | suite <name> | report <dir> [report.md]`,
      );
    }
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exitCode = 1;
  }
}
