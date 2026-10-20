/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
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
// v10.yml (v10 branch): `v10Check` and the V10 integration
// tests. Not a TypeScript scope. Decided apart from RULES, by V10_PATH: the
// V10 modules, their tests and spikes, the V10 examples (the eShop, the Java
// smoke test and the quickstart, with the quickstart pages its test keeps in
// step), the end-to-end benchmark (wow-benchmarks/e2e, D224), the platform
// they import, the coverage report v10.yml uploads (test/code-coverage-report:
// v10CoverageReport), and the root build and Gradle configuration every module
// reads.
const V10 = 'v10';
const V10_PATH =
  /^(?:(?:api|bom|compiler|core|query|backends|edge|host|extensions|migration|platform|test\/(?:architecture|code-coverage-report|routing-spike|lease-spike|test-dsl|tck|tck-containers)|example\/(?:eshop|quickstart|java-smoke)|wow-benchmarks\/e2e|wow-dependencies|gradle|config)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|documentation\/docs\/(?:en|zh)\/guide\/v10\/quickstart\.md$|\.github\/workflows\/v10\.yml$)/;
// typescript-contract.yml (v10 branch): `test:v10` of integration-test, the
// V10 store (`@ahoo-wang/wow-view-store`) and the view engine on it
// against the V10 view store service (platform/view-store) built from this
// commit. Decided apart from RULES, by V10_CONTRACT_PATH: the packages the
// suites import (the libraries' own tests and the engine's apart from its
// port conformance suite left out), integration-test, the service, the V10
// modules on its runtime and test classpath, the root build and the workflow.
// Markdown is left out: nothing the suites run reads it.
const V10_CONTRACT = 'v10Contract';
const V10_CONTRACT_PATH =
  /^(?:typescript\/(?:wow-(?:client|view-store)\/(?!(?:test|expected|scripts)\/)|wow-view-engine\/(?!test\/(?!conformance\/))|integration-test\/)|(?:api|compiler|core|query|backends|edge|host|platform\/view-store|test\/(?:tck|test-dsl)|wow-dependencies|gradle)\/|(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew)$|\.github\/workflows\/typescript-contract\.yml$)/;
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
  [/^typescript\/wow-view-engine\/test\//, [TYPESCRIPT, VIEW_ENGINE]],
  // View-engine and the stories build on the client and the React hooks; the
  // site compiles its samples against the client and the generator.
  [
    /^typescript\/wow-client\//,
    [TYPESCRIPT, SDK, DOCS, VIEW_ENGINE, STORYBOOK, LEGACY_CONTRACT],
  ],
  [/^typescript\/wow-generator\//, [TYPESCRIPT, SDK, DOCS, LEGACY_CONTRACT]],
  // The site's samples compile against the committed generated clients.
  [
    /^typescript\/integration-test\/src\/generated\//,
    [TYPESCRIPT, DOCS, LEGACY_CONTRACT],
  ],
  // The contract runs the integration tests; the unit job leaves them out.
  [/^typescript\/integration-test\//, [TYPESCRIPT, LEGACY_CONTRACT]],
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
    /^documentation\/docs\/(?:en|zh)\/(?:guide\/typescript\/view-engine[\w-]*\.md$|reference\/typescript\/wow-view-engine\/)/,
    [DOCS, VIEW_ENGINE_DOCS],
  ],
  [/^documentation\//, [DOCS]],
  // The V10 framework error table's snapshot (api/manifest, review R2.5-4):
  // wow-client's tests check FrameworkErrorCodes against it
  // (test/v10/frameworkErrors.test.ts), so a change to the table reruns them.
  [/^api\/manifest\/src\/test\/resources\/framework-errors\.json$/, [SDK]],
  // The single version source: quality checks every package.json against it.
  [/^gradle\.properties$/, [TYPESCRIPT]],
  // The Gradle build: v10.yml (V10_PATH).
  [/^(?:build\.gradle\.kts|settings\.gradle\.kts|gradlew)$/, []],
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
    [TYPESCRIPT, LEGACY_CONTRACT, WORKFLOWS],
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
  // live under test/, below), the BOM, the dependency versions, the examples
  // and the end-to-end benchmark: v10.yml (V10_PATH); no client or site builds
  // on them yet.
  [
    /^(?:api|bom|compiler|core|query|backends|edge|host|extensions|migration|platform|wow-dependencies|wow-benchmarks|example|gradle)\//,
    [],
  ],
  // Other Gradle modules, the 9.x compensation dashboard (dashboard-test.yml)
  // and analytics console (analytics-console-test.yml), and prose.
  [
    /^(?:test|config|deploy|docs|skills|scripts|compensation|analytics|\.claude\/skills)\//,
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
  [
    /^api\/manifest\/src\/test\/resources\/framework-errors\.json$/,
    () => ['wow-client'],
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
    LEGACY_CONTRACT,
    WORKFLOWS,
    COMPAT_DEBT,
    PACKAGE,
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

/**
 * The commits a run compares, `{ base, head }`, or undefined to check
 * everything. A merge queue's run (`merge_group`) compares the group's commit
 * with the tip of the branch it merges into, both from the event payload, as
 * no workflow expression names them; every other event passes BASE_SHA and
 * HEAD_SHA (a pull request's base and head, a push's before and after). No base
 * (manual dispatch, a new branch's first push) checks everything.
 */
export function changeRange({ eventName, event, baseSha, headSha }) {
  const [base, head] =
    eventName === 'merge_group'
      ? [event?.merge_group?.base_sha, event?.merge_group?.head_sha]
      : [baseSha, headSha];
  return base && head && !/^0+$/.test(base) ? { base, head } : undefined;
}

/** The changeRange of this run, from what GitHub Actions sets in `env`. */
export function runChangeRange(env = process.env) {
  const { GITHUB_EVENT_NAME, GITHUB_EVENT_PATH, BASE_SHA, HEAD_SHA } = env;
  const event =
    GITHUB_EVENT_NAME === 'merge_group' && GITHUB_EVENT_PATH
      ? JSON.parse(readFileSync(GITHUB_EVENT_PATH, 'utf8'))
      : undefined;
  return changeRange({
    eventName: GITHUB_EVENT_NAME,
    event,
    baseSha: BASE_SHA,
    headSha: HEAD_SHA,
  });
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { GITHUB_OUTPUT } = process.env;
  const range = runChangeRange();
  const paths = range
    ? execFileSync(
        'git',
        // Three dots: this branch's own changes, not main's since it branched.
        [
          'diff',
          '--no-renames',
          '--name-only',
          '-z',
          `${range.base}...${range.head}`,
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
