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
import {
  eq,
  exists,
  gt,
  gte,
  isFalse,
  isNull,
  isTrue,
  lt,
  lte,
  ne,
  notNull,
  Operator,
} from '../../../src';

describe('Condition — Comparison Conditions', () => {
  it('should create EQ condition', () => {
    expect(eq('name', 'test')).toEqual({
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    });
  });

  it('should create NE condition', () => {
    expect(ne('name', 'test')).toEqual({
      field: 'name',
      operator: Operator.NE,
      value: 'test',
    });
  });

  it('should create GT condition', () => {
    expect(gt('age', 18)).toEqual({
      field: 'age',
      operator: Operator.GT,
      value: 18,
    });
  });

  it('should create LT condition', () => {
    expect(lt('age', 18)).toEqual({
      field: 'age',
      operator: Operator.LT,
      value: 18,
    });
  });

  it('should create GTE condition', () => {
    expect(gte('age', 18)).toEqual({
      field: 'age',
      operator: Operator.GTE,
      value: 18,
    });
  });

  it('should create LTE condition', () => {
    expect(lte('age', 18)).toEqual({
      field: 'age',
      operator: Operator.LTE,
      value: 18,
    });
  });
});

describe('Condition — Null and Boolean Conditions', () => {
  it('should create NULL condition', () => {
    expect(isNull('name')).toEqual({
      field: 'name',
      operator: Operator.NULL,
    });
  });

  it('should create NOT_NULL condition', () => {
    expect(notNull('name')).toEqual({
      field: 'name',
      operator: Operator.NOT_NULL,
    });
  });

  it('should create TRUE condition', () => {
    expect(isTrue('isActive')).toEqual({
      field: 'isActive',
      operator: Operator.TRUE,
    });
  });

  it('should create FALSE condition', () => {
    expect(isFalse('isActive')).toEqual({
      field: 'isActive',
      operator: Operator.FALSE,
    });
  });

  it('should create EXISTS condition with default value', () => {
    expect(exists('name')).toEqual({
      field: 'name',
      operator: Operator.EXISTS,
      value: true,
    });
  });

  it('should create EXISTS condition with custom value', () => {
    expect(exists('name', false)).toEqual({
      field: 'name',
      operator: Operator.EXISTS,
      value: false,
    });
  });
});
