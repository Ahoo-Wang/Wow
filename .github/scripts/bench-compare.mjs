/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The tables of an ABAB on one machine: `bench.sh compare <root>` (`node .github/scripts/bench-compare.mjs <root>`). The root holds a
// directory per session named `<round>-<arm>-<backend>` (`1-A-postgres`, `2-B-mongo`), and each session a directory
// per phase: a fixed-rate run (`result.json`) or a closed-loop sweep (`saturation.json`); a heap histogram beside a
// phase (`<phase>.hist`, from `jcmd GC.class_histogram`) adds the states its node's caches held. For each backend and
// phase, the tables give every session's numbers, by arm and round, and each arm's ratio to the first arm, from the
// medians of their rounds. The per-operation costs are what a run measures best (stage 0: within a few percent); a
// session's tails are its disk's.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const SESSION = /^(\d+)-([^-]+)-(.+)$/;

/** Metrics that count or place rather than cost: shown as whole numbers, never as a ratio. */
const COUNTS = new Set([
  'failed',
  'peak at',
  'peak is the top step',
  'states cached',
]);

/** The store counters a run's metrics take per operation, by store. */
const STORE_COUNTERS = {
  postgres: ['xact_commit', 'tup_fetched', 'tup_inserted', 'tup_updated'],
  mongo: ['insert', 'query', 'update', 'command'],
};

/** A fixed-rate run's metrics: per operation unless named otherwise. */
export function runMetrics(result) {
  const results = result.results;
  const resources = result.resources;
  const ops = results.ok;
  const metrics = {
    throughput: results.throughput,
    failed: results.failed,
    'p50 ms': results.latencyMs.p50,
    'p99 ms': results.latencyMs.p99,
  };
  for (const [name, node] of Object.entries(resources.processes ?? {})) {
    metrics[`${name} CPU ms`] = (node.cpuSeconds * 1000) / ops;
    if (node.allocatedBytes != null)
      metrics[`${name} KB allocated`] = node.allocatedBytes / ops / 1024;
  }
  for (const [name, container] of Object.entries(resources.containers ?? {})) {
    const short = name.split('-').at(-1);
    metrics[`${short} CPU ms`] =
      ((container.cpuPercentAverage / 100) * resources.seconds * 1000) / ops;
  }
  for (const [store, counters] of Object.entries(STORE_COUNTERS)) {
    const sampled = resources.stores?.[store];
    if (!sampled?.before || !sampled?.after) continue;
    for (const counter of counters) {
      if (counter in sampled.after && counter in sampled.before) {
        metrics[`${store} ${counter}`] =
          (sampled.after[counter] - sampled.before[counter]) / ops;
      }
    }
  }
  return metrics;
}

/** A sweep's metrics: its peak, marked as a lower bound when the peak is the top step. */
export function sweepMetrics(saturation) {
  const steps = saturation.steps ?? [];
  const top = steps.at(-1)?.concurrency;
  return {
    peak: saturation.saturationThroughput,
    'peak at': saturation.saturationConcurrency,
    'peak is the top step': saturation.saturationConcurrency === top ? 1 : 0,
  };
}

/** The states the caches of a heap histogram hold: the instances of `CachedState`. */
export function cachedStates(histogram) {
  const line = histogram
    .split('\n')
    .find(it => /\sme\.ahoo\.wow\.kernel\.CachedState(\s|$)/.test(it));
  return line ? Number(line.trim().split(/\s+/)[1]) : undefined;
}

/** Every session under [root]: its round, arm, backend and the metrics of each phase. */
export function readSessions(root) {
  return readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && SESSION.test(entry.name))
    .map(entry => {
      const [, round, arm, backend] = SESSION.exec(entry.name);
      const dir = join(root, entry.name);
      const phases = {};
      for (const phase of readdirSync(dir, { withFileTypes: true }).filter(it =>
        it.isDirectory(),
      )) {
        const at = join(dir, phase.name);
        let metrics;
        if (existsSync(join(at, 'result.json'))) {
          metrics = runMetrics(
            JSON.parse(readFileSync(join(at, 'result.json'), 'utf8')),
          );
        } else if (existsSync(join(at, 'saturation.json'))) {
          metrics = sweepMetrics(
            JSON.parse(readFileSync(join(at, 'saturation.json'), 'utf8')),
          );
        } else {
          continue;
        }
        const histogram = join(dir, `${phase.name}.hist`);
        if (existsSync(histogram))
          metrics['states cached'] = cachedStates(
            readFileSync(histogram, 'utf8'),
          );
        phases[phase.name] = metrics;
      }
      return { round: Number(round), arm, backend, phases };
    });
}

export function median(values) {
  const sorted = values.filter(it => Number.isFinite(it)).sort((a, b) => a - b);
  if (sorted.length === 0) return undefined;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
}

function format(value, name) {
  if (value == null || !Number.isFinite(value)) return '-';
  if (COUNTS.has(name)) return Math.round(value).toLocaleString('en-US');
  if (Math.abs(value) >= 100) return Math.round(value).toLocaleString('en-US');
  if (Math.abs(value) >= 10) return value.toFixed(1);
  return value.toFixed(3);
}

/** The Markdown tables of [sessions]: one per backend and phase. */
export function renderTables(sessions) {
  const arms = [...new Set(sessions.map(it => it.arm))].sort();
  const lines = [];
  for (const backend of [...new Set(sessions.map(it => it.backend))].sort()) {
    const ofBackend = sessions
      .filter(it => it.backend === backend)
      .sort(
        (a, b) =>
          arms.indexOf(a.arm) - arms.indexOf(b.arm) || a.round - b.round,
      );
    const phases = [
      ...new Set(ofBackend.flatMap(it => Object.keys(it.phases))),
    ].sort();
    for (const phase of phases) {
      const columns = ofBackend.filter(it => it.phases[phase]);
      const names = [
        ...new Set(columns.flatMap(it => Object.keys(it.phases[phase]))),
      ];
      const ratios = arms
        .slice(1)
        .filter(arm => columns.some(it => it.arm === arm));
      lines.push(`### ${backend}, ${phase}`, '');
      lines.push(
        `| | ${columns.map(it => `${it.arm} (${it.round})`).join(' | ')} |` +
          ratios.map(arm => ` ${arm}/${arms[0]} |`).join(''),
      );
      lines.push(`| --- |${' --- |'.repeat(columns.length + ratios.length)}`);
      for (const name of names) {
        const values = columns.map(it => it.phases[phase][name]);
        const base = median(
          columns
            .filter(it => it.arm === arms[0])
            .map(it => it.phases[phase][name]),
        );
        const cells = ratios.map(arm => {
          if (COUNTS.has(name) && name !== 'states cached') return '';
          const value = median(
            columns
              .filter(it => it.arm === arm)
              .map(it => it.phases[phase][name]),
          );
          return base ? format(value / base) : '-';
        });
        const shown = values.map(it => format(it, name)).join(' | ');
        lines.push(
          `| ${name} | ${shown} |${cells.map(it => ` ${it} |`).join('')}`,
        );
      }
      lines.push('');
    }
  }
  return lines.join('\n');
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const root = process.argv[2];
  if (!root) {
    console.error(
      'usage: node bench-compare.mjs <root of the sessions, each <round>-<arm>-<backend>>',
    );
    process.exit(2);
  }
  console.log(renderTables(readSessions(root)));
}
