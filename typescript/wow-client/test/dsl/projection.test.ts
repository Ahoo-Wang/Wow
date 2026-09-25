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
import { projection } from '../../src';

describe('projection', () => {
  it('should create projection object with no parameters', () => {
    const result = projection();
    expect(result).toEqual({
      include: undefined,
      exclude: undefined,
    });
  });

  it('should create projection object with include fields', () => {
    const includeFields = ['field1', 'field2'];
    const result = projection({ include: includeFields });
    expect(result).toEqual({
      include: includeFields,
      exclude: undefined,
    });
  });

  it('should create projection object with exclude fields', () => {
    const excludeFields = ['field1', 'field2'];
    const result = projection({ exclude: excludeFields });
    expect(result).toEqual({
      include: undefined,
      exclude: excludeFields,
    });
  });

  it('should create projection object with both include and exclude fields', () => {
    const includeFields = ['field1', 'field2'];
    const excludeFields = ['field3', 'field4'];
    const result = projection({
      include: includeFields,
      exclude: excludeFields,
    });
    expect(result).toEqual({
      include: includeFields,
      exclude: excludeFields,
    });
  });
});

describe('field paths', () => {
  it.each(['include', 'exclude'] as const)('refuses a bad %s path', key => {
    expect(() => projection({ [key]: ['9 not a path'] })).toThrow(
      'Query field is invalid',
    );
  });

  it('keeps both keys whether or not they were given', () => {
    // The shape this factory returns is its own contract; validating a path
    // is no reason to change it.
    expect(Object.keys(projection())).toEqual(['include', 'exclude']);
    expect(Object.keys(projection({ include: ['a'] }))).toEqual([
      'include',
      'exclude',
    ]);
  });
});
