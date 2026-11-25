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
 * Round two of the deep review: `parseWaitPlan` says it reads a `Wow-Wait`
 * value "as the service reads it". #6821 (#6815) made the service read a hop
 * in one pass, and refuse a plan past 4,096 characters or 32 hops before any
 * hop is read (`WaitHeader.parse`). The TypeScript reader keeps the pattern
 * the service dropped, `^([A-Za-z]+)\s*(?:\(\s*([^()]*?)\s*\))?$`, whose
 * three quantifiers share the white space of a hop left open, and bounds
 * neither the length nor the hops.
 */

import { describe, expect, it } from 'vitest';
import { parseHop, parseWaitPlan } from '../src/index.js';

describe('r2 the wait plan reader as the service reads it (#6821)', () => {
  it('r2 a hop left open with white space is refused in time linear in its length', () => {
    // Under the plan's 4,096 characters: the service refuses it at once.
    const open = `accepted(${' '.repeat(2_000)}x`;
    const started = performance.now();
    expect(() => parseHop(open)).toThrow(TypeError);
    // The service's own test gives the same read a 1 s budget.
    expect(performance.now() - started < 1_000).toBe(true);
  });

  it('r2 a plan of more than 32 hops is refused, as the service refuses it', () => {
    const plan = Array.from({ length: 33 }, () => 'committed').join(' > ');
    expect(() => parseWaitPlan(plan)).toThrow(TypeError);
  });

  it('r2 a plan longer than 4,096 characters is refused, as the service refuses it', () => {
    // A plan that reads as `committed` once trimmed, past the service's bound.
    const plan = `committed${' '.repeat(4_100)}`;
    expect(() => parseWaitPlan(plan)).toThrow(TypeError);
  });
});
