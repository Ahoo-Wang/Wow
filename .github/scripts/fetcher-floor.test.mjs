/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  devFetchers,
  fetcherFloor,
  lockedFetchers,
  peerFetchers,
  unpinned,
  withFloorOverrides,
} from './fetcher-floor.mjs';
import { floorPeers } from './package-check.mjs';
import { ROOT } from './project-version.mjs';

const workspace = readFileSync(join(ROOT, 'pnpm-workspace.yaml'), 'utf8');

const YAML = `packages:
  - typescript/*

catalogs:
  peers:
    # a comment
    '@ahoo-wang/fetcher': '^5.1.5 || ^6.0.0'
    '@ahoo-wang/fetcher-decorator': '^5.1.5 || ^6.0.0'
    react: ^19.0.0

catalog:
  '@ahoo-wang/fetcher': '^6.0.0'
  '@ahoo-wang/fetcher-openapi': '^6.0.0'
  antd: ^6.6.5
`;

test('the floor is the lower bound of the peers catalog, the same for every fetcher peer', () => {
  assert.deepEqual(peerFetchers(YAML), {
    '@ahoo-wang/fetcher': '^5.1.5 || ^6.0.0',
    '@ahoo-wang/fetcher-decorator': '^5.1.5 || ^6.0.0',
  });
  assert.equal(fetcherFloor(YAML), '5.1.5');
  assert.throws(
    () =>
      fetcherFloor(
        YAML.replace(
          "'@ahoo-wang/fetcher-decorator': '^5.1.5",
          "'@ahoo-wang/fetcher-decorator': '^5.1.4",
        ),
      ),
    /disagree on the floor: 5\.1\.5, 5\.1\.4/,
  );
  assert.throws(
    () => fetcherFloor(YAML.replaceAll("'^5.1.5 || ^6.0.0'", "'*'")),
    /no lower bound in \*/,
  );
});

test('pinning overrides every development fetcher to the floor', () => {
  assert.deepEqual(Object.keys(devFetchers(YAML)), [
    '@ahoo-wang/fetcher',
    '@ahoo-wang/fetcher-openapi',
  ]);
  assert.equal(
    withFloorOverrides(YAML),
    `${YAML}\noverrides:\n  '@ahoo-wang/fetcher': '5.1.5'\n  '@ahoo-wang/fetcher-openapi': '5.1.5'\n`,
  );
  const lockfile = `packages:

  '@ahoo-wang/fetcher-storage@6.0.0':
    resolution: {}

  '@ahoo-wang/fetcher@6.0.0':
    resolution: {}

  antd@6.6.5:
    resolution: {}

snapshots:

  '@ahoo-wang/fetcher-cosec@6.0.0(@ahoo-wang/fetcher-storage@6.0.0)':
    dependencies: {}
`;
  assert.deepEqual(lockedFetchers(lockfile), [
    '@ahoo-wang/fetcher-storage',
    '@ahoo-wang/fetcher',
    '@ahoo-wang/fetcher-cosec',
  ]);
  assert.equal(
    withFloorOverrides(YAML, lockfile),
    `${YAML}\noverrides:\n  '@ahoo-wang/fetcher': '5.1.5'\n  '@ahoo-wang/fetcher-cosec': '5.1.5'\n  '@ahoo-wang/fetcher-openapi': '5.1.5'\n  '@ahoo-wang/fetcher-storage': '5.1.5'\n`,
  );
  assert.throws(
    () => withFloorOverrides(`${YAML}overrides:\n  foo: 1.0.0\n`),
    /already declares overrides/,
  );
});

test('the workspace develops above the floor and the consumer check can pin it', () => {
  const floor = fetcherFloor(workspace);
  assert.match(floor, /^\d+\.\d+\.\d+$/);
  // The dev catalog names every fetcher peer, so pinning reaches each.
  for (const name of Object.keys(peerFetchers(workspace)))
    assert.ok(name in devFetchers(workspace), name);
  assert.deepEqual(
    floorPeers(workspace),
    Object.keys(peerFetchers(workspace)).map(name => `${name}@${floor}`),
  );
});

test('verify names every workspace fetcher not on the floor', () => {
  const root = mkdtempSync(join(tmpdir(), 'fetcher-floor-'));
  try {
    const pkg = (name, manifest, installed) => {
      const dir = join(root, 'typescript', name);
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, 'package.json'), JSON.stringify(manifest));
      for (const [dependency, version] of Object.entries(installed)) {
        const target = join(dir, 'node_modules', ...dependency.split('/'));
        mkdirSync(target, { recursive: true });
        writeFileSync(
          join(target, 'package.json'),
          JSON.stringify({ name: dependency, version }),
        );
      }
    };
    pkg(
      'client',
      {
        peerDependencies: { '@ahoo-wang/fetcher': 'catalog:peers' },
        devDependencies: {
          '@ahoo-wang/fetcher': 'catalog:',
          '@ahoo-wang/fetcher-openapi': 'catalog:',
          vitest: 'catalog:',
        },
      },
      { '@ahoo-wang/fetcher': '5.1.5', '@ahoo-wang/fetcher-openapi': '6.0.0' },
    );
    pkg(
      'react',
      { devDependencies: { '@ahoo-wang/fetcher-decorator': 'catalog:' } },
      {},
    );
    mkdirSync(join(root, 'typescript', 'not-a-package'));
    assert.deepEqual(unpinned(root, '5.1.5'), [
      'typescript/client: @ahoo-wang/fetcher-openapi is 6.0.0',
      'typescript/react: @ahoo-wang/fetcher-decorator is not installed',
    ]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('the fetcher-floor job pins, verifies, tests and smoke-tests the floor, and the gate needs it', () => {
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/typescript.yml'),
    'utf8',
  );
  const job = workflow.slice(
    workflow.indexOf('\n  fetcher-floor:'),
    workflow.indexOf('\n  view-engine-shard:'),
  );
  const steps = [
    'node .github/scripts/fetcher-floor.mjs pin',
    'pnpm install --no-frozen-lockfile',
    'node .github/scripts/fetcher-floor.mjs verify',
    'test',
    'node .github/scripts/package-check.mjs --fetcher-floor',
  ];
  let at = 0;
  for (const step of steps) {
    const found = job.indexOf(step, at);
    assert.ok(found > at, `fetcher-floor runs ${step} in order`);
    at = found;
  }
  for (const pkg of ['wow-client', 'wow-react', 'wow-generator'])
    assert.match(job, new RegExp(pkg), pkg);
  const gate = workflow.slice(workflow.indexOf('\n  typescript-gate:'));
  assert.match(gate, /^\s+fetcher-floor,$/m);
});
