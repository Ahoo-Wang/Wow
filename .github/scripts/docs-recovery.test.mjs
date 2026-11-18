/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  RECOVERY,
  boundBlock,
  handoverBlock,
  leasesBlock,
  seconds,
  span,
} from './docs-recovery.mjs';

const M5MAX = {
  environment: 'm5max',
  cpu: 'Apple M5 Max, 18 cores, 64 GB',
  jdk: '25.0.5+8',
};
const ACTIONS = {
  environment: 'actions',
  cpu: 'GitHub Actions ubuntu-26.04, CPU model not recorded',
  jdk: '25.0.5',
};

const lease = (arm, commit, extra) => ({
  id: `lost-leases.${arm}`,
  kind: 'lost-leases',
  lost: 64,
  rounds: 3,
  commit,
  changed: false,
  source: 'm5max-recovery/lost-leases',
  ...M5MAX,
  ...extra,
});

const handover = (store, arm, commit, extra) => ({
  id: `handover.${store}.${arm}`,
  kind: 'handover',
  store,
  nodes: 3,
  commit,
  changed: false,
  source: 'm5max-recovery/handover',
  ...M5MAX,
  ...extra,
});

const live = (id, commit, job, extra) => ({
  id: `${id}.${job}`,
  job,
  sent: 6000,
  failed: 0,
  worstSecondP99Ms: 2000,
  catchUpSeconds: 50,
  commit,
  source: `m5max-recovery/${id}/${job}`,
  ...ACTIONS,
  ...extra,
});

const both = (id, commit, extra) =>
  RECOVERY.jobs.map(job => live(id, commit, job, extra));

const headline = figures => [
  { report: 'm5max-recovery', environment: 'm5max', figures },
];

const leases = [
  lease('fix', 'f1', {
    firstBackSeconds: [2.4, 2.7],
    lastBackSeconds: [2.4, 3.5],
    backAtEnd: [64, 64, 64],
  }),
  lease('base', 'b1', {
    firstBackSeconds: [47.2, 47.8],
    lastBackSeconds: null,
    backAtEnd: [16, 28, 16],
  }),
];

const handovers = [
  ['mongo', 'main', 'm1', { changed: true, failedWaits: [15, 2, 2] }],
  ['mongo', 'fix', 'x1', { changed: true, failedWaits: [1, 1, 2] }],
  ['postgres', 'fix', 'x1', { changed: true, failedWaits: [0] }],
  ['mongo', '6412', 'm2', { failedWaits: [1, 1, 1] }],
  ['mongo', 'patch', 'p1', { failedWaits: [1, 1, 1] }],
  ['postgres', 'patch', 'p1', { failedWaits: [0], p99Ms: [20, 20] }],
].map(([store, arm, commit, extra]) =>
  handover(store, arm, commit, {
    sent: extra.failedWaits.map(() => 5250),
    p99Ms: [1581, 1983],
    ...extra,
  }),
);

const bounds = [
  ...both('rehearsal.bound-2', 'c2', {
    kind: 'rehearsal',
    maxCatchUps: 2,
    fix: 'h1',
    run: '101',
  }),
  ...both('rehearsal.bound-0', 'c0', {
    kind: 'rehearsal',
    maxCatchUps: 0,
    fix: 'h1',
    run: '100',
    failed: 7,
  }),
];

const all = headline([...leases, ...handovers, ...bounds]);

test('a duration prints in seconds, and a range as one value when both ends agree', () => {
  assert.equal(seconds(2.4), '2.4 s');
  assert.equal(seconds(107.2), '107.2 s');
  assert.equal(span([2.4, 3.5], seconds), '2.4–3.5 s');
  assert.equal(span([22, 22], seconds), '22 s');
});

test('the lost-leases table sets the fix against the build before it, and says when not all were back', () => {
  const block = leasesBlock('en', all);
  assert.match(block, /Apple M5 Max, 18 cores, 64 GB, JDK 25\.0\.5\+8/);
  assert.match(block, /all 64 of its leases/);
  assert.match(block, /3 rounds a side/);
  assert.match(
    block,
    /\| fix `f1` \| 2\.4–2\.7 s \| 2\.4–3\.5 s \| 64, 64, 64 of 64 \|/,
  );
  assert.match(
    block,
    /\| before the fix `b1` \| 47\.2–47\.8 s \| not all back \| 16, 28, 16 of 64 \|/,
  );
  assert.match(leasesBlock('zh', all), /16、28、16（共 64）/);
});

test('the hand-over tables are one a batch, in order, each build named for a reader, and a line says which batches had log probes', () => {
  const block = handoverBlock('en', all);
  assert.match(block, /5,250 commands a round/);
  assert.match(
    block,
    /\| before the fix `m1` \| MongoDB \| 15, 2, 2 \| 1,581–1,983 ms \|/,
  );
  assert.match(block, /\| first fix `x1` \| PostgreSQL \| 0 \|/);
  assert.match(block, /\| first fix `m2` \| MongoDB \|/);
  assert.match(
    block,
    /^Batch 1's builds carried log probes, which add log lines only\.$/m,
  );
  assert.doesNotMatch(block, /\| [^|]*probes/);
  assert.match(
    block,
    /\| the code that ships `p1` \| PostgreSQL \| 0 \| 20 ms \|/,
  );
  const [, first, second] = block.split(/\*\*Batch \d:\*\*/);
  assert.match(first, /`m1`[\s\S]*`x1`/);
  assert.doesNotMatch(first, /`m2`|`p1`/);
  assert.match(second, /`m2`[\s\S]*`p1`/);
});

test('the bound table names the runner image whose CPU was not recorded, each arm its run, and the build both ran', () => {
  const block = boundBlock('en', all);
  assert.match(
    block,
    /GitHub Actions ubuntu-26\.04, CPU model not recorded, JDK 25\.0\.5/,
  );
  assert.match(block, /\(101 and 100\)/);
  assert.match(
    block,
    /\| 2 \(the default\), `c2` \| runner \| 0 \| 2,000 ms \| 50 s \|/,
  );
  assert.match(block, /\| no bound, `c0` \| kind \| 7 \|/);
  assert.match(
    block,
    /Both runs were of `h1` with the rehearsal's instrumentation, at the earlier rule/,
  );
  assert.match(
    boundBlock('zh', all),
    /GitHub Actions ubuntu-26\.04，未记录 CPU 型号/,
  );
});

test('a table refuses rows that differ in where they ran, and a missing headline or figure', () => {
  const moved = headline([
    leases[0],
    { ...leases[1], cpu: 'AMD EPYC 7763 64-Core Processor' },
  ]);
  assert.throws(() => leasesBlock('en', moved), /differ in CPU/);
  assert.throws(
    () => leasesBlock('en', []),
    /need the m5max-recovery headline/,
  );
  assert.throws(
    () => handoverBlock('en', headline(handovers.slice(1))),
    /no figure handover\.mongo\.main/,
  );
});
