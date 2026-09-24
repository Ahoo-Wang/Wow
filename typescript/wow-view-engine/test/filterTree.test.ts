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
 * The tree an editor holds, edited as values: reading a node at a path,
 * inserting, updating and removing one, merging two trees, and the equality
 * that tells "changed but not applied" from "the same condition again".
 */

import { FilterOperator } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  conditionOf,
  conditions,
  emptyFilter,
  isEmptyFilter,
  insertAt,
  isNegation,
  isSimpleTree,
  mergeFilters,
  negateAt,
  nodeAt,
  removeAt,
  removeConditionAt,
  sameFilterNode,
  sameFilterTree,
  updateAt,
  validateFilter,
  type FilterLeaf,
  type FilterTree,
} from '../src/index.js';
import { andTree as tree, filterFields as fields } from './fixtures/filter.js';

describe('isSimpleTree', () => {
  it('admits one AND group of leaves and nothing else', () => {
    expect(
      isSimpleTree(tree({ field: 'id', operator: 'EQ', value: 'A' })),
    ).toBe(true);
    expect(isSimpleTree({ op: 'or', children: [] })).toBe(false);
    expect(isSimpleTree(tree(tree()))).toBe(false);
  });

  /**
   * D18-7: a condition is negated by wrapping it in a `nor` group of its
   * own, which the simple editor draws as the pill with its switch pressed.
   * A `nor` over two conditions, or over a group, is still the advanced
   * editor's.
   */
  it('admits a negated condition, which is a nor group of one leaf', () => {
    const leaf: FilterLeaf = { field: 'id', operator: 'EQ', value: 'A' };
    const negated: FilterTree = { op: 'nor', children: [leaf] };

    expect(isNegation(negated)).toBe(true);
    expect(isNegation({ op: 'nor', children: [leaf, leaf] })).toBe(false);
    expect(isNegation({ op: 'nor', children: [tree()] })).toBe(false);
    expect(isNegation({ op: 'and', children: [leaf] })).toBe(false);

    expect(isSimpleTree(tree(negated, leaf))).toBe(true);
    expect(isSimpleTree(tree({ op: 'nor', children: [leaf, leaf] }))).toBe(
      false,
    );
  });

  it('negates a condition in place and un-negates it by the same path', () => {
    const leaf: FilterLeaf = { field: 'id', operator: 'EQ', value: 'A' };
    const other: FilterLeaf = { field: 'name', operator: 'EQ', value: 'B' };

    const wrapped = negateAt(tree(leaf, other), [0]);
    expect(wrapped.children).toEqual([{ op: 'nor', children: [leaf] }, other]);
    // The path the pill now holds is the leaf's inside its wrapper.
    expect(negateAt(wrapped, [0, 0])).toEqual(tree(leaf, other));

    // Nothing to negate: a group, a path leading nowhere, or the root.
    expect(negateAt(tree(tree()), [0])).toEqual(tree(tree()));
    expect(negateAt(tree(leaf), [4])).toEqual(tree(leaf));
    expect(negateAt(tree(leaf), [])).toEqual(tree(leaf));
  });
});

/**
 * A3: the wrapper D18-7 stores a negation as is the kernel's to know. The
 * picker, the pill and the condition strip ask what a node says and where
 * the condition sits, and never read `children[0]` themselves.
 */
describe('reading a condition', () => {
  const leaf: FilterLeaf = { field: 'id', operator: 'EQ', value: 'A' };
  const other: FilterLeaf = { field: 'amount', operator: 'GT', value: 1 };
  const negated: FilterTree = { op: 'nor', children: [leaf] };

  it('answers the condition a node asks, wrapper or no wrapper', () => {
    expect(conditionOf(leaf)).toBe(leaf);
    expect(conditionOf(negated)).toBe(leaf);

    // Not one condition: a group of several, an empty group, a `nor` over a
    // group, and everything a store may hand over that is no node at all.
    expect(conditionOf(tree(leaf, other))).toBeNull();
    expect(conditionOf(tree())).toBeNull();
    expect(conditionOf({ op: 'nor', children: [tree(leaf)] })).toBeNull();
    expect(conditionOf(null)).toBeNull();
    expect(conditionOf(7)).toBeNull();
  });

  it("gives each condition of a group with the leaf's own path", () => {
    const group = tree(leaf, negated, other);

    expect(conditions(group)).toEqual([
      { leaf, index: 0, path: [0], negated: false },
      { leaf, index: 1, path: [1, 0], negated: true },
      { leaf: other, index: 2, path: [2], negated: false },
    ]);

    // `at` is the group's own path, so the paths are the tree's.
    expect(conditions(group, [3, 1]).map(found => found.path)).toEqual([
      [3, 1, 0],
      [3, 1, 1, 0],
      [3, 1, 2],
    ]);
  });

  it('leaves out what is not one condition, indexes saying where', () => {
    const group = tree(tree(leaf, other), null as never, other);

    expect(conditions(group)).toEqual([
      { leaf: other, index: 2, path: [2], negated: false },
    ]);
    // Anything but a group asks nothing.
    expect(conditions(leaf)).toEqual([]);
    expect(conditions(undefined)).toEqual([]);
  });

  it('removes a negated condition by its leaf path, wrapper and all', () => {
    expect(removeConditionAt(tree(negated, other), [0, 0])).toEqual(
      tree(other),
    );
    // A condition on its own, and a nested group, go exactly as named.
    expect(removeConditionAt(tree(leaf, other), [0])).toEqual(tree(other));
    expect(removeConditionAt(tree(tree(leaf), other), [0])).toEqual(
      tree(other),
    );
    // A leaf inside a group that is not a negation keeps that group.
    expect(removeConditionAt(tree(tree(leaf, other)), [0, 0])).toEqual(
      tree(tree(other)),
    );
    // The root is no wrapper to unwrap: a root `nor` of one keeps its shape
    // and loses the leaf, which is what the advanced editor draws.
    expect(removeConditionAt({ op: 'nor', children: [leaf] }, [0])).toEqual({
      op: 'nor',
      children: [],
    });
  });
});

describe('tree editing', () => {
  const leaf = (field: string, value: string): FilterLeaf => ({
    field,
    operator: `${FilterOperator.EQ}`,
    value,
  });

  const nested: FilterTree = {
    op: 'and',
    children: [
      leaf('id', 'a'),
      { op: 'or', children: [leaf('id', 'b'), leaf('id', 'c')] },
    ],
  };

  it('addresses a node by its path', () => {
    expect(nodeAt(nested, [])).toBe(nested);
    expect(nodeAt(nested, [0])).toMatchObject({ value: 'a' });
    expect(nodeAt(nested, [1, 1])).toMatchObject({ value: 'c' });
    expect(nodeAt(nested, [9])).toBeNull();
    // A leaf has no children to descend into.
    expect(nodeAt(nested, [0, 0])).toBeNull();
  });

  it('replaces a node deep in the tree without touching its siblings', () => {
    const next = updateAt(nested, [1, 0], node => ({
      ...(node as FilterLeaf),
      value: 'changed',
    }));

    expect(nodeAt(next, [1, 0])).toMatchObject({ value: 'changed' });
    expect(nodeAt(next, [1, 1])).toBe(nodeAt(nested, [1, 1]));
    expect(nodeAt(next, [0])).toBe(nodeAt(nested, [0]));
    expect(nested).toEqual(nested);
  });

  it('leaves the tree alone for a path that leads nowhere', () => {
    expect(updateAt(nested, [], () => null)).toBe(nested);
    expect(updateAt(nested, [9], () => null)).toEqual(nested);
    // Descending through a leaf is not a path.
    expect(updateAt(nested, [0, 0], () => null)).toEqual(nested);
  });

  it('removes a node at any depth', () => {
    expect(removeAt(nested, [1, 0])).toMatchObject({
      children: [{ value: 'a' }, { children: [{ value: 'c' }] }],
    });
    expect(removeAt(nested, [0]).children).toHaveLength(1);
  });

  it('appends to the root or to a nested group', () => {
    expect(insertAt(nested, [], leaf('id', 'd')).children).toHaveLength(3);
    expect(
      nodeAt(insertAt(nested, [1], leaf('id', 'd')), [1, 2]),
    ).toMatchObject({ value: 'd' });
    // A leaf is not a group, so there is nothing to append to.
    expect(insertAt(nested, [0], leaf('id', 'd'))).toEqual(nested);
  });

  it('merges trees by AND, flattening and dropping the empty ones', () => {
    const merged = mergeFilters(
      { op: 'and', children: [leaf('id', 'a')] },
      emptyFilter(),
      null,
      { op: 'or', children: [leaf('id', 'b')] },
    );

    expect(merged.op).toBe('and');
    expect(merged.children).toEqual([
      leaf('id', 'a'),
      { op: 'or', children: [leaf('id', 'b')] },
    ]);
    expect(mergeFilters()).toEqual(emptyFilter());
  });

  it('hands the base back as it stands when there is nothing to merge', () => {
    // No wrapper an admission never saw: an `or` root stays an `or` root.
    const any: FilterTree = {
      op: 'or',
      children: [
        { field: 'id', operator: 'EQ', value: 'a' },
        { field: 'amount', operator: 'GT', value: 1 },
      ],
    };
    expect(mergeFilters(any)).toBe(any);
    expect(mergeFilters(any, null, emptyFilter())).toBe(any);
    expect(mergeFilters(null)).toEqual(emptyFilter());
  });

  it('does not drop a tree that lost its shape as if it were empty', () => {
    // A stored filter whose only entry is malformed says nothing valid, but
    // it is not empty: dropping it behind an injected scope would run the
    // query wider than the view was saved to be, and report nothing.
    const broken = { op: 'and', children: [null] } as unknown as FilterTree;
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };

    expect(isEmptyFilter(broken)).toBe(false);
    expect(isEmptyFilter(emptyFilter())).toBe(true);
    const merged = mergeFilters(broken, scope);
    expect(merged.children).toHaveLength(2);
    expect(
      validateFilter(fields, merged, builtinFieldKinds).map(found => ({
        code: found.code,
        path: found.path,
      })),
    ).toEqual([{ code: 'filter.node.invalid', path: ['children', 0] }]);
  });
});

describe('node and tree comparison', () => {
  const leaf = (value: unknown): FilterLeaf => ({
    field: 'id',
    operator: `${FilterOperator.EQ}`,
    value: value as FilterLeaf['value'],
  });

  it('compares a leaf by field, operator and value', () => {
    expect(sameFilterNode(leaf('a'), leaf('a'))).toBe(true);
    expect(sameFilterNode(leaf('a'), leaf('b'))).toBe(false);
    expect(
      sameFilterNode(leaf('a'), { ...leaf('a'), field: 'warehouse' }),
    ).toBe(false);
    expect(
      sameFilterNode(leaf('a'), {
        ...leaf('a'),
        operator: `${FilterOperator.NE}`,
      }),
    ).toBe(false);
  });

  it('reads a value that travelled through a store as the same value', () => {
    // A stored config comes back as new objects every time, so identity is
    // no answer: a list condition would read as edited on every render.
    expect(sameFilterNode(leaf(['a', 'b']), leaf(['a', 'b']))).toBe(true);
    expect(sameFilterNode(leaf(['a', 'b']), leaf(['b', 'a']))).toBe(false);
    expect(sameFilterNode(leaf(['a']), leaf(['a', 'b']))).toBe(false);
    expect(sameFilterNode(leaf(['a']), leaf('a'))).toBe(false);
    expect(
      sameFilterNode(leaf({ from: 1, to: 2 }), leaf({ to: 2, from: 1 })),
    ).toBe(true);
    expect(sameFilterNode(leaf({ from: 1 }), leaf({ from: 1, to: 2 }))).toBe(
      false,
    );
    expect(sameFilterNode(leaf({ from: 1 }), leaf({ to: 1 }))).toBe(false);
    expect(sameFilterNode(leaf(null), leaf('a'))).toBe(false);
  });

  it('compares a group by its operator, not by what is in it', () => {
    const or: FilterTree = { op: 'or', children: [leaf('a')] };
    expect(sameFilterNode(or, { op: 'or', children: [leaf('z')] })).toBe(true);
    expect(sameFilterNode(or, { op: 'and', children: [leaf('a')] })).toBe(
      false,
    );
    // A group and a leaf are never the same thing, whichever side it is on.
    expect(sameFilterNode(or, leaf('a'))).toBe(false);
    expect(sameFilterNode(leaf('a'), or)).toBe(false);
  });

  it('treats a missing node as unequal to any node', () => {
    expect(sameFilterNode(null, null)).toBe(true);
    expect(sameFilterNode(null, leaf('a'))).toBe(false);
    expect(sameFilterNode(leaf('a'), null)).toBe(false);
  });

  it('compares a whole tree, children and all', () => {
    const tree: FilterTree = {
      op: 'and',
      children: [leaf('a'), { op: 'or', children: [leaf('b')] }],
    };
    expect(sameFilterTree(tree, structuredClone(tree))).toBe(true);
    expect(
      sameFilterTree(tree, {
        op: 'and',
        children: [leaf('a'), { op: 'or', children: [leaf('z')] }],
      }),
    ).toBe(false);
    expect(sameFilterTree(tree, emptyFilter())).toBe(false);
  });

  /**
   * The editor asks what changed on every render, and it asks it of a
   * *draft* — something `validateFilter` has not admitted and may never
   * admit. A recursive comparison would exhaust the stack on one, which is a
   * crash during render rather than a finding.
   */
  it('answers a tree deeper than any stack without throwing', () => {
    const deep = (depth: number): FilterTree => {
      let node: FilterTree = { op: 'and', children: [leaf('a')] };
      for (let level = 0; level < depth; level += 1)
        node = { op: 'and', children: [node] };
      return node;
    };

    // Far past any call stack, and still under the node budget: a real
    // answer, arrived at iteratively.
    expect(sameFilterTree(deep(15_000), deep(15_000))).toBe(true);
    expect(sameFilterTree(deep(15_000), deep(15_001))).toBe(false);
    // Past the budget the answer is "not the same" — the only safe one, and
    // a tree the panel refuses to draw anyway.
    expect(sameFilterTree(deep(40_000), deep(40_000))).toBe(false);
    // The budget is a parameter, so a caller with tighter limits may say so.
    expect(sameFilterTree(deep(20), deep(20), 4)).toBe(false);
  });

  it('answers a cyclic value without walking it for ever', () => {
    const left: Record<string, unknown> = { from: 1 };
    left.self = left;
    const right: Record<string, unknown> = { from: 1 };
    right.self = right;

    // Two cycles that mean the same thing are still not the same answer a
    // finite walk can give, so the budget ends it at "no".
    expect(sameFilterNode(leaf(left), leaf(right))).toBe(false);
    // The same object is the same value without looking inside it at all.
    expect(sameFilterNode(leaf(left), leaf(left))).toBe(true);

    const cyclic = (): FilterTree => {
      const tree: FilterTree = { op: 'and', children: [] };
      tree.children.push(tree);
      return tree;
    };
    expect(sameFilterTree(cyclic(), cyclic())).toBe(false);
    const one = cyclic();
    expect(sameFilterTree(one, one)).toBe(true);
  });
});
