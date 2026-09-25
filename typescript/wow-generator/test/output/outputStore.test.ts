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
 * The output of one run: claiming files, and what a commit writes, removes
 * and records. Regenerating over earlier output on disk is held by
 * test/output/regeneration.test.ts.
 */

import { Project } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { GENERATION_MANIFEST, OutputStore } from '../../src/output/outputStore';

function project(): Project {
  return new Project({ useInMemoryFileSystem: true });
}

/** Commits one file, as a run that wrote it. */
async function commitOnce(target: Project, text: string) {
  const store = OutputStore.open(target, '/output');
  store.claim('models/types.ts').addStatements(text);
  await store.commit();
  return store;
}

describe('OutputStore', () => {
  describe('claim', () => {
    it('creates the file under the output directory, empty, and owns it', () => {
      const target = project();
      target.createSourceFile('/output/models/types.ts', 'stale text');
      const store = OutputStore.open(target, '/output');

      const file = store.claim('models/types.ts');

      expect(file.getFilePath()).toBe('/output/models/types.ts');
      expect(file.getFullText()).toBe('');
      expect([...store.files]).toEqual(['/output/models/types.ts']);
    });

    it('hands out the same file on a second claim, keeping what was written', () => {
      const store = OutputStore.open(project(), '/output');
      store.claim('models/types.ts').addStatements('export type A = 1;');

      const again = store.claim('models/types.ts');

      expect(again.getFullText()).toContain('export type A = 1;');
    });

    it('claims a file of a directory under the output', () => {
      const store = OutputStore.open(project(), '/output');
      expect(store.claim('index.ts', '/output/models').getFilePath()).toBe(
        '/output/models/index.ts',
      );
    });

    // `filePath` derives from a (possibly remote, attacker-controlled)
    // OpenAPI schema key: a key like "../../etc/cron.d/pwn" must not write or
    // clobber a file outside the output directory.
    it('rejects a path that escapes the output directory', () => {
      const target = project();
      const store = OutputStore.open(target, '/output');

      expect(() => store.claim('../../../etc/passwd')).toThrow(GeneratorError);
      expect(() => store.claim('../../../etc/passwd')).toThrow(
        'Path traversal detected',
      );
      expect(target.getSourceFiles()).toEqual([]);
    });

    // With a relative output directory (`src/generated`), the check once
    // joined the base twice, so the path it checked was not the path written.
    it('rejects a path that escapes a relative output directory', () => {
      const target = project();
      const store = OutputStore.open(target, 'src/generated');

      expect(() => store.claim('../../etc/evil')).toThrow(GeneratorError);
      expect(target.getSourceFiles()).toEqual([]);
    });
  });

  describe('commit', () => {
    it('writes the files and records their hashes', async () => {
      const target = project();
      await commitOnce(target, 'export type A = 1;');
      const fs = target.getFileSystem();

      expect(fs.readFileSync('/output/models/types.ts')).toBe(
        'export type A = 1;\n',
      );
      const manifest = JSON.parse(
        fs.readFileSync(`/output/${GENERATION_MANIFEST}`),
      );
      expect(manifest.version).toBe(1);
      expect(Object.keys(manifest.files)).toEqual(['models/types.ts']);
      expect(manifest.files['models/types.ts']).toMatch(/^[a-f0-9]{64}$/);
    });

    it('removes a file of the last run this run does not write, when it is unchanged', async () => {
      const target = project();
      await commitOnce(target, 'export type A = 1;');
      const store = OutputStore.open(target, '/output');
      store.claim('other/types.ts').addStatements('export type B = 2;');
      store.forgetStale();

      await store.commit();

      const fs = target.getFileSystem();
      expect(fs.fileExistsSync('/output/models/types.ts')).toBe(false);
      expect(
        Object.keys(
          JSON.parse(fs.readFileSync(`/output/${GENERATION_MANIFEST}`)).files,
        ),
      ).toEqual(['other/types.ts']);
    });

    it('stops after writing when interrupted: it removes nothing and keeps the last manifest', async () => {
      const target = project();
      await commitOnce(target, 'export type A = 1;');
      const fs = target.getFileSystem();
      const manifest = fs.readFileSync(`/output/${GENERATION_MANIFEST}`);
      const store = OutputStore.open(target, '/output');
      store.claim('other/types.ts').addStatements('export type B = 2;');
      store.forgetStale();
      const interruption = new AbortController();
      const saving = store.commit(interruption.signal);
      interruption.abort();

      await expect(saving).rejects.toBe(interruption.signal.reason);

      expect(fs.readFileSync('/output/other/types.ts')).toBe(
        'export type B = 2;\n',
      );
      expect(fs.fileExistsSync('/output/models/types.ts')).toBe(true);
      expect(fs.readFileSync(`/output/${GENERATION_MANIFEST}`)).toBe(manifest);
    });

    it('writes nothing when interrupted before it starts', async () => {
      const target = project();
      const store = OutputStore.open(target, '/output');
      store.claim('models/types.ts').addStatements('export type A = 1;');
      const interruption = new AbortController();
      interruption.abort();

      await expect(store.commit(interruption.signal)).rejects.toBe(
        interruption.signal.reason,
      );

      expect(target.getFileSystem().directoryExistsSync('/output')).toBe(false);
    });
  });

  describe('open', () => {
    it('takes the files of the last run into the same project out of it', async () => {
      const target = project();
      const last = OutputStore.open(target, '/drafts');
      last.claim('draft.ts').addStatements('export type Draft = 1;');

      OutputStore.open(target, '/output', last);

      expect(target.getSourceFile('/drafts/draft.ts')).toBeUndefined();
    });

    it('fails with an output error on a manifest that is not one', () => {
      const target = project();
      target
        .getFileSystem()
        .writeFileSync(`/output/${GENERATION_MANIFEST}`, '{"version":2}');

      expect(() => OutputStore.open(target, '/output')).toThrow(
        `Invalid generation manifest: /output/${GENERATION_MANIFEST}`,
      );
    });
  });
});
