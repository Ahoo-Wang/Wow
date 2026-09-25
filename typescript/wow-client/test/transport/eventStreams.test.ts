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

import type { FetchExchange } from '@ahoo-wang/fetcher';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import { describe, expect, it } from 'vitest';
import {
  COMMAND_STREAM_ENDPOINT,
  CommandResultEventStreamResultExtractor,
  ErrorCodes,
  QUERY_STREAM_ENDPOINT,
  QueryEventStreamResultExtractor,
  WowError,
} from '../../src';

/**
 * An exchange whose response is the event stream the server writes, in the
 * shape of `WebFluxResponseStrategy`: rows without an `event:` name, command
 * results named by their stage, and on failure one last event named by the
 * error code whose data is the `ErrorInfo`.
 */
function exchange(...events: string[]): FetchExchange {
  const response = new Response(events.map(event => `${event}\n\n`).join(''), {
    headers: { 'Content-Type': 'text/event-stream' },
  });
  return { requiredResponse: response } as unknown as FetchExchange;
}

async function read<T>(
  stream: ReadableStream<JsonServerSentEvent<T>>,
): Promise<T[]> {
  const rows: T[] = [];
  for await (const event of stream) rows.push(event.data);
  return rows;
}

describe('QueryEventStreamResultExtractor', () => {
  it('passes the rows of a query stream', async () => {
    const stream = await QueryEventStreamResultExtractor(
      exchange('data:{"id":"a"}', 'data:{"id":"b"}'),
    );
    await expect(read(stream)).resolves.toEqual([{ id: 'a' }, { id: 'b' }]);
  });

  it('errors with a WowError at the error event the server sends midway', async () => {
    const stream = await QueryEventStreamResultExtractor(
      exchange(
        'data:{"id":"a"}',
        'id:1\nevent:IllegalArgument\ndata:{"errorCode":"IllegalArgument","errorMsg":"limit too large"}',
      ),
    );
    const rows: unknown[] = [];
    const failure = await (async () => {
      try {
        for await (const event of stream) rows.push(event.data);
      } catch (error) {
        return error;
      }
    })();
    expect(rows).toEqual([{ id: 'a' }]);
    expect(failure).toBeInstanceOf(WowError);
    expect(failure).toMatchObject({
      errorCode: ErrorCodes.ILLEGAL_ARGUMENT,
      errorMsg: 'limit too large',
    });
  });

  it('names the error by the event when its data is not an ErrorInfo', async () => {
    const stream = await QueryEventStreamResultExtractor(
      exchange('event:InternalServerError\ndata:"boom"'),
    );
    await expect(read(stream)).rejects.toMatchObject({
      name: 'WowError',
      errorCode: ErrorCodes.INTERNAL_SERVER_ERROR,
    });
  });
});

describe('CommandResultEventStreamResultExtractor', () => {
  it('passes one command result per stage, a failed result included', async () => {
    const stream = await CommandResultEventStreamResultExtractor(
      exchange(
        'event:SENT\ndata:{"stage":"SENT","errorCode":"Ok"}',
        'event:PROCESSED\ndata:{"stage":"PROCESSED","errorCode":"IllegalState"}',
      ),
    );
    await expect(read(stream)).resolves.toEqual([
      { stage: 'SENT', errorCode: 'Ok' },
      { stage: 'PROCESSED', errorCode: 'IllegalState' },
    ]);
  });

  it('errors with a WowError at an event that is not a stage', async () => {
    const stream = await CommandResultEventStreamResultExtractor(
      exchange(
        'event:SENT\ndata:{"stage":"SENT","errorCode":"Ok"}',
        'event:RequestTimeout\ndata:{"errorCode":"RequestTimeout","errorMsg":"waited 30s"}',
      ),
    );
    await expect(read(stream)).rejects.toMatchObject({
      errorCode: ErrorCodes.REQUEST_TIMEOUT,
      errorMsg: 'waited 30s',
    });
  });
});

describe.each([
  {
    name: 'COMMAND_STREAM_ENDPOINT',
    preset: COMMAND_STREAM_ENDPOINT,
    extractor: CommandResultEventStreamResultExtractor,
  },
  {
    name: 'QUERY_STREAM_ENDPOINT',
    preset: QUERY_STREAM_ENDPOINT,
    extractor: QueryEventStreamResultExtractor,
  },
])('$name', ({ preset, extractor }) => {
  it('asks for an event stream and reads it with its extractor', () => {
    expect(preset).toStrictEqual({
      headers: { Accept: 'text/event-stream' },
      resultExtractor: extractor,
    });
  });

  it('is frozen, so no client can change it for the others', () => {
    expect(Object.isFrozen(preset)).toBe(true);
    expect(Object.isFrozen(preset.headers)).toBe(true);
  });
});
