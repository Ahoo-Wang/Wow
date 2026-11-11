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

import { FilterOperator } from '@ahoo-wang/wow-client/dsl';
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { FilterLeaf } from '../src/model/index.js';
import { builtinFieldKinds, issue } from '../src/filter/index.js';
import { treeController } from '../src/react/filterTree.js';
import type { GroupBlock } from '../src/ui/filter/GroupBlock.js';
import { NestedPredicate } from '../src/ui/filter/NestedPredicate.js';

type GroupProps = Parameters<typeof GroupBlock>[0];

/** A group block that draws nothing and keeps what it was handed. */
function recordingGroup(): { Group: typeof GroupBlock; seen: GroupProps[] } {
  const seen: GroupProps[] = [];
  const Group: typeof GroupBlock = props => {
    seen.push(props);
    return <></>;
  };
  return { Group, seen };
}

describe('NestedPredicate', () => {
  it('draws a condition on a field the view no longer has as an empty group under its own name', () => {
    // A stored element match on a field the definition has since dropped,
    // whose value never became a tree: nothing to list, nothing to read.
    const leaf: FilterLeaf = {
      field: 'gone',
      operator: FilterOperator.ELEMENT_MATCH,
      value: null,
    };
    const inside = issue('filter.value.required', ['children', 0, 'value']);
    const elsewhere = issue('filter.value.required', ['children', 1]);
    const outer = treeController({
      tree: { op: 'and', children: [leaf] },
      fields: [],
      kinds: builtinFieldKinds,
      issues: [inside, elsewhere],
      onChange: vi.fn(),
    });
    const { Group, seen } = recordingGroup();

    render(
      <NestedPredicate filter={outer} leaf={leaf} path={[0]} Group={Group} />,
    );

    const [props] = seen;
    expect(props?.filter.tree).toEqual({ op: 'and', children: [] });
    expect(props?.filter.fields).toEqual([]);
    expect(props?.filter.fieldGroups).toEqual([]);
    expect(props?.filter).not.toHaveProperty('optionSource');
    // Named by what the leaf says, the only name it has left.
    expect(props?.scope).toBe('gone');
    // Only the findings under this leaf, addressed against its own tree.
    expect(props?.filter.issues).toEqual([{ ...inside, path: ['value'] }]);
  });
});
