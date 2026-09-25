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

// Refreshes test-durations.json, the weights the shard sequencer balances
// the CI shards with, from Vitest JSON reports. Each Chromium shard job of
// typescript-storybook.yml uploads its report as `storybook-durations-<n>`:
//
//   gh run download <run-id> -p 'storybook-durations-*' -D "$tmp"
//   node scripts/test-durations.mjs "$tmp"/*/*.json
//
// A file a report measures takes its new duration, a file no report mentions
// keeps its old one, and a file that no longer exists is dropped.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DURATIONS_FILE } from './shard-sequencer.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const reports = process.argv.slice(2);
if (reports.length === 0) {
  console.error(
    'usage: node scripts/test-durations.mjs <vitest-json-report>...',
  );
  process.exit(2);
}

const durations = existsSync(DURATIONS_FILE)
  ? JSON.parse(readFileSync(DURATIONS_FILE, 'utf8'))
  : {};
const measured = {};
for (const report of reports) {
  const { testResults } = JSON.parse(readFileSync(report, 'utf8'));
  for (const { name, startTime, endTime } of testResults) {
    const path = relative(root, name).split(sep).join('/');
    // Rounded to a tenth of a second, so a refresh changes only what moved.
    const ms = Math.round(Math.max(0, endTime - startTime) / 100) * 100;
    // Several browser instances of one file: the slowest one counts.
    measured[path] = Math.max(measured[path] ?? 0, ms);
  }
}
const next = Object.fromEntries(
  Object.entries({ ...durations, ...measured })
    .filter(([path]) => existsSync(join(root, path)))
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
);
writeFileSync(DURATIONS_FILE, `${JSON.stringify(next, null, 2)}\n`);
console.log(
  `${Object.keys(measured).length} files measured, ${Object.keys(next).length} kept`,
);
