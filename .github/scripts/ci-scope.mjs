/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { PUBLISHED } from './publish-npm.mjs';

// typescript.yml: the static checks (format, lint, types, versions, ledger).
const TYPESCRIPT = 'typescript';
// typescript.yml: unit tests of every package but view-engine, whose suite
// runs sharded on its own.
const SDK = 'sdk';
// typescript.yml: the VitePress documentation site.
const DOCS = 'docs';
// typescript.yml: the view-engine suite, sharded apart from the other packages.
const VIEW_ENGINE = 'viewEngine';
// typescript.yml: Markdown inside typescript/ alone, formatting only.
const PACKAGE_DOCS = 'packageDocs';
// typescript.yml: view-engine's Markdown alone, and the tests that read it
// (`test:docs`) instead of its whole suite.
const VIEW_ENGINE_DOCS = 'viewEngineDocs';
// typescript-storybook.yml: the stories, their static build and interactions.
const STORYBOOK = 'storybook';
// typescript-contract.yml: the client, generator and integration tests
// against an example server built from this repository.
const CONTRACT = 'contract';
// typescript-contract.yml: generated code against the published 8.x servers.
const LEGACY_CONTRACT = 'legacyContract';
// typescript.yml: actionlint over every workflow, the release workflow included.
const WORKFLOWS = 'workflows';
// typescript.yml: the compat-debt ledger alone, for a change to Kotlin main
// sources that turns on no TypeScript check: the ledger pairs their removal
// markers too (#3755), and quality, which runs it otherwise, is off then.
const COMPAT_DEBT = 'compatDebt';
// typescript.yml: the Package job, which packs the PUBLISHED npm packages and
// checks them as a consumer gets them. On with sdk, and for any path inside a
// PUBLISHED package, view-engine's included, without rerunning the unit
// matrix: a change to what a tarball holds is checked before the release
// preflight finds it.
const PACKAGE = 'package';
// mixed-version.yml: the released example server and the example server built
// from this commit on one Kafka and MongoDB, each processing what the other
// sends (test/wow-it MixedVersionClusterTest). Not a TypeScript scope.
const MIXED_VERSION = 'mixedVersion';
// v10.yml (v10 branch): `-Pwow.scope=v10 v10Check` and the V10 integration
// tests. Not a TypeScript scope. Decided apart from RULES, by V10_PATH: the
// V10 modules, their tests and spikes, the V10 examples (the eShop and the
// quickstart, with the quickstart pages its test keeps in step), the end-to-end
// benchmark against 9.x (wow-benchmarks/e2e, D224), the platform
// they import, and the root build and Gradle configuration every module reads.
const V10 = 'v10';
const V10_PATH =
  /^(?:(?:api|bom|compiler|core|query|backends|edge|host|extensions|migration|platform|test\/(?:architecture|kernel-benchmarks|routing-spike|lease-spike|test-dsl|tck|tck-containers)|example\/(?:eshop|quickstart)|wow-benchmarks\/e2e|wow-dependencies|gradle|build-logic|config)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|documentation\/docs\/(?:en|zh)\/guide\/v10\/quickstart\.md$|\.github\/workflows\/v10\.yml$)/;
// typescript-contract.yml (v10 branch): `test:v10` of integration-test, the
// V10 store (`@ahoo-wang/wow-view-store/v10`) and the view engine on it
// against the V10 view store service (platform/view-store) built from this
// commit. Decided apart from RULES, by V10_CONTRACT_PATH: the packages the
// suites import (the libraries' own tests and the engine's apart from its
// port conformance suite left out), integration-test, the service, the V10
// modules on its runtime and test classpath, the root build and the workflow.
// Markdown is left out: nothing the suites run reads it.
const V10_CONTRACT = 'v10Contract';
const V10_CONTRACT_PATH =
  /^(?:typescript\/(?:wow-(?:client|view-store)\/(?!(?:test|expected|scripts)\/)|wow-view-engine\/(?!test\/(?!conformance\/))|integration-test\/)|(?:api|compiler|core|query|backends|edge|host|platform\/view-store|test\/(?:tck|test-dsl)|wow-dependencies|gradle|build-logic)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|\.github\/workflows\/typescript-contract\.yml$)/;
// ops-console-test.yml (v10 branch): the ops console's lint, types, tests and
// build, and its end-to-end suite against the ops server it is served by. Not a
// TypeScript scope of typescript.yml. Decided apart from RULES, by
// OPS_CONSOLE_PATH: the console, the ops service (its Manifest the console's
// client is generated from, its routes the suite runs against), the workspace
// packages and files the console builds with, and the workflow itself.
const OPS_CONSOLE = 'opsConsole';
const OPS_CONSOLE_PATH =
  /^(?:platform\/ops\/|typescript\/wow-(?:client|generator|view-engine|view-store)\/|patches\/|(?:package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|tsconfig\.base\.json)$|\.github\/workflows\/ops-console-test\.yml$)/;
// A Kotlin source a removal marker may sit in.
const KOTLIN_MAIN = /(?:^|\/)src\/main\/kotlin\/.+\.kt$/;

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
  // The port's conformance suite runs over WowViewStore in the same-source
  // contract (integration-test/test/view-store/) too.
  [
    /^typescript\/wow-view-engine\/test\/conformance\//,
    [TYPESCRIPT, VIEW_ENGINE, CONTRACT],
  ],
  [/^typescript\/wow-view-engine\/test\//, [TYPESCRIPT, VIEW_ENGINE]],
  // View-engine and the stories build on the client and the React hooks; the
  // site compiles its samples against the client and the generator.
  [
    /^typescript\/wow-client\//,
    [TYPESCRIPT, SDK, DOCS, VIEW_ENGINE, STORYBOOK, CONTRACT, LEGACY_CONTRACT],
  ],
  [
    /^typescript\/wow-generator\//,
    [TYPESCRIPT, SDK, DOCS, CONTRACT, LEGACY_CONTRACT],
  ],
  // The site's samples compile against the committed generated clients.
  [
    /^typescript\/integration-test\/src\/generated\//,
    [TYPESCRIPT, DOCS, CONTRACT, LEGACY_CONTRACT],
  ],
  // The contracts run the integration tests; the unit job leaves them out.
  [/^typescript\/integration-test\//, [TYPESCRIPT, CONTRACT, LEGACY_CONTRACT]],
  // The documentation site embeds the Storybook these render, and the
  // same-source contract runs the hooks against the example server.
  [
    // view-engine doesn't depend on wow-react; the stories and the site do.
    /^typescript\/wow-react\//,
    [TYPESCRIPT, SDK, STORYBOOK, DOCS, CONTRACT],
  ],
  // The same-source contract runs view-engine's runtime against the server
  // (integration-test/test/view-engine/); its own tests and prose do not.
  [
    /^typescript\/wow-view-engine\//,
    [TYPESCRIPT, VIEW_ENGINE, STORYBOOK, DOCS, CONTRACT],
  ],
  // The same-source contract runs WowViewStore against the view store server
  // (integration-test/test/view-store/); the site lists the package, and the
  // stories render it.
  [
    /^typescript\/wow-view-store\//,
    [TYPESCRIPT, SDK, DOCS, STORYBOOK, CONTRACT],
  ],
  // The static checks lint the stories and check their formatting.
  [/^typescript\/storybook\//, [TYPESCRIPT, STORYBOOK, DOCS]],
  [/^typescript\//, [TYPESCRIPT, SDK]],
  // The site's view-engine pages that view-engine's own tests read
  // (test:docs): the guides the theme and quickstart tests hold (the theming
  // guide's generated token tables too), and the API reference, whose symbol
  // index and issue-code tables are generated.
  [
    /^documentation\/docs\/(?:en|zh)\/(?:guide\/typescript\/view-engine[\w-]*\.md$|reference\/typescript\/wow-view-engine\/)/,
    [DOCS, VIEW_ENGINE_DOCS],
  ],
  [/^documentation\//, [DOCS]],
  // The committed contract snapshots of wow-openapi: wow-client's tests read
  // them (test/fixtures/serverContract.ts) for its endpoint paths and header
  // names; they change with the routes the same-source contract serves.
  [/^wow-openapi\/src\/test\/resources\/openapi\//, [SDK, CONTRACT]],
  // The modules whose bytes cross between versions (messages, buses, stores,
  // the wait endpoint and the REST headers), the example cluster the
  // mixed-version test starts, and the dependency versions (a Jackson bump can
  // change the JSON): the same-source contract and the mixed-version test.
  [
    /^(?:wow-(?:api|core|metadata|rest-contract|kafka|redis|mongo|webflux|spring|spring-boot-starter)|example\/example-(?:api|domain|server)|gradle)\//,
    [CONTRACT, MIXED_VERSION],
  ],
  // The mixed-version harness and the container images it shares with the
  // integration tests.
  [
    /^test\/(?:wow-it\/|wow-tck\/src\/main\/kotlin\/me\/ahoo\/wow\/tck\/container\/)/,
    [MIXED_VERSION],
  ],
  // The V10 examples, the eShop and its end-to-end tests, and the quickstart
  // (v10 branch): the 9.x example server the same-source contract runs does
  // not build on them.
  [/^example\/(?:eshop|quickstart)\//, []],
  // Sources and build of the example server the same-source contract runs:
  // every project on its runtime classpath, plus the Gradle build.
  [
    /^(?:wow-[^/]+|schema|example|build-logic|gradle|test\/wow-mock|compensation\/wow-compensation-(?:api|core))\//,
    [CONTRACT],
  ],
  // The view store server the same-source contract starts beside the example
  // server, for WowViewStore.
  [/^view-store\//, [CONTRACT]],
  // The single version source: quality checks every package.json against it.
  [/^gradle\.properties$/, [TYPESCRIPT, CONTRACT]],
  [/^(?:build\.gradle\.kts|settings\.gradle\.kts|gradlew)$/, [CONTRACT]],
  // The compat-debt ledger: quality checks it against the markers.
  [/^docs\/compat-debt\.md$/, [TYPESCRIPT]],
  // Lint and format rules only the static checks read.
  [
    /^(?:eslint\.config\.js|\.prettierrc|\.prettierignore)$/,
    [TYPESCRIPT, DOCS],
  ],
  // The workflow runs every job it defines, the light ones included.
  [
    /^\.github\/workflows\/typescript\.yml$/,
    [
      TYPESCRIPT,
      SDK,
      DOCS,
      VIEW_ENGINE,
      PACKAGE_DOCS,
      VIEW_ENGINE_DOCS,
      WORKFLOWS,
    ],
  ],
  // Prettier checks the workflow file, so the static checks run too.
  [
    /^\.github\/workflows\/typescript-contract\.yml$/,
    [TYPESCRIPT, CONTRACT, LEGACY_CONTRACT, WORKFLOWS],
  ],
  [
    /^\.github\/workflows\/typescript-storybook\.yml$/,
    [TYPESCRIPT, STORYBOOK, WORKFLOWS],
  ],
  // The nightly Firefox and WebKit run never runs on a pull request; Prettier
  // still checks the file.
  [
    /^\.github\/workflows\/typescript-storybook-browsers\.yml$/,
    [TYPESCRIPT, WORKFLOWS],
  ],
  [/^\.github\/workflows\/mixed-version\.yml$/, [MIXED_VERSION, WORKFLOWS]],
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
  // platform: no package, story or site builds on it; ops-console-test.yml
  // checks it (OPS_CONSOLE_PATH), and v10.yml runs with the ops server that
  // serves it (V10_PATH).
  [/^platform\/ops\/console\//, []],
  // The V10 modules (documentation/designs/v10/06-modules.md §4; their tests
  // live under test/, below): no example server, client or site builds on
  // them yet.
  [
    /^(?:api|compiler|core|query|backends|edge|host|extensions|migration|platform)\//,
    [],
  ],
  // Other Gradle modules, the dashboard (dashboard-test.yml), the analytics
  // service and its console (analytics-console-test.yml) and prose.
  [
    /^(?:test|config|deploy|document|docs|skills|scripts|compensation|dataset-registry|analytics|\.claude\/skills)\//,
    [],
  ],
  [/^\.github\/(?!scripts\/[^/]+\.mjs$|workflows\/typescript[^/]*\.yml$)/, []],
  [/^[^/]+\.(?:kts|md|properties)$/, []],
  [
    /^(?:gradlew\.bat|LICENSE|codecov\.yml|renovate\.json|\.editorconfig|\.gitattributes|\.gitignore)$/,
    [],
  ],
];

// typescript.yml's unit matrix, which the sdk scope turns on: the packages a
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
  [/^typescript\/(wow-(?:react|generator|view-store))\//, match => [match[1]]],
  [/^wow-openapi\/src\/test\/resources\/openapi\//, () => ['wow-client']],
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
    CONTRACT,
    LEGACY_CONTRACT,
    WORKFLOWS,
    COMPAT_DEBT,
    PACKAGE,
    MIXED_VERSION,
    V10,
    V10_CONTRACT,
    OPS_CONSOLE,
  ];
  const result = Object.fromEntries(keys.map(key => [key, false]));
  // Light scopes a path asks for alongside their full scope (the workflow
  // that defines them), which the full scope must not turn off.
  const kept = new Set();
  for (const path of paths) {
    const rule = RULES.find(([pattern]) => pattern.test(path));
    // Every scope, quality among them, so the ledger alone would repeat it.
    if (!rule)
      return Object.fromEntries(keys.map(key => [key, key !== COMPAT_DEBT]));
    for (const key of rule[1]) {
      result[key] = true;
      if (key in FULL && rule[1].includes(FULL[key])) kept.add(key);
    }
  }
  for (const [light, full] of Object.entries(FULL))
    if (result[full] && !kept.has(light)) result[light] = false;
  // Decided apart from RULES, whose first match wins: a Kotlin path keeps the
  // scopes it has and adds this one, unless quality already checks the ledger.
  result[COMPAT_DEBT] =
    !result[TYPESCRIPT] && paths.some(path => KOTLIN_MAIN.test(path));
  // Also apart from RULES: a published package's path keeps its scopes.
  result[PACKAGE] =
    result[SDK] ||
    paths.some(path => PUBLISHED.some(dir => path.startsWith(`${dir}/`)));
  // Also apart from RULES: a V10 path keeps its scopes, and an unknown path
  // has already returned every scope above.
  result[V10] = paths.some(path => V10_PATH.test(path));
  // Also apart from RULES, the same way.
  result[V10_CONTRACT] = paths.some(
    path => V10_CONTRACT_PATH.test(path) && !path.endsWith('.md'),
  );
  // And the ops console's, the same way.
  result[OPS_CONSOLE] = paths.some(path => OPS_CONSOLE_PATH.test(path));
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
