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
import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  type KnownQueryErrorCode,
  type QueryErrorCode,
  QueryErrorCodes,
  type QueryViolation,
} from '../../src/dsl.js';
import type { Violation } from '../../src/index.js';

/** The string `const val`s of the Kotlin QueryErrorCodes of `:query-api`, in source order. */
function kotlinQueryErrorCodes(): Record<string, string> {
  const source = readFileSync(
    new URL(
      '../../../../api/query-api/src/main/kotlin/me/ahoo/wow/api/query/QueryErrorCodes.kt',
      import.meta.url,
    ),
    'utf8',
  );
  return Object.fromEntries(
    [...source.matchAll(/const val (\w+) = "([^"]*)"/g)].map(
      ([, name, value]) => [name, value],
    ),
  );
}

describe('QueryErrorCodes', () => {
  it('mirrors the Kotlin QueryErrorCodes, name for name and in order', () => {
    expect(Object.entries(QueryErrorCodes)).toEqual(
      Object.entries(kotlinQueryErrorCodes()),
    );
  });

  it('is frozen, its codes are literal types, and the union is open', () => {
    expect(Object.isFrozen(QueryErrorCodes)).toBe(true);
    expectTypeOf(
      QueryErrorCodes.UNKNOWN_FIELD,
    ).toEqualTypeOf<'UNKNOWN_FIELD'>();
    expectTypeOf<'UNKNOWN_FIELD'>().toMatchTypeOf<KnownQueryErrorCode>();
    // A code a newer server added is still a QueryErrorCode.
    expectTypeOf<'SOME_FUTURE_CODE'>().toMatchTypeOf<QueryErrorCode>();
  });

  it("reads a problem's violation as a QueryViolation", () => {
    expectTypeOf<Violation>().toMatchTypeOf<QueryViolation>();
  });
});
