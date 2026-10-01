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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { newRequestId } from '../src/runtime/requestId.js';

const UUID_V4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('newRequestId', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses crypto.randomUUID where the context is secure', () => {
    expect(newRequestId()).toMatch(UUID_V4);
  });

  it('still makes a v4 UUID over plain HTTP, where randomUUID is missing', () => {
    const { getRandomValues } = globalThis.crypto;
    vi.stubGlobal('crypto', {
      getRandomValues: getRandomValues.bind(globalThis.crypto),
    });
    const ids = new Set(Array.from({ length: 200 }, () => newRequestId()));
    for (const id of ids) expect(id).toMatch(UUID_V4);
    expect(ids.size).toBe(200);
  });
});
