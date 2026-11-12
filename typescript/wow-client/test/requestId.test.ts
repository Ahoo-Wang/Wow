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
 * The request id the client generates: a UUIDv7 (RFC 9562), so that the ids
 * a service indexes arrive in the order they were made.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { newRequestId } from '../src/index.js';

/** The 128 bits of a UUID, from its text. */
function bitsOf(id: string): bigint {
  return BigInt(`0x${id.replace(/-/g, '')}`);
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('newRequestId', () => {
  it('is a UUIDv7: version 7, variant 10', () => {
    for (let index = 0; index < 1_000; index++) {
      const bits = bitsOf(newRequestId());
      expect((bits >> 76n) & 0xfn).toBe(7n);
      expect((bits >> 62n) & 0x3n).toBe(0b10n);
    }
  });

  it('starts with the Unix milliseconds it was made in, big-endian', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    for (const time of [0, 1, 1_762_920_000_123, 2 ** 48 - 1]) {
      vi.setSystemTime(time);
      expect(bitsOf(newRequestId()) >> 80n).toBe(BigInt(time));
    }
    vi.setSystemTime(1_762_920_000_123);
    expect(newRequestId().slice(0, 13)).toBe('019a7638-6a7b');
  });

  it('sorts by the millisecond it was made in, as text and as bits', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const ids = [
      255, 256, 65_535, 65_536, 1_762_920_000_000, 1_762_920_000_001,
    ].map(time => {
      vi.setSystemTime(time);
      return newRequestId();
    });
    expect([...ids].sort()).toEqual(ids);
    expect(ids.map(bitsOf)).toEqual(
      ids.map(bitsOf).sort((left, right) => (left < right ? -1 : 1)),
    );
  });

  it('makes no id twice over a large sample, within the same millisecond too', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(1_762_920_000_000);
    const ids = new Set(Array.from({ length: 100_000 }, () => newRequestId()));
    expect(ids.size).toBe(100_000);
  });

  it('keeps its shape without crypto.getRandomValues', () => {
    vi.stubGlobal('crypto', undefined);
    const bits = bitsOf(newRequestId());
    expect((bits >> 76n) & 0xfn).toBe(7n);
    expect((bits >> 62n) & 0x3n).toBe(0b10n);
  });
});
