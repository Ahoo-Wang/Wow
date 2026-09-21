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
 * The condition builder's controller: the draft it edits, what it blocks and
 * what it marks, the "changed but not applied" state, a host scope filter it
 * must not swallow, and the tree controller underneath it all.
 */

import { AggregationGroupType, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  type FilterTree,
  type ViewInstance,
  type RecordViewRuntime,
} from '../src/index.js';
import {
  treeController,
  useFilterEditor,
  useOpenView,
} from '../src/react/index.js';
import {
  analysisConfig,
  mine,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

describe('useFilterEditor', () => {
  it('sets a condition, or a whole group, back to nothing said', async () => {
    const { engine } = engineWith();
    const runtime = await engine.open('orders-1');
    const { result } = renderHook(() => useFilterEditor(runtime));
    act(() => {
      result.current.addLeaf('warehouse');
      result.current.updateLeaf([0], { value: 'CN' });
      result.current.addGroup('or');
      result.current.addLeaf('status', [1]);
      result.current.updateLeaf([1, 0], { value: 'open' });
    });

    act(() => result.current.clearValue([0]));
    expect(result.current.tree.children[0]).toMatchObject({
      field: 'warehouse',
      value: '',
    });
    act(() => result.current.clearValue([1]));
    expect(result.current.tree.children[1]).toMatchObject({
      op: 'or',
      children: [{ field: 'status', value: '' }],
    });
  });

  it('offers only the fields not yet a condition of the group', async () => {
    const { engine } = engineWith();
    const runtime = await engine.open('orders-1');
    const { result } = renderHook(() => useFilterEditor(runtime));

    const before = result.current.fieldsFor().map(field => field.name);
    act(() => result.current.addLeaf('warehouse'));
    const after = result.current.fieldsFor().map(field => field.name);

    expect(before).toContain('warehouse');
    expect(after).not.toContain('warehouse');
    expect(after.length).toBe(before.length - 1);
    // A nested group starts with every field again.
    act(() => result.current.addGroup('or'));
    expect(result.current.fieldsFor([1]).map(field => field.name)).toContain(
      'warehouse',
    );
  });

  async function openEditor() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return result;
  }

  it('is inert without a runtime', () => {
    const { result } = renderHook(() => useFilterEditor(null));

    expect(result.current.tree).toEqual({ op: 'and', children: [] });
    expect(result.current.fields).toEqual([]);
    expect(result.current.operatorsFor('id')).toEqual([]);
    expect(result.current.editorFor([0])).toBeNull();
    expect(result.current.applied).toEqual([]);
    expect(() => {
      result.current.addLeaf('id');
      result.current.addGroup('or');
      result.current.remove([0]);
      result.current.clear();
      result.current.submit();
      result.current.setMode('advanced');
      result.current.focus();
      result.current.blur();
    }).not.toThrow();
  });

  it('adds, edits and removes nodes on the draft', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('warehouse'));
    expect(result.current.filter.count).toBe(1);
    expect(result.current.filter.simple).toBe(true);
    expect(result.current.filter.editorFor([0])).toMatchObject({
      input: 'text',
    });
    expect(
      result.current.filter.operatorsFor('warehouse').length,
    ).toBeGreaterThan(0);

    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.EQ}`,
        value: 'CN',
      }),
    );
    expect(result.current.opened.runtime?.getSnapshot().dirty).toBe(true);

    act(() => result.current.filter.submit());
    await waitFor(() => expect(result.current.filter.applied).toHaveLength(1));

    act(() => result.current.filter.remove([0]));
    expect(result.current.filter.count).toBe(0);
  });

  it('ignores a field the definition does not declare', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('nope'));

    expect(result.current.filter.count).toBe(0);
  });

  it('reseeds a value the new operator cannot hold', async () => {
    const result = await openEditor();

    // `amount` starts at `EQ 0`. `BETWEEN` needs two bounds, so carrying the
    // scalar across would mark the row invalid on a switch the user made on
    // purpose, and block apply on a mistake they did not make.
    act(() => result.current.filter.addLeaf('amount'));
    expect(result.current.filter.issues).toEqual([]);

    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.BETWEEN}`,
      }),
    );

    // Back to unfilled rather than to `[0, 0]`, which would be a condition
    // the user never asked for.
    expect(result.current.filter.tree.children[0]).toEqual({
      field: 'amount',
      operator: `${FilterOperator.BETWEEN}`,
      value: null,
    });
    expect(result.current.filter.issues).toEqual([]);
  });

  it('keeps a value the new operator still admits', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('amount'));
    act(() => result.current.filter.updateLeaf([0], { value: 5 }));
    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.GTE}`,
      }),
    );

    // Both operators take one number, so what the user typed survives.
    expect(result.current.filter.tree.children[0]).toMatchObject({ value: 5 });
  });

  it('lets a patch that carries its own value through untouched', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('amount'));
    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.BETWEEN}`,
        value: [1, 9],
      }),
    );

    expect(result.current.filter.tree.children[0]).toMatchObject({
      value: [1, 9],
    });
  });

  it('nests a group, which leaves simple mode behind', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addGroup('or'));
    act(() => result.current.filter.addLeaf('warehouse', [0]));

    expect(result.current.filter.simple).toBe(false);
    expect(result.current.filter.count).toBe(1);

    act(() => result.current.filter.setMode('advanced'));
    expect(result.current.filter.mode).toBe('advanced');

    act(() => result.current.filter.clear());
    expect(result.current.filter.tree.children).toEqual([]);
  });

  it('reports the filter issues the validator produced, and only those', async () => {
    const result = await openEditor();

    // A value the kind cannot read is an error and the editor has to say so.
    // An *unfilled* one is not — see the tests below.
    act(() => result.current.filter.addLeaf('warehouse'));
    act(() => result.current.filter.updateLeaf([0], { value: 7 as never }));
    act(() => result.current.opened.runtime?.edit({ pageSize: 0 }));

    const codes = result.current.filter.issues.map(found => found.code);
    expect(codes).toContain('filter.value.expected-string');
    expect(
      codes.every(
        code =>
          code.startsWith('filter.') || code.startsWith('config.filterMode.'),
      ),
    ).toBe(true);
    // The page size error belongs to the view, not to this editor.
    expect(codes.some(code => code.startsWith('record.'))).toBe(false);
  });

  it('keeps element-scoped filter issues out of this editor', async () => {
    // An element's own filter is validated in its own field scope and its
    // findings are addressed under ['elements', i, 'filter', …]; carrying them
    // by code alone would let them mark top-level conditions as invalid.
    const elemented = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
      ],
      analysis: {
        count: true,
        fields: [
          {
            field: 'warehouse',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
        ],
        elements: [
          {
            path: 'items',
            aggregations: [{ field: 'sku', groups: [], functions: [] }],
          },
        ],
      },
    });
    const { engine } = engineWith({
      definitions: [elemented],
      instances: [
        {
          ...mine,
          config: analysisConfig({
            elements: [
              {
                path: 'items',
                filter: {
                  op: 'and',
                  children: [{ field: 'ghost', operator: 'EQ', value: 'x' }],
                },
              },
            ],
          }),
        },
      ],
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    const codes = result.current.filter.issues.map(found => found.code);
    expect(codes).not.toContain('filter.field.unknown');
  });

  it('starts a condition on an operator the field allows', async () => {
    const definition = ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string' },
        {
          name: 'warehouse',
          label: 'Warehouse',
          kind: 'string',
          operators: [`${FilterOperator.CONTAINS}`],
        },
      ],
      record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
      // The capability has to match the fields above: `validateDefinition`
      // refuses an analysis over a field the definition does not declare.
      analysis: { count: true, fields: [] },
      views: [],
    });
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({ table: { columns: [{ field: 'id' }] } }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    act(() => result.current.filter.addLeaf('warehouse'));

    expect(result.current.filter.tree.children[0]).toMatchObject({
      operator: `${FilterOperator.CONTAINS}`,
    });
  });

  it('composes edits made in one batch', async () => {
    const result = await openEditor();

    act(() => {
      result.current.filter.addGroup('or');
      result.current.filter.addLeaf('warehouse', [0]);
    });

    const [group] = result.current.filter.tree.children;
    expect(group).toMatchObject({ op: 'or' });
    expect(result.current.filter.count).toBe(1);
  });

  it('pauses auto refresh while an editor holds focus', async () => {
    const result = await openEditor();

    act(() => result.current.filter.focus());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(true);

    act(() => result.current.filter.blur());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(false);
  });
});

/**
 * The tree-editing half of the controller, over any tree rather than a
 * runtime's draft. It is what lets a condition holding a condition render
 * through the same components as the filter around it.
 */
describe('useFilterEditor pending and applied', () => {
  async function openEditor() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().result).toBeTruthy(),
    );
    return result;
  }

  it('describes the conditions the rows on screen came back under', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    expect(filter().applied).toEqual([]);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());

    // Applied, but not answered yet: the summary sits beside the rows and
    // must describe those rows, not the query that is still in flight.
    expect(filter().applied).toEqual([]);
    await waitFor(() => expect(filter().applied).toHaveLength(1));
  });

  it('marks the draft nodes that have not been applied yet', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().isPending([0])).toBe(false);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });

    expect(filter().pending).toBe(true);
    // The new condition alone; the root still says the same `and`.
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
    expect(filter().isPending([])).toBe(false);

    act(() => filter().submit());
    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().isPending([0])).toBe(false);
  });

  it('counts a changed group operator without counting its children', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addGroup('or');
      filter().addLeaf('warehouse', [0]);
      filter().updateLeaf([0, 0], { value: 'CN' });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().updateGroup([0], 'and'));

    // One group moved, and the condition inside it did not: a single edit
    // reported as three would be a number the user cannot act on.
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
    expect(filter().isPending([0, 0])).toBe(false);

    act(() => filter().updateGroup([], 'or'));
    expect(filter().isPending([])).toBe(true);
    expect(filter().pendingCount).toBe(2);
  });

  it('counts a condition the draft no longer has', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().remove([0]));

    // Taking the last condition out is as much an unapplied edit as adding
    // one: the rows on screen are still the narrow ones, and a badge of 0
    // beside a live Apply button is the count contradicting itself.
    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
  });

  it('counts every condition a cleared filter dropped', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().clear());

    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(2);
  });

  it('counts a draft over the tree budget as one edit, without walking it', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    let nested: FilterTree = { op: 'and', children: [] };
    for (let level = 0; level < 12; level += 1)
      nested = { op: 'and', children: [nested] };
    act(() => result.current.opened.runtime?.edit({ filter: nested }));

    expect(filter().issues.map(found => found.code)).toContain(
      'filter.tree.too-deep',
    );
    // Apply refused it, so it stands apart from what ran: that is pending
    // (D17-6). But the panel draws no tree admission refused, so there is
    // no pill to mark, and a tree from a store may hold a cycle — it counts
    // as one edit and is not compared node by node.
    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(1);
    expect(filter().conditionsPending).toBe(true);
    expect(filter().isPending([0])).toBe(false);
    expect(filter().applied).toEqual([]);
  });

  /**
   * D17-6: the credential is the whole config. A sort, a page size or a
   * filter mode edited and not applied — or applied and refused — used to
   * leave the dot dark while the rows answered another configuration.
   */
  it('counts every member of the config that differs from what was applied', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    const runtime = () => result.current.opened.runtime;

    act(() =>
      (runtime() as RecordViewRuntime | null)?.edit({
        sort: [{ field: 'amount', direction: 'DESC' }],
      }),
    );

    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(1);
    // The conditions did not move, so there is nothing for discard to put
    // back: the dot is on, the discard button is not.
    expect(filter().conditionsPending).toBe(false);

    act(() => filter().setMode('advanced'));
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    // One per member, and the conditions node by node.
    expect(filter().pendingCount).toBe(3);
    expect(filter().conditionsPending).toBe(true);

    act(() => filter().submit());
    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().conditionsPending).toBe(false);
  });

  it('keeps summarising the result while the draft goes over budget', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    let nested: FilterTree = { op: 'and', children: [] };
    for (let level = 0; level < 12; level += 1)
      nested = { op: 'and', children: [nested] };
    act(() => result.current.opened.runtime?.edit({ filter: nested }));

    expect(filter().issues.map(found => found.code)).toContain(
      'filter.tree.too-deep',
    );
    expect(filter().pending).toBe(true);
    // The rows on screen are still the narrow ones: what produced them was
    // admitted before it ran, so it is within budget whatever the draft has
    // since become. A summary that blanked while the user edited would stop
    // describing the data it sits beside.
    expect(filter().applied).toHaveLength(1);
    expect(filter().applied[0]?.text).toContain('CN');
  });

  it('keeps the root editor working when a metric owns the oversized tree', async () => {
    const analysis: ViewInstance = {
      id: 'orders-analysis',
      definitionId: 'orders',
      title: 'By warehouse',
      scope: 'personal',
      revision: '1',
      config: analysisConfig(),
    };
    const { engine } = engineWith({ instances: [analysis] });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-analysis');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    const filter = () => result.current.filter;

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    // A metric's own filter is validated in its own scope and re-pathed under
    // ['metrics', …], and it reports the very same budget codes as this tree.
    const wide: FilterTree = {
      op: 'and',
      children: Array.from({ length: 400 }, () => ({
        field: 'amount',
        operator: 'EQ' as const,
        value: 1,
      })),
    };
    act(() =>
      result.current.opened.runtime?.edit({
        metrics: [{ type: 'COUNT', alias: 'orders', filter: wide }],
      }),
    );
    expect(
      result.current.opened.runtime
        ?.getSnapshot()
        .issues.map(found => found.code),
    ).toContain('filter.tree.too-many-nodes');

    // That tree is not the one this editor draws, so the code alone must not
    // switch the editor off: its own conditions are still comparable, still
    // applicable, and the summary still describes the rows on screen.
    expect(filter().issues).toEqual([]);
    expect(filter().applied).toHaveLength(1);
    act(() => filter().updateLeaf([0], { value: 'US' }));
    expect(filter().pending).toBe(true);
    // The edited condition, and the metric whose edit has not run either
    // (D17-6 counts every member) — but not the metric's oversized tree as
    // if it were this editor's.
    expect(filter().pendingCount).toBe(2);
    expect(filter().isPending([0])).toBe(true);
  });

  it('counts only the blocking findings that point at a condition', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    expect(filter().blocked).toBe(0);

    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { value: 'heavy' as never });
    });

    expect(filter().blocked).toBe(1);

    // An error elsewhere in the config blocks apply too, but no pill can be
    // marked for it, so the filter panel must not claim it.
    act(() => result.current.opened.runtime?.edit({ pageSize: 5000 }));
    expect(filter().blocked).toBe(1);
  });
});

describe('useFilterEditor under a host scope filter', () => {
  /** What an embedding host narrows the view to; never in the draft. */
  const scope: FilterTree = {
    op: 'and',
    children: [{ field: 'status', operator: 'EQ', value: 'OPEN' }],
  };

  /** A summary item's path as the tree editor addresses a node. */
  function indexes(path: readonly (string | number)[]): number[] {
    return path.filter((step): step is number => typeof step === 'number');
  }

  async function openScoped() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1', scope);
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().result).toBeTruthy(),
    );
    return result;
  }

  it('describes the host conditions apart from the view own', async () => {
    const result = await openScoped();
    const filter = () => result.current.filter;

    expect(filter().scoped).toHaveLength(1);
    expect(filter().scoped[0]).toMatchObject({
      field: 'status',
      path: ['children', 0],
    });
    expect(filter().applied).toEqual([]);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(2));

    // The scope ran with them, and `result.config` holds the merged tree —
    // but a badge for it would offer a remove nobody here can honour, and
    // the path it carried would address the draft's next condition instead.
    expect(filter().applied.map(item => item.path)).toEqual([
      ['children', 0],
      ['children', 1],
    ]);
    expect(filter().applied.some(item => item.text.includes('OPEN'))).toBe(
      false,
    );

    // The path a badge carries reaches the leaf it names, and only it.
    act(() => {
      filter().clearValue(indexes(filter().applied[1].path));
      filter().submit();
    });
    await waitFor(() => expect(filter().applied).toHaveLength(1));
    expect(filter().applied[0].text).toContain('CN');
    expect(filter().scoped).toHaveLength(1);
  });

  it('addresses an or-root draft through the config that produced the result', async () => {
    const result = await openScoped();
    const filter = () => result.current.filter;

    act(() => {
      filter().updateGroup([], 'or');
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    // `mergeFilters` carries an `or` draft in as the first child of an `and`,
    // so every path into the merged tree is one level deeper than the tree
    // the editor draws. The summary reads `own`, which is that very tree.
    const summary = filter().applied[0];
    expect(summary).toMatchObject({ group: 'or', path: [] });
    expect(summary.text).toContain('CN');
    expect(summary.text).not.toContain('OPEN');

    const runtime = result.current.opened.runtime;
    expect(runtime?.getSnapshot().result?.config.filter).toMatchObject({
      op: 'and',
    });
    expect(runtime?.getSnapshot().result?.own.filter).toMatchObject({
      op: 'or',
    });

    act(() => {
      filter().clearValue(indexes(summary.path));
      filter().submit();
    });
    await waitFor(() => expect(filter().applied).toEqual([]));
    // The host's own condition is not the editor's to take out, and taking
    // the view's out did not touch it.
    expect(filter().scoped).toHaveLength(1);
    expect(runtime?.scopeFilter).toEqual(scope);
  });
});

describe('treeController', () => {
  const fields = [
    { name: 'sku', label: 'SKU', kind: 'string' as const },
    { name: 'qty', label: 'Qty', kind: 'number' as const },
  ];

  function controller(tree: FilterTree = { op: 'and', children: [] }) {
    let current = tree;
    const build = () =>
      treeController({
        tree: current,
        fields,
        kinds: builtinFieldKinds,
        issues: [],
        onChange: next => {
          current = next;
        },
      });
    return {
      act: (run: (c: ReturnType<typeof build>) => void) => run(build()),
      tree: () => current,
    };
  }

  it('adds, edits and removes without holding state', () => {
    const own = controller();

    own.act(c => c.addLeaf('sku'));
    expect(own.tree().children).toHaveLength(1);

    own.act(c => c.updateLeaf([0], { value: 'A' }));
    expect(own.tree().children[0]).toMatchObject({ field: 'sku', value: 'A' });

    own.act(c => c.remove([0]));
    expect(own.tree().children).toEqual([]);
  });

  it('nests a group and changes how it combines', () => {
    const own = controller();

    own.act(c => c.addGroup('or'));
    own.act(c => c.addLeaf('qty', [0]));
    expect(own.tree().children[0]).toMatchObject({ op: 'or' });

    own.act(c => c.updateGroup([0], 'nor'));
    expect(own.tree().children[0]).toMatchObject({ op: 'nor' });

    // `updateAt` leaves the root alone, so the root is written directly.
    own.act(c => c.updateGroup([], 'or'));
    expect(own.tree().op).toBe('or');
  });

  it('answers what a field offers and what edits it', () => {
    const own = controller({
      op: 'and',
      children: [{ field: 'sku', operator: 'EQ', value: 'A' }],
    });

    own.act(c => {
      expect(c.operatorsFor('sku')).toContain('CONTAINS');
      expect(c.operatorsFor('gone')).toEqual([]);
      expect(c.editorFor([0])).toMatchObject({ input: 'text' });
      expect(c.editorFor([9])).toBeNull();
    });
  });

  it('ignores a field the caller does not offer', () => {
    const own = controller();

    own.act(c => c.addLeaf('gone'));

    expect(own.tree().children).toEqual([]);
  });
});
