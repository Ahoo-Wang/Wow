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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { filter } from '../../src/dsl';
import {
  EventQueryClient,
  SnapshotQueryClient,
  WowProblemError,
} from '../../src/v10';
import {
  jsonResponse,
  readAll,
  type SentRequest,
  sseResponse,
  stubFetch,
  testFetcher,
} from '../client/fetchStub';

const PRODUCT = { context: 'eshop-catalog', name: 'product' };
const client = () => ({ fetcher: testFetcher() });
const query = { filter: filter.matchAll() };

function row(id: string): string {
  return `id:${id}\nevent:row\ndata:{"id":"${id}"}\n\n`;
}

function end(reason: string, resumable = reason !== 'complete'): string {
  return `event:end\ndata:${JSON.stringify({ reason, resumable })}\n\n`;
}

/** A stream that sends `text`, then fails as a dropped connection does. */
function dropped(text: string): Response {
  let sent = false;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (sent) {
        controller.error(new TypeError('network error'));
        return;
      }
      sent = true;
      controller.enqueue(new TextEncoder().encode(text));
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

function busy(retryAfter = '2'): Response {
  return new Response(
    JSON.stringify({
      type: 'urn:wow:error:wow.query.busy',
      title: 'Too Many Requests',
      status: 429,
      code: 'wow.query.busy',
    }),
    {
      status: 429,
      headers: {
        'Content-Type': 'application/problem+json',
        'Retry-After': retryAfter,
      },
    },
  );
}

/** Answers the requests in turn. */
function answers(...responses: Array<(request: SentRequest) => Response>) {
  let index = 0;
  return stubFetch(request => {
    const next = responses[index++];
    if (!next) throw new Error('No more answers.');
    return next(request);
  });
}

const lastEventIds = (requests: SentRequest[]) =>
  requests.map(r => r.headers.get('Last-Event-ID'));

const ids = (rows: unknown[]) => rows.map(r => (r as { id: string }).id);

describe('resumable query streams (decision D253)', () => {
  const delays: number[] = [];
  beforeEach(() => {
    delays.length = 0;
    const real = globalThis.setTimeout;
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((
      handler: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return real(handler, 0);
    }) as typeof setTimeout);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('resumes at once after a hold that expired, from the last id', async () => {
    const { requests } = answers(
      () => sseResponse(row('p1') + row('p2') + end('hold-expired')),
      () => sseResponse(row('p3') + end('complete')),
    );
    const products = new SnapshotQueryClient(PRODUCT, client());
    expect(ids(await readAll(await products.listStream(query)))).toEqual([
      'p1',
      'p2',
      'p3',
    ]);
    expect(lastEventIds(requests)).toEqual([null, 'p2']);
    expect(requests[1].body).toEqual(requests[0].body);
    expect(requests[1].headers.get('Accept')).toBe('text/event-stream');
    expect(delays).toEqual([]);
  });

  it('resumes with a backoff after a reclaimed permit, a stopping node, a cut-off and a dropped connection', async () => {
    const { requests } = answers(
      () => sseResponse(row('p1') + end('permit-reclaimed')),
      () => sseResponse(end('node-stopping')),
      () => sseResponse(row('p2')),
      () => dropped(row('p3')),
      // A drop may lose what arrived but was not read yet: the stream resumes after the last record read.
      request =>
        sseResponse(
          (request.headers.get('Last-Event-ID') === 'p3' ? '' : row('p3')) +
            row('p4') +
            end('complete'),
        ),
    );
    const stream = await new EventQueryClient(PRODUCT, client()).listStream(
      query,
      { resume: { initialDelayMs: 100, maxDelayMs: 150 } },
    );
    expect(ids(await readAll(stream))).toEqual(['p1', 'p2', 'p3', 'p4']);
    expect(lastEventIds(requests).slice(0, 4)).toEqual([
      null,
      'p1',
      'p1',
      'p2',
    ]);
    expect(['p2', 'p3']).toContain(lastEventIds(requests)[4]);
    // Doubled while no record arrives, and from the start again after one.
    expect(delays.slice(0, 3)).toEqual([100, 150, 100]);
    // 100 when p3 was read before the drop, else the second retry in a row.
    expect([100, 150]).toContain(delays[3]);
  });

  it('waits the Retry-After of a 429 before resuming', async () => {
    const { requests } = answers(
      () => sseResponse(row('p1') + end('permit-reclaimed')),
      () => busy('2'),
      () => sseResponse(row('p2') + end('complete')),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
      { resume: { initialDelayMs: 10 } },
    );
    expect(ids(await readAll(stream))).toEqual(['p1', 'p2']);
    expect(lastEventIds(requests)).toEqual([null, 'p1', 'p1']);
    expect(delays).toEqual([10, 2000]);
  });

  it('gives up after its retries, with what stopped it', async () => {
    answers(
      () => sseResponse(row('p1') + end('permit-reclaimed')),
      () => busy('0'),
      () => busy('0'),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
      { resume: { maxRetries: 2 } },
    );
    const read: unknown[] = [];
    const failure = await (async () => {
      for await (const r of stream) read.push(r);
    })().catch(e => e);
    expect(ids(read)).toEqual(['p1']);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure.code).toBe('wow.query.busy');
  });

  it('fails at once on a resume the server refuses, such as an invalid position', async () => {
    answers(
      () => sseResponse(row('p1') + end('hold-expired')),
      () =>
        jsonResponse(
          {
            type: 'urn:wow:error:wow.query.invalid',
            title: 'Bad Request',
            status: 400,
            code: 'wow.query.invalid',
          },
          400,
        ),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
    );
    await expect(readAll(stream)).rejects.toMatchObject({
      code: 'wow.query.invalid',
    });
  });

  it('resumes an error event that is retryable, and fails on one that is not', async () => {
    const storage =
      'event:error\ndata:{"type":"about:blank","title":"t","status":503,"code":"wow.storage.unavailable"}\n\n';
    const { requests } = answers(
      () => sseResponse(row('p1') + storage),
      () => sseResponse(row('p2') + end('complete')),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
    );
    expect(ids(await readAll(stream))).toEqual(['p1', 'p2']);
    expect(lastEventIds(requests)).toEqual([null, 'p1']);
    // A stream without positions cannot be resumed: the problem ends it.
    answers(() => sseResponse('event:row\ndata:{"id":"x"}\n\n' + storage));
    await expect(
      readAll(
        await new SnapshotQueryClient(PRODUCT, client()).listStream(query),
      ),
    ).rejects.toMatchObject({ code: 'wow.storage.unavailable' });
  });

  it('starts again from the first record when dropped before any', async () => {
    const { requests } = answers(
      () => dropped(''),
      () => sseResponse(row('p1') + end('complete')),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
    );
    expect(ids(await readAll(stream))).toEqual(['p1']);
    expect(lastEventIds(requests)).toEqual([null, null]);
  });

  it('counts a limit across resumes, and stops once it is read', async () => {
    const { requests } = answers(
      () => sseResponse(row('p1') + row('p2') + end('hold-expired')),
      () => sseResponse(row('p3') + end('hold-expired')),
    );
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream({
      ...query,
      limit: 3,
    });
    expect(ids(await readAll(stream))).toEqual(['p1', 'p2', 'p3']);
    expect(requests.map(r => (r.body as { limit: number }).limit)).toEqual([
      3, 1,
    ]);
  });

  it('exposes each position, and starts after a position given', async () => {
    const { requests } = answers(() =>
      sseResponse(row('p8') + row('p9') + end('complete')),
    );
    const rows = await readAll(
      await new SnapshotQueryClient(PRODUCT, client()).listStreamRows(query, {
        resume: { lastEventId: 'p7' },
      }),
    );
    expect(rows).toEqual([
      { record: { id: 'p8' }, position: 'p8' },
      { record: { id: 'p9' }, position: 'p9' },
    ]);
    expect(lastEventIds(requests)).toEqual(['p7']);
  });

  it('never resumes when told not to, and ends a stream without positions at its end', async () => {
    answers(() => sseResponse(row('p1') + end('hold-expired')));
    const once = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
      { resume: false },
    );
    await expect(readAll(once)).rejects.toThrow('hold-expired');
    answers(() => sseResponse('event:row\ndata:{"id":"x"}\n\n'));
    const rows = await readAll(
      await new SnapshotQueryClient(PRODUCT, client()).listStreamRows(query),
    );
    expect(rows).toEqual([{ record: { id: 'x' } }]);
    // A stream that drops after records without positions fails.
    answers(() => dropped('event:row\ndata:{"id":"x"}\n\n'));
    await expect(
      readAll(
        await new SnapshotQueryClient(PRODUCT, client()).listStream(query),
      ),
    ).rejects.toThrow('network error');
  });

  it('stops waiting to resume once aborted', async () => {
    vi.mocked(globalThis.setTimeout).mockImplementation(((
      _handler: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return 0;
    }) as typeof setTimeout);
    const controller = new AbortController();
    answers(() => sseResponse(row('p1') + end('permit-reclaimed')));
    const reader = (
      await new SnapshotQueryClient(PRODUCT, client()).listStream(query, {
        signal: controller.signal,
      })
    ).getReader();
    await reader.read();
    const next = reader.read();
    await vi.waitFor(() => expect(delays).toContain(200));
    controller.abort(new Error('left while waiting'));
    await expect(next).rejects.toThrow('left while waiting');
  });

  it('retries a resume that failed without an answer, and ends a dropped stream whose limit was read', async () => {
    let failed = false;
    const { requests } = stubFetch(request => {
      if (requests.length === 1)
        return sseResponse(row('p1') + end('hold-expired'));
      if (!failed) {
        failed = true;
        throw new TypeError('connection refused');
      }
      expect(request.headers.get('Last-Event-ID')).toBe('p1');
      return sseResponse(row('p2') + end('complete'));
    });
    expect(
      ids(
        await readAll(
          await new SnapshotQueryClient(PRODUCT, client()).listStream(query),
        ),
      ),
    ).toEqual(['p1', 'p2']);
    expect(requests).toHaveLength(3);
    // Dropped once its limit was read: nothing to resume.
    // A drop may lose the record read last: then the stream asks for it again.
    const limited = answers(
      () => dropped(row('p1')),
      () => sseResponse(row('p1') + end('complete')),
    );
    const rows = await readAll(
      await new SnapshotQueryClient(PRODUCT, client()).listStream({
        ...query,
        limit: 1,
      }),
    );
    expect(ids(rows)).toEqual(['p1']);
    expect(limited.requests.length).toBeLessThanOrEqual(2);
  });

  it('stops resuming once aborted, and cancels the current read', async () => {
    const controller = new AbortController();
    answers(() => sseResponse(row('p1') + end('hold-expired')));
    const stream = await new SnapshotQueryClient(PRODUCT, client()).listStream(
      query,
      { signal: controller.signal },
    );
    const reader = stream.getReader();
    expect(ids([(await reader.read()).value])).toEqual(['p1']);
    controller.abort(new Error('left'));
    await expect(reader.read()).rejects.toThrow('left');
    answers(() => sseResponse(row('p1') + row('p2')));
    const cancelled = await new SnapshotQueryClient(
      PRODUCT,
      client(),
    ).listStream(query);
    const cancelling = cancelled.getReader();
    await cancelling.read();
    await cancelling.cancel('done');
  });
});
