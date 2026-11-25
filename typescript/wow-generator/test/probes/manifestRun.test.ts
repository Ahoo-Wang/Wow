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
 * What a Manifest run takes and asks for besides the Manifest (D486 ③): it
 * reads none of the configuration, and says so when the configuration sets
 * API clients; and the package asks for `@ahoo-wang/wow-client` only as an
 * optional peer, since its built code loads none.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../../src/api/errors';
import { generateCold, removeDirectories } from '../support/generation';
import { manifest } from '../support/manifestProbes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

describe('a Manifest run', () => {
  it('warns that it reads none of the API clients a configuration sets', async () => {
    const result = await generateCold(manifest(), directories, {
      apiClients: { Catalog: { ignorePathParameters: ['tenantId'] } },
    });
    expect(result.exitCode).toBe(EXIT_CODES.success);
    expect(result.logger.warnings).toEqual([
      expect.stringMatching(
        /^The configuration .*wow-generator\.config\.json sets apiClients, which a Manifest run does not read: they configure the clients of an OpenAPI document\.$/,
      ),
    ]);
  });

  it('warns of nothing when the configuration sets no API clients', async () => {
    const result = await generateCold(manifest(), directories, {});
    expect(result.exitCode).toBe(EXIT_CODES.success);
    expect(result.logger.warnings).toEqual([]);
  });
});

describe('the package', () => {
  it('asks for @ahoo-wang/wow-client as an optional peer, as an OpenAPI-only project needs none', () => {
    const pkg = JSON.parse(
      readFileSync(join(import.meta.dirname, '../../package.json'), 'utf8'),
    ) as {
      peerDependencies: Record<string, string>;
      peerDependenciesMeta: Record<string, { optional?: boolean }>;
    };
    expect(pkg.peerDependencies['@ahoo-wang/wow-client']).toBe('workspace:~');
    expect(pkg.peerDependenciesMeta['@ahoo-wang/wow-client']).toEqual({
      optional: true,
    });
  });
});
