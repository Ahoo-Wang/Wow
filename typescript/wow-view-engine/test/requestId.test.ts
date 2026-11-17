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

import { newRequestId as clientRequestId } from '@ahoo-wang/wow-client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { newRequestId } from '../src/runtime/requestId.js';

/** A version 7 UUID: the version nibble 7, the variant bits 10. */
const UUID_V7 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** The millisecond a UUIDv7 was made in: its first 48 bits. */
const madeAt = (id: string) => parseInt(id.replace(/-/g, '').slice(0, 12), 16);

describe('newRequestId', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("is the client's generator, not one of the engine's own", () => {
    expect(newRequestId).toBe(clientRequestId);
  });

  it('makes a UUIDv7 that starts with the millisecond it was made in', () => {
    const now = Date.UTC(2026, 10, 16, 9, 30);
    vi.useFakeTimers({ now, toFake: ['Date'] });
    const id = newRequestId();
    expect(id).toMatch(UUID_V7);
    expect(madeAt(id)).toBe(now);
  });

  it('still makes one over plain HTTP, where randomUUID is missing', () => {
    const { getRandomValues } = globalThis.crypto;
    vi.stubGlobal('crypto', {
      getRandomValues: getRandomValues.bind(globalThis.crypto),
    });
    const ids = new Set(Array.from({ length: 200 }, () => newRequestId()));
    for (const id of ids) expect(id).toMatch(UUID_V7);
    expect(ids.size).toBe(200);
  });
});
