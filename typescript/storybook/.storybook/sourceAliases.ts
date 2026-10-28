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

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

export interface SourceAlias {
  find: RegExp;
  replacement: string;
}

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Every entry a workspace package exports, resolved to its source: `.` is
 * `src/index.ts`, `./x` is `src/x.ts` or `src/x/index.ts`, a stylesheet is the
 * same file under `src`, and `./x/*.css` matches each file there. Read from
 * `package.json` `exports`, so a new entry resolves without an edit here; the
 * names match exactly, so `@ahoo-wang/a/b` never lands under `a`'s index.
 */
export function sourceAliases(
  packagesRoot: string,
  directories: readonly string[],
): SourceAlias[] {
  return directories.flatMap(directory => {
    const root = join(packagesRoot, directory);
    const manifest = JSON.parse(
      readFileSync(join(root, 'package.json'), 'utf8'),
    ) as { name: string; exports: Record<string, unknown> };
    return Object.keys(manifest.exports)
      .filter(subpath => subpath !== './package.json')
      .map(subpath => {
        const name = manifest.name + subpath.slice(1);
        if (subpath.includes('*')) {
          const [before, after] = subpath.slice(2).split('*');
          return {
            find: new RegExp(
              `^${escape(manifest.name + '/' + before)}(.+)${escape(after)}$`,
            ),
            replacement: join(root, 'src', `${before}$1${after}`),
          };
        }
        return {
          find: new RegExp(`^${escape(name)}$`),
          replacement: source(root, subpath),
        };
      });
  });
}

function source(root: string, subpath: string): string {
  if (subpath === '.') return join(root, 'src', 'index.ts');
  const entry = subpath.slice(2);
  const candidates = /\.\w+$/.test(entry)
    ? [entry]
    : [`${entry}.ts`, join(entry, 'index.ts')];
  const found = candidates
    .map(candidate => join(root, 'src', candidate))
    .find(existsSync);
  if (!found) {
    throw new Error(`No source for ${subpath} under ${join(root, 'src')}`);
  }
  return found;
}
