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
// The TypeScript scopes; `package` is derived from them, `jvm` gates the JVM jobs, and `viewStoreContract`,
// `opsConsole` and `catalogConsole` are decided apart from the rules; each has its own tests.
const KOTLIN_GATES = new Set([
  'package',
  'jvm',
  'viewStoreContract',
  'opsConsole',
  'catalogConsole',
]);
const gates = paths =>
  Object.entries(scopes(paths)).filter(([key]) => !KOTLIN_GATES.has(key));
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

test('ci.yml runs every job it defines, and the static checks', () => {
  assert.deepEqual(on(['.github/workflows/ci.yml']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngine',
    'packageDocs',
    'viewEngineDocs',
    'storybook',
    'workflows',
  ]);
  const ci = scopes(['.github/workflows/ci.yml']);
  assert.ok(
    ci.jvm &&
      ci.viewStoreContract &&
      ci.opsConsole &&
      ci.catalogConsole &&
      ci.package,
  );
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
    '.github/workflows/v10-image-deploy.yml',
    '.github/workflows/nightly.yml',
    '.github/workflows/gitee-sync.yml',
  ])
    assert.deepEqual(on([path]), ['workflows'], path);
});

test('the Gradle build, the dependency versions and the examples skip the TypeScript workflow jobs', () => {
  for (const path of [
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle/libs.versions.toml',
    'gradlew',
    'bom/dependencies/build.gradle.kts',
    'test/benchmarks/README.md',
    'test/benchmarks/results/reports/v10-counts-e1-e3.md',
    'example/README.md',
  ])
    assert.ok(none([path]), path);
});

test('the view store client runs its unit tests, the site and the stories', () => {
  for (const path of [
    'typescript/wow-view-store/src/wowViewStore.ts',
    'typescript/wow-view-store/src/wowViewStore.ts',
    'typescript/wow-view-store/package.json',
  ])
    assert.deepEqual(
      on([path]),
      ['typescript', 'sdk', 'docs', 'storybook'],
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
  assert.deepEqual(
    on(['typescript/wow-view-engine/test/conformance/viewStoreConformance.ts']),
    ['typescript', 'viewEngine'],
  );
});

test('the version source runs the static checks', () => {
  assert.deepEqual(on(['gradle.properties']), ['typescript']);
});

test('the client, the generator and the generated clients the site compiles against', () => {
  assert.deepEqual(on(['typescript/wow-client/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngine',
    'storybook',
  ]);
  assert.deepEqual(on(['typescript/wow-generator/src/cli.ts']), [
    'typescript',
    'sdk',
    'docs',
  ]);
  assert.deepEqual(on(['example/eshop/web/src/generated/basket/index.ts']), [
    'docs',
  ]);
  assert.deepEqual(on(['typescript/integration-test/test/a.test.ts']), [
    'typescript',
  ]);
});

test('view-engine and what it builds on run its suite, the stories and the site', () => {
  // view-engine doesn't depend on wow-react, so a wow-react change skips its suite.
  assert.deepEqual(on(['typescript/wow-react/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'storybook',
  ]);
  for (const path of [
    'typescript/wow-view-engine/src/index.ts',
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
    'example/eshop/web/src/generated/basket/index.ts',
    'gradle.properties',
    'eslint.config.js',
    '.github/workflows/typescript-storybook-browsers.yml',
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
    'documentation/docs/en/typescript/view-engine/theming.md',
    'documentation/docs/zh/typescript/view-engine/index.md',
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
  ]);
  assert.deepEqual(on([docs, 'typescript/wow-client/src/index.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngine',
    'storybook',
  ]);
  // Code that leaves the view-engine suite off keeps the light docs tests.
  assert.deepEqual(on([docs, 'typescript/wow-generator/src/cli.ts']), [
    'typescript',
    'sdk',
    'docs',
    'viewEngineDocs',
  ]);
  assert.deepEqual(on([docs, 'documentation/docs/index.md']), [
    'docs',
    'packageDocs',
    'viewEngineDocs',
  ]);
  assert.deepEqual(on(['typescript/MIGRATION.md', 'gradle.properties']), [
    'typescript',
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
    'example/eshop/web/src/generated/basket/index.ts',
  ])
    assert.ok(scopes([path]).docs, path);
  for (const path of [
    'typescript/integration-test/test/view-store/service.test.ts',
  ])
    assert.ok(!scopes([path]).docs, path);
});

test('Kotlin, Gradle, console and prose changes skip the TypeScript workflow jobs', () => {
  for (const path of [
    'test/tck/src/main/kotlin/Spec.kt',
    'deploy/ops/deployment.yaml',
    'platform/ops/console/src/main.tsx',
    'platform/ops/console/package.json',
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

test('V10 modules skip the TypeScript workflow jobs', () => {
  for (const path of [
    'api/api/src/main/kotlin/me/ahoo/wow/api/decide/Command.kt',
    'api/manifest/build.gradle.kts',
    'compiler/compiler/src/main/kotlin/me/ahoo/wow/compiler/model/ModelSource.kt',
    'compiler/gradle-plugin/src/functionalTest/kotlin/A.kt',
    'test/tck/src/main/kotlin/A.kt',
    'core/kernel/src/main/kotlin/A.kt',
    'backends/mongo/src/integrationTest/kotlin/A.kt',
    'backends/postgres/build.gradle.kts',
    'edge/http/src/main/kotlin/A.kt',
    'host/spring-boot/src/main/kotlin/A.kt',
    'migration/migrate-data/src/main/kotlin/A.kt',
    'test/architecture/src/test/kotlin/A.kt',
    'test/lease-spike/src/integrationTest/kotlin/A.kt',
    'edge/cosec/build.gradle.kts',
    'extensions/cosid/build.gradle.kts',
    'query/query/src/main/kotlin/A.kt',
    'backends/kafka/build.gradle.kts',
    'backends/clickhouse/src/main/kotlin/A.kt',
    'example/eshop/catalog/catalog-domain/src/main/kotlin/A.kt',
    'example/eshop/e2e/src/integrationTest/kotlin/A.kt',
    'example/eshop/README.md',
    'example/quickstart/src/main/kotlin/A.kt',
    'example/java-smoke/src/main/java/A.java',
  ])
    assert.ok(none([path]), path);
});

test('the modules, their tests, the platform and the root build run the JVM jobs', () => {
  for (const path of [
    'api/api/src/main/kotlin/me/ahoo/wow/api/decide/Command.kt',
    'compiler/gradle-plugin/src/functionalTest/kotlin/A.kt',
    'core/kernel/src/main/kotlin/A.kt',
    'backends/mongo/src/integrationTest/kotlin/A.kt',
    'edge/http/src/main/kotlin/A.kt',
    'host/spring-boot/src/main/kotlin/A.kt',
    'migration/migrate-data/build.gradle.kts',
    'platform/ops/ops-domain/src/main/kotlin/A.kt',
    'platform/ops/console/src/main.tsx',
    'platform/view-store/view-store-server/manifest/baseline.json',
    'test/architecture/src/test/kotlin/A.kt',
    'test/routing-spike/src/integrationTest/kotlin/A.kt',
    'test/lease-spike/src/integrationTest/kotlin/A.kt',
    'test/test-dsl/src/main/kotlin/A.kt',
    'test/tck/src/main/kotlin/A.kt',
    'test/tck-containers/build.gradle.kts',
    // The coverage reports ci.yml uploads (coverageReport).
    'test/code-coverage-report/build.gradle.kts',
    'edge/cosec/src/main/kotlin/A.kt',
    'extensions/cosid/src/main/kotlin/A.kt',
    'query/query/src/main/kotlin/A.kt',
    'backends/kafka/src/main/kotlin/A.kt',
    'backends/clickhouse/build.gradle.kts',
    'example/eshop/payment/payment-server/build.gradle.kts',
    'example/eshop/e2e/build.gradle.kts',
    'example/eshop/DX-GAPS.md',
    'example/quickstart/build.gradle.kts',
    'example/java-smoke/build.gradle.kts',
    // The V10-against-9.x end-to-end benchmark (D224): its V10 side is in :check.
    'test/benchmarks/server/build.gradle.kts',
    'test/benchmarks/load/src/main/kotlin/A.kt',
    'test/benchmarks/harness/bench.sh',
    'bom/bom/build.gradle.kts',
    'bom/dependencies/build.gradle.kts',
    'gradle/libs.versions.toml',
    'config/detekt/detekt.yml',
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    'gradlew',
    '.github/workflows/ci.yml',
    // An unknown path or a CI script runs everything.
    '.github/scripts/ci-scope.mjs',
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).jvm, path);
  for (const path of [
    'typescript/wow-client/src/index.ts',
    'documentation/designs/v10/roadmap.md',
    'documentation/docs/en/guide/v10/index.md',
    // Its samples are held by docs-samples.mjs, in the docs-layout job of every pull request.
    'documentation/docs/en/guide/v10/quickstart.md',
    'README.md',
    'codecov.yml',
    '.github/workflows/nightly.yml',
  ])
    assert.ok(!scopes([path]).jvm, path);
  // A V10 path keeps the scopes its rule gives it.
  assert.deepEqual(on(['gradle.properties']), ['typescript']);
  assert.ok(scopes(['.github/workflows/ci.yml']).workflows);
});

test('the V10 store, what its suites import, the service and its modules run the V10 contract', () => {
  for (const path of [
    'typescript/wow-view-store/src/wowViewStore.ts',
    'typescript/wow-view-store/package.json',
    'typescript/wow-client/src/index.ts',
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-engine/test/conformance/viewStoreConformance.ts',
    'typescript/integration-test/test/view-store/engine.test.ts',
    'typescript/integration-test/vitest.config.ts',
    'typescript/integration-test/package.json',
    'platform/view-store/view-store-server/src/main/kotlin/A.kt',
    'platform/view-store/view-store-domain/build.gradle.kts',
    'api/api/src/main/kotlin/A.kt',
    'compiler/compiler/src/main/kotlin/A.kt',
    'core/kernel/src/main/kotlin/A.kt',
    'query/query-tck/src/main/kotlin/A.kt',
    'backends/postgres/src/main/kotlin/A.kt',
    'edge/http/src/main/kotlin/A.kt',
    'host/spring-boot/src/main/kotlin/A.kt',
    'test/tck/src/main/kotlin/A.kt',
    'test/test-dsl/src/main/kotlin/A.kt',
    'bom/dependencies/build.gradle.kts',
    'gradle/libs.versions.toml',
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    'gradlew',
    '.github/workflows/ci.yml',
    // An unknown path or a CI script runs everything.
    '.github/scripts/ci-scope.mjs',
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).viewStoreContract, path);
  for (const path of [
    'typescript/wow-view-store/README.md',
    'typescript/wow-view-store/test/wowViewStore.test.ts',
    'typescript/wow-client/test/index.test.ts',
    'typescript/wow-view-engine/test/setup.ts',
    'typescript/wow-view-engine/README.md',
    'typescript/wow-react/src/index.ts',
    'typescript/wow-generator/src/cli.ts',
    'typescript/storybook/stories/view-engine/WowViewStore.test.stories.tsx',
    'typescript/integration-test/README.md',
    'platform/ops/ops-domain/src/main/kotlin/A.kt',
    'platform/view-store/README.md',
    'migration/migrate-data/build.gradle.kts',
    'extensions/cosid/src/main/kotlin/A.kt',
    'test/architecture/src/test/kotlin/A.kt',
    'example/eshop/e2e/build.gradle.kts',
    'documentation/designs/v10/roadmap.md',
    '.github/workflows/package-deploy.yml',
  ])
    assert.ok(!scopes([path]).viewStoreContract, path);
  // A V10 contract path keeps the scopes its rule gives it.
  assert.deepEqual(
    on(['platform/view-store/view-store-server/build.gradle.kts']),
    [],
  );
  assert.ok(
    scopes(['platform/view-store/view-store-server/build.gradle.kts']).jvm,
  );
});

test('the ops console, the ops service and what the console builds with run the ops-console jobs', () => {
  for (const path of [
    'platform/ops/console/src/main.tsx',
    'platform/ops/console/package.json',
    'platform/ops/ops-server/src/main/resources/application.yaml',
    'platform/ops/ops-server/manifest/baseline.json',
    'platform/ops/ops-domain/src/main/kotlin/A.kt',
    'typescript/wow-client/src/index.ts',
    'typescript/wow-generator/src/cli.ts',
    'typescript/wow-view-engine/src/index.ts',
    'typescript/wow-view-store/src/source.ts',
    'package.json',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    'tsconfig.base.json',
    '.github/workflows/ci.yml',
    // An unknown path or a CI script runs everything.
    '.github/scripts/ci-scope.mjs',
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).opsConsole, path);
  for (const path of [
    'platform/view-store/view-store-server/manifest/baseline.json',
    'typescript/wow-react/src/index.ts',
    'core/kernel/src/main/kotlin/A.kt',
    'README.md',
    '.github/workflows/package-deploy.yml',
  ])
    assert.ok(!scopes([path]).opsConsole, path);
  // The console's own path turns on no TypeScript scope, and keeps the JVM's.
  assert.deepEqual(on(['platform/ops/console/src/main.tsx']), []);
  assert.ok(scopes(['platform/ops/console/src/main.tsx']).jvm);
});

test('the catalog console, the catalog service and what the console builds with run the catalog-console jobs', () => {
  for (const path of [
    'platform/analytics/catalog/console/src/main.tsx',
    'platform/analytics/catalog/console/package.json',
    'platform/analytics/catalog/server/src/main/resources/application.yaml',
    'platform/analytics/catalog/server/manifest/baseline.json',
    'platform/analytics/catalog/domain/src/main/kotlin/A.kt',
    'typescript/wow-client/src/index.ts',
    'typescript/wow-view-engine/src/index.ts',
    'pnpm-lock.yaml',
    '.github/workflows/ci.yml',
    'new-directory/index.ts',
  ])
    assert.ok(scopes([path]).catalogConsole, path);
  for (const path of [
    'platform/ops/console/src/main.tsx',
    'core/kernel/src/main/kotlin/A.kt',
    'README.md',
    '.github/workflows/package-deploy.yml',
  ])
    assert.ok(!scopes([path]).catalogConsole, path);
  // The console's own path turns on no TypeScript scope, and keeps the JVM's.
  assert.deepEqual(on(['platform/analytics/catalog/console/src/main.tsx']), []);
  assert.ok(scopes(['platform/analytics/catalog/console/src/main.tsx']).jvm);
  assert.ok(
    !scopes(['platform/analytics/catalog/console/src/main.tsx']).opsConsole,
  );
});

test('isolated changes retain their relevant validation', () => {
  assert.deepEqual(on(['documentation/docs/index.md']), ['docs']);
  assert.deepEqual(
    on(['core/kernel/src/main/kotlin/A.kt', 'documentation/package.json']),
    ['docs'],
  );
  assert.deepEqual(
    on(['typescript/wow-react/src/index.ts', 'example/README.md']),
    ['typescript', 'sdk', 'docs', 'storybook'],
  );
  assert.deepEqual(
    on(['typescript/wow-generator/src/cli.ts', 'typescript/storybook/a.ts']),
    ['typescript', 'sdk', 'docs', 'storybook'],
  );
  assert.ok(all(['README.md', 'pnpm-lock.yaml']));
  assert.ok(
    all(['core/kernel/src/main/kotlin/A.kt', 'new-directory/index.ts']),
  );
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
        'workflows',
      ]
        .map(key => `${key}=${value}\n`)
        .join('') +
      `package=${value}\n` +
      `jvm=${value}\n` +
      `viewStoreContract=${value}\n` +
      `opsConsole=${value}\n` +
      `catalogConsole=${value}\n` +
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
  assert.ok(scopes(['typescript/wow-generator/src/cli.ts']).docs);
});

test('the unit matrix reruns a package and the packages built on it', () => {
  const every = ['wow-client', 'wow-react', 'wow-generator', 'wow-view-store'];
  // The client's sources reach the hooks, the generator and the view store,
  // which import its dist; the generator's reach the view store too, whose
  // generated client is held to what it writes; the others' reach only
  // themselves.
  assert.deepEqual(unitPackages(['typescript/wow-client/src/index.ts']), every);
  assert.deepEqual(unitPackages(['typescript/wow-client/package.json']), every);
  assert.deepEqual(unitPackages(['typescript/wow-react/src/index.ts']), [
    'wow-react',
  ]);
  assert.deepEqual(unitPackages(['typescript/wow-generator/src/cli.ts']), [
    'wow-generator',
    'wow-view-store',
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
  // The V10 framework error table's snapshot (review R2.5-4), which
  // wow-client's tests read, reruns wow-client and runs the JVM jobs.
  const errorTable = 'api/manifest/src/test/resources/framework-errors.json';
  assert.deepEqual(unitPackages([errorTable]), ['wow-client']);
  assert.deepEqual(on([errorTable]), ['sdk']);
  assert.ok(scopes([errorTable]).jvm);
  // The view store service's Manifest baseline, which wow-view-store's
  // generated clients are held to, reruns wow-view-store and the JVM jobs.
  const viewStoreBaseline =
    'platform/view-store/view-store-server/manifest/baseline.json';
  assert.deepEqual(unitPackages([viewStoreBaseline]), ['wow-view-store']);
  assert.ok(scopes([viewStoreBaseline]).sdk);
  assert.ok(scopes([viewStoreBaseline]).jvm);
  // Whatever else turns the sdk scope on reruns them all.
  for (const path of [
    '.github/workflows/ci.yml',
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
    'core/kernel/src/main/kotlin/A.kt',
  ])
    assert.deepEqual(unitPackages([path]), [], path);
  for (const paths of [
    ['typescript/wow-react/README.md', 'typescript/wow-react/src/index.ts'],
    ['typescript/wow-view-engine/src/index.ts', 'eslint.config.js'],
  ])
    assert.equal(unitPackages(paths).length > 0, scopes(paths).sdk, paths);
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
    'core/kernel/src/main/kotlin/A.kt',
    'documentation/docs/en/index.md',
  ])
    assert.ok(!scopes([path]).package, path);
});

// A test that reads a file outside its package (a service's Manifest baseline,
// a Kotlin source it is held to, another package's build) must run when that
// file changes: #4715 changed the view store's baseline, and wow-view-store's
// generated-client test, which the change never ran, broke on v10. Each
// consumer's test files are scanned for the repository paths they name.
test('every source a TypeScript test reads selects its job', () => {
  const root = new URL('../../', import.meta.url).pathname;
  const tracked = new Set(
    execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8' })
      .split('\n')
      .filter(Boolean),
  );
  const unit = name => path => unitPackages([path]).includes(name);
  const consumers = [
    ['typescript/wow-client', unit('wow-client')],
    ['typescript/wow-react', unit('wow-react')],
    ['typescript/wow-generator', unit('wow-generator')],
    ['typescript/wow-view-store', unit('wow-view-store')],
    ['typescript/wow-view-engine', path => scopes([path]).viewEngine],
    ['platform/ops/console', path => scopes([path]).opsConsole],
    [
      'platform/analytics/catalog/console',
      path => scopes([path]).catalogConsole,
    ],
    ['example/eshop/web', path => scopes([path]).jvm || scopes([path]).sdk],
  ];
  const TOP =
    /^(?:api|core|query|backends|edge|host|extensions|migration|platform|example|typescript|test|documentation)\//;
  const LITERAL = /['"`]([^'"`\n$]+\.[a-z]{2,5})['"`]/g;
  const TEST = /(?:^|\/)(?:test|e2e)\/|\.test\.[cm]?[jt]sx?$/;
  const problems = [];
  let reads = 0;
  for (const [dir, runs] of consumers) {
    const files = [...tracked].filter(
      file =>
        file.startsWith(`${dir}/`) &&
        TEST.test(file) &&
        /\.[cm]?[jt]sx?$/.test(file) &&
        !file.includes('/src/generated/'),
    );
    for (const file of files) {
      // Code only: a comment naming a path reads nothing.
      const source = readFileSync(join(root, file), 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:])\/\/.*$/gm, '$1');
      for (const [, literal] of source.matchAll(LITERAL)) {
        let path = literal.startsWith('.')
          ? join(file, '..', literal)
          : TOP.test(literal)
            ? literal
            : undefined;
        if (!path) continue;
        // Another package's build: its sources.
        path = path.replace(
          /^(typescript\/[^/]+)\/dist\/.*/,
          '$1/src/index.ts',
        );
        if (path.startsWith(`${dir}/`) || !tracked.has(path)) continue;
        reads++;
        if (!runs(path)) problems.push(`${file} reads ${path}`);
      }
    }
  }
  assert.ok(reads >= 5, `only ${reads} reads found; the scan broke`);
  assert.deepEqual(problems, []);
});
