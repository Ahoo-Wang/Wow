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
 * Round two of the deep review: the public surface of wow-client against
 * what its TSDoc, its documentation pages and the Kotlin client promise.
 * Each test fails on `370ac57604` for the issue it names, and passes once
 * that defect is fixed.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { aggregation, filter } from '../src/dsl.js';
import {
  CommandClient,
  FrameworkErrorCodes,
  FRAMEWORK_RETRIES,
  parseJson,
  parseRetryAfter,
  requestIdOf,
  SnapshotQueryClient,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import { problemOf, problemResponse } from '../src/testing.js';
import { jsonResponse, sseResponse, stubFetch } from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';
import {
  client,
  PRODUCT,
  problemResponse as problemAnswer,
  result,
} from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('r2: a wait plan of exactly ten seconds', () => {
  // A plan's timeout is 10 s unless `.within` says otherwise
  // (documentation/docs/en/typescript/commands.md:143), and Kotlin's
  // HttpCommandClient states every plan's timeout as Wow-Wait-Timeout
  // (`plan?.timeout?.let { put(WowHeaders.WAIT_TIMEOUT, ...) }`). The
  // TypeScript client compares the plan's timeout with
  // WaitPlan.DEFAULT_TIMEOUT and states none when they are equal.
  it('states the timeout a caller set with within(10_000)', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    await commands.wait({
      command: 'eshop-catalog.product.Rename',
      id: 'p1',
      wait: WaitPlan.COMMITTED.within(10_000),
    });
    expect(requests[0].headers.get('Wow-Wait-Timeout')).toBe('10s');
  });

  it("states a plan's own timeout as the Kotlin client does", async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    await commands.wait({
      command: 'eshop-catalog.product.Rename',
      id: 'p1',
      wait: WaitPlan.COMMITTED,
    });
    expect(requests[0].headers.get('Wow-Wait-Timeout')).toBe('10s');
  });
});

describe('r2: /testing builds the problems a service answers', () => {
  // problemOf's TSDoc: "An RFC 9457 problem as a service answers it". The
  // service writes `retry` on every problem (edge/http Problem.members) and
  // the catalog's title for a framework code (ErrorStatuses.title), and
  // answers Retry-After when a retry may get past it (Problem.respond).
  it('writes the retry member every service problem carries', () => {
    expect(problemOf(503, FrameworkErrorCodes.STORAGE_UNAVAILABLE).retry).toBe(
      FRAMEWORK_RETRIES.get(FrameworkErrorCodes.STORAGE_UNAVAILABLE)?.retry,
    );
    expect(problemOf(404, FrameworkErrorCodes.AGGREGATE_NOT_FOUND).retry).toBe(
      'none',
    );
    expect(problemOf(404).retry).toBe('none');
  });

  it('titles a framework code as the error catalog does', () => {
    expect(problemOf(404, FrameworkErrorCodes.AGGREGATE_NOT_FOUND).title).toBe(
      'Aggregate not found',
    );
    expect(problemOf(409, FrameworkErrorCodes.COMMAND_REFUSED).title).toBe(
      'Command refused',
    );
  });

  it('answers Retry-After for a problem a retry may get past', () => {
    const response = problemResponse(
      problemOf(503, FrameworkErrorCodes.STORAGE_UNAVAILABLE),
    );
    expect(response.headers.get('Retry-After')).toBe('1');
  });
});

describe('r2: a query stream the client cannot resume', () => {
  const query = { filter: filter.matchAll() };

  // documentation/docs/en/typescript/queries.md:151: "A failure the client
  // cannot resume errors the stream with its WowProblemError". Kotlin's
  // HttpQueryApi answers a stream cut off, or whose connection is lost,
  // past its retries with `wow.peer.unavailable` (`lost(...)`).
  it('errors a stream cut off past its retries with wow.peer.unavailable', async () => {
    stubFetch(() => sseResponse('id:p1\nevent:row\ndata:{"id":"p1"}\n\n'));
    const rows = await new SnapshotQueryClient(PRODUCT, client()).stream(
      query,
      { resume: { maxRetries: 0 } },
    );
    const failure = await failureOf(rows);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect((failure as WowProblemError).code).toBe(
      FrameworkErrorCodes.PEER_UNAVAILABLE,
    );
  });

  it('errors a stream whose connection is lost past its retries with wow.peer.unavailable', async () => {
    stubFetch(() => {
      let sent = false;
      const body = new ReadableStream<Uint8Array>({
        pull(controller) {
          if (sent) {
            controller.error(new TypeError('network error'));
            return;
          }
          sent = true;
          controller.enqueue(
            new TextEncoder().encode('id:p1\nevent:row\ndata:{"id":"p1"}\n\n'),
          );
        },
      });
      return new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      });
    });
    const rows = await new SnapshotQueryClient(PRODUCT, client()).stream(
      query,
      { resume: { maxRetries: 0 } },
    );
    const failure = await failureOf(rows);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect((failure as WowProblemError).code).toBe(
      FrameworkErrorCodes.PEER_UNAVAILABLE,
    );
  });
});

/** What reading `stream` to its end fails with. */
async function failureOf(stream: ReadableStream<unknown>): Promise<unknown> {
  const reader = stream.getReader();
  try {
    while (!(await reader.read()).done) {
      // Each row read; the failure is what ends the stream.
    }
  } catch (error) {
    return error;
  }
  return undefined;
}

describe('r2: a long larger than a number holds', () => {
  // documentation/docs/en/reference/json-values.md:11: "a client reads a
  // larger `long` as a `bigint`". wow-client reads a marked long as its
  // exact text (typescript/generate.md), and generated types say `string`.
  // The test holds the page and the client to one answer, whichever changes.
  it('is read as json-values.md says a client reads it', () => {
    const page = readFileSync(
      fileURLToPath(
        new URL(
          '../../../documentation/docs/en/reference/json-values.md',
          import.meta.url,
        ),
      ),
      'utf8',
    );
    const said = /a client reads a larger `long` as an? `?(\w+)`?/.exec(page);
    const read = parseJson('{"id":9007199254740993}', { id: true }) as {
      id: unknown;
    };
    expect(said?.[1]).toBe(typeof read.id);
  });
});

describe('r2: a retry member this client does not know', () => {
  // Kotlin's Retry may gain a case in a minor release (its KDoc), and the
  // Kotlin client reads a `retry` it does not know as absent: the catalog's
  // for the code, else the bare-status rule, else none
  // (edge/client Problems.kt, `WireNames.entryOf<Retry>(it) ?: ...`).
  // retryOf hands any defined `retry` back as it is, typed as a `Retry`.
  it('is read as the Kotlin client reads it', () => {
    const newer = new WowProblemError({
      type: 'urn:wow:error:wow.storage.unavailable',
      title: 'Storage unavailable',
      status: 503,
      code: FrameworkErrorCodes.STORAGE_UNAVAILABLE,
      retry: 'after-restart' as never,
    });
    expect(newer.retry).toBe('same-request');
  });
});

describe("r2: a command's headers and the request id it is sent with", () => {
  // requestIdOf: "The request id (`Wow-Request-Id`) a command that failed
  // with `error` was sent with". A CommandRequest takes RequestOptions'
  // `headers`, which `send` (http.ts) spreads after the client's own, so a
  // header naming Wow-Request-Id, in any case, replaces the id the client
  // generated or was given, and requestIdOf answers an id never sent. A
  // retry with it is another request, which may commit the command twice.
  it('answers the id the command was sent with', async () => {
    const { requests } = stubFetch(() =>
      problemAnswer(503, FrameworkErrorCodes.STORAGE_UNAVAILABLE),
    );
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    const failure = await commands
      .wait({
        command: 'eshop-catalog.product.Rename',
        id: 'p1',
        requestId: 'request-1',
        headers: { 'wow-request-id': 'request-2' },
      })
      .catch((error: unknown) => error);
    expect(requestIdOf(failure)).toBe(
      requests[0].headers.get('Wow-Request-Id'),
    );
  });
});

describe('r2: a Retry-After that is neither delay seconds nor an HTTP date', () => {
  // parseRetryAfter: "delay seconds or an HTTP date" (RFC 9110: 1*DIGIT or
  // an IMF-fixdate). It falls through to Date.parse, which reads `1.5` as
  // a date in 2001 and `-1` as one in 2001 too, so both read as 0 ms: a
  // retry at once, where an absent header waits the catalog's 1 s. Kotlin's
  // `Problems.retryAfterOf` reads both as absent (edge/client Problems.kt).
  it('is read as absent, as the Kotlin client reads it', () => {
    expect(parseRetryAfter('1.5')).toBeUndefined();
    expect(parseRetryAfter('-1')).toBeUndefined();
  });
});

describe("r2: an aggregation's group key over an exact field", () => {
  // generate.md names two kinds of value read without marks, a command's
  // `results` and an aggregation's metric values. A `terms` key over an
  // int64 field (a snowflake id past 2^53) is a third: `aggregate` reads its
  // rows with no marks (queries.ts, `aggregate`), so two groups read one key,
  // though the client holds the field's mark (`type.textNumbers.state`).
  it('keeps two groups apart', async () => {
    // The service writes each key as the JSON number it is.
    stubFetch(
      () =>
        new Response(
          '[{"owner":9007199254740993,"n":1},{"owner":9007199254740992,"n":2}]',
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
    );
    const snapshots = new SnapshotQueryClient(
      { ...PRODUCT, textNumbers: { state: { ownerKey: true } } },
      client(),
    );
    const rows = await snapshots.aggregate<{ owner: unknown; n: number }>(
      aggregation.query({
        groupBy: [aggregation.terms('state.ownerKey', 'owner')],
        metrics: [aggregation.count('n')],
      }),
    );
    expect(rows[0].owner).not.toEqual(rows[1].owner);
  });
});
