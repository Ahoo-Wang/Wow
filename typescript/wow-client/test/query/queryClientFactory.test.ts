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

import { expect, it } from 'vitest';
import type { QueryClientOptions } from '../../src';
import { QueryClientFactory, ResourceAttributionPathSpec } from '../../src';

it.each([
  'createSnapshotQueryClient',
  'createLoadStateAggregateClient',
  'createOwnerLoadStateAggregateClient',
  'createEventStreamQueryClient',
] as const)('preserves basePath precedence in %s', method => {
  const cases: {
    defaultBasePath?: string;
    options?: QueryClientOptions;
    expected: string;
  }[] = [
    {
      defaultBasePath: '/factory',
      options: { basePath: undefined },
      expected: '/factory',
    },
    { defaultBasePath: '', options: { basePath: undefined }, expected: '' },
    { defaultBasePath: '/factory', expected: '/factory' },
    {
      defaultBasePath: '/factory',
      options: { basePath: '/client' },
      expected: '/client',
    },
    { defaultBasePath: '/factory', options: { basePath: '' }, expected: '' },
    {
      options: { basePath: undefined, aggregateName: 'client' },
      expected: 'context/tenant/{tenantId}/client',
    },
  ];
  for (const { defaultBasePath, options, expected } of cases) {
    const factory = new QueryClientFactory({
      basePath: defaultBasePath,
      contextAlias: 'context',
      resourceAttribution: ResourceAttributionPathSpec.TENANT,
      aggregateName: 'default',
    });
    expect(factory[method](options).apiMetadata?.basePath).toBe(expected);
  }
});
