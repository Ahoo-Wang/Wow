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

// Compares the screenshot baselines (README「截图基线」): the `visual`
// Vitest project against a browser in Playwright's Linux container
// (scripts/linux-browser.mjs), over the story files that carry a `'visual'`
// story. Any other argument goes to Vitest — `--update` takes new pictures.
//
//   pnpm --filter wow-storybook test:visual [--update]
//
// The files are named on the command line because the Storybook plugin
// hands Vitest every story file and filters by tag only once a file is
// loaded; loading eighty files into a browser across the container's proxy
// to run three of them is most of the run, and the part that fails.
import { spawn } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const TAG = /tags:\s*\[[^\]]*'visual'/;

function storyFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return storyFiles(path);
    return /\.stories\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

const files = storyFiles(join(root, 'stories'))
  .filter(file => TAG.test(readFileSync(file, 'utf8')))
  .map(file => relative(root, file))
  .sort();
if (files.length === 0) {
  console.error('No story carries the visual tag.');
  process.exit(1);
}

const child = spawn(
  process.execPath,
  [
    join(root, 'scripts/linux-browser.mjs'),
    '--',
    'vitest',
    'run',
    '--project=visual',
    // The files before the arguments passed through: Vitest's `--update`
    // takes an optional value, and followed by a file it took the first
    // one — `Home.test.stories.tsx` — for it, so `--update` dropped the
    // home report from the run and never retook its picture.
    ...files,
    ...process.argv.slice(2),
  ],
  { cwd: root, stdio: 'inherit' },
);
child.on('exit', (status, signal) => process.exit(status ?? (signal ? 1 : 0)));
