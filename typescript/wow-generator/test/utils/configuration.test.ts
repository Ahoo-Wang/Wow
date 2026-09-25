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

import { resolve } from 'path';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CONFIG_PATH } from '../../src/api/configuration';
import {
  LEGACY_CONFIG_PATH,
  loadConfiguration,
  loadResource,
  resolveConfiguration,
} from '../../src/utils';
import { GeneratorError } from '../../src/api/errors';
import type { Logger } from '../../src/api/logger';

vi.mock('@/utils/resources.ts', async importOriginal => ({
  ...(await importOriginal<typeof import('../../src/utils/resources')>()),
  loadResource: vi.fn(),
}));

const mockLoadResource = vi.mocked(loadResource);

function testLogger() {
  return {
    debug: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
  } satisfies Logger;
}

function notFound(): NodeJS.ErrnoException {
  const error: NodeJS.ErrnoException = new Error(
    "ENOENT: no such file or directory, open './wow-generator.config.json'",
  );
  error.code = 'ENOENT';
  return error;
}

const DEFAULT_SOURCE = {
  path: './wow-generator.config.json',
  explicit: false,
};
const EXPLICIT_SOURCE = { path: './custom.config.json', explicit: true };

describe('loadConfiguration', () => {
  beforeEach(() => {
    mockLoadResource.mockReset();
  });

  it('reports the settings it resolved, so a run answers "did my option take effect?"', async () => {
    mockLoadResource.mockResolvedValue(
      '{ "apiClients": { "Catalog": { "ignorePathParameters": ["tenantId"] } } }',
    );
    const logger = testLogger();

    const config = await loadConfiguration(DEFAULT_SOURCE, logger);

    expect(config).toEqual({
      apiClients: { Catalog: { ignorePathParameters: ['tenantId'] } },
    });
    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining('apiClients=Catalog'),
    );
    expect(logger.warn).not.toHaveBeenCalled();
  });

  it('names the absolute path it looked at, not the relative one', async () => {
    mockLoadResource.mockResolvedValue('{}');
    const logger = testLogger();

    await loadConfiguration(DEFAULT_SOURCE, logger);

    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining(resolve('./wow-generator.config.json')),
    );
  });

  it('leaves a URL source as written', async () => {
    mockLoadResource.mockResolvedValue('{}');
    const logger = testLogger();

    await loadConfiguration(
      { path: 'https://example.com/config.json', explicit: true },
      logger,
    );

    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining('https://example.com/config.json'),
    );
  });

  it('carries on with defaults when the default path holds no file', async () => {
    mockLoadResource.mockRejectedValue(notFound());
    const logger = testLogger();

    await expect(
      loadConfiguration(DEFAULT_SOURCE, logger),
    ).resolves.toBeUndefined();
    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining('No configuration file at'),
    );
  });

  it('fails when a configuration the caller named is missing', async () => {
    mockLoadResource.mockRejectedValue(notFound());
    const logger = testLogger();

    const error = await loadConfiguration(EXPLICIT_SOURCE, logger).catch(
      e => e,
    );
    expect(error).toBeInstanceOf(GeneratorError);
    expect(error.kind).toBe('configuration');
    expect(error.message).toMatch(
      /Cannot read configuration .*custom\.config\.json/,
    );
  });

  it('fails when the file cannot be read for any other reason', async () => {
    const denied: NodeJS.ErrnoException = new Error(
      'EACCES: permission denied',
    );
    denied.code = 'EACCES';
    mockLoadResource.mockRejectedValue(denied);
    const logger = testLogger();

    await expect(
      loadConfiguration(DEFAULT_SOURCE, logger),
    ).rejects.toThrowError(/permission denied/);
  });

  it('renders a non-Error rejection rather than "[object Object]"', async () => {
    mockLoadResource.mockRejectedValue('the disk is on fire');

    await expect(
      loadConfiguration(DEFAULT_SOURCE, testLogger()),
    ).rejects.toThrowError(/the disk is on fire/);
  });

  it('fails when the content cannot be parsed', async () => {
    mockLoadResource.mockResolvedValue('{ "apiClients": ');
    const logger = testLogger();

    await expect(
      loadConfiguration(DEFAULT_SOURCE, logger),
    ).rejects.toThrowError(/Cannot parse configuration/);
  });

  it('treats an empty file as no configuration, and says so', async () => {
    mockLoadResource.mockResolvedValue('   \n\t ');
    const logger = testLogger();

    await expect(loadConfiguration(DEFAULT_SOURCE, logger)).resolves.toEqual(
      {},
    );
    expect(logger.warn).toHaveBeenCalledWith(expect.stringContaining('empty'));
  });

  it.each([
    ['an array', '[]'],
    ['a string', '"apiClients"'],
    ['a number', '42'],
    ['null', 'null'],
  ])('fails when the document is %s rather than an object', async (_, body) => {
    mockLoadResource.mockResolvedValue(body);

    await expect(
      loadConfiguration(DEFAULT_SOURCE, testLogger()),
    ).rejects.toThrowError(/must be a JSON or YAML object/);
  });

  it('warns about a misspelled top-level option instead of silently ignoring it', async () => {
    mockLoadResource.mockResolvedValue('{ "apiClient": { "Catalog": {} } }');
    const logger = testLogger();

    await loadConfiguration(DEFAULT_SOURCE, logger);

    expect(logger.warn).toHaveBeenCalledWith(
      expect.stringContaining('apiClient'),
    );
    // The summary reports what the generator actually read, so a typo shows
    // up as an empty setting rather than as the value the misspelled key held.
    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining('apiClients=none'),
    );
  });

  it('accepts and reports apiClients', async () => {
    mockLoadResource.mockResolvedValue(
      '{ "apiClients": { "Catalog": { "ignorePathParameters": ["tenantId"] } } }',
    );
    const logger = testLogger();

    await loadConfiguration(DEFAULT_SOURCE, logger);

    expect(logger.debug).toHaveBeenCalledWith(
      expect.stringContaining('apiClients=Catalog'),
    );
    expect(logger.warn).not.toHaveBeenCalled();
  });

  it('fails when apiClients is not keyed by tag', async () => {
    mockLoadResource.mockResolvedValue('{ "apiClients": ["Catalog"] }');

    await expect(
      loadConfiguration(DEFAULT_SOURCE, testLogger()),
    ).rejects.toThrowError(/apiClients .* must be an object keyed by tag name/);
  });

  it('fails when an apiClients entry is not an object', async () => {
    mockLoadResource.mockResolvedValue('{ "apiClients": { "Catalog": true } }');

    await expect(
      loadConfiguration(DEFAULT_SOURCE, testLogger()),
    ).rejects.toThrowError(/apiClients\["Catalog"\] .* must be an object/);
  });

  it('fails when ignorePathParameters is not a list of names', async () => {
    mockLoadResource.mockResolvedValue(
      '{ "apiClients": { "Catalog": { "ignorePathParameters": "tenantId" } } }',
    );

    await expect(
      loadConfiguration(DEFAULT_SOURCE, testLogger()),
    ).rejects.toThrowError(/must be an array of strings/);
  });

  it('warns about a misspelled apiClients option', async () => {
    mockLoadResource.mockResolvedValue(
      '{ "apiClients": { "Catalog": { "ignorePathParameter": [] } } }',
    );
    const logger = testLogger();

    await loadConfiguration(DEFAULT_SOURCE, logger);

    expect(logger.warn).toHaveBeenCalledWith(
      expect.stringContaining('ignorePathParameter'),
    );
  });

  it('reads YAML as readily as JSON', async () => {
    mockLoadResource.mockResolvedValue(
      'apiClients:\n  Catalog:\n    ignorePathParameters:\n      - tenantId',
    );
    const logger = testLogger();

    await expect(loadConfiguration(DEFAULT_SOURCE, logger)).resolves.toEqual({
      apiClients: { Catalog: { ignorePathParameters: ['tenantId'] } },
    });
  });
});

describe('resolveConfiguration', () => {
  beforeEach(() => {
    mockLoadResource.mockReset();
  });

  it('reads wow-generator.config.json by default', async () => {
    mockLoadResource.mockResolvedValue('{ "apiClients": {} }');
    const logger = testLogger();

    const resolved = await resolveConfiguration(undefined, logger);

    expect(mockLoadResource).toHaveBeenCalledTimes(1);
    expect(mockLoadResource).toHaveBeenCalledWith(
      DEFAULT_CONFIG_PATH,
      undefined,
    );
    expect(resolved).toEqual({
      config: { apiClients: {} },
      origin: resolve(DEFAULT_CONFIG_PATH),
    });
    expect(logger.warn).not.toHaveBeenCalled();
  });

  it('falls back to fetcher-generator.config.json with a deprecation warning', async () => {
    mockLoadResource.mockImplementation(async (path: string) => {
      if (path === DEFAULT_CONFIG_PATH) throw notFound();
      return '{ "apiClients": { "Catalog": {} } }';
    });
    const logger = testLogger();

    const resolved = await resolveConfiguration(undefined, logger);

    expect(resolved).toEqual({
      config: { apiClients: { Catalog: {} } },
      origin: resolve(LEGACY_CONFIG_PATH),
    });
    expect(logger.warn).toHaveBeenCalledWith(
      `${resolve(LEGACY_CONFIG_PATH)} uses the deprecated name; rename it to wow-generator.config.json. The old name is no longer read from v10.`,
    );
  });

  it('generates with defaults when neither name exists', async () => {
    mockLoadResource.mockRejectedValue(notFound());

    await expect(
      resolveConfiguration(undefined, testLogger()),
    ).resolves.toEqual({ config: {} });
    expect(mockLoadResource).toHaveBeenCalledTimes(2);
  });

  it('never falls back from a path the caller named', async () => {
    mockLoadResource.mockRejectedValue(notFound());

    await expect(
      resolveConfiguration('./custom.config.json', testLogger()),
    ).rejects.toThrowError(/Cannot read configuration/);
    expect(mockLoadResource).toHaveBeenCalledTimes(1);
  });
});
