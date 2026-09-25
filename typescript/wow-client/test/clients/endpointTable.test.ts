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
 * Every public method of every client, and the requests it sends.
 *
 * Each method is called once on fixed input against a stubbed `fetch`; its
 * requests (method, URL, every header, body) and what it resolves to are
 * compared with `golden/client-endpoints.json`. The methods are found by
 * reflection on each client's prototype, so a method without a row fails
 * here: the table cannot silently miss one.
 *
 * Moving a client, sharing its endpoint options, or changing how it is
 * decorated must leave the file unchanged. A change to it is a change to the
 * transport and is made on purpose with
 * `pnpm exec vitest run test/clients/endpointTable.test.ts -u`.
 *
 * The per-client suites beside this one cover cancellation, errors and the
 * stream error event; this table only pins what goes over the wire.
 */

import { HttpMethod } from '@ahoo-wang/fetcher';
import { describe, expect, it } from 'vitest';
import {
  CommandClient,
  CommandStage,
  EventStreamQueryClient,
  LoadOwnerStateAggregateClient,
  LoadStateAggregateClient,
  SnapshotQueryClient,
  WowMetadataClient,
  aggregation,
  commandHeaders,
  cursorQuery,
  filter,
  listQuery,
  pagedQuery,
  singleQuery,
  waitStrategy,
} from '../../src';
import {
  BASE_URL,
  jsonResponse,
  sseResponse,
  stubFetch,
  testFetcher,
} from './fetchStub';
import { canonicalJson, sortedObject } from '../fixtures/canonicalJson.js';

const aggregationQuery = aggregation.query({
  filter: filter.eq('state.status', 'PAID'),
  groupBy: [aggregation.terms('state.productId', 'product')],
  metrics: [aggregation.count('orders')],
});
const cursor = cursorQuery({ filter: filter.matchAll(), size: 2 });
const count = filter.eq('state.status', 'PAID');
const list = listQuery({ filter: filter.matchAll(), limit: 5 });
const paged = pagedQuery({ filter: filter.matchAll() });
const single = singleQuery({ filter: filter.aggregateId('cart-1') });
const command = {
  path: 'add_cart_item',
  method: HttpMethod.POST,
  urlParams: { path: { ownerId: 'o-1' } },
  headers: {
    ...commandHeaders({ requestId: 'request-1' }),
    ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 5_000 }),
  },
  body: { productId: 'p-1', quantity: 2 },
};

/** A client class and a call for each public method it has. */
interface ClientRows<C> {
  create: () => C;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  type: abstract new (...args: any[]) => C;
  calls: Record<string, (client: C) => Promise<unknown>>;
  /** The event its streams expect first; a query row unless set. */
  streamsLeadWith?: 'row' | 'stage';
}

function rows<C>(table: ClientRows<C>): ClientRows<unknown> {
  return table as ClientRows<unknown>;
}

const TABLE: Record<string, ClientRows<unknown>> = {
  CommandClient: rows({
    type: CommandClient,
    streamsLeadWith: 'stage',
    create: () =>
      new CommandClient({
        basePath: 'owner/{ownerId}/cart',
        fetcher: testFetcher(),
      }),
    calls: {
      send: c => c.send(command),
      sendAndWaitStream: c => c.sendAndWaitStream(command),
    },
  }),
  SnapshotQueryClient: rows({
    type: SnapshotQueryClient,
    create: () =>
      new SnapshotQueryClient({ basePath: 'cart', fetcher: testFetcher() }),
    calls: {
      aggregate: c => c.aggregate(aggregationQuery),
      aggregateStream: c => c.aggregateStream(aggregationQuery),
      cursor: c => c.cursor(cursor),
      cursorState: c => c.cursorState(cursor),
      count: c => c.count(count),
      list: c => c.list(list),
      listStream: c => c.listStream(list),
      listState: c => c.listState(list),
      listStateStream: c => c.listStateStream(list),
      paged: c => c.paged(paged),
      pagedState: c => c.pagedState(paged),
      single: c => c.single(single),
      singleState: c => c.singleState(single),
      getById: c => c.getById('cart-1'),
      getStateById: c => c.getStateById('cart-1'),
      getByIds: c => c.getByIds(['cart-1', 'cart-2']),
      getStateByIds: c => c.getStateByIds(['cart-1', 'cart-2']),
    },
  }),
  EventStreamQueryClient: rows({
    type: EventStreamQueryClient,
    create: () =>
      new EventStreamQueryClient({ basePath: 'cart', fetcher: testFetcher() }),
    calls: {
      aggregate: c => c.aggregate(aggregationQuery),
      aggregateStream: c => c.aggregateStream(aggregationQuery),
      cursor: c => c.cursor(cursor),
      count: c => c.count(count),
      list: c => c.list(list),
      listStream: c => c.listStream(list),
      paged: c => c.paged(paged),
      load: c => c.load('cart-1', 1, 10),
      loadStream: c => c.loadStream('cart-1', 1, 10),
    },
  }),
  LoadStateAggregateClient: rows({
    type: LoadStateAggregateClient,
    create: () =>
      new LoadStateAggregateClient({
        basePath: 'cart',
        fetcher: testFetcher(),
      }),
    calls: {
      load: c => c.load('cart-1'),
      loadVersioned: c => c.loadVersioned('cart-1', 3),
      loadTimeBased: c => c.loadTimeBased('cart-1', 1700000000000),
    },
  }),
  LoadOwnerStateAggregateClient: rows({
    type: LoadOwnerStateAggregateClient,
    create: () =>
      new LoadOwnerStateAggregateClient({
        basePath: 'owner/{ownerId}/cart',
        urlParams: { path: { ownerId: 'o-1' } },
        fetcher: testFetcher(),
      }),
    calls: {
      load: c => c.load(),
      loadVersioned: c => c.loadVersioned(3),
      loadTimeBased: c => c.loadTimeBased(1700000000000),
    },
  }),
  WowMetadataClient: rows({
    type: WowMetadataClient,
    create: () => new WowMetadataClient({ fetcher: testFetcher() }),
    calls: { metadata: c => c.metadata() },
  }),
};

/** The JSON every non-streaming request is answered with. */
const ANSWER = { row: 1 };

const ROW_EVENT = `data:${JSON.stringify(ANSWER)}\n\n`;
const STAGE_EVENT = `event:${CommandStage.SENT}\ndata:${JSON.stringify({ stage: CommandStage.SENT })}\n\n`;
const ERROR_EVENT =
  'event:NotFound\ndata:{"errorCode":"NotFound","errorMsg":"x"}\n\n';

/**
 * The stream a streaming request is answered with: the event the client
 * expects first (a query row, an unnamed event; or a command stage), the
 * other kind, then Wow's error event. Where a method stops tells which
 * result extractor it uses: a query stream stops at the stage, a command
 * stream at the row, and a plain JSON event stream reads all three as data.
 */
function stream(leadsWith: 'row' | 'stage'): string {
  return leadsWith === 'row'
    ? ROW_EVENT + STAGE_EVENT + ERROR_EVENT
    : STAGE_EVENT + ROW_EVENT + ERROR_EVENT;
}

/** What a stream passes on, and the error it ends with, if any. */
async function readStream(stream: ReadableStream<unknown>) {
  const elements: unknown[] = [];
  try {
    for await (const element of stream) elements.push(element);
    return { elements: canonicalJson(elements) };
  } catch (error) {
    return {
      elements: canonicalJson(elements),
      error: {
        name: (error as Error).name,
        errorCode: (error as { errorCode?: string }).errorCode,
      },
    };
  }
}

async function sendRow(
  create: () => unknown,
  call: (client: unknown) => Promise<unknown>,
  streamsLeadWith: 'row' | 'stage',
) {
  const { requests } = stubFetch(request =>
    request.headers.get('Accept') === 'text/event-stream'
      ? sseResponse(stream(streamsLeadWith))
      : jsonResponse(ANSWER),
  );
  const result = await call(create());
  return {
    requests: requests.map(request => ({
      method: request.method,
      url: request.url.slice(BASE_URL.length),
      headers: sortedObject(request.headers.entries()),
      body: canonicalJson(request.body),
    })),
    resolves:
      result instanceof ReadableStream
        ? { stream: await readStream(result) }
        : { value: canonicalJson(result) },
  };
}

describe('the client endpoint table', () => {
  it.each(Object.entries(TABLE))(
    '%s has a row for every public method',
    (_, { type, calls }) => {
      const methods = Object.getOwnPropertyNames(type.prototype)
        .filter(name => name !== 'constructor')
        .sort();
      expect(Object.keys(calls).sort()).toEqual(methods);
    },
  );

  it('sends what golden/client-endpoints.json records', async () => {
    const table: Record<string, unknown> = {};
    for (const [client, rows] of Object.entries(TABLE))
      for (const [method, call] of Object.entries(rows.calls))
        table[`${client}.${method}`] = await sendRow(
          rows.create,
          call,
          rows.streamsLeadWith ?? 'row',
        );
    await expect(JSON.stringify(table, null, 2) + '\n').toMatchFileSnapshot(
      '../golden/client-endpoints.json',
    );
  });
});
