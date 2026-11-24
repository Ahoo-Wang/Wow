/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// What an idle service costs its store: `pnpm bench:idle <out>` (`node .github/scripts/bench-idle.mjs <out>`), run by
// `bench.sh idle` (test/benchmarks/harness/idle.sh) on the raw inputs it wrote to <out>. It writes <out>/idle.json
// (schema `wow-idle/1`) and <out>/idle.txt:
//
// - the store's reads a second while the nodes idle, by operation: MongoDB's opcounters over the window and its
//   profiler's operations by namespace and command; PostgreSQL's statements (pg_stat_statements);
// - the log head reads a second among them: MongoDB's aggregations on a log's partition counters
//   (`wow_log__<context>__partition`), PostgreSQL's `SELECT sequence FROM "<...>__partition" WHERE partition = $1`;
// - the log counter reads a second, the head reads among them: every read of a log's partition counters, the node's
//   probe of its tails and the batched heads too (MongoDB's aggregations and finds on them, PostgreSQL's head
//   lookups and `SELECT partition, sequence FROM "<...>__partition" WHERE partition = ANY($1)`);
// - the store container's CPU, in percent of one core, with the nodes idle and with no node, and the difference;
// - the machine it ran on, as the session read it (chip, cores, memory, Docker's CPUs and memory).
//
// `pnpm bench:idle --environment <chip>` prints the environment a report is named after: `actions` on GitHub Actions,
// else the chip's name (`Apple M4 Pro` gives `m4pro`), so a report is named for the machine it ran on
// (`results/reports/<environment>-idle/`). `pnpm bench:idle --table <report>` prints the markdown table of a report's
// sessions, one row per `<session>/idle.json`.
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

/** A MongoDB counter as mongosh's JSON.stringify writes it: a number, or a Long `{low, high}`. */
const counter = value =>
  typeof value === 'number'
    ? value
    : (value.high ?? 0) * 2 ** 32 + ((value.low ?? 0) >>> 0);

/** `key=value` lines; `meta`'s value is itself `key=value;key=value`, kept as it is. */
export function parseSession(text) {
  const session = {};
  for (const line of text.split('\n')) {
    const at = line.indexOf('=');
    if (at > 0) session[line.slice(0, at)] = line.slice(at + 1);
  }
  return session;
}

/**
 * The environment a report is named after: `actions` on GitHub Actions ([actions]), else the chip's name in lower case
 * without its maker and spaces (`Apple M4 Pro` gives `m4pro`, `Apple M5 Max` gives `m5max`), else `local`.
 */
export function environmentName(chip, actions = false) {
  if (actions) return 'actions';
  const name = (chip ?? '')
    .replace(/^(apple|intel\(r\)|amd)\s+/i, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '');
  return name || 'local';
}

/** The machine a session read: `machine.*` lines of session.txt; null for a session that recorded none. */
export function machineOf(session) {
  if (!session['machine.chip']) return null;
  const number = key => (session[key] ? Number(session[key]) : null);
  return {
    chip: session['machine.chip'],
    cores: number('machine.cores'),
    performanceCores: number('machine.performanceCores'),
    efficiencyCores: number('machine.efficiencyCores'),
    memoryBytes: number('machine.memoryBytes'),
    os: session['machine.os'] ?? null,
    docker: {
      cpus: number('machine.dockerCpus'),
      memoryBytes: number('machine.dockerMemoryBytes'),
    },
  };
}

/** The machine as a report states it: `Apple M4 Pro, 14 cores (10 performance, 4 efficiency), 24 GB`. */
export function describeMachine(machine) {
  if (!machine) return 'machine not recorded';
  const split =
    machine.performanceCores && machine.efficiencyCores
      ? ` (${machine.performanceCores} performance, ${machine.efficiencyCores} efficiency)`
      : '';
  const gb = bytes => `${Math.round(bytes / 2 ** 30)} GB`;
  const docker = machine.docker.cpus
    ? `; Docker ${machine.docker.cpus} CPUs, ${gb(machine.docker.memoryBytes)}`
    : '';
  return `${machine.chip}, ${machine.cores} cores${split}, ${gb(machine.memoryBytes)}${docker}`;
}

/** The mean of the numbers in [text], one a line, and how many there were; null with none. */
export function meanOf(text) {
  const samples = text
    .split('\n')
    .map(line => Number.parseFloat(line))
    .filter(Number.isFinite);
  if (samples.length === 0) return null;
  return {
    mean: samples.reduce((a, b) => a + b, 0) / samples.length,
    samples: samples.length,
  };
}

const round = (value, places = 2) =>
  Math.round(value * 10 ** places) / 10 ** places;

/** A log's partition counters: the collection or table every head read starts from. */
const PARTITIONS = /^wow_log__.+__partition$/;
const HEAD_STATEMENT =
  /^SELECT sequence FROM "?(wow_log__[^"\s]+__partition)"? WHERE partition = \$1$/;
/** The batched heads and PostgreSQL's probe of the tails: the counter rows of several partitions in one statement. */
const HEADS_STATEMENT =
  /^SELECT partition, sequence FROM "?(wow_log__[^"\s]+__partition)"? WHERE partition = ANY\(\$1\)$/;

/** MongoDB's reads a second: the opcounters over the window, and the profiler's operations, the head reads apart. */
export function mongoReads(opcounters, profile) {
  const seconds = opcounters.millis / 1000;
  const counters = Object.fromEntries(
    Object.keys(opcounters.after).map(name => [
      name,
      round(
        (counter(opcounters.after[name]) - counter(opcounters.before[name])) /
          seconds,
      ),
    ]),
  );
  const profiled = profile.millis / 1000;
  const operations = profile.operations.map(operation => {
    const counted = PARTITIONS.test(
      operation.ns.slice(operation.ns.indexOf('.') + 1),
    );
    return {
      operation: `${operation.op} ${operation.command ?? ''} ${operation.ns}`
        .replace(/\s+/g, ' ')
        .trim(),
      perSecond: profiled > 0 ? round(operation.count / profiled) : null,
      head:
        counted &&
        operation.op === 'command' &&
        operation.command === 'aggregate',
      counter:
        counted &&
        ((operation.op === 'command' && operation.command === 'aggregate') ||
          (operation.op === 'query' && operation.command === 'find')),
    };
  });
  const sum = key =>
    operations
      .filter(o => o[key])
      .reduce((total, o) => total + (o.perSecond ?? 0), 0);
  return {
    opcountersPerSecond: counters,
    profiledSeconds: round(profiled),
    logHeadReadsPerSecond: round(sum('head')),
    logCounterReadsPerSecond: round(sum('counter')),
    operations: operations.map(({ operation, perSecond }) => ({
      operation,
      perSecond,
    })),
  };
}

/** PostgreSQL's statements a second over the window, the head reads apart. */
export function postgresReads(statements, millis) {
  const seconds = millis / 1000;
  const rows = statements
    .split('\n')
    .filter(line => line.includes('\t'))
    .map(line => {
      const at = line.indexOf('\t');
      return {
        calls: Number(line.slice(0, at)),
        statement: line.slice(at + 1),
      };
    })
    .filter(row => !/pg_stat_statements/.test(row.statement));
  const calls = matches =>
    rows
      .filter(row => matches(row.statement))
      .reduce((sum, row) => sum + row.calls, 0);
  const heads = calls(it => HEAD_STATEMENT.test(it));
  const counters = calls(
    it => HEAD_STATEMENT.test(it) || HEADS_STATEMENT.test(it),
  );
  const total = calls(() => true);
  return {
    windowSeconds: round(seconds),
    statementsPerSecond: round(total / seconds),
    logHeadReadsPerSecond: round(heads / seconds),
    logCounterReadsPerSecond: round(counters / seconds),
    operations: rows.map(row => ({
      operation: row.statement.slice(0, 160),
      perSecond: round(row.calls / seconds),
    })),
  };
}

/** idle.json from the raw inputs of one `bench.sh idle` session. */
export function summarize({
  session,
  cpuWithNodes,
  cpuWithoutNodes,
  opcounters,
  profile,
  statements,
  statementsMillis,
}) {
  const parsed = parseSession(session);
  const withNodes = meanOf(cpuWithNodes);
  const withoutNodes = meanOf(cpuWithoutNodes);
  const reads =
    parsed.backend === 'mongo'
      ? mongoReads(opcounters, profile)
      : postgresReads(statements, statementsMillis);
  return {
    schema: 'wow-idle/1',
    commit: parsed.commit,
    backend: parsed.backend,
    nodes: Number(parsed.nodes),
    accounts: Number(parsed.accounts),
    serverArgs: parsed.serverArgs,
    quietSeconds: Number(parsed.quietSeconds),
    windowSeconds: Number(parsed.windowSeconds),
    meta: parsed.meta,
    environment: environmentName(
      parsed['machine.chip'],
      parsed['machine.actions'] === 'true',
    ),
    machine: machineOf(parsed),
    storeCpu: {
      withNodes: withNodes && round(withNodes.mean),
      withoutNodes: withoutNodes && round(withoutNodes.mean),
      added:
        withNodes && withoutNodes
          ? round(withNodes.mean - withoutNodes.mean)
          : null,
      samples: {
        withNodes: withNodes?.samples ?? 0,
        withoutNodes: withoutNodes?.samples ?? 0,
      },
    },
    logHeadReadsPerSecond: reads.logHeadReadsPerSecond,
    logCounterReadsPerSecond: reads.logCounterReadsPerSecond,
    reads,
  };
}

/** idle.txt: the summary a person reads. */
export function render(idle) {
  const lines = [
    `${idle.backend}, ${idle.nodes} node(s), ${idle.accounts} accounts, ${idle.serverArgs} (commit ${idle.commit})`,
    `machine: ${describeMachine(idle.machine)}`,
    `log head reads: ${idle.logHeadReadsPerSecond}/s`,
    `log counter reads (the head reads, the probe and the batched heads): ${idle.logCounterReadsPerSecond}/s`,
    `store CPU (% of one core): ${idle.storeCpu.withNodes} with the nodes idle, ${idle.storeCpu.withoutNodes} with no node, ` +
      `${idle.storeCpu.added} added by the nodes (${idle.storeCpu.samples.withNodes} and ${idle.storeCpu.samples.withoutNodes} samples)`,
  ];
  if (idle.reads.opcountersPerSecond) {
    lines.push(
      `opcounters a second: ${Object.entries(idle.reads.opcountersPerSecond)
        .map(([name, value]) => `${name} ${value}`)
        .join(', ')}`,
    );
  } else {
    lines.push(`statements a second: ${idle.reads.statementsPerSecond}`);
  }
  lines.push('operations a second:');
  for (const { operation, perSecond } of idle.reads.operations.slice(0, 15))
    lines.push(`  ${perSecond}  ${operation}`);
  return `${lines.join('\n')}\n`;
}

/** The markdown table of [sessions] (idle.json each, by session name), in their order. */
export function table(sessions) {
  const rows = [
    '| Session | Server arguments | Log head reads a second | Store CPU, nodes idle | No node | Added by the nodes | Other reads a second |',
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const [name, idle] of sessions) {
    const total = idle.reads.opcountersPerSecond
      ? Object.values(idle.reads.opcountersPerSecond).reduce((a, b) => a + b, 0)
      : idle.reads.statementsPerSecond;
    rows.push(
      `| ${name} | \`${idle.serverArgs}\` | ${idle.logHeadReadsPerSecond} | ${idle.storeCpu.withNodes} % | ` +
        `${idle.storeCpu.withoutNodes} % | ${idle.storeCpu.added} | ${round(total - idle.logHeadReadsPerSecond)} |`,
    );
  }
  return `${rows.join('\n')}\n`;
}

function main(args) {
  const [first, value] = args;
  if (first === '--environment') {
    process.stdout.write(
      `${environmentName(value, process.env.GITHUB_ACTIONS === 'true')}\n`,
    );
    return;
  }
  if (first === '--table') {
    const sessions = readdirSync(value, { withFileTypes: true })
      .filter(
        entry =>
          entry.isDirectory() &&
          existsSync(join(value, entry.name, 'idle.json')),
      )
      .map(entry => [
        entry.name,
        JSON.parse(readFileSync(join(value, entry.name, 'idle.json'), 'utf8')),
      ]);
    process.stdout.write(table(sessions));
    return;
  }
  const out = first;
  if (!out) {
    console.error(
      'usage: node .github/scripts/bench-idle.mjs <out> | --environment <chip> | --table <report>',
    );
    process.exit(2);
  }
  const read = name => readFileSync(join(out, name), 'utf8');
  const optional = name => (existsSync(join(out, name)) ? read(name) : null);
  const idle = summarize({
    session: read('session.txt'),
    cpuWithNodes: read('cpu-with-nodes.txt'),
    cpuWithoutNodes: read('cpu-without-nodes.txt'),
    opcounters:
      optional('opcounters.json') && JSON.parse(read('opcounters.json')),
    profile: optional('profile.json') && JSON.parse(read('profile.json')),
    statements: optional('statements.tsv'),
    statementsMillis:
      optional('statements.json') && JSON.parse(read('statements.json')).millis,
  });
  writeFileSync(join(out, 'idle.json'), `${JSON.stringify(idle, null, 2)}\n`);
  const text = render(idle);
  writeFileSync(join(out, 'idle.txt'), text);
  process.stdout.write(text);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href)
  main(process.argv.slice(2));
