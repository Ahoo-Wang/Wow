/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { scopes, unitPackages } from './ci-scope.mjs';

const script = new URL('./ci-scope.mjs', import.meta.url).pathname;
// The TypeScript workflow scopes; `compatDebt` and `package` are derived from
// them, and `mixedVersion` gates a Kotlin workflow; each has its own tests.
const gates = paths =>
  Object.entries(scopes(paths)).filter(
    ([key]) =>
      key !== 'compatDebt' && key !== 'package' && key !== 'mixedVersion',
  );
const all = paths => gates(paths).every(([, value]) => value);
const none = paths => gates(paths).every(([, value]) => !value);
const on = paths =>
  gates(paths)
    .filter(([, value]) => value)
    .map(([key]) => key);

test('workspace configuration, CI scripts and unknown paths run every gate', () => {
  for (const path of [
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    // A dependency patch pnpm-workspace.yaml applies.
    'patches/jsdom@29.1.1.patch',
    'tsconfig.base.json',
    '.github/scripts/ci-scope.mjs',
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
  // Scheduled and dispatched only: nothing it defines runs on a pull request.
  assert.deepEqual(
    on(['.github/workflows/typescript-storybook-browsers.yml']),
    ['typescript', 'workflows'],
  );
});

test('any other workflow, the release workflow included, runs only the workflow lint', () => {
  for (const path of [
    '.github/workflows/package-deploy.yml',
    '.github/workflows/documentation-deploy.yml',
    '.github/workflows/view-store-deploy.yml',
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
    // The view store server the contract starts for WowViewStore.
    'view-store/wow-view-store-api/build.gradle.kts',
    'view-store/wow-view-store-starter/src/main/kotlin/ViewStoreRoutes.kt',
  ])
    assert.deepEqual(on([path]), ['contract'], path);
});

test('the view store client runs its unit tests, the site and the contract', () => {
  for (const path of [
    'typescript/wow-view-store/src/wowViewStore.ts',
    'typescript/wow-view-store/package.json',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'sdk', 'docs', 'contract'],
      path,
    );
  assert.deepEqual(on(['typescript/wow-view-store/test/errors.test.ts']), [
    'typescript',
    'sdk',
  ]);
  assert.deepEqual(on(['typescript/wow-view-store/README.md']), [
    'docs',
    'packageDocs',
  ]);
  // The engine's port conformance suite runs over WowViewStore in the contract.
  assert.deepEqual(
    on(['typescript/wow-view-engine/test/conformance/viewStoreConformance.ts']),
    ['typescript', 'viewEngine', 'contract'],
  );
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

test('view-engine and what it builds on run its suite, the stories, the site and the contract', () => {
  // view-engine doesn't depend on wow-react, so a wow-react change skips its suite.
  assert.deepEqual(on(['typescript/wow-react/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'storybook',
    'contract',
  ]);
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-engine/docs/design/ui/layout.svg',
    'typescript/wow-view-engine/package.json',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'docs', 'viewEngine', 'storybook', 'contract'],
      path,
    );
  // Its own tests stay out of the contract: they run no server.
  assert.ok(!scopes(['typescript/wow-view-engine/test/setup.ts']).contract);
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

test('the TypeScript Skills run the site, which checks their samples', () => {
  for (const path of [
    'skills/wow-view-definition/SKILL.md',
    'skills/wow-view-definition/references/choices.md',
    'skills/wow-view-host/references/actions.md',
    'skills/wow-client/SKILL.md',
    'skills/wow-client/references/generator.md',
  ])
    assert.deepEqual(on([path]), ['docs'], path);
  for (const path of [
    'skills/wow-view-host/evals/b74-view-host-run-early/prompt.md',
    'skills/wow-client/evals/a86-client-command/graders/skill-fired.md',
    'skills/wow-view-definition/agents/openai.yaml',
    'skills/wow-data-query/SKILL.md',
  ])
    assert.ok(none([path]), path);
});

test("the site's view-engine pages also run the tests that read them", () => {
  for (const path of [
    'documentation/docs/en/reference/typescript/wow-view-engine/store.md',
    'documentation/docs/zh/reference/typescript/wow-view-engine/issues.md',
    'documentation/docs/en/reference/typescript/wow-view-engine/symbols-ui.md',
    'documentation/docs/en/guide/typescript/view-engine-theming.md',
    'documentation/docs/zh/guide/typescript/view-engine.md',
  ])
    assert.deepEqual(on([path]), ['docs', 'viewEngineDocs'], path);
  for (const path of [
    'documentation/docs/en/reference/typescript/wow-view-store/index.md',
    'documentation/docs/en/guide/typescript/quick-start.md',
  ])
    assert.deepEqual(on([path]), ['docs'], path);
});

test("every view-engine test that reads the site's pages is in test:docs", () => {
  // A site page alone runs only test:docs of the view engine's suite
  // (viewEngineDocs, above), so a test reading one outside it would not run
  // when that page changes — as the theming guide's token tables once were.
  const root = new URL('../../typescript/wow-view-engine/', import.meta.url);
  const testDocs = JSON.parse(
    readFileSync(new URL('package.json', root)),
  ).scripts['test:docs'].split(/\s+/);
  const readers = readdirSync(new URL('test/', root))
    .filter(name => name.endsWith('.test.ts'))
    .filter(name =>
      /documentation\/|fixtures\/themeDocs/.test(
        readFileSync(new URL(`test/${name}`, root), 'utf8'),
      ),
    );
  assert.ok(readers.includes('themeFiles.test.ts'), readers.join(', '));
  for (const name of readers)
    assert.ok(testDocs.includes(`test/${name}`), `test:docs misses ${name}`);
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
    'contract',
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

test('wire modules, the example cluster, dependency versions and the harness run the mixed-version test', () => {
  for (const path of [
    'wow-api/src/main/kotlin/me/ahoo/wow/api/command/CommandMessage.kt',
    'wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitHeader.kt',
    'wow-kafka/src/main/kotlin/me/ahoo/wow/kafka/AbstractKafkaBus.kt',
    'wow-redis/src/main/kotlin/me/ahoo/wow/redis/bus/AbstractRedisMessageBus.kt',
    'wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt',
    'wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/wait/CommandWaitHandlerFunction.kt',
    'wow-spring/src/main/kotlin/A.kt',
    'wow-spring-boot-starter/src/main/kotlin/A.kt',
    'example/example-api/src/main/kotlin/A.kt',
    'example/example-domain/src/main/kotlin/A.kt',
    'example/example-server/src/dist/config/application.yaml',
    'gradle/libs.versions.toml',
    'test/wow-it/src/integrationTest/kotlin/me/ahoo/wow/it/mixed/MixedVersionClusterTest.kt',
    'test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/container/ContainerImages.kt',
    // An unknown path runs everything.
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).mixedVersion, path);
  for (const path of [
    'wow-openapi/src/main/kotlin/Router.kt',
    'wow-elasticsearch/src/main/kotlin/A.kt',
    'wow-springdoc/src/main/kotlin/A.kt',
    'example/README.md',
    'test/wow-tck/src/main/kotlin/Spec.kt',
    'typescript/wow-client/src/index.ts',
    'documentation/docs/en/guide/test-runtime.md',
    'compensation/wow-compensation-api/src/main/kotlin/Api.kt',
    '.github/workflows/integration-test.yml',
  ])
    assert.ok(!scopes([path]).mixedVersion, path);
  assert.deepEqual(
    Object.entries(scopes(['.github/workflows/mixed-version.yml']))
      .filter(([, value]) => value)
      .map(([key]) => key),
    ['workflows', 'mixedVersion'],
  );
  // Still only the same-source contract among the TypeScript scopes.
  assert.deepEqual(on(['wow-kafka/src/main/kotlin/A.kt']), ['contract']);
});

test('isolated changes retain their relevant validation', () => {
  assert.deepEqual(on(['documentation/docs/index.md']), ['docs']);
  assert.deepEqual(
    on(['wow-core/src/main/kotlin/A.kt', 'documentation/package.json']),
    ['docs', 'contract'],
  );
  assert.deepEqual(
    on(['typescript/wow-react/src/index.ts', 'example/README.md']),
    ['typescript', 'sdk', 'docs', 'storybook', 'contract'],
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
        .join('') +
      // Everything runs quality, which checks the ledger itself.
      'compatDebt=false\n' +
      `package=${value}\n` +
      `mixedVersion=${value}\n` +
      `unitPackages=${value ? '["wow-client","wow-react","wow-generator","wow-view-store"]' : '[]'}\n`;
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

test("a library's own tests, goldens and scripts rerun only that package", () => {
  for (const path of [
    'typescript/wow-client/test/dsl/filter.test.ts',
    'typescript/wow-client/test/golden/dsl-wire.json',
    'typescript/wow-client/scripts/api-report.mjs',
    'typescript/wow-react/test/requestStateTable.test.tsx',
    'typescript/wow-generator/test/openaiGolden.test.ts',
    'typescript/wow-generator/expected/demo-spec/types.ts',
  ])
    assert.deepEqual(on([path]), ['typescript', 'sdk'], path);
  assert.deepEqual(on(['typescript/wow-view-engine/test/setup.ts']), [
    'typescript',
    'viewEngine',
  ]);
  // Source still reaches everything built on it.
  assert.ok(scopes(['typescript/wow-client/src/index.ts']).viewEngine);
  assert.ok(scopes(['typescript/wow-generator/src/cli.ts']).contract);
});

test('the unit matrix reruns a package and the packages built on it', () => {
  const every = ['wow-client', 'wow-react', 'wow-generator', 'wow-view-store'];
  // The client's sources reach the hooks, the generator and the view store,
  // which import its dist; theirs reach only themselves.
  assert.deepEqual(unitPackages(['typescript/wow-client/src/index.ts']), every);
  assert.deepEqual(unitPackages(['typescript/wow-client/package.json']), every);
  assert.deepEqual(unitPackages(['typescript/wow-react/src/index.ts']), [
    'wow-react',
  ]);
  assert.deepEqual(unitPackages(['typescript/wow-generator/src/cli.ts']), [
    'wow-generator',
  ]);
  assert.deepEqual(
    unitPackages(['typescript/wow-view-store/src/wowViewStore.ts']),
    ['wow-view-store'],
  );
  // A library's own tests, goldens and scripts rerun only that library.
  assert.deepEqual(
    unitPackages(['typescript/wow-client/test/dsl/filter.test.ts']),
    ['wow-client'],
  );
  assert.deepEqual(
    unitPackages([
      'typescript/wow-generator/expected/demo-spec/types.ts',
      'typescript/wow-react/test/requestStateTable.test.tsx',
    ]),
    ['wow-react', 'wow-generator'],
  );
  // The Kotlin contract snapshots wow-client's tests read rerun wow-client.
  assert.deepEqual(
    unitPackages([
      'wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json',
    ]),
    ['wow-client'],
  );
  assert.deepEqual(
    on([
      'wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json',
    ]),
    ['sdk', 'contract'],
  );
  // Whatever else turns the sdk scope on reruns them all.
  for (const path of [
    '.github/workflows/typescript.yml',
    'pnpm-lock.yaml',
    'typescript/new-package/src/index.ts',
    'new-directory/index.ts',
  ])
    assert.deepEqual(unitPackages([path]), every, path);
  // No package without the sdk scope, and a package whenever it is on: the
  // matrix is never empty for a job that runs.
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-react/README.md',
    'typescript/storybook/stories/view-engine/Home.stories.tsx',
    'wow-core/src/main/kotlin/A.kt',
  ])
    assert.deepEqual(unitPackages([path]), [], path);
  for (const paths of [
    ['typescript/wow-react/README.md', 'typescript/wow-react/src/index.ts'],
    ['typescript/wow-view-engine/src/index.ts', 'eslint.config.js'],
  ])
    assert.equal(unitPackages(paths).length > 0, scopes(paths).sdk, paths);
});

test('a Kotlin main source runs the compat-debt ledger unless quality does', () => {
  const kotlin = 'wow-api/src/main/kotlin/me/ahoo/wow/api/query/Condition.kt';
  assert.ok(scopes([kotlin]).compatDebt);
  assert.ok(!scopes([kotlin]).typescript);
  // Quality checks the ledger already when TypeScript changed too.
  assert.ok(!scopes([kotlin, 'typescript/wow-client/src/index.ts']).compatDebt);
  // An unknown path runs everything, quality among it.
  assert.ok(!scopes(['new-directory/index.ts']).compatDebt);
  // Kotlin tests hold no removal marker the ledger pairs.
  assert.ok(
    !scopes(['wow-api/src/test/kotlin/me/ahoo/wow/api/query/ConditionTest.kt'])
      .compatDebt,
  );
});

test('a path inside a published package runs the Package job without the unit matrix', () => {
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-engine/package.json',
    'typescript/wow-view-engine/README.md',
    'typescript/wow-view-engine/test/setup.ts',
    'typescript/wow-react/README.md',
  ]) {
    assert.ok(scopes([path]).package, path);
    assert.ok(!scopes([path]).sdk, path);
    assert.deepEqual(unitPackages([path]), [], path);
  }
  // Whatever turns sdk on runs it too; a package nobody publishes does not.
  for (const path of [
    'typescript/wow-client/src/index.ts',
    'pnpm-lock.yaml',
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).package, path);
  for (const path of [
    'typescript/storybook/stories/view-engine/Home.stories.tsx',
    'typescript/integration-test/test/view-store/wowViewStore.test.ts',
    'typescript/AGENTS.md',
    'wow-core/src/main/kotlin/A.kt',
    'documentation/docs/en/index.md',
  ])
    assert.ok(!scopes([path]).package, path);
});
