/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import {
  catalogEntries,
  concretePath,
  hygieneProblems,
  staleIgnoreEntries,
  staleLabelerGlobs,
  uncalledScripts,
  unusedCatalogEntries,
} from './repo-hygiene.mjs';

const CATALOG = `[versions]
kotlin = "2.4.21" # the compiler
jmh = "1.37"
guava = "33"

[libraries]
guava = { module = "com.google.guava:guava", version.ref = "guava" }
spring-boot-dependencies = { module = "org.springframework.boot:spring-boot-dependencies", version = "4" }
jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
mockk = { module = "io.mockk:mockk", version = "1" }

[bundles]
testing = ["mockk"]

[plugins]
kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
`;

test('the catalog is read by section', () => {
  assert.deepEqual(
    catalogEntries(CATALOG).map(({ section, name }) => `${section}.${name}`),
    [
      'versions.kotlin',
      'versions.jmh',
      'versions.guava',
      'libraries.guava',
      'libraries.spring-boot-dependencies',
      'libraries.jmh-core',
      'libraries.mockk',
      'bundles.testing',
      'plugins.kotlin',
      'plugins.kotlin-spring',
    ],
  );
});

test('catalog entries no build script uses are reported, versions through version.ref', () => {
  const scripts = [
    'plugins { alias(libs.plugins.kotlin) }\n',
    'dependencies { api(platform(libs.spring.boot.dependencies)); implementation(libs.guava.get()) }\n',
    'dependencies { testImplementation(libs.bundles.testing) }\n',
  ];
  assert.deepEqual(unusedCatalogEntries(CATALOG, scripts), [
    'versions.jmh',
    'libraries.jmh-core',
    'plugins.kotlin-spring',
  ]);
});

test('an accessor that only starts with an entry does not use it', () => {
  assert.deepEqual(
    unusedCatalogEntries(
      '[libraries]\nkotlin = { module = "a:b", version = "1" }\n',
      ['libs.kotlin.logging'],
    ),
    ['libraries.kotlin'],
  );
});

test('wildcards and names matched at any depth are not concrete paths', () => {
  assert.equal(concretePath('**/build/'), null);
  assert.equal(concretePath('node_modules/'), null);
  assert.equal(concretePath('*.tsbuildinfo'), null);
  assert.equal(concretePath('/*'), null);
  assert.equal(concretePath('# typescript/x/'), null);
  assert.equal(concretePath('/build/'), 'build');
  assert.equal(concretePath('!/typescript/'), 'typescript');
  assert.equal(concretePath('typescript/*/dist/'), 'typescript/*/dist');
});

const FILES = [
  'typescript/wow-client/src/index.ts',
  'typescript/wow-client/test/api/root.api.md',
  'wow-benchmarks/results/reports/e2e.md',
  'api/api/build.gradle.kts',
];

test('a .prettierignore entry must match a tracked file', () => {
  const text = [
    'typescript/wow-client/test/api/',
    'typescript/*/src/',
    'typescript/wow-generator/expected/',
    'typescript/wow-client/test/ap',
    '!/typescript/',
  ].join('\n');
  assert.deepEqual(staleIgnoreEntries(text, FILES, { tracked: true }), [
    'typescript/wow-generator/expected',
    'typescript/wow-client/test/ap',
  ]);
});

test('a .gitignore entry must sit in a directory that holds a tracked file', () => {
  const text = [
    '/build/',
    'wow-benchmarks/results/jmh/',
    'typescript/*/dist/',
    'compensation/dashboard/dist/',
  ].join('\n');
  assert.deepEqual(staleIgnoreEntries(text, FILES, { tracked: false }), [
    'compensation/dashboard/dist',
  ]);
});

test('labeler globs must match a tracked file; branch patterns and pure wildcards are skipped', () => {
  const yaml = `dependencies:
  - head-branch:
      - '^renovate/'
'area: api':
  - changed-files:
      - any-glob-to-any-file:
          - 'api/**'
          - 'wow-core/**'
          - '**/*.md'
          - '*.gradle.kts'
          - 'api/ap'
`;
  assert.deepEqual(staleLabelerGlobs(yaml, FILES), ['wow-core/**', 'api/ap']);
});

test('a CI script must be named by a workflow, a package.json script or another script', () => {
  const files = [
    '.github/scripts/called.mjs',
    '.github/scripts/called.test.mjs',
    '.github/scripts/imported.mjs',
    '.github/scripts/orphan.mjs',
    '.github/scripts/orphan.test.mjs',
    '.github/workflows/ci.yml',
    'typescript/wow-client/scripts/verify-package.mjs',
  ];
  const texts = {
    '.github/workflows/ci.yml': 'run: node .github/scripts/called.mjs',
    'typescript/wow-client/scripts/verify-package.mjs':
      "import '../../../.github/scripts/imported.mjs'",
    '.github/scripts/called.test.mjs': "import './orphan.mjs'",
    '.github/scripts/orphan.mjs': '// orphan.mjs names itself',
  };
  assert.deepEqual(
    uncalledScripts(files, file => texts[file] ?? ''),
    ['.github/scripts/orphan.mjs'],
  );
});

function repository(files) {
  const root = mkdtempSync(join(tmpdir(), 'repo-hygiene-'));
  for (const [file, text] of Object.entries(files)) {
    mkdirSync(dirname(join(root, file)), { recursive: true });
    writeFileSync(join(root, file), text);
  }
  execFileSync('git', ['init', '-q'], { cwd: root });
  execFileSync('git', ['add', '-A'], { cwd: root });
  return root;
}

test('allowed problems pass, and an allowance that is no longer a problem fails', () => {
  const root = repository({
    'gradle/libs.versions.toml': CATALOG,
    'build.gradle.kts':
      'plugins { alias(libs.plugins.kotlin); alias(libs.plugins.kotlin.spring) }\n' +
      'dependencies { implementation(libs.guava); api(platform(libs.spring.boot.dependencies)); testImplementation(libs.bundles.testing) }\n',
    '.gitignore': '**/build/\n',
  });
  try {
    const allowed = new Map([
      ['gradle/libs.versions.toml: versions.jmh', '#1'],
      ['gradle/libs.versions.toml: libraries.jmh-core', '#1'],
      ['gradle/libs.versions.toml: libraries.guava', '#2'],
    ]);
    assert.deepEqual(hygieneProblems(root, allowed), [
      'gradle/libs.versions.toml: libraries.guava: allowed for #2, but no longer a problem; delete it from ALLOWED',
    ]);
    assert.deepEqual(hygieneProblems(root, new Map()), [
      'gradle/libs.versions.toml: versions.jmh: no build script uses it',
      'gradle/libs.versions.toml: libraries.jmh-core: no build script uses it',
    ]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('the repository holds', () => {
  assert.deepEqual(hygieneProblems(), []);
});
