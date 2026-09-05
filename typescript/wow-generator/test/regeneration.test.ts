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

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CodeGenerator } from '../src';
import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';

const directories: string[] = [];
afterEach(() =>
  directories
    .splice(0)
    .forEach(dir => rmSync(dir, { recursive: true, force: true })),
);
const logger = {
  info() {},
  success() {},
  error() {},
  progress() {},
  progressWithCount() {},
};

describe('review regressions', () => {
  it('regenerates included output without accumulating declarations', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'fetcher-regeneration-'));
    directories.push(dir);
    const inputPath = join(dir, 'openapi.json');
    const tsConfigFilePath = join(dir, 'tsconfig.json');
    const outputDir = join(dir, 'out');
    writeFileSync(
      tsConfigFilePath,
      JSON.stringify({
        compilerOptions: { experimentalDecorators: true },
        include: ['out/**/*.ts'],
      }),
    );
    writeFileSync(
      inputPath,
      JSON.stringify({
        openapi: '3.0.4',
        info: {},
        paths: {
          '/message': {
            get: {
              tags: ['Messages'],
              operationId: 'message',
              responses: {
                '200': {
                  content: {
                    'application/json': { schema: { type: 'string' } },
                  },
                },
              },
            },
          },
        },
      }),
    );
    const options = { inputPath, outputDir, tsConfigFilePath, logger };
    await new CodeGenerator(options).generate();
    const first = readFileSync(join(outputDir, 'MessagesApiClient.ts'), 'utf8');
    writeFileSync(join(outputDir, 'custom.ts'), 'export const custom = 1;');
    await new CodeGenerator(options).generate();
    expect(readFileSync(join(outputDir, 'custom.ts'), 'utf8')).toBe(
      'export const custom = 1;',
    );
    expect(readFileSync(join(outputDir, 'MessagesApiClient.ts'), 'utf8')).toBe(
      first,
    );
  });
});

function regenerationFixture() {
  const dir = mkdtempSync(join(tmpdir(), 'fetcher-owned-output-'));
  directories.push(dir);
  const inputPath = join(dir, 'openapi.json');
  const outputDir = join(dir, 'out');
  const options = {
    inputPath,
    outputDir,
    logger,
    skipAddingFilesFromTsConfig: true,
    compilerOptions: { experimentalDecorators: true },
  };
  return {
    options,
    path: (...parts: string[]) => join(outputDir, ...parts),
    write: (spec: object) => writeFileSync(inputPath, JSON.stringify(spec)),
  };
}

function namespaceSpec(namespace: string): OpenAPI {
  return {
    openapi: '3.0.4',
    info: {},
    paths: {
      '/item': {
        get: {
          tags: [`${namespace}.Items`],
          operationId: 'getItem',
          responses: {
            '200': {
              content: {
                'application/json': {
                  schema: { $ref: `#/components/schemas/${namespace}.User` },
                },
              },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        [`${namespace}.User`]: {
          type: 'object',
          properties: { id: { type: 'string' } },
          required: ['id'],
        },
      },
    },
  };
}

it.each(['same', 'new'] as const)(
  'removes renamed schema/tag files across %s generator instances without tsconfig output discovery',
  async instance => {
    const fixture = regenerationFixture();
    fixture.write(namespaceSpec('old'));
    const generator = new CodeGenerator(fixture.options);
    await generator.generate();
    for (const file of ['types.ts', 'ItemsApiClient.ts', 'index.ts']) {
      expect(existsSync(fixture.path('old', file))).toBe(true);
    }
    fixture.write(namespaceSpec('next'));
    await (
      instance === 'same' ? generator : new CodeGenerator(fixture.options)
    ).generate();
    for (const file of ['types.ts', 'ItemsApiClient.ts', 'index.ts']) {
      expect(existsSync(fixture.path('old', file))).toBe(false);
      expect(existsSync(fixture.path('next', file))).toBe(true);
    }
    const barrel = readFileSync(fixture.path('index.ts'), 'utf8');
    expect(barrel).toContain('./next');
    expect(barrel).not.toContain('./old');
  },
);

it('cleans empty output barrels while preserving handwritten and edited generated bytes', async () => {
  const fixture = regenerationFixture();
  const initial = namespaceSpec('old');
  initial.components!.schemas!['gone.Item'] = { type: 'string' };
  fixture.write(initial);
  await new CodeGenerator(fixture.options).generate();
  const custom =
    '// Handwritten file. Keep spacing.\nexport const custom   = 1;';
  writeFileSync(fixture.path('old', 'custom.ts'), custom);
  const edited =
    '// Handwritten addition.\n' +
    readFileSync(fixture.path('old', 'types.ts'), 'utf8');
  writeFileSync(fixture.path('old', 'types.ts'), edited);
  fixture.write({
    openapi: '3.0.4',
    info: {},
    paths: {},
    components: { schemas: {} },
  });
  await new CodeGenerator(fixture.options).generate();
  expect(readFileSync(fixture.path('old', 'custom.ts'), 'utf8')).toBe(custom);
  expect(readFileSync(fixture.path('old', 'types.ts'), 'utf8')).toBe(edited);
  expect(existsSync(fixture.path('old', 'ItemsApiClient.ts'))).toBe(false);
  expect(existsSync(fixture.path('gone', 'types.ts'))).toBe(false);
  for (const path of [
    fixture.path('index.ts'),
    fixture.path('old', 'index.ts'),
    fixture.path('gone', 'index.ts'),
  ]) {
    expect(existsSync(path)).toBe(false);
  }
});

it('keeps disk output and manifest on partial failure and discards drafts before same-instance retry', async () => {
  const fixture = regenerationFixture();
  fixture.write(namespaceSpec('old'));
  const generator = new CodeGenerator(fixture.options);
  await generator.generate();
  const files = [
    'index.ts',
    'old/types.ts',
    'old/ItemsApiClient.ts',
    'old/index.ts',
  ];
  const before = files.map(path => readFileSync(fixture.path(path), 'utf8'));
  const manifestPath = fixture.path('.fetcher-generator.json');
  const manifestBefore = existsSync(manifestPath)
    ? readFileSync(manifestPath, 'utf8')
    : undefined;
  fixture.write({
    openapi: '3.0.4',
    info: {},
    paths: {},
    components: {
      schemas: {
        'draft.Temporary': { type: 'string' },
        'bad.Cycle': { $ref: '#/components/schemas/bad.Cycle' },
      },
    },
  });
  await expect(generator.generate()).rejects.toThrow(/cyclic/i);
  expect(files.map(path => readFileSync(fixture.path(path), 'utf8'))).toEqual(
    before,
  );
  expect(
    existsSync(manifestPath) ? readFileSync(manifestPath, 'utf8') : undefined,
  ).toBe(manifestBefore);
  expect(existsSync(fixture.path('draft', 'types.ts'))).toBe(false);
  expect(existsSync(fixture.path('bad', 'types.ts'))).toBe(false);
  fixture.write(namespaceSpec('next'));
  await generator.generate();
  expect(existsSync(fixture.path('next', 'types.ts'))).toBe(true);
  expect(existsSync(fixture.path('draft', 'types.ts'))).toBe(false);
  expect(existsSync(fixture.path('bad', 'types.ts'))).toBe(false);
  const barrel = readFileSync(fixture.path('index.ts'), 'utf8');
  expect(barrel).not.toContain('./draft');
  expect(barrel).not.toContain('./bad');
});

it('rejects manifest paths outside output before deleting existing files', async () => {
  const fixture = regenerationFixture();
  fixture.write(namespaceSpec('old'));
  await new CodeGenerator(fixture.options).generate();
  const manifestPath = fixture.path('.fetcher-generator.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const original = readFileSync(fixture.path('old', 'types.ts'), 'utf8');
  writeFileSync(fixture.path('..', 'outside.ts'), original);
  manifest.files['../outside.ts'] = manifest.files['old/types.ts'];
  writeFileSync(manifestPath, JSON.stringify(manifest));
  fixture.write({ openapi: '3.0.4', info: {}, paths: {} });
  await expect(new CodeGenerator(fixture.options).generate()).rejects.toThrow(
    /outside the output directory/,
  );
  expect(readFileSync(fixture.path('..', 'outside.ts'), 'utf8')).toBe(original);
  expect(readFileSync(fixture.path('old', 'types.ts'), 'utf8')).toBe(original);
});

it('does not delete an owned path redirected outside output by a directory symlink', async () => {
  const fixture = regenerationFixture();
  fixture.write(namespaceSpec('old'));
  await new CodeGenerator(fixture.options).generate();
  const original = readFileSync(fixture.path('old', 'types.ts'), 'utf8');
  const moved = fixture.path('..', 'moved');
  renameSync(fixture.path('old'), moved);
  symlinkSync(moved, fixture.path('old'), 'junction');
  fixture.write({ openapi: '3.0.4', info: {}, paths: {} });
  await expect(new CodeGenerator(fixture.options).generate()).rejects.toThrow(
    /outside the output directory/,
  );
  expect(readFileSync(join(moved, 'types.ts'), 'utf8')).toBe(original);
});

it('tracks saved BOM bytes using the project filesystem and its relative output directory', async () => {
  const fixture = regenerationFixture();
  fixture.write(namespaceSpec('old'));
  const generator = new CodeGenerator({
    ...fixture.options,
    outputDir: 'out',
    useInMemoryFileSystem: true,
  });
  const project = generator['project'];
  project.createSourceFile('/out/old/types.ts', '\uFEFFexport const old = 1;');
  await project.save();
  await generator.generate();
  const fs = project.getFileSystem();
  expect(fs.readFileSync('/out/old/types.ts').charCodeAt(0)).toBe(0xfeff);
  fixture.write({ openapi: '3.0.4', info: {}, paths: {} });
  await generator.generate();
  expect(fs.fileExistsSync('/out/old/types.ts')).toBe(false);
  expect(fs.fileExistsSync('/out/index.ts')).toBe(false);
  expect(fs.fileExistsSync('/out/.fetcher-generator.json')).toBe(true);
});

it('waits for every file write before rejecting a failed save', async () => {
  const fixture = regenerationFixture();
  fixture.write(namespaceSpec('old'));
  const generator = new CodeGenerator(fixture.options);
  await generator.generate();
  const oldPaths = ['old/types.ts', 'old/ItemsApiClient.ts', 'old/index.ts'];
  const oldBytes = oldPaths.map(path =>
    readFileSync(fixture.path(path), 'utf8'),
  );
  const manifest = readFileSync(
    fixture.path('.fetcher-generator.json'),
    'utf8',
  );
  const fileSystem = generator['project'].getFileSystem();
  const writeFile = fileSystem.writeFile.bind(fileSystem);
  const failure = new Error('injected write failure');
  let releaseDelayed!: () => void;
  const delayed = new Promise<void>(resolve => {
    releaseDelayed = resolve;
  });
  let reportStarted!: () => void;
  const started = new Promise<void>(resolve => {
    reportStarted = resolve;
  });
  let reportFailed!: () => void;
  const failed = new Promise<void>(resolve => {
    reportFailed = resolve;
  });
  let reportFinished!: () => void;
  const finished = new Promise<void>(resolve => {
    reportFinished = resolve;
  });
  let delayedFinished = false;
  let rejectionObserved = false;
  const write = vi
    .spyOn(fileSystem, 'writeFile')
    .mockImplementation(async (path, text) => {
      if (path.endsWith('/next/types.ts')) {
        reportFailed();
        throw failure;
      }
      if (path.endsWith('/next/ItemsApiClient.ts')) {
        reportStarted();
        try {
          await delayed;
          await writeFile(path, text);
          delayedFinished = true;
        } finally {
          reportFinished();
        }
        return;
      }
      await writeFile(path, text);
    });
  fixture.write(namespaceSpec('next'));
  const generation = generator.generate().then(
    () => {
      throw new Error('Expected the injected write failure');
    },
    error => {
      rejectionObserved = true;
      return error;
    },
  );
  try {
    await Promise.all([started, failed]);
    // Let the failing write's rejection propagate without releasing the other write.
    await new Promise<void>(resolve => setImmediate(resolve));
    expect(rejectionObserved).toBe(false);
    expect(delayedFinished).toBe(false);
    releaseDelayed();
    await expect(generation).resolves.toBe(failure);
    expect(delayedFinished).toBe(true);
    expect(
      oldPaths.map(path => readFileSync(fixture.path(path), 'utf8')),
    ).toEqual(oldBytes);
    expect(readFileSync(fixture.path('.fetcher-generator.json'), 'utf8')).toBe(
      manifest,
    );
  } finally {
    releaseDelayed();
    await Promise.allSettled([generation, finished]);
    write.mockRestore();
  }
});
