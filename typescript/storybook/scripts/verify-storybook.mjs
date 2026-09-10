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
const storyFiles = (await readdir(storyRoot, { recursive: true })).filter(
  file => file.endsWith('.stories.tsx') && !file.endsWith('.test.stories.tsx'),
);
const targets = new Set();
for (const file of storyFiles) {
  const source = await readFile(new URL(file, storyRoot), 'utf8');
  for (const match of source.matchAll(
    /\.\/\?path=\/(?:docs|story)\/([^'"\s)]+)/g,
  ))
    targets.add(decodeURIComponent(match[1]));
}
assert.ok(targets.size > 0, 'Stories must link to executable examples');
for (const id of targets)
  assert.ok(index.entries[id], `Missing navigation target: ${id}`);
const stories = Object.values(index.entries).filter(
  entry => entry.type === 'story',
);
const viewEngineGroups = new Set([
  '开始体验',
  '订单业务流程',
  '开发接入',
  '专项场景',
]);
for (const entry of stories.filter(
  entry => entry.title.startsWith('View Engine/') && entry.tags.includes('dev'),
)) {
  const parts = entry.title.split('/');
  assert.ok(
    viewEngineGroups.has(parts[1]),
    `${entry.id}: ungrouped View Engine chapter`,
  );
  assert.ok(
    parts.length >= 3 && parts.length <= 4,
    `${entry.id}: unexpected navigation depth`,
  );
}
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
const experiment = index.entries['development-http-service--http-view-service'];
assert.ok(experiment, 'HTTP experiment must remain directly accessible');
assert.ok(
  !experiment.tags.includes('test') && !experiment.tags.includes('autodocs'),
);
assert.ok(
  !index.entries['development-http-service--docs'],
  'HTTP experiment must not mount in ordinary docs',
);
console.log(
  `Verified ${targets.size} navigation targets, ${regression.length} regression stories and isolated HTTP experiment.`,
);
