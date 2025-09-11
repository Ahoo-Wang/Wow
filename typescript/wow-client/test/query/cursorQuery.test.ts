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
  cursorCondition,
  cursorQuery,
  cursorSort,
  CURSOR_ID_START,
} from '../../src';
import { SortDirection } from '../../src';
import { and, eq, gt, lt } from '../../src';
import { listQuery } from '../../src';

describe('cursorQuery', () => {
  it('should create cursor condition with ascending direction', () => {
    const field = 'id';
    const cursorId = 'cursor123';
    const direction = SortDirection.ASC;

    const result = cursorCondition({ field, cursorId, direction });

    expect(result).toEqual(gt(field, cursorId));
  });

  it('should create cursor condition with descending direction', () => {
    const field = 'id';
    const cursorId = 'cursor123';
    const direction = SortDirection.DESC;

    const result = cursorCondition({ field, cursorId, direction });

    expect(result).toEqual(lt(field, cursorId));
  });

  it('should create cursor condition with default values', () => {
    const field = 'id';

    const result = cursorCondition({ field });

    expect(result).toEqual(lt(field, CURSOR_ID_START));
  });

  it('should create cursor sort configuration', () => {
    const field = 'id';
    const direction = SortDirection.ASC;

    const result = cursorSort({ field, direction });

    expect(result).toEqual({
      field,
      direction,
    });
  });

  it('should create cursor sort with default direction', () => {
    const field = 'id';

    const result = cursorSort({ field });

    expect(result).toEqual({
      field,
      direction: SortDirection.DESC,
    });
  });

  it('should enhance base query with cursor parameters', () => {
    const options = {
      field: 'id',
      cursorId: 'cursor123',
      direction: SortDirection.ASC,
      query: listQuery({
        condition: eq('status', 'active'),
        limit: 10,
      }),
    };

    const result = cursorQuery(options);

    expect(result.condition).toBeDefined();
    expect(result.sort).toBeDefined();
    expect(result.sort).toHaveLength(1);
    expect(result.sort![0]).toEqual({
      field: options.field,
      direction: options.direction,
    });
    expect(result.limit).toBe(10);
  });

  it('should combine cursor condition with existing condition using AND operator', () => {
    const options = {
      field: 'id',
      cursorId: 'cursor123',
      direction: SortDirection.ASC,
      query: listQuery({
        condition: eq('status', 'active'),
        limit: 10,
      }),
    };

    const result = cursorQuery(options);
    const expectedCondition = and(
      cursorCondition(options),
      eq('status', 'active'),
    );

    expect(result.condition).toEqual(expectedCondition);
  });
});