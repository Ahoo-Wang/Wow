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
  ConditionOptionKey,
  dateOptions,
  DeletionState,
  ignoreCaseOptions,
  Operator,
  raw,
} from '../../src';

// Operator-condition tests split by category:
//   ./condition/logical.test.ts      — AND/OR/NOR, ID, Tenant/Owner, Deletion
//   ./condition/comparison.test.ts   — EQ/NE/GT…/NULL/EXISTS
//   ./condition/string-array.test.ts — CONTAINS/MATCH/IN/BETWEEN
//   ./condition/date.test.ts         — TODAY/THIS_WEEK/RECENT_DAYS…
// This file keeps utility/enum tests.

describe('Condition', () => {
  describe('DeletionState', () => {
    it('should have ACTIVE state', () => {
      expect(DeletionState.ACTIVE).toBe('ACTIVE');
    });

    it('should have DELETED state', () => {
      expect(DeletionState.DELETED).toBe('DELETED');
    });
  });

  describe('ConditionOptionKey', () => {
    it('should have IGNORE_CASE key', () => {
      expect(ConditionOptionKey.IGNORE_CASE_OPTION_KEY).toBe('ignoreCase');
    });

    it('should have DATE_PATTERN key', () => {
      expect(ConditionOptionKey.DATE_PATTERN_OPTION_KEY).toBe('datePattern');
    });

    it('should have ZONE_ID key', () => {
      expect(ConditionOptionKey.ZONE_ID_OPTION_KEY).toBe('zoneId');
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
    });

    describe('dateOptions', () => {
      it('should return undefined when both datePattern and zoneId are undefined', () => {
        expect(dateOptions(undefined, undefined)).toBeUndefined();
      });

      it('should return options with datePattern only', () => {
        expect(dateOptions('yyyy-MM-dd', undefined)).toEqual({
          datePattern: 'yyyy-MM-dd',
        });
      });

      it('should return options with zoneId only', () => {
        expect(dateOptions(undefined, 'UTC')).toEqual({
          zoneId: 'UTC',
        });
      });

      it('should return options with both datePattern and zoneId', () => {
        expect(dateOptions('yyyy-MM-dd', 'UTC')).toEqual({
          datePattern: 'yyyy-MM-dd',
          zoneId: 'UTC',
        });
      });
    });

    describe('Raw Condition', () => {
      it('should create RAW condition', () => {
        const rawValue = { custom: 'query' };
        expect(raw(rawValue)).toEqual({
          operator: Operator.RAW,
          value: rawValue,
        });
      });
    });
  });
});
