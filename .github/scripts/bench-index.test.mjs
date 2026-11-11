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
import { INDEX, readIndex } from './bench-index.mjs';

/**
 * A stand-in for bench.sh's index program: index.json with a snapshot index of one document a product and an events
 * index of two, the events one [short] documents short when set.
 */
function fakeIndexBench(dir, { short = 0 } = {}) {
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
const products = Number(opt('products'));
fs.writeFileSync(out + '/index.json', JSON.stringify({ products, commits: products * 2, seedSeconds: 50, indices: [
  { kind: 'snapshot', index: 'eshop_wow_snapshot__eshop_catalog__product', documents: products, storeBytes: products * 2048,
    indexTotal: products * 2, indexTimeMillis: products, textFields: 2, keywordFields: 14 },
  { kind: 'events', index: 'eshop_wow_events__eshop_catalog__product', documents: products * 2 - ${short}, storeBytes: products * 2048,
    indexTotal: products * 2, indexTimeMillis: products * 3, textFields: 3, keywordFields: 15 },
] }));
fs.writeFileSync(out + '/measure.json', JSON.stringify({ measure: 'index', products }));
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

test('the index group is the index figures, on the head on MongoDB', () => {
  assert.deepEqual(suitesOf('index'), ['index-es']);
  assert.equal(SUITES['index-es'].kind, 'measure');
  assert.deepEqual(
    SUITES['index-es'].arms.map(arm => [arm.install, arm.backend]),
    [['head', 'mongo']],
  );
});

test('the index figures run once, and read each index a document and an indexing operation', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'index-es',
    options(dir, fakeIndexBench(dir), 'index-es'),
  );
  assert.equal(suite.program, 'index');
  assert.deepEqual(
    suite.runs.map(run => [run.name, run.status]),
    [['index', 'ok']],
  );
  const calls = readFileSync(join(dir, 'calls.log'), 'utf8').trim().split('\n');
  assert.deepEqual(
    calls.map(call => call.replace(/ --out=.*/, '')),
    [`measure --program=index --products=${INDEX.products}`],
  );
  const [snapshot, events] = suite.runs[0].measure.indices;
  assert.equal(snapshot.bytesPerDocument, 2048);
  assert.equal(snapshot.indexMicrosPerOperation, 500);
  assert.equal(events.bytesPerDocument, 1024);
  assert.equal(events.indexMicrosPerOperation, 1500);
  const text = renderSuite(suite);
  assert.match(
    text,
    /\| snapshot \(`eshop_wow_snapshot__eshop_catalog__product`\) \| 100000 \| 195\.3 MiB \| 2048 \| 200000 \| 100\.0 s \| 500\.0 \| 2 \| 14 \|/,
  );
  assert.match(
    text,
    /\| events \(`eshop_wow_events__eshop_catalog__product`\) \| 200000 \| 195\.3 MiB \| 1024 \| 200000 \| 300\.0 s \| 1500\.0 \| 3 \| 15 \|/,
  );
  assert.deepEqual(anomalies(suite), []);
});

test('an index short of its documents is an anomaly of the index figures', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  const suite = runSuite(
    'index-es',
    options(dir, fakeIndexBench(dir, { short: 5 }), 'index-es'),
  );
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind, row.detail]),
    [['index', 'documents missing', 'events: 199995 of 200000']],
  );
});

test('the index program without its results reads nothing, and its failed run is an anomaly', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-'));
  assert.equal(readIndex(join(dir, 'nothing')), undefined);
  const failing = join(dir, 'bench.sh');
  writeFileSync(failing, '#!/usr/bin/env node\nprocess.exit(1);\n');
  chmodSync(failing, 0o755);
  const suite = runSuite('index-es', options(dir, failing, 'index-es'));
  assert.equal(suite.failures, 1);
  assert.deepEqual(
    anomalies(suite).map(row => [row.run, row.kind]),
    [['index', 'run failed']],
  );
  assert.match(renderSuite(suite), /No run completed\./);
});
