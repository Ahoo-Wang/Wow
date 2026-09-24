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
 * Regression probes from the pre-release review (R2): each feeds the
 * smallest document that showed a problem through the CLI, in a directory
 * where nothing resolves `@ahoo-wang/*`, then compiles the output with
 * `tsc --strict`. A problem either becomes code that compiles and means
 * what the document says, or a clear error with a non-zero exit code.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../src/errors';
import {
  BUNDLER_OPTIONS,
  generateCold,
  NODE_NEXT_OPTIONS,
  removeDirectories,
  runGenerated,
  typeCheck,
} from './support/generation';
import { document, getOperation, wowDocument } from './support/specs';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

/** Generates a document and expects it to compile, optionally with a consumer file. */
async function generateCompiling(
  spec: unknown,
  consumer?: string,
  config?: unknown,
) {
  const result = await generateCold(spec, directories, config);
  expect(result.errors).toEqual([]);
  expect(result.exitCode).toBe(EXIT_CODES.success);
  if (consumer !== undefined) {
    writeFileSync(join(result.dir, 'consumer.ts'), consumer);
  }
  expect(typeCheck(result.dir, BUNDLER_OPTIONS)).toEqual([]);
  return {
    ...result,
    read: (path: string) => readFileSync(join(result.output, path), 'utf8'),
  };
}

async function generateFailing(spec: unknown, config?: unknown) {
  const result = await generateCold(spec, directories, config);
  expect(result.exitCode).toBe(EXIT_CODES.specification);
  expect(result.errors).toHaveLength(1);
  return result.errors[0];
}

describe('R2-08 imports do not depend on the output directory', () => {
  it('writes every import explicitly, so a cold directory compiles', async () => {
    const { read } = await generateCompiling(wowDocument());
    const commandClient = read('shop/order/commandClient.ts');
    expect(commandClient).toContain(
      "import { SHOP_BOUNDED_CONTEXT_ALIAS } from '../boundedContext.js';",
    );
  });
});

describe('R2-09 relative imports carry .js', () => {
  it('compiles under NodeNext', async () => {
    const { dir, read } = await generateCompiling(wowDocument());
    expect(read('index.ts')).toContain("export * from './shop/index.js';");
    expect(typeCheck(dir, NODE_NEXT_OPTIONS)).toEqual([]);
  });

  it('is what NodeNext checks: an extensionless import fails there', async () => {
    const { dir, output } = await generateCompiling(wowDocument());
    const index = join(output, 'index.ts');
    writeFileSync(
      index,
      readFileSync(index, 'utf8').replace('./shop/index.js', './shop'),
    );
    expect(typeCheck(dir, NODE_NEXT_OPTIONS).join('\n')).toContain('TS2834');
  });
});

describe('R2-22 comment terminators in the document', () => {
  it('escapes */ in descriptions, titles and summaries', async () => {
    const { read } = await generateCompiling(
      document(
        {
          '/jobs': getOperation(
            'listJobs',
            { $ref: '#/components/schemas/Job' },
            { summary: 'Runs */5 * * * *', description: 'glob **/*.ts */' },
          ),
        },
        {
          Job: {
            type: 'object',
            title: 'A job */',
            description: 'cron */5 * * * *',
            properties: {
              cron: { type: 'string', description: 'e.g. */10 * * * *' },
            },
            required: ['cron'],
          },
        },
      ),
    );
    expect(read('types.ts')).toContain('cron *\\/5 * * * *');
    expect(read('ItemsApiClient.ts')).toContain('Runs *\\/5 * * * *');
  });
});

describe('R2-23 names that are not identifiers', () => {
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

describe('R2-24 names that collide after normalisation', () => {
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

describe('R2-25 nullable references', () => {
  it('admits null for {nullable, allOf: [$ref]}', async () => {
    await generateCompiling(
      document(
        { '/m': getOperation('m', { $ref: '#/components/schemas/Model' }) },
        {
          Model: {
            type: 'object',
            properties: {
              nulRef: {
                nullable: true,
                allOf: [{ $ref: '#/components/schemas/Other' }],
              },
            },
            required: ['nulRef'],
          },
          Other: { type: 'object', properties: { x: { type: 'string' } } },
        },
      ),
      `import type { Model } from './out/index.js';
       const nothing: Model['nulRef'] = null;
       void nothing;`,
    );
  });
});

describe('R2-26 success responses', () => {
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

describe('R2-27 method names', () => {
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

describe('R2-28 what the generator leaves out', () => {
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

describe('R2-29 discriminators, recursive maps and global names', () => {
  it('narrows a discriminated union by its property', async () => {
    await generateCompiling(
      document(
        { '/p': getOperation('pet', { $ref: '#/components/schemas/Pet' }) },
        {
          Pet: {
            oneOf: [
              { $ref: '#/components/schemas/Cat' },
              { $ref: '#/components/schemas/Dog' },
            ],
            discriminator: {
              propertyName: 'petType',
              mapping: { cat: '#/components/schemas/Cat' },
            },
          },
          Cat: {
            type: 'object',
            properties: {
              petType: { type: 'string' },
              meow: { type: 'boolean' },
            },
            required: ['petType', 'meow'],
          },
          Dog: {
            type: 'object',
            properties: {
              petType: { type: 'string' },
              bark: { type: 'boolean' },
            },
            required: ['petType', 'bark'],
          },
        },
      ),
      `import type { Pet } from './out/index.js';
       declare const pet: Pet;
       if (pet.petType === 'cat') { const meow: boolean = pet.meow; void meow; }
       if (pet.petType === 'Dog') { const bark: boolean = pet.bark; void bark; }`,
    );
  });

  it('generates a map of its own type', async () => {
    const { read } = await generateCompiling(
      document(
        { '/d': getOperation('dict', { $ref: '#/components/schemas/Dict' }) },
        {
          Dict: {
            type: 'object',
            additionalProperties: { $ref: '#/components/schemas/Dict' },
          },
        },
      ),
    );
    expect(read('types.ts')).toContain('[key: string]: Dict;');
  });

  it('keeps models named Record and Response from shadowing the globals', async () => {
    const { read } = await generateCompiling(
      document(
        {
          '/r': getOperation('record', { $ref: '#/components/schemas/Record' }),
          '/s': getOperation('response', {
            $ref: '#/components/schemas/Response',
          }),
          '/t': {
            get: { tags: ['Items'], operationId: 'raw', responses: {} },
          },
        },
        {
          Record: { type: 'object', properties: { a: { type: 'string' } } },
          Response: { type: 'object', properties: { b: { type: 'string' } } },
        },
      ),
      `import { ItemsApiClient, type Response as ResponseModel } from './out/index.js';
       declare const client: ItemsApiClient;
       const raw: Promise<Response> = client.raw();
       const model: Promise<ResponseModel> = client.response();
       void raw; void model;`,
    );
    expect(read('ItemsApiClient.ts')).toContain('Response as _Response');
  });
});

describe('R2-30 malformed Wow metadata', () => {
  it.each([
    [
      'an event stream without anyOf',
      { eventStream: { type: 'object', properties: {} } },
      'the event stream schema #/components/schemas/shop.order.OrderAggregatedDomainEventStream has no properties.body.items.anyOf listing the domain events',
    ],
    [
      'a snapshot count without a request body',
      { countBody: null },
      'it has no request body',
    ],
    [
      'an inline event body',
      { events: [{ name: 'e' }], eventStream: undefined },
      undefined,
    ],
  ] as const)('reports %s', async (_, options, problem) => {
    const spec = wowDocument(options as never);
    if (problem === undefined) {
      const stream =
        spec.components.schemas['shop.order.OrderAggregatedDomainEventStream'];
      stream.properties.body.items.anyOf[0].properties.body = {
        type: 'object',
      };
    }
    const message = await generateFailing(spec);
    expect(message).toMatch(/^Cannot read the Wow metadata of shop\.order\./);
    expect(message).toContain(
      problem ??
        'domain event 0 of #/components/schemas/shop.order.OrderAggregatedDomainEventStream needs a properties.name.const and a properties.body that is a $ref to the event schema',
    );
    expect(message).toMatch(
      /wow-generator reads documents from Wow 8\.10 or later\.$/,
    );
  });
});

describe('R2-17 query client types', () => {
  it('types the query fields without widening them to string', async () => {
    const { read } = await generateCompiling(
      wowDocument({
        events: [{ name: 'order_closed', body: { type: 'object' } }],
      }),
      `import { filter } from '@ahoo-wang/wow-client';
       import { OrderAggregatedFields, orderQueryClientFactory } from './out/index.js';
       const client = orderQueryClientFactory.createSnapshotQueryClient();
       void client.count(filter.eq('id', 'x'));
       void client.count(filter.eq(OrderAggregatedFields.ID, 'x'));
       // @ts-expect-error a field the aggregate does not have
       void client.count(filter.eq('nope', 'x'));`,
    );
    const queryClient = read('shop/order/queryClient.ts');
    expect(queryClient).toContain(
      'new QueryClientFactory<OrderState, `${OrderAggregatedFields}`, OrderDomainEventType>',
    );
    expect(read('shop/order/types.ts')).toContain(
      'export type OrderClosed = globalThis.Record<string, never>;',
    );
  });
});

describe('R2-18 resource attribution parameters', () => {
  const spec = (wow: boolean) => {
    const plain = document({
      '/tenant/{tenantId}/items': getOperation('list', undefined, {
        parameters: [
          {
            name: 'tenantId',
            in: 'path',
            required: true,
            schema: { type: 'string' },
          },
        ],
      }),
    });
    if (wow) plain.info['x-wow-context-alias'] = 'shop';
    return plain;
  };

  it('keeps tenantId in a document that is not from Wow', async () => {
    const { read } = await generateCompiling(spec(false));
    expect(read('ItemsApiClient.ts')).toContain("@path('tenantId') tenantId");
  });

  it("leaves tenantId to Wow's interceptor in a Wow document", async () => {
    const { read } = await generateCompiling(spec(true));
    expect(read('shop/ItemsApiClient.ts')).not.toContain("@path('tenantId')");
  });
});

describe('Aggregate route segments', () => {
  it('queries the route segment the aggregate uses, not its name', async () => {
    const { read } = await generateCompiling(
      wowDocument({ aggregate: 'order', resource: 'sales-order' }),
    );
    expect(read('shop/order/queryClient.ts')).toContain(
      "aggregateName: 'sales-order',",
    );
  });
});

describe('R4-09 command clients keep their base path', () => {
  it('merges apiMetadata over the bounded context default', async () => {
    const { read } = await generateCompiling(
      wowDocument(),
      `import type { Fetcher } from '@ahoo-wang/fetcher';
       import { OrderCommandClient, OrderStreamCommandClient } from './out/index.js';
       declare const fetcher: Fetcher;
       const client = new OrderCommandClient({ fetcher });
       const stream = new OrderStreamCommandClient({ fetcher });
       void client; void stream;`,
    );
    expect(read('shop/order/commandClient.ts')).toContain(
      'this.apiMetadata = { ...DEFAULT_COMMAND_CLIENT_OPTIONS, ...apiMetadata };',
    );
  });

  it('sends a command under the bounded context when given only a fetcher', async () => {
    const { dir } = await generateCompiling(wowDocument());
    const output = runGenerated(
      dir,
      `const { OrderCommandClient, OrderStreamCommandClient } = await import('./out/index.js');
const fetcher = new Fetcher({ baseURL: 'http://api.test' });
await new OrderCommandClient({ fetcher }).createOrder({ body: { id: '1' } });
await new OrderCommandClient({ fetcher, basePath: '' }).createOrder({ body: { id: '1' } });
await new OrderStreamCommandClient({ fetcher }).createOrder({ body: { id: '1' } }).catch(() => {});
console.log(JSON.stringify(requests.map(request => [request.url, request.headers.accept ?? null])));`,
    );
    expect(JSON.parse(output)).toEqual([
      ['http://api.test/shop/order/create_order', null],
      ['http://api.test/order/create_order', null],
      ['http://api.test/shop/order/create_order', 'text/event-stream'],
    ]);
  });
});

describe('R2-16 generated files', () => {
  it('start with the generated-code marker and use single quotes', async () => {
    const { read } = await generateCompiling(wowDocument());
    const client = read('shop/order/commandClient.ts');
    expect(
      client.startsWith('// Code generated by wow-generator. DO NOT EDIT.\n'),
    ).toBe(true);
    expect(client).not.toContain('"');
    expect(read('shop/order/types.ts')).not.toContain('```json');
  });
});
