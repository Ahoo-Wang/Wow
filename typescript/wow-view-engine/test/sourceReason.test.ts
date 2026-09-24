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
import { sourceReason } from '../src/runtime/sourceReason.js';

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
          errorCode: 'IllegalArgument',
          errorMsg: 'HTTP page window[12000] must not exceed 10000.',
        }),
      ),
    ).resolves.toBe('HTTP page window[12000] must not exceed 10000.');
  });

  it('falls back to the service’s code when it gave no message', async () => {
    await expect(
      sourceReason(exchangeError({ errorCode: 'NotFound', errorMsg: ' ' })),
    ).resolves.toBe('NotFound');
  });

  it('says only the status when the body names nothing — never the URL', async () => {
    for (const body of [null, 'Bad Gateway', {}, new Error('not json')]) {
      const reason = await sourceReason(exchangeError(body, 502));
      expect(reason).toBe('HTTP 502');
      expect(reason).not.toContain('http://');
    }
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
