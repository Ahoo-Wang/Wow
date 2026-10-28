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
import {
  sourceFailure,
  sourceReason,
} from '../src/runtime/failure/sourceReason.js';
import { QueryErrorCodes } from '@ahoo-wang/wow-client/dsl';

/**
 * A rejection shaped like fetcher's `ExchangeError`: the engine reads an
 * exchange by what it offers, not by its class.
 */
function exchangeError(
  body: unknown,
  status = 400,
  message = 'Request failed with status code 400 for http://svc/x/snapshot/paged',
) {
  return Object.assign(new Error(message), {
    exchange: {
      response: { status },
      extractResult: () =>
        body instanceof Error ? Promise.reject(body) : Promise.resolve(body),
    },
  });
}

describe('sourceReason', () => {
  it('says what the service said, not what the HTTP client said', async () => {
    await expect(
      sourceReason(
        exchangeError({
          type: 'urn:wow:error:wow.query.invalid',
          title: 'Invalid query',
          status: 400,
          code: 'wow.query.invalid',
          detail: 'The page window 12000 exceeds 10000.',
        }),
      ),
    ).resolves.toBe('The page window 12000 exceeds 10000.');
  });

  it('falls back to the problem’s title, then its code', async () => {
    await expect(
      sourceReason(
        exchangeError({ type: 'about:blank', title: 'Not Found', status: 404 }),
      ),
    ).resolves.toBe('Not Found');
    await expect(
      sourceReason(exchangeError({ code: 'wow.query.failed', detail: ' ' })),
    ).resolves.toBe('wow.query.failed');
  });

  it('reads the problem a WowProblemError carries, and its first coded violation', async () => {
    const problem = {
      type: 'urn:wow:error:wow.query.invalid',
      title: 'Invalid query',
      status: 400,
      code: 'wow.query.invalid',
      detail: 'The query failed admission.',
      violations: [
        { path: 'x', code: '', message: 'no code' },
        {
          path: 'state.sku',
          code: QueryErrorCodes.UNKNOWN_FIELD,
          message: ' ',
        },
      ],
    };
    await expect(
      sourceFailure(
        Object.assign(new Error('[wow.query.invalid] …'), {
          problem,
          status: 400,
        }),
      ),
    ).resolves.toEqual({
      reason: 'The query failed admission.',
      violation: {
        code: QueryErrorCodes.UNKNOWN_FIELD,
        path: 'state.sku',
        message: 'The query failed admission.',
      },
      errorCode: 'wow.query.invalid',
      status: 400,
    });
  });

  it('says only the status when the body names nothing — never the URL', async () => {
    for (const body of [null, 'Bad Gateway', {}, new Error('not json')]) {
      const reason = await sourceReason(exchangeError(body, 502));
      expect(reason).toBe('HTTP 502');
      expect(reason).not.toContain('http://');
    }
  });

  it('marks fetch’s own TypeError unreachable, bare or as an exchange’s cause', async () => {
    await expect(
      sourceFailure(new TypeError('Failed to fetch')),
    ).resolves.toEqual({ reason: 'Failed to fetch', unreachable: true });
    await expect(
      sourceFailure(
        Object.assign(new Error('Load failed'), {
          cause: new TypeError('Load failed'),
          exchange: { response: undefined },
        }),
      ),
    ).resolves.toEqual({ reason: 'Load failed', unreachable: true });
    // One the service answered is not: it was reached.
    await expect(
      sourceFailure(
        Object.assign(new TypeError('x'), {
          exchange: { response: { status: 502 } },
        }),
      ),
    ).resolves.toEqual({ reason: 'HTTP 502', status: 502 });
    await expect(sourceFailure(new Error('gateway down'))).resolves.toEqual({
      reason: 'gateway down',
    });
  });

  it('reads anything else by its own message', async () => {
    await expect(sourceReason(new Error('gateway down'))).resolves.toBe(
      'gateway down',
    );
    await expect(sourceReason('refused')).resolves.toBe('refused');
    await expect(
      sourceReason(Object.assign(new Error('x'), { exchange: null })),
    ).resolves.toBe('x');
  });
});
