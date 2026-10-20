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

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { FrameworkErrorCodes, retryHintOf } from '../src/index.js';

/**
 * The server's framework error table (`FrameworkErrors.ALL` of the Kotlin
 * `manifest` module), as its test writes it: the client's code table must name
 * the same codes (review A5-8).
 */
const serverTable: ReadonlyArray<{ code: string; status: number }> = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL(
        '../../../api/manifest/src/test/resources/framework-errors.json',
        import.meta.url,
      ),
    ),
    'utf8',
  ),
);

describe('FrameworkErrorCodes', () => {
  it('names exactly the framework codes of the server', () => {
    expect([...Object.values(FrameworkErrorCodes)].sort()).toEqual(
      serverTable.map(entry => entry.code).sort(),
    );
  });

  it('retries every code the server answers with 503 or 429', () => {
    const retryable = serverTable.filter(
      entry => entry.status === 503 || entry.status === 429,
    );
    expect(retryable.length).toBeGreaterThan(0);
    for (const entry of retryable) {
      expect(retryHintOf(entry.code, entry.status).retryable).toBe(true);
    }
  });
});
