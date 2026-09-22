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

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = join(import.meta.dirname, '..');

/** Every source file, save the vendored primitives the tree summarises. */
function sources(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (entry === 'components' || entry === 'lib') continue;
      found.push(...sources(path));
    } else if (/\.(tsx?|css)$/.test(entry)) found.push(path);
  }
  return found;
}

/**
 * AGENTS.md's structure tree is what an agent reads first, and a tree that
 * is missing sixty files sends it looking in the wrong places (A-04). The
 * tree therefore names every file, and this is what keeps it true.
 */
describe('AGENTS.md', () => {
  const agents = readFileSync(join(ROOT, 'AGENTS.md'), 'utf8');
  const tree = agents.slice(
    agents.indexOf('## Project Structure'),
    agents.indexOf('## ', agents.indexOf('## Project Structure') + 10),
  );

  it('names every source file in its structure tree', () => {
    const missing = sources(join(ROOT, 'src'))
      .map(path => relative(join(ROOT, 'src'), path))
      .filter(path => !tree.includes(path.split('/').pop() ?? path));
    expect(missing).toEqual([]);
  });
});
