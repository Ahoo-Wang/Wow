/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The table of an ABBA comparison on one machine: `bench.sh abba` (`node .github/scripts/bench-abba.mjs <root>`). The
// root holds a directory per sweep and arm, `<sweep>-<arm>-<backend>/sweep`, each a closed-loop sweep's
// saturation.json, its stamped output (saturate.log: each line's epoch seconds first) and its nodes' logs. For each
// backend and step, the table gives each arm's throughput a sweep, its median against the first arm's (the ratio of
// the medians, and the lowest over the highest to the highest over the lowest), each arm's own spread (its sweeps'
// range over their median, the band a ratio must clear), the refused share, p50 and p99; then, from the nodes'
// `bench-pipeline` lines over the step's measured window, the append calls a second and the streams a call, the
// append clock's call and statement, the lanes' groups waiting, and the subscriptions' widest window.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const SESSION = /^(\d+)-(.+)-(postgres|mongo)$/;

/** The host's foreign cores, a step's window's mean, at which the step counts as disturbed (the snapshot's bar). */
export const DISTURBED_CORES = 2;
const STEP_LINE = /^(\d+) .*\[saturate\] \S+: concurrency=(\d+) /;
const PIPELINE = /^(\S+)\s.*bench-pipeline (\{.*\})\s*$/;

const median = values => {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
};
const num = (value, digits = 0) =>
  Number.isFinite(value)
    ? value.toLocaleString('en-US', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      })
    : '-';

/** The sessions under [root]: each sweep's arm, backend, steps and the epoch second each step ended. */
export function sessionsOf(root) {
  const sessions = [];
  for (const entry of readdirSync(root).sort()) {
    const match = SESSION.exec(entry);
    const dir = join(root, entry, 'sweep');
    const file = join(dir, 'saturation.json');
    if (!match || !existsSync(file)) continue;
    const saturation = JSON.parse(readFileSync(file, 'utf8'));
    const ends = stepEnds(readIf(join(dir, 'saturate.log')));
    sessions.push({
      sweep: Number(match[1]),
      arm: match[2],
      backend: match[3],
      steps: saturation.steps.map(step => ({
        ...step,
        endedAt: ends[step.concurrency],
      })),
      pipeline: readdirSync(dir)
        .filter(name => /^node-\d+\.log$/.test(name))
        .flatMap(name => pipelineLines(readIf(join(dir, name)))),
    });
  }
  return sessions;
}

const readIf = file => (existsSync(file) ? readFileSync(file, 'utf8') : '');

/** The epoch second each step's line was printed at, its measured window's end, by its concurrency. */
export function stepEnds(log) {
  const ends = {};
  for (const line of log.split('\n')) {
    const match = STEP_LINE.exec(line);
    if (match) ends[Number(match[2])] = Number(match[1]);
  }
  return ends;
}

/** A node log's `bench-pipeline` lines: each one's epoch milliseconds and its sections, parsed as the JSON it is. */
export function pipelineLines(log) {
  const lines = [];
  for (const line of log.split('\n')) {
    const match = PIPELINE.exec(line);
    if (!match) continue;
    try {
      lines.push({ at: Date.parse(match[1]), sections: JSON.parse(match[2]) });
    } catch {
      // A line cut short, as a node's last before it stops: not a sample.
    }
  }
  return lines;
}

/**
 * The pipeline over a step's measured window ([endedAt] less its [seconds]): the append calls a second and the
 * streams a call, the clock's call and statement in milliseconds a call, the lanes' groups waiting (mean) and the
 * subscriptions' widest window (largest); empty without two lines in the window.
 */
export function pipelineOf(lines, endedAt, seconds) {
  if (!Number.isFinite(endedAt)) return {};
  const within = lines.filter(
    line => line.at >= (endedAt - seconds) * 1000 && line.at <= endedAt * 1000,
  );
  if (within.length < 2) return {};
  const [first, last] = [within[0].sections, within.at(-1).sections];
  const span = (within.at(-1).at - within[0].at) / 1000;
  const delta = (section, field) =>
    (last[section]?.[field] ?? NaN) - (first[section]?.[field] ?? NaN);
  const calls = delta('appendCalls', 'calls');
  const clocked = delta('appendClock', 'calls');
  const waiting = within
    .map(line => line.sections.appendDepth?.handedGroups)
    .filter(Number.isFinite);
  const windows = within.flatMap(line =>
    Object.values(line.sections.windowMax ?? {}),
  );
  return {
    callsPerSecond: calls / span,
    streamsPerCall: delta('appendCalls', 'streams') / calls,
    callMs: delta('appendClock', 'call') / clocked / 1000,
    statementMs: delta('appendClock', 'statement') / clocked / 1000,
    groupsWaiting: waiting.length
      ? waiting.reduce((a, b) => a + b, 0) / waiting.length
      : NaN,
    windowMax: windows.length ? Math.max(...windows) : NaN,
  };
}

const refusedShare = step => {
  const errors = Object.values(step.errors ?? {}).reduce((a, b) => a + b, 0);
  return step.ok + errors > 0 ? errors / (step.ok + errors) : 0;
};

/** The host's foreign CPU samples (abba_host's host.tsv): each second's epoch and foreign cores. */
export function hostSamples(tsv) {
  return tsv
    .split('\n')
    .slice(1)
    .map(line => line.split('\t'))
    .filter(fields => fields.length >= 2 && Number.isFinite(Number(fields[0])))
    .map(([epoch, cores]) => ({ epoch: Number(epoch), cores: Number(cores) }));
}

/** The mean foreign cores over a step's measured window; NaN without a sample in it. */
export function foreignOf(samples, endedAt, seconds) {
  const within = samples.filter(
    sample => sample.epoch >= endedAt - seconds && sample.epoch <= endedAt,
  );
  return within.length
    ? within.reduce((sum, sample) => sum + sample.cores, 0) / within.length
    : NaN;
}

/**
 * The table's rows: per backend and step, each arm's sweeps and their figures, in [order] (the arms as the command
 * named them, the first the base; arms.txt), else by name.
 */
export function rowsOf(sessions, order = [], host = []) {
  const named = [...new Set(sessions.map(session => session.arm))].sort();
  const arms = [
    ...order.filter(arm => named.includes(arm)),
    ...named.filter(arm => !order.includes(arm)),
  ];
  const rows = [];
  for (const backend of [
    ...new Set(sessions.map(session => session.backend)),
  ]) {
    const ofBackend = sessions.filter(session => session.backend === backend);
    const steps = [
      ...new Set(
        ofBackend.flatMap(session =>
          session.steps.map(step => step.concurrency),
        ),
      ),
    ].sort((a, b) => a - b);
    for (const concurrency of steps) {
      const of = arm =>
        ofBackend
          .filter(session => session.arm === arm)
          .sort((a, b) => a.sweep - b.sweep)
          .flatMap(session =>
            session.steps
              .filter(step => step.concurrency === concurrency)
              .map(step => ({
                ...step,
                pipeline: pipelineOf(
                  session.pipeline,
                  step.endedAt,
                  step.seconds,
                ),
                foreign: foreignOf(host, step.endedAt, step.seconds),
              })),
          );
      const base = of(arms[0]).map(step => step.throughput);
      for (const arm of arms) {
        const runs = of(arm);
        if (runs.length === 0) continue;
        const throughputs = runs.map(step => step.throughput);
        const mid = median(throughputs);
        const mean = key => {
          const values = runs
            .map(step => step.pipeline[key])
            .filter(Number.isFinite);
          return values.length
            ? values.reduce((a, b) => a + b, 0) / values.length
            : NaN;
        };
        rows.push({
          backend,
          concurrency,
          arm,
          throughputs,
          ratio:
            arm === arms[0] || base.length === 0 ? NaN : mid / median(base),
          range:
            arm === arms[0] || base.length === 0
              ? null
              : [
                  Math.min(...throughputs) / Math.max(...base),
                  Math.max(...throughputs) / Math.min(...base),
                ],
          spread:
            throughputs.length > 1
              ? (Math.max(...throughputs) - Math.min(...throughputs)) / mid
              : NaN,
          refused: Math.max(...runs.map(refusedShare)),
          p50: median(runs.map(step => step.p50Ms)),
          p99: median(runs.map(step => step.p99Ms)),
          callsPerSecond: mean('callsPerSecond'),
          streamsPerCall: mean('streamsPerCall'),
          callMs: mean('callMs'),
          statementMs: mean('statementMs'),
          groupsWaiting: mean('groupsWaiting'),
          windowMax: Math.max(
            ...runs
              .map(step => step.pipeline.windowMax)
              .filter(Number.isFinite),
            -Infinity,
          ),
          foreign: Math.max(
            ...runs.map(step => step.foreign).filter(Number.isFinite),
            -Infinity,
          ),
        });
      }
    }
  }
  return rows;
}

/** The Markdown table of [rows]. */
export function tableOf(rows) {
  const lines = [];
  for (const backend of [...new Set(rows.map(row => row.backend))]) {
    lines.push(
      `## ${backend}`,
      '',
      '| Clients | Arm | Throughput a sweep (/s) | × first arm (median, range) | Own spread | Refused (most) | p50 ms | p99 ms | Calls/s | Streams/call | Call ms | Statement ms | Groups waiting | Widest window | Foreign cores (most) |',
      '| ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
    );
    for (const row of rows.filter(each => each.backend === backend)) {
      const ratio = Number.isFinite(row.ratio)
        ? `${num(row.ratio, 3)} (${num(row.range[0], 3)}–${num(row.range[1], 3)})`
        : '-';
      lines.push(
        `| ${num(row.concurrency)} | ${row.arm} | ${row.throughputs.map(value => num(value)).join(', ')} | ${ratio} | ` +
          `${Number.isFinite(row.spread) ? `${num(row.spread * 100, 1)}%` : '-'} | ${num(row.refused * 100, 2)}% | ` +
          `${num(row.p50, 1)} | ${num(row.p99, 1)} | ${num(row.callsPerSecond)} | ${num(row.streamsPerCall, 1)} | ` +
          `${num(row.callMs, 2)} | ${num(row.statementMs, 2)} | ${num(row.groupsWaiting, 1)} | ` +
          `${Number.isFinite(row.windowMax) && row.windowMax >= 0 ? num(row.windowMax) : '-'} | ` +
          `${row.foreign >= 0 ? `${num(row.foreign, 2)}${row.foreign >= DISTURBED_CORES ? ' (disturbed)' : ''}` : '-'} |`,
      );
    }
    lines.push(
      '',
      `An arm's ratio counts only where its range clears both arms' own spreads; the pipeline columns are each sweep's mean over the step's measured window. A step whose window had ${DISTURBED_CORES} or more foreign cores on average (the host's processes other than the benchmark's) is disturbed: run it again.`,
      '',
    );
  }
  return lines.join('\n');
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const root = process.argv[2];
  if (!root) {
    console.error('usage: bench-abba.mjs <root>');
    process.exit(2);
  }
  const order = readIf(join(root, 'arms.txt')).split('\n').filter(Boolean);
  const host = hostSamples(readIf(join(root, 'host.tsv')));
  console.log(tableOf(rowsOf(sessionsOf(root), order, host)));
}
