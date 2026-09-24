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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EXIT_CODES, GeneratorError } from '../../src/errors';
import type {
  GenerationResult,
  GeneratorOptions,
  Logger,
} from '../../src/types';
import {
  generateAction,
  parseHeaders,
  runGenerate,
  validateInput,
} from '../../src/utils';

const generate = vi.fn<() => Promise<GenerationResult>>();
const constructed: GeneratorOptions[] = [];

vi.mock('../../src/index', () => ({
  CodeGenerator: class CodeGenerator {
    constructor(options: GeneratorOptions) {
      constructed.push(options);
    }
    generate = generate;
  },
}));

function testLogger() {
  return {
    info: vi.fn(),
    warn: vi.fn(),
    success: vi.fn(),
    error: vi.fn(),
    progress: vi.fn(),
    progressWithCount: vi.fn(),
  } satisfies Logger;
}

describe('validateInput', () => {
  it('rejects an empty input', () => {
    expect(validateInput('')).toBe(false);
  });

  it('accepts http and https URLs', () => {
    expect(validateInput('http://example.com')).toBe(true);
    expect(validateInput('https://example.com')).toBe(true);
  });

  it('rejects other URL schemes', () => {
    expect(validateInput('ftp://example.com')).toBe(false);
    expect(validateInput('file:///etc/passwd')).toBe(false);
  });

  it('accepts file paths, including Windows drive paths', () => {
    expect(validateInput('not-a-url')).toBe(true);
    expect(validateInput('/path/to/file')).toBe(true);
    expect(validateInput('relative/path')).toBe(true);
    expect(validateInput('C:\\specs\\openapi.json')).toBe(true);
  });

  // The generator runs on the developer's machine against a document they
  // chose; the usual source of /v3/api-docs is an intranet service.
  it('accepts private, link-local and loopback hosts', () => {
    for (const url of [
      'http://10.0.0.5/v3/api-docs',
      'http://192.168.1.2/v3/api-docs',
      'http://172.16.0.1/v3/api-docs',
      'http://169.254.169.254/v3/api-docs',
      'http://[fd12:3456::1]/v3/api-docs',
      'http://localhost:8080/v3/api-docs',
      'http://127.0.0.1:3000/spec.json',
      'http://compensation-service.dev.svc.cluster.local/v3/api-docs',
    ]) {
      expect(validateInput(url), url).toBe(true);
    }
  });
});

describe('parseHeaders', () => {
  it('parses repeated "Name: value" arguments', () => {
    expect(
      parseHeaders(['Authorization: Bearer a:b', 'X-Tenant:  t1 ']),
    ).toEqual({ Authorization: 'Bearer a:b', 'X-Tenant': 't1' });
  });

  it('rejects an argument without a name', () => {
    expect(() => parseHeaders(['Bearer token'])).toThrow(
      'Invalid --header "Bearer token": expected "Name: value".',
    );
    expect(() => parseHeaders([': value'])).toThrow(GeneratorError);
  });
});

describe('runGenerate', () => {
  beforeEach(() => {
    generate.mockReset();
    constructed.length = 0;
  });

  it('prints one summary line and exits 0', async () => {
    generate.mockResolvedValue({
      files: ['/out/a.ts', '/out/b.ts'],
      configPath: `${process.cwd()}/wow-generator.config.json`,
      warnings: 1,
    });
    const logger = testLogger();

    await expect(
      runGenerate({ input: 'spec.json', output: 'out' }, logger),
    ).resolves.toBe(EXIT_CODES.success);
    expect(logger.success).toHaveBeenCalledWith(
      'Generated 2 files into out with wow-generator.config.json, 1 warning',
    );
    expect(logger.error).not.toHaveBeenCalled();
  });

  it('passes headers and the timeout to the generator', async () => {
    generate.mockResolvedValue({ files: [], warnings: 0 });

    await runGenerate(
      {
        input: 'http://10.0.0.5/v3/api-docs',
        output: 'out',
        header: ['Authorization: Bearer t'],
        timeout: '5000',
      },
      testLogger(),
    );

    expect(constructed[0]).toMatchObject({
      inputPath: 'http://10.0.0.5/v3/api-docs',
      headers: { Authorization: 'Bearer t' },
      timeoutMs: 5000,
    });
  });

  it('refuses an invalid input with exit code 2', async () => {
    const logger = testLogger();

    await expect(
      runGenerate({ input: 'ftp://example.com/spec', output: 'out' }, logger),
    ).resolves.toBe(EXIT_CODES.input);
    expect(logger.error).toHaveBeenCalledWith(
      'Invalid input "ftp://example.com/spec": expected a file path or an http(s) URL.',
    );
    expect(constructed).toHaveLength(0);
  });

  it('passes the schema doc mode, and refuses an unknown one', async () => {
    generate.mockResolvedValue({ files: [], warnings: 0 });
    await runGenerate(
      { input: 'spec.json', output: 'out', schemaDocs: 'full' },
      testLogger(),
    );
    expect(constructed[0].schemaDocs).toBe('full');
    await expect(
      runGenerate(
        { input: 'spec.json', output: 'out', schemaDocs: 'all' },
        testLogger(),
      ),
    ).resolves.toBe(EXIT_CODES.input);
  });

  it('refuses an invalid timeout with exit code 2', async () => {
    await expect(
      runGenerate(
        { input: 'spec.json', output: 'out', timeout: 'soon' },
        testLogger(),
      ),
    ).resolves.toBe(EXIT_CODES.input);
  });

  it.each([
    ['input', EXIT_CODES.input],
    ['configuration', EXIT_CODES.configuration],
    ['specification', EXIT_CODES.specification],
  ] as const)(
    'reports a %s error as one line and exits %i',
    async (kind, exitCode) => {
      const cause = new Error('root cause');
      generate.mockRejectedValue(
        new GeneratorError(kind, 'what went wrong', { cause }),
      );
      const logger = testLogger();

      await expect(
        runGenerate({ input: 'spec.json', output: 'out' }, logger),
      ).resolves.toBe(exitCode);
      expect(logger.error).toHaveBeenCalledTimes(1);
      expect(logger.error).toHaveBeenCalledWith('what went wrong');
    },
  );

  it('adds the cause of a known error with --verbose', async () => {
    const cause = new Error('root cause');
    generate.mockRejectedValue(
      new GeneratorError('input', 'what went wrong', { cause }),
    );
    const logger = testLogger();

    await runGenerate(
      { input: 'spec.json', output: 'out', verbose: true },
      logger,
    );
    expect(logger.error).toHaveBeenCalledWith('Caused by:', cause);
  });

  it('reports an unexpected error without a stack trace and exits 1', async () => {
    const error = new TypeError('boom');
    generate.mockRejectedValue(error);
    const logger = testLogger();

    await expect(
      runGenerate({ input: 'spec.json', output: 'out' }, logger),
    ).resolves.toBe(EXIT_CODES.internal);
    expect(logger.error).toHaveBeenCalledWith('Code generation failed: boom');
    expect(logger.error).not.toHaveBeenCalledWith('', error);
  });

  it('prints the stack trace of an unexpected error with --verbose', async () => {
    const error = new TypeError('boom');
    generate.mockRejectedValue(error);
    const logger = testLogger();

    await runGenerate(
      { input: 'spec.json', output: 'out', verbose: true },
      logger,
    );
    expect(logger.error).toHaveBeenCalledWith('', error);
  });

  it('exits 4 with --strict when the run logged a warning', async () => {
    generate.mockResolvedValue({ files: [], warnings: 2 });
    const logger = testLogger();

    await expect(
      runGenerate({ input: 'spec.json', output: 'out', strict: true }, logger),
    ).resolves.toBe(EXIT_CODES.specification);
    expect(logger.error).toHaveBeenCalledWith(
      '--strict: the run logged 2 warnings.',
    );
  });

  it('exits 0 with --strict when the run was clean', async () => {
    generate.mockResolvedValue({ files: [], warnings: 0 });

    await expect(
      runGenerate(
        { input: 'spec.json', output: 'out', strict: true },
        testLogger(),
      ),
    ).resolves.toBe(EXIT_CODES.success);
  });
});

describe('generateAction', () => {
  it('sets the process exit code instead of exiting', async () => {
    generate.mockRejectedValue(new GeneratorError('input', 'unreadable'));
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const previous = process.exitCode;
    try {
      await generateAction({ input: 'spec.json', output: 'out', quiet: true });
      expect(process.exitCode).toBe(EXIT_CODES.input);
      expect(error).toHaveBeenCalledWith('error: unreadable');
    } finally {
      process.exitCode = previous;
    }
  });
});
