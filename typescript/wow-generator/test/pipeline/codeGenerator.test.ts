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

import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Project } from 'ts-morph';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CodeGenerator, GeneratorError, SilentLogger } from '../../src';
import { SIGNAL_SEAM } from '../../src/pipeline/seams';
import { createCodeGenerator, recordingLogger } from '../support/generation';

const directories: string[] = [];
afterEach(() =>
  directories
    .splice(0)
    .forEach(dir => rmSync(dir, { recursive: true, force: true })),
);

function writeSpec(spec: unknown): string {
  const dir = mkdtempSync(join(tmpdir(), 'wow-generator-index-'));
  directories.push(dir);
  const path = join(dir, 'openapi.json');
  writeFileSync(path, JSON.stringify(spec));
  return path;
}

const SPEC = {
  openapi: '3.0.4',
  info: { title: 'Catalog', version: '1' },
  paths: {
    '/items/{id}': {
      get: {
        tags: ['Items'],
        operationId: 'getItem',
        parameters: [
          {
            name: 'id',
            in: 'path',
            required: true,
            schema: { type: 'string' },
          },
        ],
        responses: {
          '200': {
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/catalog.Item' },
              },
            },
          },
        },
      },
    },
  },
  components: {
    schemas: {
      'catalog.Item': {
        type: 'object',
        properties: { id: { type: 'string' } },
        required: ['id'],
      },
    },
  },
};

describe('CodeGenerator', () => {
  it('generates models, clients and barrels, and reports what it wrote', async () => {
    const project = new Project({ useInMemoryFileSystem: true });
    const generator = createCodeGenerator(
      {
        inputPath: writeSpec(SPEC),
        outputDir: '/out',
        configPath: undefined,
        logger: new SilentLogger(),
      },
      project,
    );

    const result = await generator.generate();

    expect(result.files).toEqual([
      '/out/ItemsApiClient.ts',
      '/out/catalog/index.ts',
      '/out/catalog/types.ts',
      '/out/index.ts',
    ]);
    expect(result.warnings).toBe(0);
    expect(result.configPath).toBeUndefined();
    const fs = project.getFileSystem();
    expect(fs.readFileSync('/out/catalog/types.ts')).toContain(
      'export interface Item',
    );
    expect(fs.readFileSync('/out/index.ts')).toContain('./catalog');
    expect(fs.fileExistsSync('/out/.wow-generator.json')).toBe(true);
  });

  it('logs each warning as it arises and counts those of one run, not of the generator instance', async () => {
    const project = new Project({ useInMemoryFileSystem: true });
    const logger = recordingLogger();
    const spec = structuredClone(SPEC) as typeof SPEC & {
      paths: Record<string, unknown>;
    };
    spec.paths['/untagged'] = {
      get: { operationId: 'untagged', responses: {} },
    };
    const generator = createCodeGenerator(
      { inputPath: writeSpec(spec), outputDir: '/out', logger },
      project,
    );

    const first = await generator.generate();
    const second = await generator.generate();

    expect(first.warnings).toBe(1);
    expect(second.warnings).toBe(1);
    expect(logger.warnings).toEqual([
      'Skipping GET /untagged: it has no tag, and the tag names its API client.',
      'Skipping GET /untagged: it has no tag, and the tag names its API client.',
    ]);
  });

  it('reads the configuration before the document, so a broken configuration fails first', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'wow-generator-index-'));
    directories.push(dir);
    const configPath = join(dir, 'wow-generator.config.json');
    writeFileSync(configPath, '[]');
    const generator = createCodeGenerator(
      {
        inputPath: join(dir, 'missing.json'),
        outputDir: '/out',
        configPath,
        logger: new SilentLogger(),
      },
      new Project({ useInMemoryFileSystem: true }),
    );

    const error = await generator.generate().catch(e => e);

    expect(error).toBeInstanceOf(GeneratorError);
    expect(error.kind).toBe('configuration');
    expect(error.message).toContain(
      `Configuration ${configPath} must be a JSON or YAML object`,
    );
  });

  it('fails with a configuration error when the tsconfig cannot be read', () => {
    const tsConfigFilePath = join(
      tmpdir(),
      'wow-generator-missing',
      'tsconfig.json',
    );
    let error: unknown;
    try {
      new CodeGenerator({
        inputPath: 'spec.json',
        outputDir: '/out',
        tsConfigFilePath,
        logger: new SilentLogger(),
      });
    } catch (thrown) {
      error = thrown;
    }
    expect(error).toBeInstanceOf(GeneratorError);
    expect((error as GeneratorError).kind).toBe('configuration');
    expect((error as GeneratorError).message).toContain(
      `Cannot read the TypeScript configuration ${tsConfigFilePath}:`,
    );
  });

  it('writes nothing when it is interrupted before it writes', async () => {
    const project = new Project({ useInMemoryFileSystem: true });
    const interruption = new AbortController();
    interruption.abort();
    const generator = createCodeGenerator(
      {
        inputPath: writeSpec(SPEC),
        outputDir: '/out',
        logger: new SilentLogger(),
        [SIGNAL_SEAM]: interruption.signal,
      },
      project,
    );

    await expect(generator.generate()).rejects.toBe(interruption.signal.reason);

    expect(project.getFileSystem().directoryExistsSync('/out')).toBe(false);
  });

  it('fails with an input error naming the document when it is not OpenAPI 3', async () => {
    const inputPath = writeSpec({ swagger: '2.0', info: {}, paths: {} });
    const generator = createCodeGenerator(
      { inputPath, outputDir: '/out', logger: new SilentLogger() },
      new Project({ useInMemoryFileSystem: true }),
    );

    const error = await generator.generate().catch(e => e);

    expect(error).toBeInstanceOf(GeneratorError);
    expect(error.kind).toBe('input');
    expect(error.message).toContain(`${inputPath} is a Swagger 2.0 document`);
  });

  it('logs through a console logger when none is given', () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    // The default level prints no details, so construction is silent.
    new CodeGenerator({ inputPath: 'spec.json', outputDir: '/out' });
    expect(log).not.toHaveBeenCalled();
  });
});
