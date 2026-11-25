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

/**
 * What a run leaves of the output besides its files (D486 ③): a checkout
 * that writes `\r\n` holds the same generation, so hashes and `--check` read
 * line ends as `\n`; and the directories a removal empties go with it.
 */

import { Project } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { GENERATION_MANIFEST, OutputStore } from '../../src/output/outputStore';

function project(): Project {
  return new Project({ useInMemoryFileSystem: true });
}

/** Commits `files`, path to text, as a run that wrote them. */
async function commit(target: Project, files: Record<string, string>) {
  const store = OutputStore.open(target, '/output');
  for (const [path, text] of Object.entries(files)) {
    store.claim(path).addStatements(text);
  }
  store.forgetStale();
  await store.commit();
}

/** Rewrites each of `paths` under /output with `\r\n` line ends, as a Windows checkout does. */
function crlf(target: Project, ...paths: string[]) {
  const fs = target.getFileSystem();
  for (const path of paths) {
    const file = `/output/${path}`;
    fs.writeFileSync(file, fs.readFileSync(file).replace(/\n/g, '\r\n'));
  }
}

describe('line ends', () => {
  it('reads a checkout that writes \\r\\n as the generation it holds', async () => {
    const target = project();
    const files = {
      'models/types.ts': 'export type A = 1;\nexport type B = 2;',
    };
    await commit(target, files);
    crlf(target, 'models/types.ts', GENERATION_MANIFEST);

    const store = OutputStore.open(target, '/output');
    store.claim('models/types.ts').addStatements(files['models/types.ts']);
    store.forgetStale();
    // `--check`: nothing differs, the manifest included.
    expect(store.differences()).toEqual([]);
  });

  it('removes a stale file a checkout rewrote with \\r\\n, which is still as the run wrote it', async () => {
    const target = project();
    await commit(target, {
      'models/a.ts': 'export type A = 1;',
      'models/b.ts': 'export type B = 2;',
    });
    crlf(target, 'models/b.ts');
    await commit(target, { 'models/a.ts': 'export type A = 1;' });
    expect(target.getFileSystem().fileExistsSync('/output/models/b.ts')).toBe(
      false,
    );
  });
});

describe('a directory a removal empties', () => {
  it('goes with the files the run no longer writes, up to the output directory, which stays', async () => {
    const target = project();
    await commit(target, {
      'shop/cart/aggregate.ts': 'export type A = 1;',
      'shop/cart/index.ts': 'export type B = 2;',
      'index.ts': 'export type C = 3;',
    });
    await commit(target, { 'index.ts': 'export type C = 3;' });
    const fs = target.getFileSystem();
    expect(fs.directoryExistsSync('/output/shop/cart')).toBe(false);
    expect(fs.directoryExistsSync('/output/shop')).toBe(false);
    expect(fs.directoryExistsSync('/output')).toBe(true);
  });

  it('stays while it holds a file the run did not write', async () => {
    const target = project();
    await commit(target, {
      'shop/cart/aggregate.ts': 'export type A = 1;',
      'index.ts': 'export type C = 3;',
    });
    target.getFileSystem().writeFileSync('/output/shop/notes.md', 'mine');
    await commit(target, { 'index.ts': 'export type C = 3;' });
    const fs = target.getFileSystem();
    expect(fs.directoryExistsSync('/output/shop/cart')).toBe(false);
    expect(fs.readFileSync('/output/shop/notes.md')).toBe('mine');
  });
});
