/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  compareVersions,
  parseVersion,
  readProjectVersion,
  setProjectVersion,
  versionMismatches,
  versionedPackageFiles,
} from './project-version.mjs';

function workspace(version = '9.1.5', overrides = {}) {
  const root = mkdtempSync(join(tmpdir(), 'project-version-'));
  writeFileSync(
    join(root, 'gradle.properties'),
    `group=me.ahoo.wow\nversion=${version}\ndescription=x\n`,
  );
  const packages = {
    'typescript/wow-client': { name: '@ahoo-wang/wow-client', version },
    'typescript/integration-test': {
      name: 'wow-integration-test',
      version,
      private: true,
    },
    'compensation/dashboard': { name: 'dashboard', private: true, version },
    documentation: { name: 'documentation', version, private: true },
    ...overrides,
  };
  for (const [dir, manifest] of Object.entries(packages)) {
    mkdirSync(join(root, dir), { recursive: true });
    writeFileSync(
      join(root, dir, 'package.json'),
      `${JSON.stringify(manifest, null, 2)}\n`,
    );
  }
  // A directory without package.json (like a stray folder) is ignored.
  mkdirSync(join(root, 'typescript', 'notes'));
  return root;
}

test('the repository itself keeps every package on the gradle version', () => {
  assert.deepEqual(versionMismatches(), []);
  assert.ok(
    versionedPackageFiles().includes('typescript/wow-client/package.json'),
  );
});

test('versioned files are the typescript packages, dashboard and documentation', () => {
  assert.deepEqual(versionedPackageFiles(workspace()), [
    'typescript/integration-test/package.json',
    'typescript/wow-client/package.json',
    'compensation/dashboard/package.json',
    'documentation/package.json',
  ]);
});

test('a drifted package or a wrong release tag is a mismatch', () => {
  const root = workspace('9.1.5', {
    'typescript/wow-react': { name: '@ahoo-wang/wow-react', version: '9.1.4' },
  });
  assert.deepEqual(versionMismatches(root), [
    'typescript/wow-react/package.json: 9.1.4 != 9.1.5 (gradle.properties)',
  ]);
  assert.equal(versionMismatches(root, 'v9.1.4').length, 2);
  assert.deepEqual(versionMismatches(workspace(), 'v9.1.5'), []);
});

test('set writes gradle.properties and every package.json, keeping layout', () => {
  const root = workspace();
  const before = readFileSync(join(root, 'documentation/package.json'), 'utf8');
  const changed = setProjectVersion('9.2.0', root);
  assert.equal(changed.length, 5);
  assert.equal(readProjectVersion(root), '9.2.0');
  assert.match(
    readFileSync(join(root, 'gradle.properties'), 'utf8'),
    /^group=me\.ahoo\.wow\nversion=9\.2\.0\ndescription=x\n$/,
  );
  assert.equal(
    readFileSync(join(root, 'documentation/package.json'), 'utf8'),
    before.replace('"9.1.5"', '"9.2.0"'),
  );
  assert.deepEqual(versionMismatches(root), []);
  assert.deepEqual(setProjectVersion('9.2.0', root), ['gradle.properties']);
});

test('set refuses anything that is not a version', () => {
  const root = workspace();
  for (const bad of ['v9.2.0', '9.2', 'next', ''])
    assert.throws(() => setProjectVersion(bad, root));
  assert.equal(readProjectVersion(root), '9.1.5');
});

test('versions parse strictly and order with prereleases first', () => {
  assert.deepEqual(parseVersion('v9.1.5'), {
    major: 9,
    minor: 1,
    patch: 5,
    prerelease: undefined,
  });
  for (const bad of ['vv5.22.3', '9.1', '9.1.5.1', 'latest', undefined])
    assert.equal(parseVersion(bad), undefined);
  const sorted = ['9.10.0', '9.2.0', '9.2.0-beta.1', '10.0.0', '9.1.5']
    .map(parseVersion)
    .sort(compareVersions)
    .map(
      ({ major, minor, patch, prerelease }) =>
        [major, minor, patch].join('.') + (prerelease ? `-${prerelease}` : ''),
    );
  assert.deepEqual(sorted, [
    '9.1.5',
    '9.2.0-beta.1',
    '9.2.0',
    '9.10.0',
    '10.0.0',
  ]);
});
