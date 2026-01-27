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
  active,
  aggregateId,
  aggregateIds,
  all,
  allIn,
  and,
  beforeToday,
  between,
  Condition,
  ConditionOptionKey,
  contains,
  dateOptions,
  deleted,
  DeletionState,
  earlierDays,
  elemMatch,
  endsWith,
  eq,
  exists,
  gt,
  gte,
  id,
  ids,
  ignoreCaseOptions,
  isFalse,
  isIn,
  isNull,
  isTrue,
  lastMonth,
  lastWeek,
  lt,
  lte,
  ne,
  nextWeek,
  nor,
  notIn,
  notNull,
  Operator,
  or,
  ownerId,
  raw,
  recentDays,
  spaceId,
  startsWith,
  tenantId,
  thisMonth,
  thisWeek,
  today,
  tomorrow,
} from '../../src';

describe('Condition', () => {
  describe('DeletionState', () => {
    it('should have correct deletion state values', () => {
      expect(DeletionState.ACTIVE).toBe('ACTIVE');
      expect(DeletionState.DELETED).toBe('DELETED');
      expect(DeletionState.ALL).toBe('ALL');
    });
  });

  describe('ConditionOptionKey', () => {
    it('should have correct condition option key values', () => {
      expect(ConditionOptionKey.IGNORE_CASE_OPTION_KEY).toBe('ignoreCase');
      expect(ConditionOptionKey.ZONE_ID_OPTION_KEY).toBe('zoneId');
      expect(ConditionOptionKey.DATE_PATTERN_OPTION_KEY).toBe('datePattern');
    });
  });

  describe('Conditions Utility Methods', () => {
    describe('ignoreCaseOptions', () => {
      it('should return undefined when ignoreCase is undefined', () => {
        expect(ignoreCaseOptions(undefined)).toBeUndefined();
      });

      it('should return options object when ignoreCase is defined', () => {
        expect(ignoreCaseOptions(true)).toEqual({
          ignoreCase: true,
        });
        expect(ignoreCaseOptions(false)).toEqual({
          ignoreCase: false,
        });
      });

      it('should return options object when ignoreCase is defined', () => {
        expect(ignoreCaseOptions(true)).toEqual({
          ignoreCase: true,
        });
        expect(ignoreCaseOptions(false)).toEqual({
          ignoreCase: false,
        });
      });
    });

    describe('dateOptions', () => {
      it('should return undefined when both datePattern and zoneId are undefined', () => {
        expect(dateOptions(undefined, undefined)).toBeUndefined();
      });

      it('should return options with datePattern when only datePattern is defined', () => {
        expect(dateOptions('yyyy-MM-dd', undefined)).toEqual({
          datePattern: 'yyyy-MM-dd',
        });
      });

      it('should return options with zoneId when only zoneId is defined', () => {
        expect(dateOptions(undefined, 'UTC')).toEqual({
          zoneId: 'UTC',
        });
      });

      it('should return options with both datePattern and zoneId when both are defined', () => {
        expect(dateOptions('yyyy-MM-dd', 'UTC')).toEqual({
          datePattern: 'yyyy-MM-dd',
          zoneId: 'UTC',
        });
      });
    });

    describe('Logical Operators', () => {
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

    describe('ID Conditions', () => {
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

    describe('Tenant and Owner Conditions', () => {
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

    describe('Deletion State Conditions', () => {
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

    describe('Comparison Conditions', () => {
      it('should create EQ condition', () => {
        const result = eq('name', 'test');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.EQ,
          value: 'test',
        });
      });

      it('should create NE condition', () => {
        const result = ne('name', 'test');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.NE,
          value: 'test',
        });
      });

      it('should create GT condition', () => {
        const result = gt('age', 18);
        expect(result).toEqual({
          field: 'age',
          operator: Operator.GT,
          value: 18,
        });
      });

      it('should create LT condition', () => {
        const result = lt('age', 18);
        expect(result).toEqual({
          field: 'age',
          operator: Operator.LT,
          value: 18,
        });
      });

      it('should create GTE condition', () => {
        const result = gte('age', 18);
        expect(result).toEqual({
          field: 'age',
          operator: Operator.GTE,
          value: 18,
        });
      });

      it('should create LTE condition', () => {
        const result = lte('age', 18);
        expect(result).toEqual({
          field: 'age',
          operator: Operator.LTE,
          value: 18,
        });
      });
    });

    describe('String Matching Conditions', () => {
      it('should create CONTAINS condition without ignoreCase', () => {
        const result = contains('name', 'test');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.CONTAINS,
          value: 'test',
          options: undefined,
        });
      });

      it('should create CONTAINS condition with ignoreCase true', () => {
        const result = contains('name', 'test', true);
        expect(result).toEqual({
          field: 'name',
          operator: Operator.CONTAINS,
          value: 'test',
          options: { ignoreCase: true },
        });
      });

      it('should create CONTAINS condition with ignoreCase false', () => {
        const result = contains('name', 'test', false);
        expect(result).toEqual({
          field: 'name',
          operator: Operator.CONTAINS,
          value: 'test',
          options: { ignoreCase: false },
        });
      });

      it('should create STARTS_WITH condition', () => {
        const result = startsWith('name', 'test', true);
        expect(result).toEqual({
          field: 'name',
          operator: Operator.STARTS_WITH,
          value: 'test',
          options: { ignoreCase: true },
        });
      });

      it('should create ENDS_WITH condition', () => {
        const result = endsWith('name', 'test', false);
        expect(result).toEqual({
          field: 'name',
          operator: Operator.ENDS_WITH,
          value: 'test',
          options: { ignoreCase: false },
        });
      });
    });

    describe('Array Conditions', () => {
      it('should create IN condition', () => {
        const result = isIn('status', 'active', 'pending');
        expect(result).toEqual({
          field: 'status',
          operator: Operator.IN,
          value: ['active', 'pending'],
        });
      });

      it('should create NOT_IN condition', () => {
        const result = notIn('status', 'deleted', 'banned');
        expect(result).toEqual({
          field: 'status',
          operator: Operator.NOT_IN,
          value: ['deleted', 'banned'],
        });
      });

      it('should create BETWEEN condition', () => {
        const result = between('age', 18, 65);
        expect(result).toEqual({
          field: 'age',
          operator: Operator.BETWEEN,
          value: [18, 65],
        });
      });

      it('should create ALL_IN condition', () => {
        const result = allIn('tags', 'tag1', 'tag2');
        expect(result).toEqual({
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
        const result = elemMatch('items', childCondition);
        expect(result).toEqual({
          field: 'items',
          operator: Operator.ELEM_MATCH,
          children: [childCondition],
        });
      });
    });

    describe('Null and Boolean Conditions', () => {
      it('should create NULL condition', () => {
        const result = isNull('name');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.NULL,
        });
      });

      it('should create NOT_NULL condition', () => {
        const result = notNull('name');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.NOT_NULL,
        });
      });

      it('should create TRUE condition', () => {
        const result = isTrue('isActive');
        expect(result).toEqual({
          field: 'isActive',
          operator: Operator.TRUE,
        });
      });

      it('should create FALSE condition', () => {
        const result = isFalse('isActive');
        expect(result).toEqual({
          field: 'isActive',
          operator: Operator.FALSE,
        });
      });

      it('should create EXISTS condition with default value', () => {
        const result = exists('name');
        expect(result).toEqual({
          field: 'name',
          operator: Operator.EXISTS,
          value: true,
        });
      });

      it('should create EXISTS condition with custom value', () => {
        const result = exists('name', false);
        expect(result).toEqual({
          field: 'name',
          operator: Operator.EXISTS,
          value: false,
        });
      });
    });

    describe('Date Conditions', () => {
      it('should create TODAY condition without options', () => {
        const result = today('createdAt');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.TODAY,
          options: undefined,
        });
      });

      it('should create TODAY condition with date pattern', () => {
        const result = today('createdAt', 'yyyy-MM-dd');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.TODAY,
          options: { datePattern: 'yyyy-MM-dd' },
        });
      });

      it('should create TODAY condition with zone ID', () => {
        const result = today('createdAt', undefined, 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.TODAY,
          options: { zoneId: 'UTC' },
        });
      });

      it('should create TODAY condition with both options', () => {
        const result = today('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.TODAY,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create BEFORE_TODAY condition', () => {
        const result = beforeToday(
          'createdAt',
          '2023-01-01',
          'yyyy-MM-dd',
          'UTC',
        );
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.BEFORE_TODAY,
          value: '2023-01-01',
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create TOMORROW condition', () => {
        const result = tomorrow('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.TOMORROW,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create THIS_WEEK condition', () => {
        const result = thisWeek('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.THIS_WEEK,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create NEXT_WEEK condition', () => {
        const result = nextWeek('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.NEXT_WEEK,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create LAST_WEEK condition', () => {
        const result = lastWeek('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.LAST_WEEK,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create THIS_MONTH condition', () => {
        const result = thisMonth('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.THIS_MONTH,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create LAST_MONTH condition', () => {
        const result = lastMonth('createdAt', 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.LAST_MONTH,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create RECENT_DAYS condition', () => {
        const result = recentDays('createdAt', 7, 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.RECENT_DAYS,
          value: 7,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });

      it('should create EARLIER_DAYS condition', () => {
        const result = earlierDays('createdAt', 30, 'yyyy-MM-dd', 'UTC');
        expect(result).toEqual({
          field: 'createdAt',
          operator: Operator.EARLIER_DAYS,
          value: 30,
          options: { datePattern: 'yyyy-MM-dd', zoneId: 'UTC' },
        });
      });
    });

    describe('Raw Condition', () => {
      it('should create RAW condition', () => {
        const rawValue = { custom: 'query' };
        const result = raw(rawValue);
        expect(result).toEqual({
          operator: Operator.RAW,
          value: rawValue,
        });
      });
    });
  });
});
