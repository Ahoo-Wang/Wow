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
import en_US from '../../../src/query/locale/en_US';
import { Operator } from '../../../src/query/operator';
import { OperatorLocale } from '../../../src/query/locale/OperatorLocale';

describe('en_US locale', () => {
  it('should be a valid OperatorLocale', () => {
    // Type check: en_US should satisfy OperatorLocale type
    const locale: OperatorLocale = en_US;
    expect(locale).toBeDefined();
  });

  it('should have translations for all operators', () => {
    const operators = Object.values(Operator);
    operators.forEach(operator => {
      expect(en_US).toHaveProperty(operator);
      expect(typeof en_US[operator]).toBe('string');
      expect(en_US[operator].length).toBeGreaterThan(0);
    });
  });

  it('should have correct logical operator translations', () => {
    expect(en_US.AND).toBe('AND');
    expect(en_US.OR).toBe('OR');
    expect(en_US.NOR).toBe('NOR');
  });

  it('should have correct ID related translations', () => {
    expect(en_US.ID).toBe('ID Equals');
    expect(en_US.IDS).toBe('ID In');
    expect(en_US.AGGREGATE_ID).toBe('Aggregate ID Equals');
    expect(en_US.AGGREGATE_IDS).toBe('Aggregate ID In');
  });

  it('should have correct comparison operator translations', () => {
    expect(en_US.EQ).toBe('Equals');
    expect(en_US.NE).toBe('Not Equals');
    expect(en_US.GT).toBe('Greater Than');
    expect(en_US.LT).toBe('Less Than');
    expect(en_US.GTE).toBe('Greater Than or Equal');
    expect(en_US.LTE).toBe('Less Than or Equal');
  });

  it('should have correct string matching translations', () => {
    expect(en_US.CONTAINS).toBe('Contains');
    expect(en_US.STARTS_WITH).toBe('Starts With');
    expect(en_US.ENDS_WITH).toBe('Ends With');
  });

  it('should have correct date operator translations', () => {
    expect(en_US.TODAY).toBe('Today');
    expect(en_US.BEFORE_TODAY).toBe('Before Today');
    expect(en_US.TOMORROW).toBe('Tomorrow');
    expect(en_US.THIS_WEEK).toBe('This Week');
    expect(en_US.THIS_MONTH).toBe('This Month');
  });

  it('should have correct null/boolean translations', () => {
    expect(en_US.NULL).toBe('Is Null');
    expect(en_US.NOT_NULL).toBe('Is Not Null');
    expect(en_US.TRUE).toBe('Is True');
    expect(en_US.FALSE).toBe('Is False');
    expect(en_US.EXISTS).toBe('Exists');
  });

  it('should have correct raw operator translation', () => {
    expect(en_US.RAW).toBe('Raw Query');
  });
});
