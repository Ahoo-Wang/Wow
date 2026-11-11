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

/**
 * A condition on the fields of an array's elements, edited as the group it
 * holds (`NestedPredicate`): the leaf's value read as a tree, and the
 * findings under the leaf addressed against that tree. `ConditionPill` draws
 * it as the body of an element-match block.
 */

import type {
  FieldOption,
  FilterLeaf,
  FilterTree,
  Issue,
} from '../../model/index.js';
import {
  elementFields,
  variantGroups,
  writeValue,
  type FilterPath,
} from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { treeController } from '../../react/filterTree.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import type { GroupBlock } from './GroupBlock.js';

/**
 * A condition whose value is itself a condition, on the fields of an array's
 * elements.
 *
 * It renders the same group the outer filter renders, because it is the same
 * thing: `treeController` writes each change straight back into the leaf that
 * carries the tree. Two conditions written side by side outside this block
 * are satisfied by any entries, one each; inside it they must be satisfied by
 * the same entry.
 */
export function NestedPredicate({
  filter,
  leaf,
  path,
  disabled,
  optionsFor,
  Group,
}: {
  filter: FilterTreeController;
  leaf: FilterLeaf;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
  Group: typeof GroupBlock;
}) {
  const messages = useViewMessages();
  const field = filter.fields.find(entry => entry.name === leaf.field);
  // Built on every render rather than memoised: `filter` and `path` are fresh
  // each time, so a memo keyed on them memoised nothing and only hid that a
  // controller is a plain object over the values already in hand.
  const nested = treeController({
    tree: asTree(leaf.value),
    fields: field ? elementFields(field) : [],
    // A payload's fields listed by the event type that has them (#3519).
    fieldGroups: field ? variantGroups(field) : [],
    kinds: filter.kinds,
    // The kind reports a predicate's findings under the leaf that holds it,
    // so they are rebased here to address the nested tree instead.
    issues: rebase(filter.issues, path),
    onChange: tree => filter.updateLeaf(path, { value: writeValue(tree) }),
    ...(filter.optionSource ? { optionSource: filter.optionSource } : {}),
    durations: false,
  });

  return (
    <div className="fve:min-w-0">
      <Group
        filter={nested}
        group={nested.tree}
        path={[]}
        disabled={disabled}
        optionsFor={optionsFor}
        scope={messages.say(field?.label ?? leaf.field)}
      />
    </div>
  );
}

/** A leaf's value read as the tree it holds; an unfinished one is empty. */
function asTree(value: unknown): FilterTree {
  return value !== null &&
    typeof value === 'object' &&
    Array.isArray((value as FilterTree).children)
    ? (value as FilterTree)
    : { op: 'and', children: [] };
}

/** Findings under one leaf, addressed against the tree that leaf carries. */
function rebase(issues: readonly Issue[], path: FilterPath): Issue[] {
  const prefix = path.flatMap(index => ['children', index]);
  return issues.flatMap(found => {
    const own = found.path.slice(0, prefix.length);
    if (own.join('.') !== prefix.join('.')) return [];
    return [{ ...found, path: found.path.slice(prefix.length) }];
  });
}
