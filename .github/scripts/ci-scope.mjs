/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Paths the TypeScript workflow never needs: the Gradle build, its sources,
// other workflows, the dashboard (dashboard-test.yml) and prose.
const UNRELATED = [
  /^(?:wow-[^/]+|test|example|build-logic|gradle|config|schema|deploy|document|docs|skills|scripts)\//,
  /^compensation\//,
  /^\.claude\/skills\//,
  /^\.github\/(?!scripts\/[^/]+\.mjs$|workflows\/typescript[^/]*\.yml$)/,
  /^[^/]+\.(?:kts|md|properties)$/,
  /^(?:gradlew|gradlew\.bat|LICENSE|codecov\.yml|renovate\.json|\.editorconfig|\.gitattributes|\.gitignore)$/,
  // Prose next to the TypeScript packages (the migration plan, AGENTS.md).
  /^typescript\/[^/]+\.md$/,
];

// Unknown paths run every gate; only known paths narrow the run.
export function scopes(paths) {
  const result = {
    // TypeScript packages and the workspace toolchain: quality and unit.
    typescript: false,
    // The VitePress documentation site.
    docs: false,
  };
  for (const path of paths) {
    if (UNRELATED.some(pattern => pattern.test(path))) continue;
    else if (path.startsWith('typescript/')) result.typescript = true;
    else if (path.startsWith('documentation/')) result.docs = true;
    else return Object.fromEntries(Object.keys(result).map(key => [key, true]));
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
