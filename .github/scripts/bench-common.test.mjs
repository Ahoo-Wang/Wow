/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  HIKARI_DEFAULT_POOL,
  buildStampOf,
  poolOf,
  poolsText,
  tuningOf,
  withPool,
  withRecorded,
} from './bench-common.mjs';

test("a PostgreSQL arm's pool is its server argument, else its property, else HikariCP's default, as Spring Boot ranks them", () => {
  assert.equal(poolOf({ backend: 'postgres' }), HIKARI_DEFAULT_POOL);
  assert.equal(HIKARI_DEFAULT_POOL, 10);
  assert.equal(
    poolOf({
      backend: 'postgres',
      props: { 'spring.datasource.hikari.maximum-pool-size': 32 },
    }),
    32,
  );
  assert.equal(
    poolOf({
      backend: 'postgres',
      serverArgs:
        '--wow.transport=none --spring.datasource.hikari.maximum-pool-size=24',
    }),
    24,
  );
  assert.equal(
    poolOf({
      backend: 'postgres',
      props: { 'spring.datasource.hikari.maximum-pool-size': '40' },
      serverArgs: '--spring.datasource.hikari.maximum-pool-size=24',
    }),
    24,
  );
  assert.equal(poolOf({ backend: 'mongo' }), undefined);
});

test("a suite's arms carry their pools, and its runners row names each", () => {
  const arms = [
    { id: 'base', backend: 'postgres' },
    {
      id: 'head',
      backend: 'postgres',
      props: { 'spring.datasource.hikari.maximum-pool-size': 20 },
    },
  ].map(withPool);
  assert.deepEqual(
    arms.map(arm => arm.pool),
    [10, 20],
  );
  assert.equal(poolsText({ arms }), 'base 10, head 20');
  assert.equal(
    poolsText({ arms: [withPool({ id: 'head', backend: 'mongo' })] }),
    '-',
  );
});

test("an arm's tuning is its bench.tuning server arguments, else its properties, and is recorded beside its pool", () => {
  assert.equal(tuningOf({ backend: 'postgres' }), undefined);
  const arm = {
    backend: 'postgres',
    serverArgs:
      '--wow.transport=none --bench.tuning.committers=4 --bench.tuning.call-cap=128',
    props: { 'bench.tuning.committers': 2, 'bench.tuning.snapshot-writes': 8 },
  };
  assert.deepEqual(tuningOf(arm), {
    committers: 4,
    'call-cap': 128,
    'snapshot-writes': 8,
  });
  assert.deepEqual(withRecorded(arm).tuning, {
    committers: 4,
    'call-cap': 128,
    'snapshot-writes': 8,
  });
  assert.equal(withRecorded(arm).pool, 10);
  assert.equal('tuning' in withRecorded({ backend: 'mongo' }), false);
});

test("an arm records its build's stamp from the install bench.yml names it, and none without one", () => {
  const install = mkdtempSync(join(tmpdir(), 'bench-common-install-'));
  assert.equal(buildStampOf(install), undefined, 'unstamped');
  writeFileSync(
    join(install, 'wow-build-stamp'),
    'commit=53208e5e49abcdef0123\ndirty=no\n',
  );
  assert.equal(buildStampOf(install), '53208e5e49');
  const env = { INSTALL_BASE: install };
  assert.equal(
    withRecorded({ id: 'base', backend: 'mongo', install: 'base' }, env).build,
    '53208e5e49',
  );
  assert.equal(
    'build' in
      withRecorded({ id: 'head', backend: 'mongo', install: 'head' }, env),
    false,
  );
  writeFileSync(
    join(install, 'wow-build-stamp'),
    'commit=1009297e71fedcba\ndirty=yes\n',
  );
  assert.equal(buildStampOf(install), '1009297e71 with changes');
});
