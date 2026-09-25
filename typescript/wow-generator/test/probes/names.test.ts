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
 * Probes of the names the generator derives: identifiers from anything the
 * document names, collisions after normalisation, and method names.
 */

import { describe, expect, it } from 'vitest';
import { generateCompiling, generateFailing } from '../support/probes';
import { document, getOperation, wowDocument } from '../support/specs';

describe('names that are not identifiers', () => {
  it('turns path parameters, schema names and command names into identifiers', async () => {
    const spec = wowDocument({ commands: ['pay-order', 'create_order'] });
    spec.paths['/items/{item-id}'] = getOperation(
      'getItem',
      { $ref: '#/components/schemas/Page«User»' },
      {
        parameters: [
          {
            name: 'item-id',
            in: 'path',
            required: true,
            schema: { type: 'string' },
          },
        ],
      },
    );
    spec.components.schemas['Page«User»'] = {
      type: 'object',
      properties: { first: { $ref: '#/components/schemas/1stThing' } },
      required: ['first'],
    };
    spec.components.schemas['1stThing'] = { type: 'string' };
    const { read } = await generateCompiling(
      spec,
      `import { ItemsApiClient, type PageUser, type _1stThing } from './out/index.js';
       declare const client: ItemsApiClient;
       const page: Promise<PageUser> = client.getItem('id-1');
       const first: _1stThing = 'x';
       void page; void first;`,
    );
    expect(read('shop/ItemsApiClient.ts')).toContain(
      "@path('item-id') itemId: string",
    );
    const commandClient = read('shop/order/commandClient.ts');
    expect(commandClient).toContain("PAY_ORDER = '/order/pay-order'");
    expect(commandClient).toContain('payOrder(');
  });

  it('keeps the acronyms of type names as the document writes them', async () => {
    const spec = document({
      '/tools': getOperation('listTools', {
        $ref: '#/components/schemas/MCPListTools',
      }),
    });
    spec.components.schemas.MCPListTools = {
      type: 'object',
      properties: {
        tools: {
          type: 'array',
          items: { $ref: '#/components/schemas/MCPTool' },
        },
      },
      required: ['tools'],
    };
    spec.components.schemas.MCPTool = {
      type: 'object',
      properties: { name: { type: 'string' } },
      required: ['name'],
    };
    const { read } = await generateCompiling(
      spec,
      `import type { MCPListTools, MCPTool } from './out/index.js';
       const tool: MCPTool = { name: 'search' };
       const tools: MCPListTools = { tools: [tool] };
       void tools;`,
    );
    expect(read('types.ts')).toContain('export interface MCPListTools {');
    expect(read('types.ts')).not.toContain('Mcp');
  });

  it('escapes quotes in event titles', async () => {
    const { read } = await generateCompiling(
      wowDocument({
        events: [{ name: 'order_created', title: "Customer's order" }],
      }),
    );
    expect(read('shop/order/queryClient.ts')).toContain(
      "order_created = 'Customer\\'s order'",
    );
  });
});

describe('names that collide after normalisation', () => {
  it('fails on schemas that generate the same model', async () => {
    const message = await generateFailing(
      document(
        { '/items': getOperation('list') },
        {
          'Foo-Bar': { type: 'object', properties: { a: { type: 'string' } } },
          FooBar: { type: 'object', properties: { b: { type: 'string' } } },
        },
      ),
    );
    expect(message).toContain('Foo-Bar, FooBar → /FooBar');
  });

  it('keeps enum values that normalise alike as distinct members', async () => {
    const { read } = await generateCompiling(
      document(
        { '/items': getOperation('list', { $ref: '#/components/schemas/S' }) },
        {
          S: {
            type: 'string',
            enum: ['in-progress', 'IN_PROGRESS', 'inProgress'],
          },
        },
      ),
      `import { S } from './out/index.js';
       const values: string[] = [S.IN_PROGRESS, S['IN_PROGRESS'], S.inProgress];
       void values;`,
    );
    expect(read('types.ts')).toContain("IN_PROGRESS = 'in-progress'");
    expect(read('types.ts')).toContain("inProgress = 'inProgress'");
  });

  it('fails on two operations of one client that name the same method', async () => {
    const message = await generateFailing(
      document({
        '/a': getOperation('users.list'),
        '/b': getOperation('orders.list'),
      }),
    );
    expect(message).toBe(
      'Operations orders.list and users.list of tag Items both generate the method ItemsApiClient.list(). Name one of them with apiClients["Items"].methodNames in the generator configuration, or with the x-fetcher-method extension.',
    );
  });

  it('resolves a method name clash through the configuration', async () => {
    const { read } = await generateCompiling(
      document({
        '/a': getOperation('users.list'),
        '/b': getOperation('orders.list'),
      }),
      undefined,
      {
        apiClients: { Items: { methodNames: { 'orders.list': 'listOrders' } } },
      },
    );
    expect(read('ItemsApiClient.ts')).toContain('listOrders(');
  });

  it('gives tags that name the same client distinct classes, with a warning', async () => {
    const spec = document({
      '/a': { get: { ...getOperation('a').get, tags: ['user-controller'] } },
      '/b': { get: { ...getOperation('b').get, tags: ['UserController'] } },
    });
    const { logger, read } = await generateCompiling(spec);
    expect(read('UserControllerApiClient.ts')).toContain('b(');
    expect(read('UserController2ApiClient.ts')).toContain('a(');
    expect(logger.warnings).toContain(
      'Tags UserController and user-controller both name the API client UserControllerApiClient; user-controller generates UserController2ApiClient.',
    );
  });

  it('leaves names two packages both export out of their common index', async () => {
    const { logger, read } = await generateCompiling(
      document(
        {
          '/a': getOperation('a', { $ref: '#/components/schemas/com.a.User' }),
          '/b': getOperation('b', { $ref: '#/components/schemas/com.b.User' }),
        },
        {
          'com.a.User': {
            type: 'object',
            properties: { a: { type: 'string' } },
          },
          'com.b.User': {
            type: 'object',
            properties: { b: { type: 'string' } },
          },
        },
      ),
    );
    expect(read('com/index.ts')).not.toContain('export *');
    expect(logger.warnings.join('\n')).toContain('User is exported by both');
  });
});

describe('method names', () => {
  it('derives names from the operationId alone', async () => {
    const { read } = await generateCompiling(
      document({
        '/a': getOperation('delete_user_by_id'),
        '/b': getOperation('get_user_by_id'),
        '/c': getOperation('getUser_1'),
        '/d': getOperation('users.list'),
      }),
    );
    const client = read('ItemsApiClient.ts');
    for (const name of [
      'deleteUserById(',
      'getUserById(',
      'getUser1(',
      'list(',
    ]) {
      expect(client).toContain(name);
    }
  });

  it('never renames an existing method when an operation is added', async () => {
    const methods = (source: string) =>
      [...source.matchAll(/^ {2}(\w+)\(/gm)].map(match => match[1]);
    const before = await generateCompiling(
      document({ '/a': getOperation('users.getProfile') }),
    );
    const after = await generateCompiling(
      document({
        '/a': getOperation('users.getProfile'),
        '/b': getOperation('getProfileV2'),
      }),
    );
    expect(methods(after.read('ItemsApiClient.ts'))).toEqual(
      expect.arrayContaining(methods(before.read('ItemsApiClient.ts'))),
    );
  });
});
