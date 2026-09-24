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

test('workspace configuration, CI scripts and unknown paths run every gate', () => {
  for (const path of [
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    'tsconfig.base.json',
    'eslint.config.js',
    '.prettierrc',
    '.prettierignore',
    '.github/scripts/ci-scope.mjs',
    '.github/workflows/typescript.yml',
    'view-store/wow-view-store-api/build.gradle.kts',
    'new-directory/index.ts',
  ])
    assert.ok(all([path]), path);
});

test('Kotlin, Gradle, dashboard and prose changes skip the TypeScript workflow', () => {
  for (const path of [
    'wow-core/src/main/kotlin/me/ahoo/wow/Wow.kt',
    'wow-benchmarks/build.gradle.kts',
    'test/wow-tck/src/main/kotlin/Spec.kt',
    'example/example-server/build.gradle.kts',
    'compensation/wow-compensation-domain/build.gradle.kts',
    'compensation/dashboard/src/App.tsx',
    'build.gradle.kts',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'gradlew',
    'schema/wow-schema.json',
    'docs/superpowers/specs/a.md',
    'skills/wow-develop/SKILL.md',
    '.claude/skills/shadcn/SKILL.md',
    '.github/workflows/local-test.yml',
    '.github/scripts/pr-safety.sh',
    'README.md',
    'AGENTS.md',
    'typescript/MIGRATION.md',
    'typescript/AGENTS.md',
  ])
    assert.ok(none([path]), path);
});

test('isolated changes retain their relevant validation', () => {
  assert.deepEqual(scopes(['documentation/docs/index.md']), {
    typescript: false,
    docs: true,
  });
  assert.deepEqual(scopes(['typescript/wow-client/src/index.ts']), {
    typescript: true,
    docs: false,
  });
  assert.deepEqual(
    scopes(['wow-core/src/main/kotlin/A.kt', 'documentation/package.json']),
    { typescript: false, docs: true },
  );
  assert.ok(all(['README.md', 'pnpm-lock.yaml']));
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

    assert.equal(
      run({ BASE_SHA: base, HEAD_SHA: head }),
      'typescript=false\ndocs=false\n',
    );
    assert.equal(
      run({ BASE_SHA: '0'.repeat(40), HEAD_SHA: head }),
      'typescript=true\ndocs=true\n',
    );
    assert.equal(run({}), 'typescript=true\ndocs=true\n');
    assert.throws(() => run({ BASE_SHA: 'missing-revision', HEAD_SHA: head }));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
