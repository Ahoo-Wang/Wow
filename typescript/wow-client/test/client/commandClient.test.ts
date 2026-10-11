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

import { HttpMethod } from '@ahoo-wang/fetcher';
import { describe, expect, it } from 'vitest';
import {
  CommandClient,
  CommandHeaders,
  CommandStage,
  commandHeaders,
  waitStrategy,
  type CommandRequest,
} from '../../src';
import {
  BASE_URL,
  expectCancellable,
  expectStreamErrorEvent,
  expectWowErrorOnFailure,
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from './fetchStub';

interface AddCartItem {
  productId: string;
  quantity: number;
}

const client = () =>
  new CommandClient({
    basePath: 'owner/{ownerId}/cart',
    fetcher: testFetcher(),
  });

const addCartItem = (
  headers: CommandRequest['headers'] = {},
): CommandRequest<AddCartItem> => ({
  path: 'add_cart_item',
  method: HttpMethod.POST,
  urlParams: { path: { ownerId: 'o-1' } },
  headers,
  body: { productId: 'p-1', quantity: 2 },
});

const commandResult = (stage: CommandStage) => ({
  id: `result-${stage}`,
  waitCommandId: 'command-1',
  stage,
  contextName: 'example',
  aggregateName: 'cart',
  tenantId: 'tenant',
  aggregateId: 'o-1',
  aggregateVersion: 1,
  requestId: 'request-1',
  commandId: 'command-1',
  function: {
    functionKind: 'COMMAND',
    contextName: 'example',
    processorName: 'Cart',
    name: 'onCommand',
  },
  errorCode: 'Ok',
  errorMsg: '',
  signalTime: 1,
  result: {},
});

const stageEvent = (stage: CommandStage) =>
  `event:${stage}\ndata:${JSON.stringify(commandResult(stage))}\n\n`;

const COMMAND_URL = `${BASE_URL}/owner/o-1/cart/add_cart_item`;

describe('CommandClient', () => {
  describe('send', () => {
    it('posts the command with its headers and parses the result', async () => {
      const { requests } = stubFetch(() =>
        jsonResponse(commandResult(CommandStage.PROCESSED)),
      );
      const headers = {
        ...commandHeaders({ requestId: 'request-1', aggregateVersion: 1 }),
        ...waitStrategy({ stage: CommandStage.SNAPSHOT, timeoutMs: 5_000 }),
      };

      const result = await client().send(addCartItem(headers));

      expect(result).toStrictEqual(commandResult(CommandStage.PROCESSED));
      expect(requests).toHaveLength(1);
      const [sent] = requests;
      expect(sent.method).toBe('POST');
      expect(sent.url).toBe(COMMAND_URL);
      expect(sent.body).toStrictEqual({ productId: 'p-1', quantity: 2 });
      expect(sent.headers.get('Accept')).not.toBe('text/event-stream');
      expect(sent.headers.get(CommandHeaders.REQUEST_ID)).toBe('request-1');
      expect(sent.headers.get(CommandHeaders.AGGREGATE_VERSION)).toBe('1');
      expect(sent.headers.get(CommandHeaders.WAIT_STAGE)).toBe('SNAPSHOT');
      expect(sent.headers.get(CommandHeaders.WAIT_TIME_OUT)).toBe('5000');
    });

    it('is cancelled by the abort the request carries', () =>
      expectCancellable(abort =>
        client().send({
          ...addCartItem(),
          ...(abort instanceof AbortController
            ? { abortController: abort }
            : { signal: abort }),
        }),
      ));

    it('rejects on an error response with the ErrorInfo', () =>
      expectWowErrorOnFailure(() => client().send(addCartItem())));
  });

  describe('sendAndWaitStream', () => {
    it('asks for an event stream and yields one result per stage', async () => {
      const { requests } = stubFetch(() =>
        sseResponse(
          stageEvent(CommandStage.SENT) + stageEvent(CommandStage.PROCESSED),
        ),
      );
      const headers = waitStrategy({ stage: CommandStage.PROCESSED });

      const stream = await client().sendAndWaitStream(addCartItem(headers));
      const results = await readAll(stream);

      // The stream yields the command results themselves; the stage the
      // envelope named is the result's own `stage`.
      expect(results).toStrictEqual([
        commandResult(CommandStage.SENT),
        commandResult(CommandStage.PROCESSED),
      ]);
      const [sent] = requests;
      expect(sent.method).toBe('POST');
      expect(sent.url).toBe(COMMAND_URL);
      expect(sent.body).toStrictEqual({ productId: 'p-1', quantity: 2 });
      expect(sent.headers.get('Accept')).toBe('text/event-stream');
      expect(sent.headers.get(CommandHeaders.WAIT_STAGE)).toBe('PROCESSED');
    });

    it('errors the stream with a WowError at an error event', () =>
      expectStreamErrorEvent(
        () => client().sendAndWaitStream(addCartItem()),
        stageEvent(CommandStage.SENT),
      ));

    it('is cancelled by the abort the request carries', () =>
      expectCancellable(abort =>
        client().sendAndWaitStream({
          ...addCartItem(),
          ...(abort instanceof AbortController
            ? { abortController: abort }
            : { signal: abort }),
        }),
      ));

    it('rejects on an error response with the ErrorInfo', () =>
      expectWowErrorOnFailure(() => client().sendAndWaitStream(addCartItem())));
  });

  it('passes attributes to the interceptors', async () => {
    const fetcher = testFetcher();
    const seen: unknown[] = [];
    fetcher.interceptors.request.use({
      name: 'attributes-probe',
      order: 0,
      intercept(exchange) {
        seen.push(exchange.attributes.get('traceId'));
      },
    });
    stubFetch(() => jsonResponse(commandResult(CommandStage.PROCESSED)));

    await new CommandClient({
      basePath: 'owner/{ownerId}/cart',
      fetcher,
    }).send(addCartItem(), { traceId: 't-1' });

    expect(seen).toStrictEqual(['t-1']);
  });
});
