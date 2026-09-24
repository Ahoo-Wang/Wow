/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

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

// A light scope stands in for a full one when only Markdown changed, so it
// turns off again once any path turns the full scope on.
const FULL = { [PACKAGE_DOCS]: TYPESCRIPT, [VIEW_ENGINE_DOCS]: VIEW_ENGINE };

// The first rule that matches a path decides which scopes it turns on; an
// empty list means no TypeScript workflow needs it. Unknown paths turn on
// every scope, so only known paths narrow a run.
const RULES = [
  // Prose inside a package. No build, story or site reads it; view-engine
  // tests its own (docs references, AGENTS.md structure, the quickstart).
  [/^typescript\/wow-view-engine\/.+\.md$/, [PACKAGE_DOCS, VIEW_ENGINE_DOCS]],
  // The migration plan, AGENTS.md and the other packages' READMEs and plans.
  [/^typescript\/.+\.md$/, [PACKAGE_DOCS]],
  // View-engine and the stories build on the client and the React hooks.
  [
    /^typescript\/wow-client\//,
    [TYPESCRIPT, SDK, VIEW_ENGINE, STORYBOOK, CONTRACT, LEGACY_CONTRACT],
  ],
  [
    /^typescript\/wow-generator\//,
    [TYPESCRIPT, SDK, CONTRACT, LEGACY_CONTRACT],
  ],
  // The contracts run the integration tests; the unit job leaves them out.
  [/^typescript\/integration-test\//, [TYPESCRIPT, CONTRACT, LEGACY_CONTRACT]],
  // The documentation site embeds the Storybook these render.
  [/^typescript\/wow-react\//, [TYPESCRIPT, SDK, VIEW_ENGINE, STORYBOOK, DOCS]],
  [
    /^typescript\/wow-view-engine\//,
    [TYPESCRIPT, VIEW_ENGINE, STORYBOOK, DOCS],
  ],
  // The static checks lint the stories and check their formatting.
  [/^typescript\/storybook\//, [TYPESCRIPT, STORYBOOK, DOCS]],
  [/^typescript\//, [TYPESCRIPT, SDK]],
  [/^documentation\//, [DOCS]],
  // Sources and build of the example server the same-source contract runs:
  // every project on its runtime classpath, plus the Gradle build.
  [
    /^(?:wow-[^/]+|schema|example|build-logic|gradle|test\/wow-mock|compensation\/wow-compensation-(?:api|core))\//,
    [CONTRACT],
  ],
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
    [TYPESCRIPT, SDK, DOCS, VIEW_ENGINE, PACKAGE_DOCS, VIEW_ENGINE_DOCS],
  ],
  // Prettier checks the workflow file, so the static checks run too.
  [
    /^\.github\/workflows\/typescript-contract\.yml$/,
    [TYPESCRIPT, CONTRACT, LEGACY_CONTRACT],
  ],
  [/^\.github\/workflows\/typescript-storybook\.yml$/, [TYPESCRIPT, STORYBOOK]],
  // Other Gradle modules, other workflows, the dashboard (dashboard-test.yml)
  // and prose.
  [
    /^(?:test|config|deploy|document|docs|skills|scripts|compensation|\.claude\/skills)\//,
    [],
  ],
  [/^\.github\/(?!scripts\/[^/]+\.mjs$|workflows\/typescript[^/]*\.yml$)/, []],
  [/^[^/]+\.(?:kts|md|properties)$/, []],
  [
    /^(?:gradlew\.bat|LICENSE|codecov\.yml|renovate\.json|\.editorconfig|\.gitattributes|\.gitignore)$/,
    [],
  ],
];

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
          ['diff', '--no-renames', '--name-only', '-z', BASE_SHA, HEAD_SHA],
          { encoding: 'utf8' },
        )
          .split('\0')
          .filter(Boolean)
      : ['*'];
  for (const [key, value] of Object.entries(scopes(paths)))
    appendFileSync(GITHUB_OUTPUT, `${key}=${value}\n`);
}
