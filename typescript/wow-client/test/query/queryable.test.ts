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

import { describe, it, expect } from 'vitest';
import { all, asc, eq } from '../../src';
import { singleQuery } from '../../src';
import { FieldSort } from '../../src';
import { Projection } from '../../src';

describe('queryable', () => {
  describe('singleQuery', () => {
    it('should create a SingleQuery with default condition when no parameters are provided', () => {
      const result = singleQuery({} as any);

      expect(result).toEqual({
        condition: all(),
      });
    });

    it('should create a SingleQuery with provided condition', () => {
      const condition = eq('name', 'test');
      const result = singleQuery({ condition });

      expect(result).toEqual({
        condition,
      });
    });

    it('should create a SingleQuery with provided projection', () => {
      const projection: Projection = { include: ['field1', 'field2'] };
      const result = singleQuery({ projection });

      expect(result).toEqual({
        condition: all(),
        projection,
      });
    });

    it('should create a SingleQuery with provided sort', () => {
      const sort: FieldSort[] = [asc('name')];
      const result = singleQuery({ sort });

      expect(result).toEqual({
        condition: all(),
        sort,
      });
    });

    it('should create a SingleQuery with all provided parameters', () => {
      const condition = eq('name', 'test');
      const projection: Projection = { include: ['field1', 'field2'] };
      const sort: FieldSort[] = [asc('name')];

      const result = singleQuery({ condition, projection, sort });

      expect(result).toEqual({
        condition,
        projection,
        sort,
      });
    });
  });
});