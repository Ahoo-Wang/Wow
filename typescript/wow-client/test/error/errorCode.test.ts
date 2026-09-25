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

import { describe, expect, expectTypeOf, it } from 'vitest';
import { type ErrorCode, ErrorCodes, type WowErrorCode } from '../../src';

describe('ErrorCode', () => {
  it('is frozen, and its codes are literal types', () => {
    expect(Object.isFrozen(ErrorCodes)).toBe(true);
    expectTypeOf(ErrorCodes.NOT_FOUND).toEqualTypeOf<'NotFound'>();
    expectTypeOf<'NotFound'>().toMatchTypeOf<WowErrorCode>();
    // An application's own code is an ErrorCode too.
    expectTypeOf<'OrderAlreadyPaid'>().toMatchTypeOf<ErrorCode>();
  });
});
