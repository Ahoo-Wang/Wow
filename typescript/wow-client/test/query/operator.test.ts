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
  it('should have all logical operators', () => {
    expect(Operator.AND).toBe('AND');
    expect(Operator.OR).toBe('OR');
    expect(Operator.NOR).toBe('NOR');
  });

  it('should have ID related operators', () => {
    expect(Operator.ID).toBe('ID');
    expect(Operator.IDS).toBe('IDS');
    expect(Operator.AGGREGATE_ID).toBe('AGGREGATE_ID');
    expect(Operator.AGGREGATE_IDS).toBe('AGGREGATE_IDS');
  });

  it('should have tenant and owner operators', () => {
    expect(Operator.TENANT_ID).toBe('TENANT_ID');
    expect(Operator.OWNER_ID).toBe('OWNER_ID');
  });

  it('should have status operators', () => {
    expect(Operator.DELETED).toBe('DELETED');
    expect(Operator.ALL).toBe('ALL');
  });

  it('should have comparison operators', () => {
    expect(Operator.EQ).toBe('EQ');
    expect(Operator.NE).toBe('NE');
    expect(Operator.GT).toBe('GT');
    expect(Operator.LT).toBe('LT');
    expect(Operator.GTE).toBe('GTE');
    expect(Operator.LTE).toBe('LTE');
  });

  it('should have string matching operators', () => {
    expect(Operator.CONTAINS).toBe('CONTAINS');
    expect(Operator.STARTS_WITH).toBe('STARTS_WITH');
    expect(Operator.ENDS_WITH).toBe('ENDS_WITH');
  });

  it('should have array operators', () => {
    expect(Operator.IN).toBe('IN');
    expect(Operator.NOT_IN).toBe('NOT_IN');
    expect(Operator.BETWEEN).toBe('BETWEEN');
    expect(Operator.ALL_IN).toBe('ALL_IN');
    expect(Operator.ELEM_MATCH).toBe('ELEM_MATCH');
  });

  it('should have null and boolean operators', () => {
    expect(Operator.NULL).toBe('NULL');
    expect(Operator.NOT_NULL).toBe('NOT_NULL');
    expect(Operator.TRUE).toBe('TRUE');
    expect(Operator.FALSE).toBe('FALSE');
    expect(Operator.EXISTS).toBe('EXISTS');
  });

  it('should have date operators', () => {
    expect(Operator.TODAY).toBe('TODAY');
    expect(Operator.BEFORE_TODAY).toBe('BEFORE_TODAY');
    expect(Operator.TOMORROW).toBe('TOMORROW');
    expect(Operator.THIS_WEEK).toBe('THIS_WEEK');
    expect(Operator.NEXT_WEEK).toBe('NEXT_WEEK');
    expect(Operator.LAST_WEEK).toBe('LAST_WEEK');
    expect(Operator.THIS_MONTH).toBe('THIS_MONTH');
    expect(Operator.LAST_MONTH).toBe('LAST_MONTH');
    expect(Operator.RECENT_DAYS).toBe('RECENT_DAYS');
    expect(Operator.EARLIER_DAYS).toBe('EARLIER_DAYS');
  });

  it('should have raw operator', () => {
    expect(Operator.RAW).toBe('RAW');
  });
});

describe('LOGICAL_OPERATORS', () => {
  it('should contain logical operators', () => {
    expect(LOGICAL_OPERATORS.has(Operator.AND)).toBe(true);
    expect(LOGICAL_OPERATORS.has(Operator.OR)).toBe(true);
    expect(LOGICAL_OPERATORS.has(Operator.NOR)).toBe(true);
  });

  it('should not contain non-logical operators', () => {
    expect(LOGICAL_OPERATORS.has(Operator.EQ)).toBe(false);
    expect(LOGICAL_OPERATORS.has(Operator.ID)).toBe(false);
    expect(LOGICAL_OPERATORS.has(Operator.ALL)).toBe(false);
  });

  it('should have exactly 3 operators', () => {
    expect(LOGICAL_OPERATORS.size).toBe(3);
  });
});

describe('EMPTY_VALUE_OPERATORS', () => {
  it('should contain operators that work with empty values', () => {
    expect(EMPTY_VALUE_OPERATORS.has(Operator.NULL)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.NOT_NULL)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.TRUE)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.FALSE)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.EXISTS)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.TODAY)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.TOMORROW)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.THIS_WEEK)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.NEXT_WEEK)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.LAST_WEEK)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.THIS_MONTH)).toBe(true);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.LAST_MONTH)).toBe(true);
  });

  it('should not contain operators that require values', () => {
    expect(EMPTY_VALUE_OPERATORS.has(Operator.EQ)).toBe(false);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.GT)).toBe(false);
    expect(EMPTY_VALUE_OPERATORS.has(Operator.IN)).toBe(false);
  });

  it('should have exactly 12 operators', () => {
    expect(EMPTY_VALUE_OPERATORS.size).toBe(12);
  });
});
