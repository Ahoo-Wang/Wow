/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

// What bench.mjs and its kinds' modules share: running bench.sh, reading its JSON, and the summaries' formats. It
// imports none of them.

export const withProps = (arm, id, props) => ({ ...arm, id, props });

/** A suite's name with its data set, as the report shows it: `e1-mongo-uniform@100k`. */
export const labelOf = suite => `${suite.name}@${suite.dataset ?? '100k'}`;

/** The spread of values: max / min - 1, 0 for fewer than two. */
export function spread(values) {
  const present = values.filter(value => Number.isFinite(value) && value > 0);
  if (present.length < 2) return 0;
  return Math.max(...present) / Math.min(...present) - 1;
}

/** The arms of round `round` (0-based) in their order: ABBA. */
export function roundOrder(arms, round) {
  return round % 2 === 0 ? arms : [...arms].reverse();
}

export const ms = value =>
  Number.isFinite(value) ? `${value.toFixed(1)} ms` : '-';

export const num = (value, digits = 1) =>
  Number.isFinite(value) ? value.toFixed(digits) : '-';

export const propsText = props =>
  props && Object.keys(props).length > 0
    ? Object.entries(props)
        .map(([key, value]) => `${key}=${value}`)
        .join(', ')
    : 'defaults';

/** The anomalies of suites as a table, or nothing. */
export function anomalyTable(rows) {
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
export function bench(benchPath, args, props, handover) {
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

export const readJson = path => JSON.parse(readFileSync(path, 'utf8'));

/** A JSON file a step may not have written, or written partly (a store query that failed): its value, or undefined. */
export function readJsonIfThere(path) {
  try {
    return existsSync(path) ? readJson(path) : undefined;
  } catch {
    return undefined;
  }
}

/** The [q] quantile of [values] (nearest rank), null for none. */
export function quantile(values, q) {
  const finite = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (finite.length === 0) return null;
  return finite[
    Math.min(finite.length - 1, Math.max(0, Math.ceil(q * finite.length) - 1))
  ];
}

/** HikariCP's default pool size, which the benchmark servers' PostgreSQL profile leaves as it is. */
export const HIKARI_DEFAULT_POOL = 10;
const POOL_PROPERTY = 'spring.datasource.hikari.maximum-pool-size';

/**
 * The JDBC pool a PostgreSQL arm's servers run with: its server argument, else its property, else HikariCP's default;
 * undefined on another store. The order is Spring Boot's: a command-line argument overrides SPRING_APPLICATION_JSON,
 * which carries the arm's properties. Every PostgreSQL figure is quoted with it: appends, snapshot writes, checkpoints,
 * tail reads, loads and the saga's reads share it.
 */
export function poolOf(arm) {
  if (arm?.backend !== 'postgres') return undefined;
  const argument = (arm.serverArgs ?? '').match(
    /(?:^|\s)--spring\.datasource\.hikari\.maximum-pool-size=(\d+)(?:\s|$)/,
  )?.[1];
  return Number(argument ?? arm.props?.[POOL_PROPERTY] ?? HIKARI_DEFAULT_POOL);
}

/** [arm] with its pool recorded (`pool`), when it has one. */
export const withPool = arm =>
  poolOf(arm) === undefined ? arm : { ...arm, pool: poolOf(arm) };

/** A suite's arms' pools for its runners row: `-` without one. */
export const poolsText = suite =>
  (suite.arms ?? [])
    .filter(arm => arm.pool !== undefined)
    .map(arm => `${arm.id} ${arm.pool}`)
    .join(', ') || '-';

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
