/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';

const index = JSON.parse(
  await readFile(process.argv[2] ?? 'storybook-static/index.json', 'utf8'),
);
// Validate chapter links as well as the landing page; renamed stories must not leave dead learning paths.
const storyRoot = new URL('../stories/', import.meta.url);
// The docs pages (the guided intro, `Intro.mdx`) link to stories too.
const storyFiles = (await readdir(storyRoot, { recursive: true })).filter(
  file =>
    (file.endsWith('.stories.tsx') && !file.endsWith('.test.stories.tsx')) ||
    file.endsWith('.mdx') ||
    file.startsWith('shared/'),
);
const docsPages = storyFiles.filter(file => file.endsWith('.mdx'));
assert.ok(docsPages.length > 0, 'The guided intro (Intro.mdx) not found');
const targets = new Set();
let hostTargets = 0;
for (const file of storyFiles) {
  const source = await readFile(new URL(file, storyRoot), 'utf8');
  for (const match of source.matchAll(
    /\.\/\?path=\/(?:docs|story)\/([^'"\s)$]+)/g,
  ))
    targets.add(decodeURIComponent(match[1]));
  // The host shell builds its links as `./?path=/story/${story}` from the
  // `story` of each navigation item, so those literals are the targets.
  if (source.includes('./?path=/story/${story}'))
    for (const match of source.matchAll(/\bstory:\s*'([^']+)'/g)) {
      targets.add(match[1]);
      hostTargets++;
    }
}
assert.ok(hostTargets > 0, 'Host navigation (shared/AppShell.tsx) not found');
// fetcher's landing and docs stories linked to examples; Wow's stories need
// not link anywhere, but every link they do make must resolve.
for (const id of targets)
  assert.ok(index.entries[id], `Missing navigation target: ${id}`);
// A link says which kind of page it opens; a docs link must land on docs.
for (const file of docsPages) {
  const source = await readFile(new URL(file, storyRoot), 'utf8');
  for (const [, kind, id] of source.matchAll(
    /\.\/\?path=\/(docs|story)\/([^'"\s)$]+)/g,
  ))
    assert.equal(
      index.entries[decodeURIComponent(id)]?.type,
      kind === 'docs' ? 'docs' : 'story',
      `${file}: ${id} is not a ${kind} entry`,
    );
}
// The catalog opens on the guided intro: the first entry of the sorted index.
assert.equal(
  Object.values(index.entries)[0]?.title,
  'View Engine/导览',
  'The first catalog entry is not the guided intro',
);
const stories = Object.values(index.entries).filter(
  entry => entry.type === 'story',
);
const regression = stories.filter(entry =>
  entry.importPath.endsWith('.test.stories.tsx'),
);
assert.ok(regression.length > 0, 'Regression stories must remain indexed');
for (const entry of regression) {
  assert.ok(
    entry.tags.includes('test'),
    `${entry.id}: regression excluded from tests`,
  );
  assert.ok(
    !entry.tags.includes('dev') && !entry.tags.includes('autodocs'),
    `${entry.id}: regression exposed in documentation`,
  );
}
console.log(
  `Verified ${targets.size} navigation targets and ${regression.length} regression stories.`,
);
