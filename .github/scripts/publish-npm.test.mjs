/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  distTag,
  isPublished,
  PUBLISHED,
  publishArgs,
  publishPlan,
  publishRefusal,
  tarballName,
} from './publish-npm.mjs';

function workspace(packages) {
  const root = mkdtempSync(join(tmpdir(), 'publish-npm-'));
  writeFileSync(join(root, 'gradle.properties'), 'version=9.1.6\n');
  for (const [dir, manifest] of Object.entries(packages)) {
    mkdirSync(join(root, dir), { recursive: true });
    writeFileSync(join(root, dir, 'package.json'), JSON.stringify(manifest));
  }
  return root;
}

const published = Object.fromEntries(
  PUBLISHED.map(dir => [
    dir,
    { name: `@ahoo-wang/${dir.split('/')[1]}`, version: '9.1.6' },
  ]),
);

test('the repository publishes wow-client, wow-react and wow-generator only', () => {
  assert.deepEqual(
    publishPlan().map(({ name }) => name),
    [
      '@ahoo-wang/wow-client',
      '@ahoo-wang/wow-react',
      '@ahoo-wang/wow-generator',
    ],
  );
});

test('private packages stay out; an unlisted public package is refused', () => {
  const root = workspace({
    ...published,
    'typescript/integration-test': {
      name: 'wow-integration-test',
      version: '9.1.6',
      private: true,
    },
  });
  assert.deepEqual(publishPlan(root), [
    {
      dir: 'typescript/wow-client',
      name: '@ahoo-wang/wow-client',
      version: '9.1.6',
    },
    {
      dir: 'typescript/wow-react',
      name: '@ahoo-wang/wow-react',
      version: '9.1.6',
    },
    {
      dir: 'typescript/wow-generator',
      name: '@ahoo-wang/wow-generator',
      version: '9.1.6',
    },
  ]);
  assert.throws(
    () =>
      publishPlan(
        workspace({
          ...published,
          'typescript/wow-view-store': {
            name: '@ahoo-wang/wow-view-store',
            version: '9.1.6',
          },
        }),
      ),
    /typescript\/wow-view-store: public but neither PUBLISHED nor HELD_BACK/,
  );
});

test('a held-back public package is not published', () => {
  const plan = publishPlan(
    workspace({
      ...published,
      'typescript/wow-view-engine': {
        name: '@ahoo-wang/wow-view-engine',
        version: '9.1.6',
      },
    }),
  );
  assert.ok(plan.every(entry => entry.dir !== 'typescript/wow-view-engine'));
});

test('a published package must be public and on the project version', () => {
  assert.throws(
    () =>
      publishPlan(
        workspace({
          ...published,
          'typescript/wow-react': {
            name: '@ahoo-wang/wow-react',
            version: '9.1.5',
            private: true,
          },
        }),
      ),
    /wow-react: private packages are never published\n.*wow-react: version 9\.1\.5 != 9\.1\.6/,
  );
});

test('only the latest line gets the latest dist-tag', () => {
  const tags = ['v9.0.18', 'v9.1.5', 'v9.1.6', 'vv5.22.3', 'v9.2.0-rc.1'];
  // The release tag itself exists on a release event.
  assert.equal(distTag('9.1.6', tags), 'latest');
  // A manual dispatch before tagging.
  assert.equal(distTag('9.1.7', tags), 'latest');
  assert.equal(distTag('9.2.0', tags), 'latest');
  assert.equal(distTag('9.0.19', tags), 'release-9.0');
  assert.equal(distTag('8.11.6', tags), 'release-8.11');
  assert.equal(distTag('9.1.6', []), 'latest');
  assert.equal(distTag('9.3.0-beta.1', tags), 'next');
  assert.throws(() => distTag('latest', tags));
});

test('an existing version is skipped; a missing version or package is published', () => {
  const answers = {
    '@ahoo-wang/wow-client@9.1.5': () => '9.1.5\n',
    '@ahoo-wang/wow-client@9.1.6': () => '',
    '@ahoo-wang/wow-new@9.1.6': () => {
      throw Object.assign(new Error('Command failed'), {
        stderr: 'npm error code E404\nnpm error 404 Not Found',
      });
    },
    '@ahoo-wang/wow-down@9.1.6': () => {
      throw Object.assign(new Error('Command failed'), {
        stderr: 'npm error code ETIMEDOUT',
      });
    },
  };
  const run = ([command, spec, field]) => {
    assert.equal(command, 'view');
    assert.equal(field, 'version');
    return answers[spec]();
  };
  assert.equal(isPublished('@ahoo-wang/wow-client', '9.1.5', run), true);
  assert.equal(isPublished('@ahoo-wang/wow-client', '9.1.6', run), false);
  assert.equal(isPublished('@ahoo-wang/wow-new', '9.1.6', run), false);
  assert.throws(
    () => isPublished('@ahoo-wang/wow-down', '9.1.6', run),
    /Command failed/,
  );
});

test('CI publishes with provenance; a dry run neither signs nor uploads', () => {
  assert.deepEqual(
    publishArgs('/t/a.tgz', 'latest', { dryRun: false, provenance: true }),
    [
      'publish',
      '/t/a.tgz',
      '--access',
      'public',
      '--tag',
      'latest',
      '--provenance',
    ],
  );
  assert.deepEqual(
    publishArgs('/t/a.tgz', 'release-9.0', { dryRun: true, provenance: true }),
    [
      'publish',
      '/t/a.tgz',
      '--access',
      'public',
      '--tag',
      'release-9.0',
      '--dry-run',
    ],
  );
  assert.deepEqual(
    publishArgs('/t/a.tgz', 'latest', { dryRun: false, provenance: false }),
    ['publish', '/t/a.tgz', '--access', 'public', '--tag', 'latest'],
  );
});

test('tarballs are found by the name pnpm pack gives them', () => {
  assert.equal(
    tarballName('@ahoo-wang/wow-client', '9.2.0-rc.0'),
    'ahoo-wang-wow-client-9.2.0-rc.0.tgz',
  );
  assert.equal(tarballName('plain', '1.0.0'), 'plain-1.0.0.tgz');
});

test('a real publish ships only a clean checkout of the release tag', () => {
  const clean = {
    status: '',
    head: 'a'.repeat(40),
    tagCommit: 'a'.repeat(40),
    version: '9.2.0',
  };
  assert.equal(publishRefusal(clean), undefined);
  assert.match(
    publishRefusal({ ...clean, status: ' M typescript/wow-client/src/a.ts\n' }),
    /not clean.*\n M typescript\/wow-client\/src\/a\.ts$/s,
  );
  assert.match(
    publishRefusal({ ...clean, status: '?? stray.txt\n' }),
    /not clean/,
  );
  assert.match(
    publishRefusal({ ...clean, tagCommit: undefined }),
    /tag v9\.2\.0 does not exist/,
  );
  assert.match(
    publishRefusal({ ...clean, head: 'b'.repeat(40) }),
    /HEAD bbbbbbbbb is not the commit of v9\.2\.0 \(aaaaaaaaa\)/,
  );
});
