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
import { Operator, LOGICAL_OPERATORS, EMPTY_VALUE_OPERATORS } from '../../src';

describe('Operator', () => {
  // The Operator enum's value for each key equals the key name itself. Verify
  // this invariant once for every key instead of 11 near-identical blocks.
  it('should have every enum value equal to its key name', () => {
    const stringKeys = Object.keys(Operator) as (keyof typeof Operator)[];
    expect(stringKeys.length).toBeGreaterThan(20);
    for (const key of stringKeys) {
      expect(Operator[key], `Operator.${String(key)}`).toBe(String(key));
    }
  });
});

describe('LOGICAL_OPERATORS', () => {
  it('should contain exactly the logical operators', () => {
    expect([...LOGICAL_OPERATORS].sort()).toEqual(
      [Operator.AND, Operator.OR, Operator.NOR].sort(),
    );
  });

  it('should not contain non-logical operators', () => {
    expect(LOGICAL_OPERATORS.has(Operator.EQ)).toBe(false);
    expect(LOGICAL_OPERATORS.has(Operator.ID)).toBe(false);
  });
});

describe('EMPTY_VALUE_OPERATORS', () => {
  it('should contain exactly the operators that work with empty values', () => {
    expect(EMPTY_VALUE_OPERATORS.size).toBe(12);
    const expectedEmpty = [
      Operator.NULL,
      Operator.NOT_NULL,
      Operator.TRUE,
      Operator.FALSE,
      Operator.EXISTS,
      Operator.TODAY,
      Operator.TOMORROW,
      Operator.THIS_WEEK,
      Operator.NEXT_WEEK,
      Operator.LAST_WEEK,
      Operator.THIS_MONTH,
      Operator.LAST_MONTH,
    ];
    for (const op of expectedEmpty) {
      expect(EMPTY_VALUE_OPERATORS.has(op), String(op)).toBe(true);
    }
  });

  it('should not contain operators that require values', () => {
    expect(EMPTY_VALUE_OPERATORS.has(Operator.EQ)).toBe(false);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.GT)).toBe(false);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.IN)).toBe(false);
  });
});
