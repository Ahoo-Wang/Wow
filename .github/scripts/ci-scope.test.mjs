/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { scopes } from './ci-scope.mjs';

const script = new URL('./ci-scope.mjs', import.meta.url).pathname;
const all = paths => Object.values(scopes(paths)).every(Boolean);
const none = paths => Object.values(scopes(paths)).every(value => !value);
const on = paths =>
  Object.entries(scopes(paths))
    .filter(([, value]) => value)
    .map(([key]) => key);

test('workspace configuration, CI scripts and unknown paths run every gate', () => {
  for (const path of [
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    'tsconfig.base.json',
    '.github/scripts/ci-scope.mjs',
    'view-store/wow-view-store-api/build.gradle.kts',
    'new-directory/index.ts',
  ])
    assert.ok(all([path]), path);
});

test('lint and format rules run only the static checks and the site build', () => {
  for (const path of ['eslint.config.js', '.prettierrc', '.prettierignore'])
    assert.deepEqual(on([path]), ['typescript', 'docs'], path);
});

test('each TypeScript workflow file runs the jobs it defines and the static checks', () => {
  assert.deepEqual(on(['.github/workflows/typescript.yml']), [
    'typescript',
    'docs',
    'viewEngine',
  ]);
  assert.deepEqual(on(['.github/workflows/typescript-storybook.yml']), [
    'typescript',
    'storybook',
  ]);
});

test('example server sources and the Gradle build run only the same-source contract', () => {
  for (const path of [
    'wow-core/src/main/kotlin/me/ahoo/wow/Wow.kt',
    'wow-openapi/src/main/kotlin/Router.kt',
    'wow-benchmarks/build.gradle.kts',
    'example/example-server/build.gradle.kts',
    'example/example-server/src/dist/config/application.yaml',
    'schema/wow-schema.json',
    'test/wow-mock/src/main/kotlin/Mock.kt',
    'compensation/wow-compensation-api/src/main/kotlin/Api.kt',
    'compensation/wow-compensation-core/build.gradle.kts',
    'build-logic/build.gradle.kts',
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle/libs.versions.toml',
    'gradlew',
  ])
    assert.deepEqual(on([path]), ['contract'], path);
});

test('the version source and the compat-debt ledger run the static checks', () => {
  assert.deepEqual(on(['gradle.properties']), ['typescript', 'contract']);
  assert.deepEqual(on(['docs/compat-debt.md']), ['typescript']);
});

test('the client, generator, integration tests and contract workflow run both contracts', () => {
  assert.deepEqual(on(['typescript/wow-client/src/index.ts']), [
    'typescript',
    'viewEngine',
    'storybook',
    'contract',
    'legacyContract',
  ]);
  for (const path of [
    'typescript/wow-generator/src/cli.ts',
    'typescript/integration-test/src/generated/index.ts',
    '.github/workflows/typescript-contract.yml',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'contract', 'legacyContract'],
      path,
    );
});

test('view-engine and what it builds on run its suite, the stories and the site', () => {
  for (const path of [
    'typescript/wow-react/src/index.ts',
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-engine/test/setup.ts',
    'typescript/wow-view-engine/docs/design/progress.md',
    'typescript/wow-view-engine/package.json',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'docs', 'viewEngine', 'storybook'],
      path,
    );
});

test('the stories run Storybook, the static checks and the site', () => {
  for (const path of [
    'typescript/storybook/stories/view-engine/Home.stories.tsx',
    'typescript/storybook/.storybook/main.ts',
    'typescript/storybook/package.json',
    'typescript/storybook/README.md',
  ])
    assert.deepEqual(on([path]), ['typescript', 'docs', 'storybook'], path);
});

test('Storybook and the packages it renders also build the site', () => {
  for (const path of [
    'typescript/storybook/stories/react/WowQueryHooks.stories.tsx',
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-react/src/index.ts',
  ])
    assert.ok(scopes([path]).docs, path);
  for (const path of [
    'typescript/wow-generator/src/cli.ts',
    'typescript/integration-test/src/generated/index.ts',
  ])
    assert.ok(!scopes([path]).docs, path);
});

test('Kotlin, Gradle, dashboard and prose changes skip the TypeScript workflows', () => {
  for (const path of [
    'test/wow-tck/src/main/kotlin/Spec.kt',
    'test/wow-it/build.gradle.kts',
    'compensation/wow-compensation-domain/build.gradle.kts',
    'compensation/wow-compensation-server/build.gradle.kts',
    'compensation/dashboard/src/App.tsx',
    'config/detekt/detekt.yml',
    'gradlew.bat',
    'docs/superpowers/specs/a.md',
    'skills/wow-develop/SKILL.md',
    '.claude/skills/shadcn/SKILL.md',
    '.github/workflows/local-test.yml',
    '.github/workflows/package-deploy.yml',
    '.github/scripts/pr-safety.sh',
    'README.md',
    'AGENTS.md',
    'typescript/MIGRATION.md',
    'typescript/AGENTS.md',
  ])
    assert.ok(none([path]), path);
});

test('isolated changes retain their relevant validation', () => {
  assert.deepEqual(on(['documentation/docs/index.md']), ['docs']);
  assert.deepEqual(
    on(['wow-core/src/main/kotlin/A.kt', 'documentation/package.json']),
    ['docs', 'contract'],
  );
  assert.deepEqual(
    on(['typescript/wow-react/src/index.ts', 'example/README.md']),
    ['typescript', 'docs', 'viewEngine', 'storybook', 'contract'],
  );
  assert.deepEqual(
    on(['typescript/wow-generator/src/cli.ts', 'typescript/storybook/a.ts']),
    ['typescript', 'docs', 'storybook', 'contract', 'legacyContract'],
  );
  assert.ok(all(['README.md', 'pnpm-lock.yaml']));
  assert.ok(all(['wow-core/src/main/kotlin/A.kt', 'new-directory/index.ts']));
});

test('the command reads the diff and runs everything without a base', () => {
  const directory = mkdtempSync(join(tmpdir(), 'ci-scope-'));
  const git = (...args) =>
    execFileSync('git', args, { cwd: directory, encoding: 'utf8' }).trim();
  const run = env => {
    const output = join(directory, `output-${Math.random()}`);
    execFileSync(process.execPath, [script], {
      cwd: directory,
      env: { ...process.env, ...env, GITHUB_OUTPUT: output },
      stdio: 'pipe',
    });
    return readFileSync(output, 'utf8');
  };
  try {
    git('init', '-q');
    git('config', 'user.email', 'test@example.invalid');
    git('config', 'user.name', 'test');
    writeFileSync(join(directory, 'README.md'), 'base\n');
    git('add', '.');
    git('commit', '-qm', 'base');
    const base = git('rev-parse', 'HEAD');
    writeFileSync(join(directory, 'README.md'), 'changed\n');
    git('commit', '-qam', 'prose');
    const head = git('rev-parse', 'HEAD');

    const output = value =>
      [
        'typescript',
        'docs',
        'viewEngine',
        'storybook',
        'contract',
        'legacyContract',
      ]
        .map(key => `${key}=${value}\n`)
        .join('');
    assert.equal(run({ BASE_SHA: base, HEAD_SHA: head }), output(false));
    assert.equal(
      run({ BASE_SHA: '0'.repeat(40), HEAD_SHA: head }),
      output(true),
    );
    assert.equal(run({}), output(true));
    assert.throws(() => run({ BASE_SHA: 'missing-revision', HEAD_SHA: head }));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
