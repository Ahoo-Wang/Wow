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
import { readPath } from '../src/record/path';

describe('readPath', () => {
  const row = {
    id: 'r-1',
    state: { owner: { name: 'Ann' }, tags: ['a', 'b'], empty: null },
    items: [{ sku: 's-1' }, { sku: 's-2' }],
  };

  it('reads a top-level and a nested field', () => {
    expect(readPath(row, 'id')).toBe('r-1');
    expect(readPath(row, 'state.owner.name')).toBe('Ann');
  });

  it('reads into arrays by a non-negative integer index', () => {
    expect(readPath(row, 'items.1.sku')).toBe('s-2');
    expect(readPath(row, 'state.tags.0')).toBe('a');
    expect(readPath(row, 'items.-1.sku')).toBeUndefined();
    expect(readPath(row, 'items.1.5')).toBeUndefined();
    expect(readPath(row, 'items.first')).toBeUndefined();
  });

  it('answers undefined where the path leads nowhere', () => {
    expect(readPath(row, 'missing.field')).toBeUndefined();
    expect(readPath(row, 'state.empty')).toBeUndefined();
    expect(readPath(row, 'state.empty.deeper')).toBeUndefined();
    expect(readPath(row, 'id.length')).toBeUndefined();
    expect(readPath(null, 'id')).toBeUndefined();
    expect(readPath(undefined, 'id')).toBeUndefined();
  });

  it('reads the row itself for an empty path', () => {
    expect(readPath(row, '')).toBe(row);
    expect(readPath(row, '..')).toBe(row);
  });
});
