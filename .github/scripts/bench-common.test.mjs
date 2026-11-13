/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  HIKARI_DEFAULT_POOL,
  poolOf,
  poolsText,
  withPool,
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
