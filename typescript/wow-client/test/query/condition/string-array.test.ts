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
import type { Condition } from '../../../src';
import {
  allIn,
  between,
  contains,
  elemMatch,
  endsWith,
  isIn,
  match,
  notIn,
  Operator,
  startsWith,
} from '../../../src';

describe('Condition — String Matching Conditions', () => {
  it('should create CONTAINS condition without ignoreCase', () => {
    expect(contains('name', 'test')).toEqual({
      field: 'name',
      operator: Operator.CONTAINS,
      value: 'test',
      options: undefined,
    });
  });

  it('should create CONTAINS condition with ignoreCase true', () => {
    expect(contains('name', 'test', true)).toEqual({
      field: 'name',
      operator: Operator.CONTAINS,
      value: 'test',
      options: { ignoreCase: true },
    });
  });

  it('should create CONTAINS condition with ignoreCase false', () => {
    expect(contains('name', 'test', false)).toEqual({
      field: 'name',
      operator: Operator.CONTAINS,
      value: 'test',
      options: { ignoreCase: false },
    });
  });

  it('should create STARTS_WITH condition', () => {
    expect(startsWith('name', 'test', true)).toEqual({
      field: 'name',
      operator: Operator.STARTS_WITH,
      value: 'test',
      options: { ignoreCase: true },
    });
  });

  it('should create ENDS_WITH condition', () => {
    expect(endsWith('name', 'test', false)).toEqual({
      field: 'name',
      operator: Operator.ENDS_WITH,
      value: 'test',
      options: { ignoreCase: false },
    });
  });

  it('should create MATCH condition', () => {
    expect(match('name', 'test keyword')).toEqual({
      field: 'name',
      operator: Operator.MATCH,
      value: 'test keyword',
    });
  });
});

describe('Condition — Array Conditions', () => {
  it('should create IN condition', () => {
    expect(isIn('status', 'active', 'pending')).toEqual({
      field: 'status',
      operator: Operator.IN,
      value: ['active', 'pending'],
    });
  });

  it('should create NOT_IN condition', () => {
    expect(notIn('status', 'deleted', 'banned')).toEqual({
      field: 'status',
      operator: Operator.NOT_IN,
      value: ['deleted', 'banned'],
    });
  });

  it('should create BETWEEN condition', () => {
    expect(between('age', 18, 65)).toEqual({
      field: 'age',
      operator: Operator.BETWEEN,
      value: [18, 65],
    });
  });

  it('should create ALL_IN condition', () => {
    expect(allIn('tags', 'tag1', 'tag2')).toEqual({
      field: 'tags',
      operator: Operator.ALL_IN,
      value: ['tag1', 'tag2'],
    });
  });

  it('should create ELEM_MATCH condition', () => {
    const childCondition: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    expect(elemMatch('items', childCondition)).toEqual({
      field: 'items',
      operator: Operator.ELEM_MATCH,
      children: [childCondition],
    });
  });
});
