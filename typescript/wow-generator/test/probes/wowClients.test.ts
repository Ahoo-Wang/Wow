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
 * Probes of the Wow command and query clients, and of the Wow metadata they
 * are read from.
 */

import { describe, expect, it } from 'vitest';
import { runGenerated } from '../support/generation';
import {
  generateCompiling,
  generateFailing,
  sharedGeneration,
} from '../support/probes';
import { document, getOperation, wowDocument } from '../support/specs';

/** The Wow document most probes of this file read, generated once. */
const wow = sharedGeneration(() => generateCompiling(wowDocument()));

describe('command clients keep their base path', () => {
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
    const { dir } = await wow();
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

describe('streaming command clients', () => {
  it("take wow-client's COMMAND_STREAM_ENDPOINT", async () => {
    const { read } = await wow();
    const client = read('shop/order/commandClient.ts');
    expect(client).toContain(
      "@api('', COMMAND_STREAM_ENDPOINT)\nexport class OrderStreamCommandClient",
    );
    expect(client).not.toContain('JsonEventStreamResultExtractor');
  });

  it("error the stream with a WowError at the server's error event", async () => {
    const { dir } = await wow();
    // Wow answers a failing stream HTTP 200, then an event named by the error
    // code whose data is the ErrorInfo (WebFluxResponseStrategy.errorResume).
    const output = runGenerated(
      dir,
      `const { OrderStreamCommandClient } = await import('./out/index.js');
const { WowError } = await import('@ahoo-wang/wow-client');
globalThis.fetch = async () =>
  new Response(
    'id:1\\nevent:SENT\\ndata:{"id":"1","stage":"SENT","errorCode":"Ok","errorMsg":""}\\n\\n' +
      'id:2\\nevent:CommandValidation\\ndata:{"errorCode":"CommandValidation","errorMsg":"id must not be blank"}\\n\\n',
    { headers: { 'Content-Type': 'text/event-stream' } },
  );
const client = new OrderStreamCommandClient({ fetcher: new Fetcher({ baseURL: 'http://api.test' }) });
const stream = await client.createOrder({ body: { id: '' } });
const stages = [];
let failure;
try {
  for await (const event of stream) stages.push(event.data.stage);
} catch (error) {
  failure = error;
}
console.log(JSON.stringify({ stages, wowError: failure instanceof WowError, errorCode: failure?.errorCode, errorMsg: failure?.errorMsg }));`,
    );
    expect(JSON.parse(output)).toEqual({
      stages: ['SENT'],
      wowError: true,
      errorCode: 'CommandValidation',
      errorMsg: 'id must not be blank',
    });
  });
});

describe('query client types', () => {
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

describe('malformed Wow metadata', () => {
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

describe('resource attribution parameters', () => {
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
