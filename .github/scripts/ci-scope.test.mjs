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
    'sdk',
    'docs',
    'viewEngine',
    'packageDocs',
    'viewEngineDocs',
    'workflows',
  ]);
  assert.deepEqual(on(['.github/workflows/typescript-storybook.yml']), [
    'typescript',
    'storybook',
    'workflows',
  ]);
  assert.deepEqual(on(['.github/workflows/typescript-contract.yml']), [
    'typescript',
    'contract',
    'legacyContract',
    'workflows',
  ]);
});

test('any other workflow, the release workflow included, runs only the workflow lint', () => {
  for (const path of [
    '.github/workflows/package-deploy.yml',
    '.github/workflows/documentation-deploy.yml',
    '.github/workflows/local-test.yml',
    '.github/workflows/gitee-sync.yml',
  ])
    assert.deepEqual(on([path]), ['workflows'], path);
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
    'sdk',
    'docs',
    'viewEngine',
    'storybook',
    'contract',
    'legacyContract',
  ]);
  assert.deepEqual(on(['typescript/wow-generator/src/cli.ts']), [
    'typescript',
    'sdk',
    'docs',
    'contract',
    'legacyContract',
  ]);
  assert.deepEqual(on(['typescript/integration-test/src/generated/index.ts']), [
    'typescript',
    'docs',
    'contract',
    'legacyContract',
  ]);
});

test('view-engine and what it builds on run its suite, the stories and the site', () => {
  assert.deepEqual(on(['typescript/wow-react/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngine',
    'storybook',
    'contract',
  ]);
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-engine/test/setup.ts',
    'typescript/wow-view-engine/docs/design/ui/layout.svg',
    'typescript/wow-view-engine/package.json',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'docs', 'viewEngine', 'storybook'],
      path,
    );
});

test('the other packages unit-test only when a package outside view-engine changes', () => {
  for (const path of [
    'typescript/wow-client/src/index.ts',
    'typescript/wow-react/src/index.ts',
    'typescript/wow-generator/src/cli.ts',
    'typescript/new-package/src/index.ts',
  ])
    assert.ok(scopes([path]).sdk, path);
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'typescript/storybook/stories/view-engine/Home.stories.tsx',
    'typescript/integration-test/src/generated/index.ts',
    'gradle.properties',
    'docs/compat-debt.md',
    'eslint.config.js',
    '.github/workflows/typescript-storybook.yml',
  ]) {
    assert.ok(!scopes([path]).sdk, path);
  }
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'eslint.config.js',
  ])
    assert.ok(scopes([path]).typescript, path);
});

test('the stories run Storybook, the static checks and the site', () => {
  for (const path of [
    'typescript/storybook/stories/view-engine/Home.stories.tsx',
    'typescript/storybook/.storybook/main.ts',
    'typescript/storybook/package.json',
  ])
    assert.deepEqual(on([path]), ['typescript', 'docs', 'storybook'], path);
});

test("view-engine's Markdown alone runs only its format check and the tests that read it", () => {
  for (const path of [
    'typescript/wow-view-engine/docs/design/progress.md',
    'typescript/wow-view-engine/docs/design/ui/layout.md',
    'typescript/wow-view-engine/AGENTS.md',
  ])
    assert.deepEqual(on([path]), ['packageDocs', 'viewEngineDocs'], path);
});

test('package READMEs also run the site, which compiles their samples', () => {
  for (const path of [
    'typescript/wow-view-engine/README.md',
    'typescript/wow-view-engine/README.zh-CN.md',
  ])
    assert.deepEqual(
      on([path]),
      ['docs', 'packageDocs', 'viewEngineDocs'],
      path,
    );
  for (const path of [
    'typescript/wow-client/README.md',
    'typescript/wow-client/README.zh-CN.md',
    'typescript/wow-generator/README.md',
    'typescript/wow-generator/README.zh-CN.md',
  ])
    assert.deepEqual(on([path]), ['docs', 'packageDocs'], path);
});

test('other Markdown under typescript/ alone runs only its format check', () => {
  for (const path of [
    'typescript/MIGRATION.md',
    'typescript/AGENTS.md',
    'typescript/wow-client/docs/superpowers/plans/a.md',
    'typescript/wow-react/AGENTS.md',
    'typescript/wow-react/README.md',
    'typescript/integration-test/README.md',
    'typescript/storybook/README.md',
  ])
    assert.deepEqual(on([path]), ['packageDocs'], path);
});

test('Markdown next to code keeps everything the code runs', () => {
  const docs = 'typescript/wow-view-engine/docs/design/decisions.md';
  // The code's own scopes run the Markdown's checks: quality formats every
  // changed file and the view-engine suite includes test:docs.
  assert.deepEqual(on([docs, 'typescript/wow-view-engine/src/index.ts']), [
    'typescript',
    'docs',
    'viewEngine',
    'storybook',
  ]);
  assert.deepEqual(on([docs, 'typescript/wow-client/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngine',
    'storybook',
    'contract',
    'legacyContract',
  ]);
  // Code that leaves the view-engine suite off keeps the light docs tests.
  assert.deepEqual(on([docs, 'typescript/wow-generator/src/cli.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngineDocs',
    'contract',
    'legacyContract',
  ]);
  assert.deepEqual(on([docs, 'documentation/docs/index.md']), [
    'docs',
    'packageDocs',
    'viewEngineDocs',
  ]);
  assert.deepEqual(on(['typescript/MIGRATION.md', 'gradle.properties']), [
    'typescript',
    'contract',
  ]);
  // The order of paths does not matter.
  assert.deepEqual(
    on(['typescript/wow-view-engine/src/index.ts', docs]),
    on([docs, 'typescript/wow-view-engine/src/index.ts']),
  );
  assert.ok(all([docs, 'pnpm-lock.yaml']));
});

test('Storybook and the packages the site renders or compiles samples against build the site', () => {
  for (const path of [
    'typescript/storybook/stories/react/WowQueryHooks.stories.tsx',
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-react/src/index.ts',
    'typescript/wow-client/src/index.ts',
    'typescript/wow-generator/src/cli.ts',
    'typescript/integration-test/src/generated/index.ts',
  ])
    assert.ok(scopes([path]).docs, path);
  for (const path of ['typescript/integration-test/test/wow/wowErrors.test.ts'])
    assert.ok(!scopes([path]).docs, path);
});

test('Kotlin, Gradle, dashboard and prose changes skip the TypeScript workflow jobs', () => {
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
    '.github/scripts/pr-safety.sh',
    'README.md',
    'AGENTS.md',
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
    ['typescript', 'sdk', 'docs', 'viewEngine', 'storybook', 'contract'],
  );
  assert.deepEqual(
    on(['typescript/wow-generator/src/cli.ts', 'typescript/storybook/a.ts']),
    ['typescript', 'sdk', 'docs', 'storybook', 'contract', 'legacyContract'],
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
        'sdk',
        'docs',
        'viewEngine',
        'packageDocs',
        'viewEngineDocs',
        'storybook',
        'contract',
        'legacyContract',
        'workflows',
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
