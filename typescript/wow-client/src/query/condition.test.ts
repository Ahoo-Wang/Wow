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
import { eq, gt, and, or, notIn, contains } from './condition';
import { Operator } from './operator';

describe('condition', () => {
  it('should create eq condition', () => {
    const condition = eq('field', 'value');
    expect(condition).toEqual({
      field: 'field',
      operator: Operator.EQ,
      value: 'value',
    });
  });

  it('should create gt condition', () => {
    const condition = gt('age', 20);
    expect(condition).toEqual({
      field: 'age',
      operator: Operator.GT,
      value: 20,
    });
  });

  it('should create and condition', () => {
    const condition1 = eq('field1', 'value1');
    const condition2 = gt('field2', 10);
    const condition = and(condition1, condition2);
    expect(condition).toEqual({
      operator: Operator.AND,
      children: [condition1, condition2],
    });
  });

  it('should create or condition', () => {
    const condition1 = eq('field1', 'value1');
    const condition2 = gt('field2', 10);
    const condition = or(condition1, condition2);
    expect(condition).toEqual({
      operator: Operator.OR,
      children: [condition1, condition2],
    });
  });

  it('should create notIn condition', () => {
    const condition = notIn('field', 'value1', 'value2');
    expect(condition).toEqual({
      field: 'field',
      operator: Operator.NOT_IN,
      value: ['value1', 'value2'],
    });
  });

  it('should create contains condition', () => {
    const condition = contains('field', 'value');
    expect(condition).toEqual({
      field: 'field',
      operator: Operator.CONTAINS,
      value: 'value',
    });
  });
});
