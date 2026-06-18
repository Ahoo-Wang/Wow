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
  active,
  aggregateId,
  aggregateIds,
  all,
  and,
  deleted,
  DeletionState,
  eq,
  gt,
  id,
  ids,
  nor,
  Operator,
  or,
  ownerId,
  spaceId,
  startsWith,
  tenantId,
} from '../../../src';

describe('Condition — Logical Operators', () => {
  it('should create AND condition', () => {
    const condition1: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const condition2: Condition = {
      field: 'age',
      operator: Operator.GT,
      value: 18,
    };
    const result = and(condition1, condition2);

    expect(result).toEqual({
      operator: Operator.AND,
      children: [condition1, condition2],
    });
  });

  it('should create AND condition with undefined and null condition', () => {
    const condition1: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const condition2: Condition = {
      field: 'age',
      operator: Operator.GT,
      value: 18,
    };
    const result = and(condition1, undefined, condition2, null);

    expect(result).toEqual({
      operator: Operator.AND,
      children: [condition1, condition2],
    });
  });

  it('should return ALL when AND conditions are empty after filtering', () => {
    const result = and(all(), undefined, null);
    expect(result).toEqual({
      operator: Operator.ALL,
    });
  });

  it('should create AND condition with no arguments and return ALL condition', () => {
    const result = and();
    expect(result).toEqual({
      operator: Operator.ALL,
    });
  });

  it('should create AND condition with one argument and return that argument', () => {
    const condition: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const result = and(condition);
    expect(result).toBe(condition);
  });

  it('should optimize nested AND conditions', () => {
    const condition1: Condition = eq('name', 'test');
    const condition2: Condition = gt('age', 18);
    const condition3: Condition = active();
    const condition4: Condition = startsWith('address', 'ShangHai');
    const nestedAnd1 = and(condition1, condition2);
    const nestedAnd2 = and(condition3, condition4);
    const result = and(nestedAnd1, nestedAnd2, all());

    expect(result).toEqual({
      operator: Operator.AND,
      children: [condition1, condition2, condition3, condition4],
    });
  });

  it('should create OR condition', () => {
    const condition1: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const condition2: Condition = {
      field: 'age',
      operator: Operator.GT,
      value: 18,
    };
    const result = or(condition1, condition2);

    expect(result).toEqual({
      operator: Operator.OR,
      children: [condition1, condition2],
    });
  });

  it('should create OR condition with undefined and null condition', () => {
    const condition1: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const condition2: Condition = {
      field: 'age',
      operator: Operator.GT,
      value: 18,
    };
    const result = or(condition1, undefined, condition2, null);

    expect(result).toEqual({
      operator: Operator.OR,
      children: [condition1, condition2],
    });
  });

  it('should create OR condition with no arguments and return ALL condition', () => {
    const result = or();
    expect(result).toEqual({
      operator: Operator.ALL,
    });
  });

  it('should create NOR condition', () => {
    const condition1: Condition = {
      field: 'name',
      operator: Operator.EQ,
      value: 'test',
    };
    const condition2: Condition = {
      field: 'age',
      operator: Operator.GT,
      value: 18,
    };
    const result = nor(condition1, condition2);

    expect(result).toEqual({
      operator: Operator.NOR,
      children: [condition1, condition2],
    });
  });

  it('should create NOR condition with no arguments and return ALL condition', () => {
    const result = nor();
    expect(result).toEqual({
      operator: Operator.ALL,
    });
  });
});

describe('Condition — ID Conditions', () => {
  it('should create ID condition', () => {
    const result = id('123');
    expect(result).toEqual({
      operator: Operator.ID,
      value: '123',
    });
  });

  it('should create IDS condition', () => {
    const result = ids(['123', '456']);
    expect(result).toEqual({
      operator: Operator.IDS,
      value: ['123', '456'],
    });
  });

  it('should create aggregate ID condition', () => {
    const result = aggregateId('agg123');
    expect(result).toEqual({
      operator: Operator.AGGREGATE_ID,
      value: 'agg123',
    });
  });

  it('should create aggregate IDS condition', () => {
    const result = aggregateIds(['agg123', 'agg456']);
    expect(result).toEqual({
      operator: Operator.AGGREGATE_IDS,
      value: ['agg123', 'agg456'],
    });
  });
});

describe('Condition — Tenant and Owner Conditions', () => {
  it('should create tenant ID condition', () => {
    const result = tenantId('tenant123');
    expect(result).toEqual({
      operator: Operator.TENANT_ID,
      value: 'tenant123',
    });
  });

  it('should create owner ID condition', () => {
    const result = ownerId('owner123');
    expect(result).toEqual({
      operator: Operator.OWNER_ID,
      value: 'owner123',
    });
  });

  it('should create space ID condition', () => {
    const result = spaceId('space123');
    expect(result).toEqual({
      operator: Operator.SPACE_ID,
      value: 'space123',
    });
  });
});

describe('Condition — Deletion State Conditions', () => {
  it('should create deleted condition', () => {
    const result = deleted(DeletionState.ACTIVE);
    expect(result).toEqual({
      operator: Operator.DELETED,
      value: DeletionState.ACTIVE,
    });
  });

  it('should create active condition', () => {
    const result = active();
    expect(result).toEqual({
      operator: Operator.DELETED,
      value: DeletionState.ACTIVE,
    });
  });

  it('should create all condition', () => {
    const result = all();
    expect(result).toEqual({
      operator: Operator.ALL,
    });
  });
});
