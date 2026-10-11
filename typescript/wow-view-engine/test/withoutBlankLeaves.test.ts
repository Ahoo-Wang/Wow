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
import { builtinFieldKinds, type FilterTree } from '../src/index.js';
import { withoutBlankLeaves } from '../src/filter/index.js';
import { ordersDefinition } from './fixtures.js';

const fields = ordersDefinition().fields;
const strip = (tree: FilterTree) =>
  withoutBlankLeaves(fields, tree, builtinFieldKinds);

describe('withoutBlankLeaves', () => {
  it('takes out blank conditions and the groups that leaves empty', () => {
    expect(
      strip({
        op: 'and',
        children: [
          { field: 'warehouse', operator: 'EQ', value: '' },
          { field: 'status', operator: 'EQ', value: 'open' },
          {
            op: 'nor',
            children: [{ field: 'warehouse', operator: 'IN', value: [] }],
          },
        ],
      }),
    ).toEqual({
      op: 'and',
      children: [{ field: 'status', operator: 'EQ', value: 'open' }],
    });
  });

  it('keeps what says something, and the tree itself when nothing is blank', () => {
    const tree: FilterTree = {
      op: 'and',
      children: [
        // An operator that takes no value is the whole condition.
        { field: 'warehouse', operator: 'IS_NULL', value: null },
        // A field this definition does not know is not this one's to judge.
        { field: 'gone', operator: 'EQ', value: '' },
      ],
    };
    expect(strip(tree)).toBe(tree);
  });
});
