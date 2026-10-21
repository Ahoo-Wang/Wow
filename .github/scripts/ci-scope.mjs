/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { PUBLISHED } from './publish-npm.mjs';

// ci.yml: the static checks (format, lint, types, versions).
const TYPESCRIPT = 'typescript';
// ci.yml: unit tests of every package but view-engine, whose suite
// runs sharded on its own.
const SDK = 'sdk';
// ci.yml: the VitePress documentation site.
const DOCS = 'docs';
// ci.yml: the view-engine suite, sharded apart from the other packages.
const VIEW_ENGINE = 'viewEngine';
// ci.yml: Markdown inside typescript/ alone, formatting only.
const PACKAGE_DOCS = 'packageDocs';
// ci.yml: view-engine's Markdown alone, and the tests that read it
// (`test:docs`) instead of its whole suite.
const VIEW_ENGINE_DOCS = 'viewEngineDocs';
// ci.yml: the stories, their static build and interactions.
const STORYBOOK = 'storybook';
// ci.yml: actionlint over every workflow, the release workflow included.
const WORKFLOWS = 'workflows';
// ci.yml: the Package job, which packs the PUBLISHED npm packages and
// checks them as a consumer gets them. On with sdk, and for any path inside a
// PUBLISHED package, view-engine's included, without rerunning the unit
// matrix: a change to what a tarball holds is checked before the release
// preflight finds it.
const PACKAGE = 'package';
// ci.yml's JVM jobs: `:check` and the integration
// tests. Not a TypeScript scope. Decided apart from RULES, by JVM_PATH: the
// V10 modules, their tests and spikes, the V10 examples (the eShop, the Java
// smoke test and the quickstart), the end-to-end benchmark (test/benchmarks, D224; not its README or
// reports), the platform
// they import, the coverage reports ci.yml uploads (test/code-coverage-report:
// coverageReport), and the root build and Gradle configuration every module
// reads.
const JVM = 'jvm';
const JVM_PATH =
  /^(?:(?:api|bom|compiler|core|query|backends|edge|host|extensions|migration|platform|test\/(?:architecture|code-coverage-report|routing-spike|lease-spike|test-dsl|tck|tck-containers|benchmarks\/(?:server|load|harness))|example\/(?:eshop|quickstart|java-smoke)|wow-dependencies|gradle|config)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|\.github\/workflows\/ci\.yml$)/;
// ci.yml's view-store-contract job: `test` of integration-test, the
// V10 store (`@ahoo-wang/wow-view-store`) and the view engine on it
// against the V10 view store service (platform/view-store) built from this
// commit. Decided apart from RULES, by VIEW_STORE_CONTRACT_PATH: the packages the
// suites import (the libraries' own tests and the engine's apart from its
// port conformance suite left out), integration-test, the service, the V10
// modules on its runtime and test classpath, the root build and the workflow.
// Markdown is left out: nothing the suites run reads it.
const VIEW_STORE_CONTRACT = 'viewStoreContract';
const VIEW_STORE_CONTRACT_PATH =
  /^(?:typescript\/(?:wow-(?:client|view-store)\/(?!(?:test|expected|scripts)\/)|wow-view-engine\/(?!test\/(?!conformance\/))|integration-test\/)|(?:api|compiler|core|query|backends|edge|host|platform\/view-store|test\/(?:tck|test-dsl)|wow-dependencies|gradle)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|\.github\/workflows\/ci\.yml$)/;
// ci.yml's ops-console jobs: the ops console's lint, types, tests and
// build, and its end-to-end suite against the ops server it is served by. Not a
// TypeScript scope. Decided apart from RULES, by
// OPS_CONSOLE_PATH: the console, the ops service (its Manifest the console's
// client is generated from, its routes the suite runs against), the workspace
// packages and files the console builds with, and the workflow itself.
const OPS_CONSOLE = 'opsConsole';
const OPS_CONSOLE_PATH =
  /^(?:platform\/ops\/|typescript\/wow-(?:client|generator|view-engine|view-store)\/|(?:package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|tsconfig\.base\.json)$|\.github\/workflows\/ci\.yml$)/;
// ci.yml's catalog-console jobs: the analytics catalog console's lint, types,
// tests and build, and its end-to-end suite against the catalog server it is
// served by, as the ops-console jobs do the ops console's. Decided
// apart from RULES, by CATALOG_CONSOLE_PATH: the console, the catalog service,
// the workspace packages and files the console builds with, and the workflow.
const CATALOG_CONSOLE = 'catalogConsole';
const CATALOG_CONSOLE_PATH =
  /^(?:platform\/analytics\/catalog\/|typescript\/wow-(?:client|generator|view-engine|view-store)\/|(?:package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|tsconfig\.base\.json)$|\.github\/workflows\/ci\.yml$)/;

// A light scope stands in for a full one when only Markdown changed, so it
// turns off again once any path turns the full scope on.
const FULL = { [PACKAGE_DOCS]: TYPESCRIPT, [VIEW_ENGINE_DOCS]: VIEW_ENGINE };

// The first rule that matches a path decides which scopes it turns on; an
// empty list means no TypeScript workflow needs it. Unknown paths turn on
// every scope, so only known paths narrow a run.
const RULES = [
  // Package READMEs whose code samples the site's tests compile
  // (documentation/test/typescript-samples.mjs).
  [
    /^typescript\/wow-view-engine\/README(?:\.zh-CN)?\.md$/,
    [PACKAGE_DOCS, VIEW_ENGINE_DOCS, DOCS],
  ],
  [
    /^typescript\/wow-(?:client|generator|view-store)\/README(?:\.zh-CN)?\.md$/,
    [PACKAGE_DOCS, DOCS],
  ],
  // Prose inside a package. No build, story or site reads it; view-engine
  // tests its own (docs references, AGENTS.md structure, the quickstart).
  [/^typescript\/wow-view-engine\/.+\.md$/, [PACKAGE_DOCS, VIEW_ENGINE_DOCS]],
  // The migration plan, AGENTS.md and the other packages' READMEs and plans.
  [/^typescript\/.+\.md$/, [PACKAGE_DOCS]],
  // A library's own tests, goldens and build scripts can't change what another
  // package sees: they rerun that package, not everything built on it.
  [
    /^typescript\/wow-(?:client|react|generator|view-store)\/(?:test|expected|scripts)\//,
    [TYPESCRIPT, SDK],
  ],
  [/^typescript\/wow-view-engine\/test\//, [TYPESCRIPT, VIEW_ENGINE]],
  // View-engine and the stories build on the client and the React hooks; the
  // site compiles its samples against the client and the generator.
  [
    /^typescript\/wow-client\//,
    [TYPESCRIPT, SDK, DOCS, VIEW_ENGINE, STORYBOOK],
  ],
  [/^typescript\/wow-generator\//, [TYPESCRIPT, SDK, DOCS]],
  // The contract runs the integration tests (VIEW_STORE_CONTRACT_PATH); the unit job
  // leaves them out.
  [/^typescript\/integration-test\//, [TYPESCRIPT]],
  // The documentation site embeds the Storybook these render.
  [
    // view-engine doesn't depend on wow-react; the stories and the site do.
    /^typescript\/wow-react\//,
    [TYPESCRIPT, SDK, STORYBOOK, DOCS],
  ],
  [
    /^typescript\/wow-view-engine\//,
    [TYPESCRIPT, VIEW_ENGINE, STORYBOOK, DOCS],
  ],
  // The site lists the package, and the stories render it.
  [/^typescript\/wow-view-store\//, [TYPESCRIPT, SDK, DOCS, STORYBOOK]],
  // The static checks lint the stories and check their formatting.
  [/^typescript\/storybook\//, [TYPESCRIPT, STORYBOOK, DOCS]],
  [/^typescript\//, [TYPESCRIPT, SDK]],
  // The site's view-engine pages that view-engine's own tests read
  // (test:docs): the guides the theme and quickstart tests hold (the theming
  // guide's generated token tables too), and the API reference, whose symbol
  // index and issue-code tables are generated.
  [
    /^documentation\/docs\/(?:en|zh)\/(?:typescript\/view-engine\/[\w-]+\.md$|reference\/typescript\/wow-view-engine\/)/,
    [DOCS, VIEW_ENGINE_DOCS],
  ],
  [/^documentation\//, [DOCS]],
  // The V10 framework error table's snapshot (api/manifest, review R2.5-4):
  // wow-client's tests check FrameworkErrorCodes against it
  // (test/frameworkErrors.test.ts), so a change to the table reruns them.
  [/^api\/manifest\/src\/test\/resources\/framework-errors\.json$/, [SDK]],
  // The V10 view store service's Manifest baseline: wow-view-store's
  // src/generated/ is wow-generator's output for it, byte for byte
  // (test/generated.test.ts), so a change to it reruns wow-view-store.
  [
    /^platform\/view-store\/view-store-server\/manifest\/baseline\.json$/,
    [SDK],
  ],
  // Other sources a package's tests read outside typescript/ (the CI script
  // test "every source a TypeScript test reads selects its job" finds them):
  // wow-client's query error codes are held to :query-api's QueryErrorCodes.kt,
  // and view-engine's store limits to the view store service's ViewConfigs.kt.
  [
    /^api\/query-api\/src\/main\/kotlin\/me\/ahoo\/wow\/api\/query\/QueryErrorCodes\.kt$/,
    [SDK],
  ],
  [
    /^platform\/view-store\/view-store-domain\/src\/main\/kotlin\/me\/ahoo\/wow\/viewstore\/domain\/view\/ViewConfigs\.kt$/,
    [VIEW_ENGINE],
  ],
  // The single version source: quality checks every package.json against it.
  [/^gradle\.properties$/, [TYPESCRIPT]],
  // The Gradle build: the JVM jobs (JVM_PATH).
  [/^(?:build\.gradle\.kts|settings\.gradle\.kts|gradlew)$/, []],
  // Lint and format rules only the static checks read.
  [
    /^(?:eslint\.config\.js|\.prettierrc|\.prettierignore)$/,
    [TYPESCRIPT, DOCS],
  ],
  // The workflow runs every job it defines, the light ones included (the JVM,
  // contract and console jobs through their own paths above).
  [
    /^\.github\/workflows\/ci\.yml$/,
    [
      TYPESCRIPT,
      SDK,
      DOCS,
      VIEW_ENGINE,
      PACKAGE_DOCS,
      VIEW_ENGINE_DOCS,
      STORYBOOK,
      WORKFLOWS,
    ],
  ],
  // The nightly Firefox and WebKit run never runs on a pull request; Prettier
  // still checks the file.
  [
    /^\.github\/workflows\/typescript-storybook-browsers\.yml$/,
    [TYPESCRIPT, WORKFLOWS],
  ],
  // Every other workflow, the release and deploy workflows included: lint it.
  [/^\.github\/workflows\/[^/]+\.ya?ml$/, [WORKFLOWS]],
  // The TypeScript Skills: the site's tests compile the view engine Skills'
  // samples and check the imports of wow-client's
  // (documentation/test/typescript-samples.mjs). Their eval cases are not read.
  [
    /^skills\/wow-(?:view-definition|view-host|client)\/(?!evals\/).+\.md$/,
    [DOCS],
  ],
  // The ops console (roadmap 7.1b), a pnpm workspace member under the V10
  // platform: no package, story or site builds on it; the ops-console jobs
  // check it (OPS_CONSOLE_PATH), and the JVM jobs run with the ops server that
  // serves it (JVM_PATH).
  [/^platform\/ops\/console\//, []],
  // The analytics catalog console (roadmap 7.2c), the same way: the
  // catalog-console jobs check it (CATALOG_CONSOLE_PATH).
  [/^platform\/analytics\/catalog\/console\//, []],
  // The site's samples compile against the eShop's committed generated
  // clients (documentation/test/typescript-samples.mjs); the eshop-web jobs
  // checks the eShop web app itself.
  [/^example\/eshop\/web\/src\/generated\//, [DOCS]],
  // The V10 modules (documentation/designs/v10/06-modules.md §4; their tests
  // live under test/, below), the BOM, the dependency versions, the examples
  // and the end-to-end benchmark: the JVM jobs (JVM_PATH); no client or site builds
  // on them yet.
  [
    /^(?:api|bom|compiler|core|query|backends|edge|host|extensions|migration|platform|wow-dependencies|example|gradle)\//,
    [],
  ],
  // Other Gradle modules and prose.
  [/^(?:test|config|deploy|docs|skills|scripts|\.claude\/skills)\//, []],
  [
    /^\.github\/(?!scripts\/[^/]+\.mjs$|workflows\/(?:ci|typescript[^/]*)\.yml$)/,
    [],
  ],
  [/^[^/]+\.(?:kts|md|properties)$/, []],
  [
    /^(?:gradlew\.bat|LICENSE|codecov\.yml|renovate\.json|\.editorconfig|\.gitattributes|\.gitignore)$/,
    [],
  ],
];

// ci.yml's unit matrix, which the sdk scope turns on: the packages a
// path reruns. A library's own tests, goldens and build scripts rerun that
// library; the client's sources rerun it and the packages built on it;
// react's, the generator's and the view store's rerun themselves. Any other
// path that turns sdk on (the workflow, the workspace, an unknown path)
// reruns them all.
const UNIT_PACKAGES = [
  'wow-client',
  'wow-react',
  'wow-generator',
  'wow-view-store',
];
const UNIT_RULES = [
  [
    /^typescript\/(wow-(?:client|react|generator|view-store))\/(?:test|expected|scripts)\//,
    match => [match[1]],
  ],
  [/^typescript\/wow-client\//, () => UNIT_PACKAGES],
  // wow-view-store's generated client is held to what the generator writes.
  [
    /^typescript\/wow-generator\/(?!test\/|expected\/|scripts\/)/,
    () => ['wow-generator', 'wow-view-store'],
  ],
  [/^typescript\/(wow-(?:react|generator|view-store))\//, match => [match[1]]],
  [
    /^(?:api\/manifest\/src\/test\/resources\/framework-errors\.json|api\/query-api\/src\/main\/kotlin\/me\/ahoo\/wow\/api\/query\/QueryErrorCodes\.kt)$/,
    () => ['wow-client'],
  ],
  [
    /^platform\/view-store\/view-store-server\/manifest\/baseline\.json$/,
    () => ['wow-view-store'],
  ],
];

export function unitPackages(paths) {
  const packages = new Set();
  for (const path of paths) {
    if (!scopes([path])[SDK]) continue;
    const rule = UNIT_RULES.find(([pattern]) => pattern.test(path));
    for (const name of rule ? rule[1](path.match(rule[0])) : UNIT_PACKAGES)
      packages.add(name);
  }
  return UNIT_PACKAGES.filter(name => packages.has(name));
}

export function scopes(paths) {
  const keys = [
    TYPESCRIPT,
    SDK,
    DOCS,
    VIEW_ENGINE,
    PACKAGE_DOCS,
    VIEW_ENGINE_DOCS,
    STORYBOOK,
    WORKFLOWS,
    PACKAGE,
    JVM,
    VIEW_STORE_CONTRACT,
    OPS_CONSOLE,
    CATALOG_CONSOLE,
  ];
  const result = Object.fromEntries(keys.map(key => [key, false]));
  // Light scopes a path asks for alongside their full scope (the workflow
  // that defines them), which the full scope must not turn off.
  const kept = new Set();
  for (const path of paths) {
    const rule = RULES.find(([pattern]) => pattern.test(path));
    if (!rule) return Object.fromEntries(keys.map(key => [key, true]));
    for (const key of rule[1]) {
      result[key] = true;
      if (key in FULL && rule[1].includes(FULL[key])) kept.add(key);
    }
  }
  for (const [light, full] of Object.entries(FULL))
    if (result[full] && !kept.has(light)) result[light] = false;
  // Decided apart from RULES, whose first match wins: a published package's
  // path keeps its scopes.
  result[PACKAGE] =
    result[SDK] ||
    paths.some(path => PUBLISHED.some(dir => path.startsWith(`${dir}/`)));
  // Also apart from RULES: a JVM path keeps its scopes, and an unknown path
  // has already returned every scope above.
  result[JVM] = paths.some(path => JVM_PATH.test(path));
  // Also apart from RULES, the same way.
  result[VIEW_STORE_CONTRACT] = paths.some(
    path => VIEW_STORE_CONTRACT_PATH.test(path) && !path.endsWith('.md'),
  );
  // And the ops console's, the same way.
  result[OPS_CONSOLE] = paths.some(path => OPS_CONSOLE_PATH.test(path));
  // And the analytics catalog console's.
  result[CATALOG_CONSOLE] = paths.some(path => CATALOG_CONSOLE_PATH.test(path));
  return result;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { BASE_SHA, HEAD_SHA, GITHUB_OUTPUT } = process.env;
  // No base (manual dispatch, a new branch's first push) runs everything.
  const paths =
    BASE_SHA && HEAD_SHA && !/^0+$/.test(BASE_SHA)
      ? execFileSync(
          'git',
          // Three dots: this branch's own changes, not main's since it branched.
          [
            'diff',
            '--no-renames',
            '--name-only',
            '-z',
            `${BASE_SHA}...${HEAD_SHA}`,
          ],
          { encoding: 'utf8' },
        )
          .split('\0')
          .filter(Boolean)
      : ['*'];
  for (const [key, value] of Object.entries(scopes(paths)))
    appendFileSync(GITHUB_OUTPUT, `${key}=${value}\n`);
  appendFileSync(
    GITHUB_OUTPUT,
    `unitPackages=${JSON.stringify(unitPackages(paths))}\n`,
  );
}
