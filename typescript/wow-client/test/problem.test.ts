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
 * Problems as a client reads them: retries, Retry-After, codes, extensions, and
 * a status that is no problem.
 */

import { describe, expect, it } from 'vitest';
import {
  parseRetryAfter,
  type Problem,
  readProblem,
  retryOf,
  toWowProblemError,
  WowProblemError,
} from '../src/index.js';
import { fromGateway } from '../src/problem.js';

describe('problems', () => {
  it('reads what a retry may do from the problem, else from the catalog', () => {
    const problem = (status: number, members: Record<string, unknown>) => ({
      type: 'about:blank',
      title: 'T',
      status,
      ...members,
    });
    // The member every problem a Wow service writes carries wins.
    expect(
      retryOf(problem(503, { code: 'wow.command.unknown', retry: 'none' })),
    ).toBe('none');
    expect(retryOf(problem(503, { code: 'wow.command.unknown' }))).toBe(
      'same-request',
    );
    expect(retryOf(problem(429, { code: 'wow.query.busy' }))).toBe(
      'new-request',
    );
    // A query that ran out of time would run as long again (decision D254 ③).
    expect(retryOf(problem(504, { code: 'wow.query.timeout' }))).toBe('none');
    expect(retryOf(problem(409, { code: 'wow.aggregate.exists' }))).toBe(
      'none',
    );
    // A body that is no Wow problem: a proxy's 503 or 429.
    expect(retryOf(problem(503, {}))).toBe('same-request');
    expect(retryOf(problem(429, {}))).toBe('same-request');
    expect(retryOf(problem(500, {}))).toBe('none');
    // A gateway's bare 502 or 504: whether its node took the command is
    // unknown, and the same request id is safe either way.
    expect(retryOf(problem(502, {}))).toBe('same-request');
    expect(retryOf(problem(504, {}))).toBe('same-request');
    expect(fromGateway(problem(502, {}))).toBe(true);
    expect(fromGateway(problem(504, {}))).toBe(true);
    expect(fromGateway(problem(503, {}))).toBe(false);
    expect(fromGateway(problem(504, { code: 'wow.query.timeout' }))).toBe(
      false,
    );
    const busy = new WowProblemError(
      problem(429, { code: 'wow.query.busy', retry: 'new-request' }) as never,
    );
    expect(busy.retryAfterMs).toBe(1000);
    expect(
      new WowProblemError(
        problem(409, { code: 'wow.aggregate.exists' }) as never,
      ).retryAfterMs,
    ).toBeUndefined();
  });

  it('reads Retry-After as seconds or a date', () => {
    expect(parseRetryAfter('2')).toBe(2000);
    expect(
      parseRetryAfter(
        'Wed, 21 Oct 2015 07:28:05 GMT',
        Date.UTC(2015, 9, 21, 7, 28, 0),
      ),
    ).toBe(5000);
    expect(parseRetryAfter('soon')).toBeUndefined();
    expect(parseRetryAfter(null)).toBeUndefined();
  });

  it('reads a Retry-After that is neither delay seconds nor an HTTP date as absent, as the Kotlin client does', () => {
    const now = Date.UTC(2015, 9, 21, 7, 28, 0);
    // `Date.parse` reads each of these as some date; RFC 9110 reads none.
    for (const value of ['1.5', '-1', '+2', 'March 7', '1e3'])
      expect(parseRetryAfter(value, now), value).toBeUndefined();
    // RFC 1123's forms, as `DateTimeFormatter.RFC_1123_DATE_TIME` reads them.
    expect(parseRetryAfter('21 Oct 2015 07:28:05 GMT', now)).toBe(5000);
    expect(parseRetryAfter('Wed, 21 Oct 2015 08:28:05 +0100', now)).toBe(5000);
    // So the catalog's wait applies to a problem whose header says nothing.
    const busy = new WowProblemError(
      {
        type: 'urn:wow:error:wow.query.busy',
        title: 'Query busy',
        status: 429,
        code: 'wow.query.busy',
      },
      { retryAfterMs: parseRetryAfter('1.5') },
    );
    expect(busy.retryAfterMs).toBe(1000);
  });

  it('reads a retry value this client does not know as absent, as the Kotlin client does', () => {
    // Kotlin's Retry may gain a case in a minor release; its client reads one
    // it does not know as absent: the catalog's, else the bare-status rule.
    const newer = (status: number, code?: string) => ({
      type: code === undefined ? 'about:blank' : `urn:wow:error:${code}`,
      title: 'T',
      status,
      ...(code === undefined ? {} : { code }),
      retry: 'after-restart' as never,
    });
    expect(retryOf(newer(503, 'wow.storage.unavailable'))).toBe('same-request');
    expect(retryOf(newer(409, 'wow.aggregate.exists'))).toBe('none');
    expect(retryOf(newer(502))).toBe('same-request');
    expect(
      new WowProblemError(newer(503, 'wow.storage.unavailable')).retry,
    ).toBe('same-request');
  });

  it('reads the code from the type when the body names none', async () => {
    const problem = await readProblem(
      new Response(
        JSON.stringify({ type: 'urn:wow:error:wow.node.closing', status: 503 }),
        { status: 503, statusText: 'Service Unavailable' },
      ),
    );
    expect(problem).toMatchObject({
      code: 'wow.node.closing',
      title: 'Service Unavailable',
    });
  });

  it('gathers the extension members of a problem as its extensions', async () => {
    const boards = [{ id: 'b1', title: 'Ops' }];
    const problem = await readProblem(
      new Response(
        JSON.stringify({
          type: 'urn:wow:error:view-store.referenced-by-shared-dashboard',
          title: 'Unprocessable Content',
          status: 422,
          detail: 'Shown by shared dashboards.',
          instance: '/view-store/view/v1/claim-view',
          code: 'view-store.referenced-by-shared-dashboard',
          boards,
        }),
        { status: 422 },
      ),
    );
    expect(problem.extensions).toEqual({ boards });
    expect(problem.detail).toBe('Shown by shared dashboards.');
    const streamed = new WowProblemError({
      type: 'about:blank',
      title: 'Failed',
      status: 500,
      hint: 'x',
    } as Problem);
    expect(streamed.problem.extensions).toEqual({ hint: 'x' });
    const given = { type: 'x', title: 't', status: 500, extensions: { a: 1 } };
    expect(new WowProblemError(given).problem).toBe(given);
    expect(
      new WowProblemError({ type: 'x', title: 't', status: 500 }).problem
        .extensions,
    ).toBeUndefined();
  });

  it('makes a problem of a status when the body is not one', async () => {
    const problem = await readProblem(
      new Response('oops', { status: 502, statusText: 'Bad Gateway' }),
    );
    expect(problem).toEqual({
      type: 'about:blank',
      title: 'Bad Gateway',
      status: 502,
    });
    const error = new WowProblemError(problem);
    expect(error.code).toBeUndefined();
    expect(error.isFramework).toBe(false);
    expect(error.message).toBe('502 Bad Gateway');
  });

  it('converts only what a server answered', async () => {
    expect(await toWowProblemError(new Error('network'))).toBeUndefined();
    expect(await toWowProblemError(null)).toBeUndefined();
    const known = new WowProblemError({ type: 'x', title: 't', status: 500 });
    expect(await toWowProblemError(known)).toBe(known);
    expect(await toWowProblemError({ cause: known })).toBe(known);
    expect(
      await toWowProblemError({ exchange: { response: new Response('{}') } }),
    ).toBeUndefined();
  });
});
