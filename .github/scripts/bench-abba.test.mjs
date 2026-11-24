/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  DISTURBED_CORES,
  containerCoresOf,
  profileText,
  foreignOf,
  hostSamples,
  pipelineLines,
  pipelineOf,
  rowsOf,
  sessionsOf,
  stepEnds,
  tableOf,
  buildsText,
  installOf,
  primedText,
} from './bench-abba.mjs';

const END = 1_800_000_000;

/** A node log line of the `bench-pipeline` line at epoch second [second], local time with its offset. */
const pipeline = (second, sections) =>
  `${new Date(second * 1000).toISOString().replace('Z', '+00:00')}  WARN 1 --- [x] [ bench-pipeline] m.a.w.b.t.BenchPipeline : bench-pipeline ${JSON.stringify({ t: 1, ...sections })}`;

/** A sweep's directory under [root]: one step at 1,024 clients ending at END, [throughput] and [errors] its result. */
function sweep(root, name, throughput, errors = {}, calls = 100) {
  const dir = join(root, name, 'sweep');
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, 'saturation.json'),
    JSON.stringify({
      steps: [
        {
          concurrency: 1024,
          seconds: 30,
          throughput,
          ok: throughput * 30,
          errors,
          p50Ms: 40,
          p99Ms: 120,
        },
      ],
    }),
  );
  writeFileSync(
    join(dir, 'saturate.log'),
    `${END - 31} [saturate] warming\n${END} [saturate] E1: concurrency=1024 throughput=${throughput}/s p50=40ms errors={}\n`,
  );
  const lines = [0, 10, 20, 30].map(offset =>
    pipeline(END - 30 + offset, {
      appendDepth: { handedGroups: 4 },
      appendCalls: {
        calls: (calls * offset) / 10,
        streams: (calls * offset * 15) / 10,
      },
      appendClock: {
        calls: (calls * offset) / 10,
        call: (calls * offset * 3000) / 10,
        statement: (calls * offset * 2500) / 10,
      },
      windowMax: { 'wow.snapshot': offset * 100 },
    }),
  );
  writeFileSync(
    join(dir, 'node-1.log'),
    `${lines.join('\n')}\n${'x'.repeat(5)} bench-pipeline {"cut`,
  );
}

test("a sweep's steps end at their lines' stamps, and a node's pipeline lines are read as JSON, a cut one left out", () => {
  assert.deepEqual(
    stepEnds(
      `12 [saturate] E1: concurrency=4 throughput=1/s\n15 [saturate] E3: concurrency=16 x\n`,
    ),
    { 4: 12, 16: 15 },
  );
  const lines = pipelineLines(
    `${pipeline(END, { appendCalls: { calls: 3 } })}\nnoise\nbench-pipeline {"cut`,
  );
  assert.equal(lines.length, 1);
  assert.equal(lines[0].at, END * 1000);
  assert.equal(lines[0].sections.appendCalls.calls, 3);
});

test("the pipeline over a step is its window's calls, streams a call, clock, groups waiting and widest window", () => {
  const lines = [0, 30].map(
    offset =>
      pipelineLines(
        pipeline(END - 30 + offset, {
          appendDepth: { handedGroups: offset ? 6 : 2 },
          appendCalls: { calls: offset * 10, streams: offset * 150 },
          appendClock: {
            calls: offset * 10,
            call: offset * 30_000,
            statement: offset * 25_000,
            commanded: offset * 10,
            command: offset * 20_000,
          },
          snapshotClock: {
            saves: offset * 40,
            snapshots: offset * 40,
            writing: offset ? 2 : 1,
            calls: offset * 4,
            call: offset * 8_000,
            statement: offset * 6_000,
            commanded: offset * 4,
            command: offset * 5_000,
          },
          windowMax: { a: offset, b: 7 },
        }),
      )[0],
  );
  const of = pipelineOf(lines, END, 30);
  assert.equal(of.callsPerSecond, 10);
  assert.equal(of.streamsPerCall, 15);
  assert.equal(of.callMs, 3);
  assert.equal(of.statementMs, 2.5);
  // On MongoDB a clock's statement less the driver's commands: 2.5 ms less 2 ms an append, 1.5 less 1.25 a write.
  assert.equal(of.appendDriverMs, 0.5);
  assert.equal(of.snapshotsPerWrite, 10);
  assert.equal(of.writeCallMs, 2);
  assert.equal(of.writeStatementMs, 1.5);
  assert.equal(of.writeDriverMs, 0.25);
  assert.equal(of.leadersWriting, 1.5);
  assert.equal(of.groupsWaiting, 4);
  assert.equal(of.windowMax, 30);
  assert.deepEqual(pipelineOf(lines.slice(1), END, 30), {});
  assert.deepEqual(pipelineOf(lines, undefined, 30), {});
});

test('an ABBA root gives each step its arms a sweep, the ratio to the first, each own spread and the refused share', () => {
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  sweep(root, '1-branch-postgres', 11_000, {
    'http-429-wow.command.busy': 3_300,
  });
  sweep(root, '2-branch-postgres', 11_400);
  sweep(root, '2-main-postgres', 10_200);
  mkdirSync(join(root, 'not-a-session'));
  const sessions = sessionsOf(root);
  assert.equal(sessions.length, 4);
  const rows = rowsOf(sessions, ['main', 'branch']);
  assert.deepEqual(
    rowsOf(sessions).map(row => row.arm),
    ['branch', 'main'],
  );
  assert.deepEqual(
    rows.map(row => [row.arm, row.throughputs]),
    [
      ['main', [10_000, 10_200]],
      ['branch', [11_000, 11_400]],
    ],
  );
  const [main, branch] = rows;
  assert.ok(Number.isNaN(main.ratio));
  assert.equal(branch.ratio, 11_200 / 10_100);
  assert.deepEqual(branch.range, [11_000 / 10_200, 11_400 / 10_000]);
  assert.equal(main.spread, 200 / 10_100);
  assert.equal(branch.refused, 3_300 / (330_000 + 3_300));
  assert.equal(main.refused, 0);
  assert.equal(main.streamsPerCall, 15);
  assert.equal(main.callMs, 3);
  assert.equal(main.statementMs, 2.5);
  assert.equal(main.windowMax, 3_000);
  const table = tableOf(rows);
  assert.match(table, /^## postgres/m);
  assert.match(
    table,
    /\| 1,024 \| branch \| 11,000, 11,400 \| 1\.109 \(1\.078–1\.140\) \| 3\.6% \| 0\.99% \|/,
  );
  assert.match(
    table,
    /\| 1,024 \| main \| 10,000, 10,200 \| - \| 2\.0% \| 0\.00% \| 40\.0 \| 120\.0 \| 10 \| 15\.0 \| 3\.00 \| 2\.50 \| - \| - \| - \| - \| - \| - \| 4\.0 \| 3,000 \| - \|/,
  );
});

test("a step's foreign cores are the host's mean over its window, and a busy one is marked disturbed", () => {
  const samples = hostSamples(
    `epoch\tforeignCores\ttop\n${END - 40}\t9\tx\n${END - 20}\t1\ty\n${END}\t3\tz\nnoise\n`,
  );
  assert.equal(samples.length, 3);
  assert.deepEqual(foreignOf(samples, END, 30), { mean: 2, worst: 3 });
  assert.ok(Number.isNaN(foreignOf(samples, END - 100, 30).mean));
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  sweep(root, '1-branch-postgres', 10_100);
  const rows = rowsOf(sessionsOf(root), ['main', 'branch'], samples);
  assert.equal(rows[0].foreign, 2 * DISTURBED_CORES);
  assert.equal(rows[0].foreignWorst, 3);
  assert.match(tableOf(rows), /\| 2\.00, 3\.00 \(disturbed\) \|/);
});

test("a step's store and Kafka cores are their containers' CPU over its window, and the profile says what was pinned", () => {
  const samples = hostSamples(
    'epoch\tforeignCores\ttop\tcontainers\n' +
      `${END - 30}\t0.1\t\twow-e2e-bench-postgres=1000000,wow-e2e-bench-kafka=500000,\n` +
      `${END}\t0.2\t\twow-e2e-bench-postgres=28000000,wow-e2e-bench-kafka=3500000,\n`,
  );
  assert.equal(
    containerCoresOf(samples, 'wow-e2e-bench-postgres', END, 30),
    0.9,
  );
  assert.equal(containerCoresOf(samples, 'wow-e2e-bench-kafka', END, 30), 0.1);
  assert.ok(
    Number.isNaN(containerCoresOf(samples, 'wow-e2e-bench-mongo', END, 30)),
  );
  const root = mkdtempSync(join(tmpdir(), 'bench-abba-'));
  sweep(root, '1-main-postgres', 10_000);
  const rows = rowsOf(sessionsOf(root), ['main'], samples);
  assert.equal(rows[0].storeCores, 0.9);
  assert.match(tableOf(rows), /\| 0\.90 \| 0\.10 \| 0\.15, 0\.20 \|/);
  assert.equal(profileText(''), '');
  assert.match(
    tableOf(rows, 'constrained'),
    /^Profile: constrained\. The store and Kafka each ran on one CPU/,
  );
});

test("a session's install.txt gives its builds in short words, and the table names each arm's distinct ones", () => {
  assert.deepEqual(
    installOf(
      'server=commit=53208e5e49abcdef dirty=no\nload=commit=1009297e71abcdef dirty=yes\n',
    ),
    { server: '53208e5e49', load: '1009297e71 with changes' },
  );
  assert.deepEqual(installOf('server=unstamped\nload=unstamped\n'), {
    server: 'unstamped',
    load: 'unstamped',
  });
  assert.deepEqual(installOf(''), { server: '?', load: '?' });
  const build = { server: '53208e5e49', load: '53208e5e49' };
  assert.equal(
    buildsText(
      [
        { arm: 'cap512', build },
        { arm: 'cap128', build: { ...build, server: '1009297e71' } },
        { arm: 'cap512', build },
      ],
      ['cap512', 'cap128'],
    ),
    'Builds: `cap512` server 53208e5e49, load 53208e5e49; `cap128` server 1009297e71, load 53208e5e49.\n',
  );
  assert.equal(buildsText([]), '');
});

test('the header says whether the sweeps were primed, as their own records say', () => {
  const session = (sweep, arm, primed) => ({ sweep, arm, primed });
  assert.equal(primedText([session(1, 'a'), session(1, 'b')]), '');
  assert.equal(
    primedText([session(1, 'a', 'true'), session(1, 'b', 'true')]),
    'Primed: every sweep (--prime: an untimed pass over every account before its steps).\n',
  );
  assert.equal(
    primedText([session(1, 'a', 'false'), session(1, 'b', 'false')]),
    'Primed: no sweep.\n',
  );
  assert.equal(
    primedText([session(1, 'a', 'true'), session(1, 'b', 'false')]),
    'Primed: 1-a; not 1-b.\n',
  );
});

// abba itself (test/benchmarks/harness/abba.sh), sourced with bench.sh's own parse, opt, log and bench_node (read out
// of bench.sh, as it defines them) under its shell options, the lock and the host sampler stubbed, and a fake bench.sh
// for the sweeps that records its arguments and writes the sweep a saturate would, `primed` as --prime says. The
// table is bench-abba.mjs's, run as abba runs it.
const SCRIPTS = import.meta.dirname;
const HARNESS = join(SCRIPTS, '../../test/benchmarks/harness');

/** bench.sh's definitions of [names]: a one-line function, or one from its `name() {` line to its closing `}`. */
function benchDefinitions(names) {
  const lines = readFileSync(join(HARNESS, 'bench.sh'), 'utf8').split('\n');
  return names
    .map(name => {
      const start = lines.findIndex(line => line.startsWith(`${name}() {`));
      assert.ok(start >= 0, `bench.sh defines ${name}`);
      if (lines[start].trimEnd().endsWith('}')) return lines[start];
      const end = lines.findIndex((line, at) => at > start && line === '}');
      return lines.slice(start, end + 1).join('\n');
    })
    .join('\n');
}

/**
 * A runner's checkout: test/benchmarks alone (no .github/scripts), a fake sweep bench.sh, and a `pnpm` first on the
 * PATH that is not there (exit 127), as on a bench.yml runner.
 */
function runner() {
  const checkout = mkdtempSync(join(tmpdir(), 'abba-checkout-'));
  const harness = join(checkout, 'test/benchmarks/harness');
  mkdirSync(harness, { recursive: true });
  writeFileSync(
    join(harness, 'bench.sh'),
    `#!/usr/bin/env bash
out=""; primed=false
for a in "$@"; do
  case "$a" in --out=*) out="\${a#--out=}" ;; --prime) primed=true ;; esac
done
printf '%s\\n' "$@" >"$out/args.txt"
cat >"$out/saturation.json" <<JSON
{"steps":[{"concurrency":4,"seconds":30,"throughput":100,"ok":3000,"errors":{},"p50Ms":1,"p99Ms":2}],
 "meta":{"primed":"$primed"}}
JSON
echo "[saturate] E1: concurrency=4 throughput=100/s p50=1ms errors={}"
`,
  );
  chmodSync(join(harness, 'bench.sh'), 0o755);
  const bin = join(checkout, 'bin');
  mkdirSync(bin);
  writeFileSync(
    join(bin, 'pnpm'),
    '#!/bin/sh\necho "pnpm: command not found" >&2\nexit 127\n',
  );
  chmodSync(join(bin, 'pnpm'), 0o755);
  return { checkout, harness, bin, root: join(checkout, 'abba') };
}

/** Runs `abba` with [args] in [checkout]; WOW_BENCH_SCRIPTS is this tree's scripts unless [scripts] says otherwise. */
const abba = ({ checkout, harness, bin, root }, args, scripts = SCRIPTS) =>
  spawnSync(
    'bash',
    [
      '-c',
      `set -euo pipefail
OPTIONS=""
PASS=()
${benchDefinitions(['log', 'bench_node', 'parse', 'opt'])}
usage() { exit 2; }
lock_take() { :; }
lock_release() { :; }
lock_wait_option() { :; }
docker() { :; }
REPO=${JSON.stringify(checkout)}
HARNESS=${JSON.stringify(harness)}
. ${JSON.stringify(join(HARNESS, 'abba.sh'))}
abba_host() { :; }
abba "$@"`,
      'abba',
      '--arm=a||',
      '--arm=b||',
      '--concurrencies=4',
      '--sweeps=1',
      `--out=${root}`,
      ...args,
    ],
    {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        WOW_BENCH_SCRIPTS: scripts,
      },
    },
  );

test("abba passes each sweep the harness's options it is given, and the table, written with node, says they were primed", () => {
  const checkout = runner();
  const result = abba(checkout, [
    '--prime',
    '--seed-concurrency=64',
    '--nodes=2',
    '--growth=4',
  ]);
  assert.equal(result.status, 0, result.stderr);
  for (const arm of ['a', 'b']) {
    const args = readFileSync(
      join(checkout.root, `1-${arm}-postgres/sweep/args.txt`),
      'utf8',
    ).split('\n');
    assert.equal(args[0], 'saturate');
    for (const option of [
      '--prime',
      '--seed-concurrency=64',
      '--nodes=2',
      '--growth=4',
      '--concurrencies=4',
    ]) {
      assert.ok(args.includes(option), `${option} reaches ${arm}'s sweep`);
    }
  }
  const table = readFileSync(join(checkout.root, 'abba.md'), 'utf8');
  assert.match(
    table,
    /\.\nPrimed: every sweep \(--prime: an untimed pass over every account before its steps\)\.\n## postgres\n/,
  );
  assert.doesNotMatch(result.stderr, /pnpm/);
});

test('abba refuses, by name and before any sweep, an option a sweep cannot take from it', () => {
  for (const [args, message] of [
    [['--reuse'], /abba does not take --reuse: each sweep is a fresh saturate/],
    [['--install=/x'], /abba does not take --install: each --arm=/],
    [['--server-args=-Xmx1g'], /abba does not take --server-args: each --arm=/],
    [
      ['--profile=constrained', '--store-cpus=3'],
      /abba does not take --store-cpus with --profile: the profile sets/,
    ],
  ]) {
    const checkout = runner();
    const result = abba(checkout, args);
    assert.equal(result.status, 2, `${args}: ${result.stderr}`);
    assert.match(result.stderr, message);
    assert.equal(existsSync(checkout.root), false, `${args}: no sweep ran`);
  }
});

test("without WOW_BENCH_SCRIPTS, a checkout with no .github/scripts names where it looked for abba's script", () => {
  const checkout = runner();
  const result = abba(checkout, [], '');
  assert.equal(result.status, 2);
  assert.match(
    result.stderr,
    /no \/.*\/\.github\/scripts\/bench-abba\.mjs: WOW_BENCH_SCRIPTS names the directory of the harness's scripts/,
  );
  assert.equal(
    existsSync(join(checkout.root, '1-a-postgres/sweep/args.txt')),
    true,
    'the sweeps ran; the table could not be written',
  );
});
