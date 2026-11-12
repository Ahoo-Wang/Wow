/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  anomalies,
  renderSuite,
  runSuite,
  SUITES,
  suitesOf,
} from './bench.mjs';
import { INSERT, readInsert, readSink, SINK } from './bench-sink.mjs';

/**
 * A stand-in for bench.sh's two sink programs. clickhouse-insert writes, per arm, CPU a row of 1 µs with no column,
 * 1 + N/2 µs per column and 1 + N/10 µs as a tuple, and a skip index whose forced use under FINAL miscounts;
 * [wrongFinal] makes the plain FINAL miscount too. sink writes the commits the load made, every one written but in
 * the scenario [unwritten] names: on ClickHouse in inserts of 10 rows and parts of 10 rows; on Elasticsearch
 * (--sink=elasticsearch) the deposits' commits 4 to a _bulk, an _update_by_query a 100 and 2 other requests, but none
 * counted in the scenario [uncounted] names.
 */
function fakeSinkBench(
  dir,
  {
    wrongFinal = false,
    unwritten,
    uncounted,
    slowCodecs = false,
    slowHead = false,
  } = {},
) {
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env node
const fs = require('node:fs');
const [command, ...args] = process.argv.slice(2);
const opt = (name) => args.find((a) => a.startsWith('--' + name + '='))?.split('=').slice(1).join('=');
const out = opt('out');
fs.mkdirSync(out, { recursive: true });
fs.appendFileSync(${JSON.stringify(join(dir, 'calls.log'))}, [command, ...args].join(' ') + '\\n');
if (opt('program') === 'clickhouse-insert') {
  const rows = Number(opt('rows'));
  const lines = [];
  for (const columns of [5, 20, 50]) for (const mode of ['none', 'per-column', 'tuple']) for (const target of ['table', 'view']) for (const repeat of [1, 2]) {
    const micros = mode === 'none' ? 1 : mode === 'per-column' ? 1 + columns / 2 : 1 + columns / 10;
    lines.push(JSON.stringify({ mode, columns, target, repeat, rows: String(rows), writtenRows: String(rows), durationMs: '100', userMicros: String(micros * rows), cpuMicros: String(micros * rows * 1.5) }));
  }
  fs.writeFileSync(out + '/insert.jsonl', lines.join('\\n') + '\\n');
  fs.writeFileSync(out + '/skip.json', JSON.stringify({ correct: 200, settings: [
    { useSkipIndexesIfFinal: 0, count: ${wrongFinal ? 201 : 200}, readRows: 49152, explain: '{"explain":"ReadFromMergeTree"}' },
    { useSkipIndexesIfFinal: 1, count: 230, readRows: 8192, explain: '{"explain":"Skip Name: status_set"}' },
  ] }));
  fs.writeFileSync(out + '/measure.json', JSON.stringify({ measure: 'clickhouse-insert', rows, block: Number(opt('block')), repeats: Number(opt('repeats')), server: '24.8.14.39' }));
  // The codec arms: an insert of a second for LZ4, 1.05 s (or 1.25 s, slowCodecs) for the Sink's codecs;
  // a merge of 2 µs a row and LZ4's bytes ×1, the Sink's ×0.6.
  const codecs = { lz4: [1000, 1, 100], wow: [${slowCodecs ? 1250 : 1050}, 1.2, 60] };
  const codecLines = [];
  const mergeLines = [];
  for (const [arm, [durationMs, micros, bytes]] of Object.entries(codecs)) {
    for (const repeat of [1, 2]) codecLines.push(JSON.stringify({ arm, repeat, rows, writtenRows: String(rows), durationMs: String(durationMs), userMicros: String(micros * rows), cpuMicros: String(micros * rows) }));
    mergeLines.push(JSON.stringify({ arm, rows, partsBefore: 200, partsAfter: '2', durationMs: '400', userMicros: String(2 * rows), cpuMicros: String(2 * rows), mergedRows: String(rows), compressedBytes: String(bytes * 1000), uncompressedBytes: '500000' }));
  }
  fs.writeFileSync(out + '/codec.jsonl', codecLines.join('\\n') + '\\n');
  fs.writeFileSync(out + '/codec-merge.jsonl', mergeLines.join('\\n') + '\\n');
} else {
  const name = out.split('/').pop();
  const expected = Number(opt('rate')) * Number(opt('seconds')) + Number(opt('accounts'));
  const written = name === ${JSON.stringify(unwritten ?? '')} ? expected - 5 : expected;
  const backlog = opt('backlog') === 'true' ? { backlog: true, drainMs: 4200 } : {};
  if (opt('sink') === 'elasticsearch') {
    const deposits = expected - Number(opt('accounts'));
    const requests = name === ${JSON.stringify(uncounted ?? '')} ? {} : { _bulk: deposits / 4, _refresh: 0, _update_by_query: deposits / 100, other: 2 };
    fs.writeFileSync(out + '/sink.json', JSON.stringify({ sink: 'elasticsearch', accounts: Number(opt('accounts')), rate: Number(opt('rate')), seconds: Number(opt('seconds')), expected, written,
      requests, node: { indexTotal: deposits, bulkOperations: deposits / 4, refreshTotal: 7 }, ...backlog }));
    fs.writeFileSync(out + '/measure.json', JSON.stringify({ measure: 'sink', sink: 'elasticsearch', accounts: Number(opt('accounts')) }));
    process.exit(0);
  }
  fs.writeFileSync(out + '/sink.json', JSON.stringify({ sink: 'clickhouse', accounts: Number(opt('accounts')), expected, written, ...backlog,
    parts: [
      { table: 'transfer_account_events', parts: String(expected / 10), rows: String(expected) },
      { table: 'transfer_account_state', parts: String(expected / 10), rows: String(expected) },
      { table: 'transfer_account_state_events', parts: String(expected / 10), rows: String(expected) },
    ],
    // The sink's inserts over 10 s on the base's build (--install), 10.5 s (12.5 s, slowHead) on the head's.
    inserts: { inserts: String(expected / 10), writtenRows: String(expected), firstMs: '1000',
      lastMs: String(1000 + (opt('install') ? 10000 : ${slowHead ? 12500 : 10500})) } }));
  fs.writeFileSync(out + '/measure.json', JSON.stringify({ measure: 'sink', sink: 'clickhouse', accounts: Number(opt('accounts')) }));
}
`,
  );
  chmodSync(path, 0o755);
  return path;
}

const options = (dir, benchPath, name) => ({
  benchPath,
  warmUp: 1,
  measure: 5,
  repeats: 1,
  fraction: 0.8,
  out: join(dir, 'results', name),
});

test("the codecs group is the reads under the Sink's codecs and the sink's ceiling, base against head", () => {
  assert.deepEqual(suitesOf('codecs'), [
    'access-dataset-ch-codecs',
    'sink-ch-ceiling',
  ]);
  assert.deepEqual(
    SUITES['access-dataset-ch-codecs'].arms.map(arm => [arm.id, arm.args]),
    [
      ['lz4', ['--codecs=lz4']],
      ['codecs', undefined],
    ],
  );
  assert.deepEqual(
    SUITES['sink-ch-ceiling'].arms.map(arm => [arm.install, arm.backend]),
    [
      ['base', 'postgres'],
      ['head', 'postgres'],
    ],
  );
});

test("the sink's ceiling drains a backlog on each build, ABBA, and reads the head against the bar", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite('sink-ch-ceiling', {
    ...options(dir, fakeSinkBench(dir), 'sink-ch-ceiling'),
    installs: { base: '/base/install' },
  });
  assert.deepEqual(
    suite.runs.map(run => run.name),
    ['1-base', '1-head', '2-head', '2-base'],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').split('\n');
  assert.equal(
    calls.filter(call => call.includes('--install=/base/install')).length,
    2,
  );
  assert.ok(calls.every(call => !call || call.includes('--backlog=true')));
  // 121,000 commits over 10 s on the base's build and 10.5 s on the head's.
  assert.equal(suite.runs[0].measure.commitsPerSecond, 12100);
  const text = renderSuite(suite);
  assert.match(text, /- base: 12,?100 commits a second/);
  assert.match(text, /- head against base: -4\.8%, within the 10% bar\./);
});

test("a sink's ceiling down by more than the bar says so", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite('sink-ch-ceiling', {
    ...options(dir, fakeSinkBench(dir, { slowHead: true }), 'sink-ch-ceiling'),
    installs: { base: '/base/install' },
  });
  assert.match(
    renderSuite(suite),
    /- head against base: -20\.0%, \*\*over\*\* the 10% bar\./,
  );
});

test('the sink group is the insert experiment and the two sinks, on the head on PostgreSQL', () => {
  assert.deepEqual(suitesOf('sink'), ['ch-insert', 'sink-ch', 'sink-es']);
  for (const name of suitesOf('sink')) {
    assert.equal(SUITES[name].kind, 'measure');
    assert.deepEqual(
      SUITES[name].arms.map(arm => [arm.install, arm.backend]),
      [['head', 'postgres']],
    );
  }
});

test('the insert experiment runs once, and reads each arm per row against the tuple bound', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'ch-insert',
    options(dir, fakeSinkBench(dir), 'ch-insert'),
  );
  assert.equal(suite.program, 'clickhouse-insert');
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [['insert', 'ok']],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.deepEqual(
    calls.map(call => call.replace(/ --out=.*/, '')),
    [
      `measure --program=clickhouse-insert --backend=postgres --rows=${INSERT.rows} --block=${INSERT.block} --repeats=${INSERT.repeats}`,
    ],
  );
  const arm = suite.runs[0].measure.arms.find(
    it => it.mode === 'per-column' && it.columns === 20 && it.target === 'view',
  );
  assert.equal(arm.inserts, 2);
  assert.equal(arm.userMicrosPerRow, 11);
  assert.equal(arm.cpuMicrosPerRow, 16.5);
  const text = renderSuite(suite);
  // 20 columns: per column 10 µs above none, the tuple 2, ×5: past the bound.
  assert.match(
    text,
    /\| 20 \| view \| 1\.00 µs \| 11\.00 µs \| 3\.00 µs \| 10\.00 µs \| 2\.00 µs \| ×5\.00 \|/,
  );
  assert.match(
    text,
    /- 20 columns into the view: .* is \*\*crossed\*\*: the tuple form\./,
  );
  // The forced skip index miscounts and is in the plan; the plain FINAL is right and reads every row.
  assert.match(text, /\| 0 \| 200 \| yes \| 49152 \| no \|/);
  assert.match(text, /\| 1 \| 230 \| \*\*no\*\* \| 8192 \| yes \|/);
  assert.deepEqual(anomalies(suite), []);
});

test('the insert experiment reads the codec arms, and the Sink codecs against LZ4 and the bar', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'ch-insert',
    options(dir, fakeSinkBench(dir), 'ch-insert'),
  );
  const { codecs } = suite.runs[0].measure;
  assert.deepEqual(
    codecs.map(it => it.arm),
    ['lz4', 'wow'],
  );
  const wow = codecs[1];
  assert.equal(wow.inserts, 2);
  assert.equal(wow.userMicrosPerRow, 1.2);
  assert.equal(Math.round(wow.rowsPerSecond), Math.round(INSERT.rows / 1.05));
  assert.equal(wow.merge.userMicrosPerRow, 2);
  assert.equal(wow.merge.compressedBytes, 60000);
  const text = renderSuite(suite);
  assert.match(
    text,
    /\| wow \| 1\.20 µs \| .* \| 2\.00 µs \| 400 ms \| 60,?000 B \| ×8\.33 \|/,
  );
  assert.match(
    text,
    /- The Sink's codecs: 4\.8% fewer rows a second than LZ4, within the 10% bar; the bytes 40\.0% fewer\./,
  );
});

test('Sink codecs that cost the insert more than the bar say so', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'ch-insert',
    options(dir, fakeSinkBench(dir, { slowCodecs: true }), 'ch-insert'),
  );
  assert.match(
    renderSuite(suite),
    /- The Sink's codecs: 20\.0% fewer rows a second than LZ4, \*\*over\*\* the 10% bar;/,
  );
});

test('a plain FINAL that miscounts is an anomaly of the insert experiment', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'ch-insert',
    options(dir, fakeSinkBench(dir, { wrongFinal: true }), 'ch-insert'),
  );
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind, row.detail]),
    [
      [
        'insert',
        'FINAL answered wrong',
        '201 rows without skip indexes, 200 right',
      ],
    ],
  );
  assert.match(renderSuite(suite), /Anomalies:/);
});

test('the sink runs each scenario once, and counts its inserts and each table’s parts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'sink-ch',
    options(dir, fakeSinkBench(dir, { unwritten: 'hot' }), 'sink-ch'),
  );
  assert.equal(suite.program, 'sink');
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    SINK.scenarios.map(scenario => [scenario.name, 'ok']),
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.deepEqual(
    calls.map(call => call.replace(/ --out=.*/, '')),
    [
      `measure --program=sink --backend=postgres --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform`,
      `measure --program=sink --backend=postgres --accounts=10 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform`,
      `measure --program=sink --backend=postgres --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=hot --hot-fraction=0.5 --hot-keys=1`,
      `measure --program=sink --backend=postgres --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform --backlog=true`,
    ],
  );
  const spread = suite.runs[0].measure;
  assert.equal(spread.commitsPerInsert, 10);
  assert.deepEqual(spread.parts.transfer_account_state, {
    parts: 1300,
    rows: 13000,
  });
  const text = renderSuite(suite);
  assert.match(
    text,
    /\| spread \| 1000 \| 13000 \| 13000 \| 1300 \| 10\.0 \| 1300, 1300, 1300 \| 10\.0, 10\.0, 10\.0 \| - \|/,
  );
  // The backlog: drained after the load, its time to drain shown.
  assert.equal(suite.runs[3].measure.drainMs, 4200);
  assert.match(
    text,
    /\| backlog \| 1000 \| 13000 \| 13000 \| 1300 \| 10\.0 \| .* \| 4\.2 s \|/,
  );
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind, row.detail]),
    [['hot', 'commits not written', "12005 of 12010 in the sink's tables"]],
  );
});

test('a sink program without its results reads nothing, and its failed run is an anomaly', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  assert.equal(readInsert(join(dir, 'nothing')), undefined);
  assert.equal(readSink(join(dir, 'nothing')), undefined);
  const failing = join(dir, 'bench.sh');
  writeFileSync(failing, '#!/usr/bin/env node\nprocess.exit(1);\n');
  chmodSync(failing, 0o755);
  const suite = runSuite('ch-insert', options(dir, failing, 'ch-insert'));
  assert.equal(suite.failures, 1);
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind]),
    [['insert', 'run failed']],
  );
  assert.match(renderSuite(suite), /No run completed\./);
});

test('the Elasticsearch sink runs each scenario on the search server, and counts its requests a 1,000 commits', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'sink-es',
    options(
      dir,
      fakeSinkBench(dir, { unwritten: 'hot', uncounted: 'mixed' }),
      'sink-es',
    ),
  );
  assert.equal(suite.program, 'sink');
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    SINK.scenarios.map(scenario => [scenario.name, 'ok']),
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.deepEqual(
    calls.map(call => call.replace(/ --out=.*/, '')),
    [
      `measure --program=sink --backend=postgres --sink=elasticsearch --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform`,
      `measure --program=sink --backend=postgres --sink=elasticsearch --accounts=10 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform`,
      `measure --program=sink --backend=postgres --sink=elasticsearch --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=hot --hot-fraction=0.5 --hot-keys=1`,
      `measure --program=sink --backend=postgres --sink=elasticsearch --accounts=1000 --rate=${SINK.rate} --seconds=${SINK.seconds} --distribution=uniform --backlog=true`,
    ],
  );
  // The deposits' commits are the commits less the accounts the seeding opened.
  const spread = suite.runs[0].measure;
  assert.equal(spread.commits, 12000);
  assert.equal(spread.commitsPerBulk, 4);
  const text = renderSuite(suite);
  assert.match(
    text,
    /\| spread \| 1000 \| 13000 \| 13000 \| 12000 \| 3000 \| 4\.0 \| 120 \(10\.0\) \| 0 \(0\.0\) \| 2 \| 12000 \| 7 \| - \|/,
  );
  // A backlog's counts are of every commit, the seeding's too.
  const backlog = suite.runs[3].measure;
  assert.equal(backlog.commits, 13000);
  assert.match(
    text,
    /\| backlog \| 1000 \| 13000 \| 13000 \| 13000 \| 3000 \| 4\.3 \| .* \| 4\.2 s \|/,
  );
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind, row.detail]),
    [
      ['hot', 'commits not written', "12005 of 12010 in the sink's tables"],
      [
        'mixed',
        'requests not counted',
        "no search-requests line in the node's log",
      ],
    ],
  );
});
