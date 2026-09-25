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

import { Project, QuoteKind } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { emitIndexFiles } from '../../src/emitters/indexFiles';

/** Writes the index files of `/out`, holding the given files. */
function index(files: Record<string, string>) {
  const project = new Project({
    useInMemoryFileSystem: true,
    manipulationSettings: { quoteKind: QuoteKind.Single },
  });
  for (const [path, text] of Object.entries(files)) {
    project.createSourceFile(`/out/${path}`, text);
  }
  const claimed: string[] = [];
  const warnings = emitIndexFiles(
    project.getDirectoryOrThrow('/out'),
    directory => {
      claimed.push(directory);
      return (
        project.getSourceFile(`${directory}/index.ts`) ??
        project.createSourceFile(`${directory}/index.ts`, '')
      );
    },
  );
  return {
    warnings,
    claimed,
    text: (path: string) =>
      project.getSourceFileOrThrow(`/out/${path}`).getFullText(),
  };
}

describe('index files', () => {
  it('re-exports the files of a directory and the indexes of its subdirectories', () => {
    const { text, claimed, warnings } = index({
      'types.ts': 'export interface Root {}',
      'shop/types.ts': 'export interface Order {}',
      'shop/itemsApiClient.ts': 'export class ItemsApiClient {}',
    });
    expect(text('index.ts')).toBe(
      "export * from './types.js';\nexport * from './shop/index.js';\n",
    );
    expect(text('shop/index.ts')).toBe(
      "export * from './itemsApiClient.js';\nexport * from './types.js';\n",
    );
    expect(claimed).toEqual(['/out/shop', '/out']);
    expect(warnings).toEqual([]);
  });

  it('re-exports by name what only one child exports, and warns about the rest', () => {
    const { text, warnings } = index({
      'a/types.ts': 'export interface User {}\nexport enum Item { A = "a" }',
      'b/types.ts': 'export interface User {}',
    });
    expect(text('index.ts')).toBe("export { Item } from './a/index.js';\n");
    expect(warnings).toEqual([
      'User is exported by both ./a/index.js and ./b/index.js in /out; its index leaves it out, so import it from its own module.',
    ]);
  });

  it('keeps an index whose every name is ambiguous a module', () => {
    const { text } = index({
      'a/types.ts': 'export interface User {}',
      'b/types.ts': 'export type User = string;',
    });
    expect(text('index.ts')).toBe('export {};\n');
  });
});
