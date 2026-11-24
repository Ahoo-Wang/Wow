/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import {
  anomalyTable,
  bench,
  labelOf,
  num,
  readJson,
} from './bench-common.mjs';

// What idle nodes cost their store, on a runner (#6702): bench.sh idle (test/benchmarks/harness/idle.sh) for each arm
// at one node and at three, its idle.json (bench-idle.mjs) read back. Its suites run in bench-measure-suites.mjs's
// `measure` kind; bench-measure.mjs dispatches here by IDLE_PROGRAMS. The head reads a second are the store's own count,
// exact on a runner; the CPU is a shared runner's, which the report says beside it. It imports bench-common.mjs alone.

export const IDLE_PROGRAMS = ['idle'];

/** The node counts each arm runs idle at: one, and three, whose head reads must not grow with the nodes. */
export const IDLE = { nodes: [1, 3] };

/**
 * How far an arm's three nodes may read the log heads past its one node before it is an anomaly: a tenth, and half a
 * read a second, for a count taken over a minute. A node probes its own partitions only, so three split one's reads.
 */
export const HEAD_READS_SLACK = { ratio: 1.1, perSecond: 0.5 };

/** The idle suites on the head's and the baseline's PostgreSQL and MongoDB arms. */
export function idleSuites(HEAD_PG, BASE_PG, HEAD_MONGO, BASE_MONGO) {
  const answers = backend =>
    `What idle nodes cost their store on ${backend} (#6702): bench.sh idle, the baseline's and the head's nodes at one node and at three with nothing sent to them, the store's reads a second by operation and the log head reads among them, and the store's CPU with the nodes idle and with no node. The reads are counted by the store and exact on a runner; the CPU is a shared runner's, read beside its own no-node figure and never quoted as this store's cost.`;
  return {
    'idle-pg': {
      group: 'idle',
      kind: 'measure',
      measure: 'idle',
      arms: [BASE_PG, HEAD_PG],
      answers: answers('PostgreSQL'),
    },
    'idle-mongo': {
      group: 'idle',
      kind: 'measure',
      measure: 'idle',
      arms: [BASE_MONGO, HEAD_MONGO],
      answers: answers('MongoDB'),
    },
  };
}

/** bench.sh idle's arguments for [arm] at [nodes] nodes, its servers on the layout's server CPUs, into [dir]. */
export function idleArgs(arm, nodes, installs, cpuset, dir) {
  return [
    'idle',
    `--backend=${arm.backend}`,
    `--nodes=${nodes}`,
    ...(installs[arm.install] ? [`--install=${installs[arm.install]}`] : []),
    ...(cpuset.server ? [`--server-cpus=${cpuset.server}`] : []),
    `--out=${dir}`,
  ];
}

/** Runs an idle suite into [suite]: each arm at each node count, its idle.json read back. */
export function runIdle(
  definition,
  suite,
  { benchPath, out, cpuset, arms, installs, save },
) {
  suite.nodes = IDLE.nodes;
  for (const nodes of IDLE.nodes) {
    for (const arm of arms) {
      const name = `${arm.id}-${nodes}`;
      const run = { name, arm: arm.id, nodes, status: 'failed' };
      if (arm.install === 'base' && !installs.base) {
        // No baseline given: the head's runs alone.
        run.status = 'unavailable';
        suite.runs.push(run);
        save();
        continue;
      }
      const dir = join(out, name);
      const status = bench(
        benchPath,
        idleArgs(arm, nodes, installs, cpuset, dir),
        arm.props,
      );
      const file = join(dir, 'idle.json');
      if (status === 0 && existsSync(file)) {
        run.status = 'ok';
        run.idle = readJson(file);
      } else {
        suite.failures++;
      }
      suite.runs.push(run);
      save();
    }
  }
}

/** The run of [arm] at [nodes] nodes that succeeded, if any. */
const okRun = (suite, arm, nodes) =>
  suite.runs.find(
    it => it.arm === arm && it.nodes === nodes && it.status === 'ok',
  );

/**
 * The anomalies of an idle suite: a run that failed, a store CPU without its no-node figure, and an arm whose three
 * nodes read the log heads more than its one node did (past [HEAD_READS_SLACK]).
 */
export function idleAnomalies(suite) {
  const rows = [];
  for (const run of suite.runs) {
    const row = (kind, detail) =>
      rows.push({
        suite: labelOf(suite),
        run: run.name,
        context: 'idle',
        kind,
        detail,
      });
    if (run.status === 'failed')
      row('run failed', 'bench.sh idle failed; see the job log');
    if (run.status === 'ok' && !Number.isFinite(run.idle?.storeCpu?.added))
      row('no CPU baseline', 'the store was not measured with no node');
    const one = run.nodes === 3 && okRun(suite, run.arm, 1);
    if (run.status === 'ok' && one) {
      const three = run.idle.logHeadReadsPerSecond;
      const single = one.idle.logHeadReadsPerSecond;
      if (
        three >
        single * HEAD_READS_SLACK.ratio + HEAD_READS_SLACK.perSecond
      )
        row(
          'three nodes read more than one',
          `${num(three, 2)} log head reads a second at three nodes, ${num(single, 2)} at one: the nodes' probes do not split the partitions' reads`,
        );
    }
  }
  return rows;
}

const otherReads = idle => {
  const total = idle.reads.opcountersPerSecond
    ? Object.values(idle.reads.opcountersPerSecond).reduce((a, b) => a + b, 0)
    : idle.reads.statementsPerSecond;
  return total - idle.logHeadReadsPerSecond;
};

/** The table of an idle suite's runs, by node count then arm, with each arm's head reads against the first arm's. */
export function idleTable(suite) {
  const lines = [
    '| Run | Arm | Nodes | Log head reads a second | Other reads a second | Store CPU, nodes idle (shared runner) | No node | Added by the nodes |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const run of suite.runs) {
    if (run.status !== 'ok') {
      lines.push(
        `| ${run.name} | \`${run.arm}\` | ${run.nodes} | ${run.status} | | | | |`,
      );
      continue;
    }
    const { idle } = run;
    lines.push(
      `| ${run.name} | \`${run.arm}\` | ${run.nodes} | ${num(idle.logHeadReadsPerSecond, 2)} | ${num(otherReads(idle), 2)} | ` +
        `${num(idle.storeCpu.withNodes, 1)} % | ${num(idle.storeCpu.withoutNodes, 1)} % | ${num(idle.storeCpu.added, 1)} |`,
    );
  }
  return lines;
}

/** The busiest operations of each run, the head reads among them, as idle.json lists them. */
function operationsText(suite) {
  const lines = [];
  for (const run of suite.runs.filter(it => it.status === 'ok')) {
    const top = run.idle.reads.operations.slice(0, 5);
    lines.push(
      `- \`${run.name}\`: ${top.map(({ operation, perSecond }) => `${num(perSecond, 2)}/s ${operation}`).join('; ')}`,
    );
  }
  return lines;
}

/** The Markdown summary of an idle suite, [definition] its SUITES entry. */
export function renderIdle(suite, definition) {
  const machine = suite.runs.find(it => it.status === 'ok')?.idle?.machine;
  const lines = [
    `## ${labelOf(suite)}`,
    '',
    definition?.answers ?? '',
    '',
    `Runner: ${suite.runner?.cpu ?? machine?.chip ?? '?'}, ${suite.runner?.cores ?? machine?.cores ?? '?'} cores. Each run: 100 accounts seeded, 45 s quiet, the store measured over 60 s with the nodes idle and over 60 s with no node.`,
    '',
    "**The store CPU here is a shared runner's.** Read it against the same run's no-node figure, never as this store's cost; the log head reads a second are the store's own count and exact.",
    '',
    ...idleTable(suite),
    '',
    'The busiest operations a second:',
    '',
    ...operationsText(suite),
    '',
  ];
  const found = anomalyTable(idleAnomalies(suite));
  if (found.length > 0) lines.push('Anomalies:', '', ...found, '');
  if (suite.failures > 0)
    lines.push(`**${suite.failures} run(s) failed**; see the job log.`, '');
  return `${lines.join('\n')}\n`;
}
