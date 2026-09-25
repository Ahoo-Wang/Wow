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

import { Fetcher } from '@ahoo-wang/fetcher';
import { describe, expect, it } from 'vitest';
import type { QueryClientOptions } from '../../../src';
import {
  EventStreamQueryClient,
  LoadOwnerStateAggregateClient,
  LoadStateAggregateClient,
  QueryClientFactory,
  QueryDescriptorClient,
  ResourceAttributionPathSpec,
  SnapshotQueryClient,
} from '../../../src';

/** Every factory method, with the client class it makes. */
const METHODS = [
  ['createSnapshotQueryClient', SnapshotQueryClient],
  ['createLoadStateAggregateClient', LoadStateAggregateClient],
  ['createLoadOwnerStateAggregateClient', LoadOwnerStateAggregateClient],
  ['createEventStreamQueryClient', EventStreamQueryClient],
] as const;

/** The metadata the factory gives its clients. */
const apiMetadataOf = (options: QueryClientOptions) =>
  new QueryClientFactory(options).createSnapshotQueryClient().apiMetadata!;

describe('QueryClientFactory', () => {
  describe('base path', () => {
    it.each([
      [{ aggregateName: 'cart' }, '/cart'],
      [
        {
          aggregateName: 'cart',
          resourceAttribution: ResourceAttributionPathSpec.TENANT,
        },
        '/tenant/{tenantId}/cart',
      ],
      [{ aggregateName: 'cart', contextAlias: 'example' }, 'example/cart'],
      [
        {
          aggregateName: 'cart',
          resourceAttribution: ResourceAttributionPathSpec.TENANT,
          contextAlias: 'example',
        },
        'example/tenant/{tenantId}/cart',
      ],
      [{}, ''],
    ] satisfies [QueryClientOptions, string][])(
      'builds it from %o',
      (options, expected) => {
        expect(apiMetadataOf(options).basePath).toBe(expected);
      },
    );

    it('takes an explicit basePath over the route, even an empty one', () => {
      expect(
        apiMetadataOf({
          basePath: '/custom/pets',
          contextAlias: 'ignored',
          aggregateName: 'ignored',
        }).basePath,
      ).toBe('/custom/pets');
      expect(
        apiMetadataOf({ basePath: '', aggregateName: 'ignored' }).basePath,
      ).toBe('');
    });

    it.each(METHODS.map(([method]) => method))(
      'resolves it with the same precedence in %s',
      method => {
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
          {
            defaultBasePath: '',
            options: { basePath: undefined },
            expected: '',
          },
          { defaultBasePath: '/factory', expected: '/factory' },
          {
            defaultBasePath: '/factory',
            options: { basePath: '/client' },
            expected: '/client',
          },
          {
            defaultBasePath: '/factory',
            options: { basePath: '' },
            expected: '',
          },
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
      },
    );
  });

  describe.each(METHODS)('%s', (method, Client) => {
    const fetcher = new Fetcher({ baseURL: 'http://localhost' });

    it('makes its client', () => {
      const factory = new QueryClientFactory({ aggregateName: 'cart' });
      expect(factory[method]()).toBeInstanceOf(Client);
    });

    it('gives the client its options over the defaults', () => {
      const factory = new QueryClientFactory({
        contextAlias: 'example',
        aggregateName: 'cart',
        headers: { 'Wow-Space-Id': 'default' },
        timeout: 1000,
      });
      const { apiMetadata } = factory[method]({
        aggregateName: 'order',
        fetcher,
        urlParams: { path: { tenantId: 't1' } },
      });
      expect(apiMetadata).toEqual({
        basePath: 'example/order',
        fetcher,
        headers: { 'Wow-Space-Id': 'default' },
        timeout: 1000,
        urlParams: { path: { tenantId: 't1' } },
      });
    });

    it('passes on only ApiMetadata keys, not the ones the factory reads', () => {
      const factory = new QueryClientFactory({
        contextAlias: 'example',
        resourceAttribution: ResourceAttributionPathSpec.OWNER,
        aggregateName: 'cart',
      });
      const apiMetadata = factory[method]({ fetcher }).apiMetadata!;
      expect(Object.keys(apiMetadata).sort()).toEqual(['basePath', 'fetcher']);
      expect(apiMetadata.basePath).toBe('example/owner/{ownerId}/cart');
    });
  });

  // The schema routes have no tenant or owner segment, whatever routes the
  // aggregate's queries take.
  describe('createQueryDescriptorClient', () => {
    const fetcher = new Fetcher({ baseURL: 'http://localhost' });

    it('makes a QueryDescriptorClient whose base path has no resource attribution', () => {
      const factory = new QueryClientFactory({
        contextAlias: 'example',
        resourceAttribution: ResourceAttributionPathSpec.TENANT_OWNER,
        aggregateName: 'cart',
        headers: { 'Wow-Space-Id': 'default' },
      });
      const client = factory.createQueryDescriptorClient({ fetcher });
      expect(client).toBeInstanceOf(QueryDescriptorClient);
      expect(client.apiMetadata).toEqual({
        basePath: 'example/cart',
        fetcher,
        headers: { 'Wow-Space-Id': 'default' },
      });
    });

    it.each([
      [{ basePath: '/custom' }, undefined, '/custom'],
      [{ basePath: '/custom' }, { basePath: '/client' }, '/client'],
      [{ aggregateName: 'cart' }, { aggregateName: 'order' }, '/order'],
    ] satisfies [QueryClientOptions, QueryClientOptions | undefined, string][])(
      'takes an explicit basePath as it is: %o, %o',
      (defaults, options, expected) => {
        expect(
          new QueryClientFactory(defaults).createQueryDescriptorClient(options)
            .apiMetadata?.basePath,
        ).toBe(expected);
      },
    );
  });
});
