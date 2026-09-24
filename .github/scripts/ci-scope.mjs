/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// typescript.yml: TypeScript packages and the workspace toolchain.
const TYPESCRIPT = 'typescript';
// typescript.yml: the VitePress documentation site.
const DOCS = 'docs';
// typescript.yml: the view-engine suite, sharded apart from the other packages.
const VIEW_ENGINE = 'viewEngine';
// typescript-storybook.yml: the stories, their static build and interactions.
const STORYBOOK = 'storybook';
// typescript-contract.yml: the client, generator and integration tests
// against an example server built from this repository.
const CONTRACT = 'contract';
// typescript-contract.yml: generated code against the published 8.x servers.
const LEGACY_CONTRACT = 'legacyContract';

// The first rule that matches a path decides which scopes it turns on; an
// empty list means no TypeScript workflow needs it. Unknown paths turn on
// every scope, so only known paths narrow a run.
const RULES = [
  // View-engine and the stories build on the client and the React hooks.
  [
    /^typescript\/wow-client\//,
    [TYPESCRIPT, VIEW_ENGINE, STORYBOOK, CONTRACT, LEGACY_CONTRACT],
  ],
  [
    /^typescript\/(?:wow-generator|integration-test)\//,
    [TYPESCRIPT, CONTRACT, LEGACY_CONTRACT],
  ],
  // The documentation site embeds the Storybook these render.
  [
    /^typescript\/(?:wow-react|wow-view-engine)\//,
    [TYPESCRIPT, VIEW_ENGINE, STORYBOOK, DOCS],
  ],
  // The static checks lint the stories and check their formatting.
  [/^typescript\/storybook\//, [TYPESCRIPT, STORYBOOK, DOCS]],
  // Prose next to the TypeScript packages (the migration plan, AGENTS.md).
  [/^typescript\/[^/]+\.md$/, []],
  [/^typescript\//, [TYPESCRIPT]],
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
  [/^\.github\/workflows\/typescript\.yml$/, [TYPESCRIPT, DOCS, VIEW_ENGINE]],
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
  const result = {
    [TYPESCRIPT]: false,
    [DOCS]: false,
    [VIEW_ENGINE]: false,
    [STORYBOOK]: false,
    [CONTRACT]: false,
    [LEGACY_CONTRACT]: false,
  };
  for (const path of paths) {
    const rule = RULES.find(([pattern]) => pattern.test(path));
    if (!rule)
      return Object.fromEntries(Object.keys(result).map(key => [key, true]));
    for (const key of rule[1]) result[key] = true;
  }
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
