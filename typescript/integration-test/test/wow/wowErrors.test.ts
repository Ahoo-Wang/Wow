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

import { describe, expect, it } from 'vitest';
import { ExchangeError, HttpMethod } from '@ahoo-wang/fetcher';
import { idGenerator } from '@ahoo-wang/fetcher-cosec';
import {
  aggregation,
  commandHeaders,
  CommandStage,
  cursorQuery,
  ErrorCodes,
  filter,
  listQuery,
  pagedQuery,
  ResourceAttributionPathSpec,
  toWowError,
  waitStrategy,
  WowError,
  WowMetadataClient,
} from '@ahoo-wang/wow-client';
import {
  type AddCartItemCommand,
  cartCommandClient,
  CartCommandEndpoints,
  exampleFetcher,
} from '../../src/wow';
import {
  cartQueryClientFactory,
  CartStreamCommandClient,
} from '../../src/generated';

const snapshotClient = cartQueryClientFactory.createSnapshotQueryClient({
  contextAlias: '',
  resourceAttribution: ResourceAttributionPathSpec.NONE,
  fetcher: exampleFetcher,
});

// No cart exists for this file's `currentUserId`.
const ownerStateClient =
  cartQueryClientFactory.createLoadOwnerStateAggregateClient({
    contextAlias: '',
    fetcher: exampleFetcher,
  });

const unknownId = `${idGenerator.generateId()}Missing`;

/** The rejection of `promise`, as a `WowError`. */
async function wowErrorOf(promise: Promise<unknown>): Promise<WowError> {
  const reason = await promise.then(
    () => {
      throw new Error('Expected the request to fail.');
    },
    (error: unknown) => error,
  );
  expect(reason).toBeInstanceOf(ExchangeError);
  const wowError = await toWowError(reason);
  expect(wowError).toBeInstanceOf(WowError);
  expect(wowError!.cause).toBe(reason);
  return wowError!;
}

/** Reads `stream` to its end, returning the rows before it failed and why. */
async function drain<T>(
  stream: ReadableStream<T>,
): Promise<{ rows: T[]; error: unknown }> {
  const rows: T[] = [];
  try {
    for await (const row of stream) {
      rows.push(row);
    }
  } catch (error) {
    return { rows, error };
  }
  return { rows, error: undefined };
}

/**
 * What a failing stream may end with: the error event, read as a WowError of
 * `errorCode`, or — when the server fails the response before the event is
 * flushed, which the same-source CI server does and a local one does not —
 * nothing at all. What it must never do is pass the ErrorInfo on as a row.
 */
function expectStreamFailure(
  outcome: { rows: unknown[]; error: unknown },
  errorCode: string,
): WowError | undefined {
  expect(outcome.rows).toEqual([]);
  if (outcome.error === undefined) return undefined;
  expect(outcome.error).toBeInstanceOf(WowError);
  expect((outcome.error as WowError).errorCode).toBe(errorCode);
  return outcome.error as WowError;
}

describe('404 Not Found', () => {
  it('should reject getStateById of an unknown id', async () => {
    const wowError = await wowErrorOf(snapshotClient.getStateById(unknownId));
    expect(wowError.errorCode).toBe(ErrorCodes.NOT_FOUND);
    expect(wowError.status).toBe(404);
    expect(wowError.errorMsg).not.toBe('');
  });

  it('should reject getById of an unknown id', async () => {
    const wowError = await wowErrorOf(snapshotClient.getById(unknownId));
    expect(wowError.errorCode).toBe(ErrorCodes.NOT_FOUND);
    expect(wowError.status).toBe(404);
  });

  it('should reject load of an owner without a cart', async () => {
    const wowError = await wowErrorOf(ownerStateClient.load());
    expect(wowError.errorCode).toBe(ErrorCodes.NOT_FOUND);
    expect(wowError.status).toBe(404);
  });
});

describe('400 Bad Request', () => {
  it('should reject a page size above the HTTP limit', async () => {
    const wowError = await wowErrorOf(
      snapshotClient.paged(
        pagedQuery({ pagination: { index: 1, size: 1000 } }),
      ),
    );
    expect(wowError.errorCode).toBe(ErrorCodes.ILLEGAL_ARGUMENT);
    expect(wowError.status).toBe(400);
    expect(wowError.errorMsg).toContain('1000');
  });

  it('should reject a list limit above the HTTP limit', async () => {
    const wowError = await wowErrorOf(
      snapshotClient.list(listQuery({ limit: 5000 })),
    );
    expect(wowError.errorCode).toBe(ErrorCodes.ILLEGAL_ARGUMENT);
    expect(wowError.status).toBe(400);
  });

  it('should reject a malformed cursor', async () => {
    const wowError = await wowErrorOf(
      snapshotClient.cursor(
        cursorQuery({ filter: filter.matchAll(), cursor: 'not-a-cursor' }),
      ),
    );
    expect(wowError.errorCode).toBe(ErrorCodes.ILLEGAL_ARGUMENT);
    expect(wowError.status).toBe(400);
  });

  it('should reject a filter on a field the schema does not know', async () => {
    const wowError = await wowErrorOf(
      // The typed client refuses the field at compile time; the server must
      // refuse it too, for callers without generated field types.
      // @ts-expect-error 'state.noSuchField' is not a cart field.
      snapshotClient.count(filter.eq('state.noSuchField', 1)),
    );
    expect(wowError.errorCode).toBe(ErrorCodes.QUERY_SCHEMA_VALIDATION);
    expect(wowError.status).toBe(400);
    expect(wowError.errorMsg).toContain('state.noSuchField');
  });

  it('should reject a command that fails validation', async () => {
    const command: AddCartItemCommand = {
      path: CartCommandEndpoints.addCartItem,
      method: HttpMethod.POST,
      headers: {
        ...commandHeaders({ requestId: idGenerator.generateId() }),
        ...waitStrategy({ stage: CommandStage.SNAPSHOT }),
      },
      body: { productId: '', quantity: 0 },
    };
    const wowError = await wowErrorOf(cartCommandClient.send(command));
    expect(wowError.errorCode).toBe(ErrorCodes.COMMAND_VALIDATION);
    expect(wowError.status).toBe(400);
    expect(wowError.bindingErrors.map(error => error.name).sort()).toEqual([
      'productId',
      'quantity',
    ]);
  });
});

describe('error event in a query stream', () => {
  // The server answers a stream request HTTP 200 and then sends the error as
  // an event named by its error code: the call resolves, the stream errors.
  it('should error listStream with a WowError', async () => {
    const stream = await snapshotClient.listStream(listQuery({ limit: 5000 }));
    const error = expectStreamFailure(
      await drain(stream),
      ErrorCodes.ILLEGAL_ARGUMENT,
    );
    if (error) {
      expect(error.errorMsg).toContain('5000');
      expect(await toWowError(error)).toBe(error);
    }
  });

  it('should error listStateStream with a WowError', async () => {
    const stream = await snapshotClient.listStateStream(
      // Unknown on purpose, as in the count test above.
      // @ts-expect-error 'state.noSuchField' is not a cart field.
      listQuery({ filter: filter.eq('state.noSuchField', 1) }),
    );
    expectStreamFailure(
      await drain(stream),
      ErrorCodes.QUERY_SCHEMA_VALIDATION,
    );
  });

  it('should error aggregateStream with a WowError', async () => {
    const stream = await snapshotClient.aggregateStream(
      aggregation.query({
        filter: filter.matchAll(),
        groupBy: [aggregation.terms('state.noSuchField', 'missing')],
        metrics: [aggregation.count('n')],
      }),
    );
    expectStreamFailure(
      await drain(stream),
      ErrorCodes.QUERY_SCHEMA_VALIDATION,
    );
  });
});

describe('error event in a command stream', () => {
  // A command that fails validation over `sendAndWaitStream` is answered HTTP
  // 200, then an event named by its error code. The generated stream client
  // takes wow-client's COMMAND_STREAM_ENDPOINT, so the stream errors with a
  // WowError instead of passing the ErrorInfo on as a command result.
  it('should error a generated stream command client with a WowError', async () => {
    const client = new CartStreamCommandClient({
      fetcher: exampleFetcher,
      basePath: '',
    });
    const stream = await client.addCartItem({
      headers: waitStrategy({ stage: CommandStage.SNAPSHOT }),
      body: { productId: '', quantity: 0 },
    });
    const error = expectStreamFailure(
      await drain(stream),
      ErrorCodes.COMMAND_VALIDATION,
    );
    if (error) {
      expect(error.bindingErrors.map(error => error.name).sort()).toEqual([
        'productId',
        'quantity',
      ]);
    }
  });
});

describe('WowMetadataClient', () => {
  it('should return the example bounded context', async () => {
    const client = new WowMetadataClient({ fetcher: exampleFetcher });
    const metadata = await client.metadata();
    const example = metadata.contexts['example-service'];
    expect(example).toBeDefined();
    expect(example.alias).toBe('example');
    expect(Object.keys(example.aggregates)).toEqual(
      expect.arrayContaining(['cart', 'order']),
    );
    expect(example.aggregates.cart.commands).toEqual(
      expect.arrayContaining(['me.ahoo.wow.example.api.cart.AddCartItem']),
    );
  });
});
