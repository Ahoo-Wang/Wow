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
 * Probes of the API clients: typed parameters and request bodies, success
 * responses, and the operations left out.
 */

import { describe, expect, it } from 'vitest';
import { runGenerated } from '../support/generation';
import { generateCompiling, generateFailing } from '../support/probes';
import { document, getOperation, wowDocument } from '../support/specs';

describe('typed parameters and request bodies', () => {
  const tenantHeader = {
    name: 'X-Tenant',
    in: 'header',
    required: true,
    description: 'The tenant\nthe request acts for',
    schema: { type: 'string' },
  };
  const spec = document(
    {
      '/items/{item-id}/search': getOperation(
        'search',
        { type: 'array', items: { $ref: '#/components/schemas/Item' } },
        {
          parameters: [
            {
              name: 'item-id',
              in: 'path',
              required: true,
              schema: { type: 'string' },
            },
            { name: 'page', in: 'query', schema: { type: 'integer' } },
            {
              name: 'q',
              in: 'query',
              required: true,
              schema: { type: 'string' },
            },
            {
              name: 'status',
              in: 'query',
              schema: { type: 'string', enum: ['open', 'closed'] },
            },
            tenantHeader,
            { name: 'session', in: 'cookie', schema: { type: 'string' } },
          ],
        },
      ),
      '/items': {
        post: {
          tags: ['Items'],
          operationId: 'create',
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Item' },
              },
            },
          },
          responses: { '201': { description: 'Created' } },
        },
        put: {
          tags: ['Items'],
          operationId: 'replace',
          requestBody: {
            required: true,
            content: {
              'application/json;charset=UTF-8': {
                schema: {
                  type: 'object',
                  properties: {
                    name: { type: 'string' },
                    tags: { type: 'array', items: { type: 'string' } },
                  },
                  required: ['name'],
                },
              },
            },
          },
          responses: { '204': { description: 'Replaced' } },
        },
      },
      '/forms': {
        post: {
          tags: ['Items'],
          operationId: 'submitForm',
          requestBody: {
            content: {
              'application/x-www-form-urlencoded': {
                schema: { type: 'object' },
              },
            },
          },
          responses: { '204': { description: 'Submitted' } },
        },
      },
      '/notes': {
        post: {
          tags: ['Items'],
          operationId: 'addNote',
          requestBody: {
            required: true,
            content: { 'text/plain': { schema: { type: 'string' } } },
          },
          responses: { '204': { description: 'Added' } },
        },
      },
    },
    {
      Item: {
        type: 'object',
        properties: { id: { type: 'string' }, name: { type: 'string' } },
        required: ['name'],
      },
    },
  );

  it('types query and header parameters and request bodies, required first', async () => {
    const { read, logger } = await generateCompiling(
      spec,
      `import { ItemsApiClient, type Item } from './out/index.js';
       declare const client: ItemsApiClient;
       const found: Promise<Item[]> = client.search('i-1', 'term', 't1', 2, 'open');
       client.search('i-1', 'term', 't1');
       // @ts-expect-error q is required
       client.search('i-1');
       // @ts-expect-error status is one of the enum values
       client.search('i-1', 'term', 't1', undefined, 'other');
       client.create({ name: 'n' });
       // @ts-expect-error the body is required
       client.create();
       client.replace({ name: 'n', tags: ['a'] });
       // @ts-expect-error name is required
       client.replace({ tags: ['a'] });
       client.submitForm();
       client.submitForm(new URLSearchParams({ a: '1' }));
       client.addNote('text');
       void found;`,
    );
    const client = read('ItemsApiClient.ts');
    expect(client).toContain(
      "search(@path('item-id') itemId: string, @query('q') q: string, @header('X-Tenant') xTenant: string, @query('page') page?: number, @query('status') status?: 'open' | 'closed', @request() httpRequest?: ParameterRequest, @attribute() attributes?: Record<string, unknown>): Promise<Item[]>",
    );
    expect(client).toContain(
      "create(@body() body: PartialBy<Item, 'id'>, @request() httpRequest?: ParameterRequest",
    );
    expect(client).toContain(
      '@param xTenant - The tenant the request acts for',
    );
    expect(logger.warnings).toEqual([
      'GET /items/{item-id}/search leaves out its cookie parameter(s) session: the browser sends cookies, and fetch cannot set them.',
    ]);
  });

  it('sends each parameter where the document says, and omits an absent one', async () => {
    const { dir } = await generateCompiling(spec);
    const output = runGenerated(
      dir,
      `const { ItemsApiClient } = await import('./out/index.js');
const client = new ItemsApiClient({ fetcher: new Fetcher({ baseURL: 'http://api.test' }) });
await client.search('i 1', 'term', 't1');
await client.search('i-1', 'term', 't1', 2, 'open');
await client.create({ name: 'n' }).catch(() => {});
await client.addNote('text').catch(() => {});
console.log(JSON.stringify(requests.map(({ url, method, headers, body }) => [method, url, headers['x-tenant'] ?? null, body])));`,
    );
    expect(JSON.parse(output)).toEqual([
      ['GET', 'http://api.test/items/i%201/search?q=term', 't1', null],
      [
        'GET',
        'http://api.test/items/i-1/search?q=term&page=2&status=open',
        't1',
        null,
      ],
      ['POST', 'http://api.test/items', null, '{"name":"n"}'],
      ['POST', 'http://api.test/notes', null, 'text'],
    ]);
  });
});

describe('success responses', () => {
  it.each([
    [
      '201 application/json',
      {
        '201': {
          content: { 'application/json': { schema: { type: 'integer' } } },
        },
      },
      'Promise<number>',
    ],
    [
      'charset parameter',
      {
        '200': {
          content: {
            'application/json;charset=UTF-8': { schema: { type: 'integer' } },
          },
        },
      },
      'Promise<number>',
    ],
    [
      'hal+json',
      {
        '200': {
          content: { 'application/hal+json': { schema: { type: 'integer' } } },
        },
      },
      'Promise<number>',
    ],
    [
      'text/plain',
      { '200': { content: { 'text/plain': { schema: { type: 'string' } } } } },
      'Promise<string>',
    ],
  ])('types a %s response', async (_, responses, returnType) => {
    const { read } = await generateCompiling(
      document({
        '/x': { get: { tags: ['Items'], operationId: 'x', responses } },
      }),
    );
    expect(read('ItemsApiClient.ts')).toContain(`): ${returnType} {`);
  });
});

describe('what the generator leaves out', () => {
  it('warns about an operation without operationId or tag', async () => {
    const { logger } = await generateCompiling(
      document({
        '/a': { get: { tags: ['Items'], responses: {} } },
        '/b': { get: { operationId: 'b', responses: {} } },
      }),
    );
    expect(logger.warnings).toEqual([
      'Skipping GET /a: it has no operationId, and the operationId names its method.',
      'Skipping GET /b: it has no tag, and the tag names its API client.',
    ]);
  });

  it('warns about an aggregate without state, and still compiles', async () => {
    const { logger } = await generateCompiling(
      wowDocument({ withoutState: true }),
    );
    expect(logger.warnings).toEqual([
      'Skipping aggregate shop.order: the document has no shop.order.snapshot_state.single operation, so it generates neither command nor query clients for it.',
    ]);
  });

  it('reads aggregates whose tags are not declared', async () => {
    const { read } = await generateCompiling(
      wowDocument({ declareTag: false }),
    );
    expect(read('shop/order/commandClient.ts')).toContain(
      'export class OrderCommandClient',
    );
  });

  it('fails on a dangling reference', async () => {
    const message = await generateFailing(
      document(
        {
          '/a': getOperation('a', {
            $ref: '#/components/schemas/DoesNotExist',
          }),
        },
        {},
      ),
    );
    expect(message).toMatch(
      /has 1 \$ref\(s\) that point at nothing: #\/components\/schemas\/DoesNotExist \(at \/paths\/~1a\/get\/responses\/200\/content\/application~1json\/schema\)$/,
    );
  });
});
