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
 * What an analysis may name, and where, once the chain a capability declares
 * is expanded: `analysisScope` and `qualify` decide which fields exist in
 * which scope, validation follows them, and compilation renames what it sends
 * relative to the scope it sends it in — which is how Wow reads it.
 */

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  analysisScope,
  builtinFieldKinds,
  compileAnalysis,
  emptyFilter,
  qualify,
  validateAnalysis,
  DEFAULT_RUNTIME_LIMITS,
  type AnalysisViewConfig,
  type FilterTree,
} from '../src/index.js';
import {
  analysisCapability as capability,
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';

// What an element holds is the definition's to say; the capability names
// which arrays this analysis may expand, in the order they nest, and how
// their fields aggregate.
const withElements = definition({
  fields: [
    ...definition().fields,
    {
      name: 'items',
      label: 'Items',
      kind: 'array',
      elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
    },
  ],
  analysis: {
    ...capability,
    elements: [
      {
        path: 'items',
        aggregations: [
          { field: 'sku', groups: [AggregationGroupType.TERMS], functions: [] },
        ],
      },
    ],
  },
});

/** A config that expands `items` and asks its question inside it. */
const onItems = (
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig =>
  config({
    elements: [{ path: 'items' }],
    groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
    chart: {
      type: 'bar',
      cartesian: { x: 'sku', series: [{ metric: 'orders' }] },
    },
    ...overrides,
  });

const check = (
  overrides: Partial<AnalysisViewConfig>,
  subject = withElements,
) => codes(validateAnalysis(subject, onItems(overrides), builtinFieldKinds));

describe('the expansion chain', () => {
  /**
   * Wow's `elements` is one ordered parent-to-child walk, and Wow's own DSL
   * test writes it as `state.orders` → `lines` → `discounts`. Two levels are
   * enough to state every rule: the second path is relative to the first, the
   * counting unit is the innermost one, and both the dimension and the metric
   * are named relative to it.
   */
  const orders = definition({
    fields: [
      ...definition().fields,
      {
        name: 'state.orders',
        label: 'Orders',
        kind: 'array',
        elements: [
          { name: 'status', label: 'Status', kind: 'string' },
          {
            name: 'lines',
            label: 'Lines',
            kind: 'array',
            elements: [
              { name: 'productId', label: 'Product', kind: 'string' },
              { name: 'amount', label: 'Amount', kind: 'number' },
            ],
          },
        ],
      },
    ],
    analysis: {
      ...capability,
      elements: [
        {
          path: 'state.orders',
          aggregations: [
            {
              field: 'status',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
        {
          path: 'lines',
          aggregations: [
            {
              field: 'productId',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            {
              field: 'amount',
              groups: [],
              functions: [AggregationFunction.SUM],
            },
          ],
        },
      ],
    },
  });

  const twoLevels = config({
    elements: [{ path: 'state.orders' }, { path: 'lines' }],
    groups: [
      {
        type: 'TERMS',
        field: 'state.orders.lines.productId',
        alias: 'product',
      },
    ],
    metrics: [
      { type: 'COUNT', alias: 'orders' },
      {
        type: 'NUMERIC',
        alias: 'total',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'state.orders.lines.amount' },
      },
    ],
    sort: [{ alias: 'total', direction: 'DESC' }],
    chart: {
      type: 'bar',
      cartesian: { x: 'product', series: [{ metric: 'total' }] },
    },
  });

  it('compiles the chain the way Wow reads it', () => {
    expect(
      codes(validateAnalysis(orders, twoLevels, builtinFieldKinds)),
    ).toEqual([]);

    const query = compileAnalysis(
      orders,
      twoLevels,
      builtinFieldKinds,
      context,
    );
    // Each path relative to the one before it, every field relative to the
    // innermost element. Spelled absolutely, `lines.productId` would resolve
    // under its own parent as `state.orders.lines.lines.productId`.
    expect(query.elements).toEqual([
      { path: 'state.orders' },
      { path: 'lines' },
    ]);
    expect(query.groupBy).toEqual([
      { type: 'TERMS', field: 'productId', alias: 'product' },
    ]);
    expect(query.metrics).toEqual([
      { type: 'COUNT', alias: 'orders' },
      {
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
        alias: 'total',
      },
    ]);
  });

  it('expands only a prefix of the declared chain', () => {
    const outer = config({
      elements: [{ path: 'state.orders' }],
      groups: [
        { type: 'TERMS', field: 'state.orders.status', alias: 'status' },
      ],
      chart: {
        type: 'bar',
        cartesian: { x: 'status', series: [{ metric: 'orders' }] },
      },
    });

    expect(codes(validateAnalysis(orders, outer, builtinFieldKinds))).toEqual(
      [],
    );
    expect(
      compileAnalysis(orders, outer, builtinFieldKinds, context).groupBy,
    ).toEqual([{ type: 'TERMS', field: 'status', alias: 'status' }]);
  });

  it('refuses an inner level expanded without the one that holds it', () => {
    // A chain is not a set: `lines` is declared, but only inside an order.
    // This is the shape a list of sibling arrays used to take.
    expect(
      codes(
        validateAnalysis(
          orders,
          config({
            elements: [{ path: 'lines' }],
            groups: [],
            sort: [],
            chart: { type: 'metric', metric: { metric: 'orders' } },
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.element.out-of-chain']);
  });

  it('refuses a path the chain never declares', () => {
    expect(
      codes(
        validateAnalysis(
          orders,
          config({
            elements: [{ path: 'ghosts' }],
            groups: [],
            sort: [],
            chart: { type: 'metric', metric: { metric: 'orders' } },
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.element.undeclared']);
  });

  it('scopes each level to what that level holds', () => {
    const scope = analysisScope(orders, orders.analysis!, {
      elements: [{ path: 'state.orders' }, { path: 'lines' }],
    });

    expect(scope.elements.map(element => element.absolute)).toEqual([
      'state.orders',
      'state.orders.lines',
    ]);
    // The counting unit is the innermost element, so that is what a dimension
    // or a metric may name.
    expect([...scope.fields.keys()]).toEqual([
      'state.orders.lines.productId',
      'state.orders.lines.amount',
    ]);
    expect(scope.rootFields.map(field => field.name)).toEqual([
      'warehouse',
      'createdAt',
      'amount',
      'state.orders',
    ]);
  });
});

describe('element scope', () => {
  // An element filter gates which entries the expansion lets through. It has
  // no editor either, so the same rule as a metric's filter applies: having
  // written one, it must actually narrow something.
  it('refuses an element filter with no conditions', () => {
    expect(
      check({ elements: [{ path: 'items', filter: emptyFilter() }] }),
    ).toEqual(['analysis.elementFilter.empty']);
  });

  it('refuses an element condition with no value', () => {
    expect(
      check({
        elements: [
          {
            path: 'items',
            filter: {
              op: 'and',
              children: [{ field: 'items.sku', operator: 'EQ', value: '' }],
            },
          },
        ],
      }),
    ).toEqual(['analysis.elementFilter.incomplete']);
  });

  it('paths an empty element filter at the filter itself', () => {
    expect(
      validateAnalysis(
        withElements,
        onItems({ elements: [{ path: 'items', filter: emptyFilter() }] }),
        builtinFieldKinds,
      ).map(found => found.path),
    ).toContainEqual(['elements', 0, 'filter']);
  });

  it('admits an element filter that names a value', () => {
    expect(
      check({
        elements: [
          {
            path: 'items',
            filter: {
              op: 'and',
              children: [{ field: 'items.sku', operator: 'EQ', value: 'A-1' }],
            },
          },
        ],
      }),
    ).toEqual([]);
  });

  it('refuses an element filter that reaches back to a root field', () => {
    // An element's gate is read inside that element: Wow answers a root name
    // there with "requires its declared element scope".
    expect(
      check({
        elements: [
          {
            path: 'items',
            filter: {
              op: 'and',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'WH-1' }],
            },
          },
        ],
      }),
    ).toEqual(['analysis.field.outside-scope']);
  });

  it('compiles an element filter relative to its own element', () => {
    const query = compileAnalysis(
      withElements,
      onItems({
        elements: [
          {
            path: 'items',
            filter: {
              op: 'and',
              children: [{ field: 'items.sku', operator: 'EQ', value: 'A-1' }],
            },
          },
        ],
      }),
      builtinFieldKinds,
      context,
    );

    expect(query.elements).toEqual([
      { path: 'items', filter: { field: 'sku', value: 'A-1', op: 'EQ' } },
    ]);
  });

  it('takes the prefix off a predicate a condition holds', () => {
    // A predicate's names are composed from the leaf's own — `items.tags`
    // holds conditions on `items.tags.name` — so the scope's prefix is on
    // them too and comes off with it. What the predicate spells below that
    // is the `elementMatch` kind's own composition, untouched here.
    const held = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            {
              name: 'tags',
              label: 'Tags',
              kind: 'elementMatch',
              elements: [{ name: 'name', label: 'Name', kind: 'string' }],
            },
          ],
        },
      ],
      analysis: withElements.analysis,
    });
    const query = compileAnalysis(
      held,
      onItems({
        elements: [
          {
            path: 'items',
            filter: {
              op: 'and',
              children: [
                {
                  field: 'items.tags',
                  operator: 'ELEMENT_MATCH',
                  value: {
                    op: 'and',
                    children: [
                      {
                        field: 'items.tags.name',
                        operator: 'EQ',
                        value: 'red',
                      },
                    ],
                  },
                },
              ],
            },
          },
        ],
      }),
      builtinFieldKinds,
      context,
    );

    expect(query.elements).toEqual([
      {
        path: 'items',
        filter: {
          op: 'ELEMENT_MATCH',
          field: 'tags',
          predicate: { op: 'EQ', field: 'name', value: 'red' },
        },
      },
    ]);
  });

  it('leaves an element without a filter alone', () => {
    // No filter at all is how "expand every entry" is said; only a filter
    // that was written and says nothing is wrong.
    expect(check({ elements: [{ path: 'items' }] })).toEqual([]);
  });

  it('admits a multi-valued element field, which a metric filter refuses', () => {
    // The scalar rule belongs to metric position, where the filter has one
    // record's value to test. An element filter is an ordinary filter over
    // the element's own fields and carries no such restriction.
    const nested = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            {
              name: 'tags',
              label: 'Tags',
              kind: 'array',
              elements: [{ name: 'name', label: 'Name', kind: 'string' }],
            },
          ],
        },
      ],
      analysis: {
        ...capability,
        elements: [
          {
            path: 'items',
            aggregations: [
              {
                field: 'sku',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
            ],
          },
        ],
      },
    });
    const onTags: FilterTree = {
      op: 'and',
      children: [{ field: 'items.tags', operator: 'IN', value: ['red'] }],
    };

    expect(
      check({ elements: [{ path: 'items', filter: onTags }] }, nested),
    ).toEqual([]);
    expect(
      check(
        {
          elements: [{ path: 'items' }],
          metrics: [{ type: 'COUNT', alias: 'orders', filter: onTags }],
        },
        nested,
      ),
    ).toEqual(['analysis.metricFilter.not-scalar']);
  });

  it('honours a caller that widened the tree limits', () => {
    // An element filter is a filter like any other, so it spends the budget
    // the caller set rather than the default one.
    const deep = (depth: number): FilterTree =>
      depth <= 1
        ? {
            op: 'and',
            children: [{ field: 'items.sku', operator: 'EQ', value: 'x' }],
          }
        : { op: 'and', children: [deep(depth - 1)] };
    const overrides = { elements: [{ path: 'items', filter: deep(12) }] };

    expect(check(overrides)).toEqual(['filter.tree.too-deep']);
    expect(
      codes(
        validateAnalysis(withElements, onItems(overrides), builtinFieldKinds, {
          limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterDepth: 16 },
        }),
      ),
    ).toEqual([]);
  });

  it('qualifies element fields with their path, always', () => {
    expect(qualify('items', 'sku')).toBe('items.sku');
    // A declaration names what it holds relative to itself. Leaving a name
    // that already begins with the path alone accepted two spellings of one
    // reference, and made `items.sku` mean one thing at the root of an
    // element and another inside a nested object sharing the array's name.
    expect(qualify('items', 'items.sku')).toBe('items.items.sku');
    expect(qualify('items', 'address.city')).toBe('items.address.city');
  });

  it('exposes element fields only once the element is configured', () => {
    const capability_ = withElements.analysis;
    if (!capability_) throw new Error('unreachable');

    const without = analysisScope(withElements, capability_, { elements: [] });
    expect(without.aggregations.has('items.sku')).toBe(false);
    expect(without.aggregations.has('warehouse')).toBe(true);
    expect(without.declaredChain).toEqual(['items']);

    const scope = analysisScope(withElements, capability_, {
      elements: [{ path: 'items' }],
    });
    expect(scope.fields.get('items.sku')?.label).toBe('SKU');
    expect(scope.aggregations.has('items.sku')).toBe(true);
    // The root's own fields are still reachable — by the range, which is the
    // one place they belong once something is expanded.
    expect(scope.fields.has('warehouse')).toBe(false);
    expect(scope.reachable.has('warehouse')).toBe(true);
  });

  it('groups by an element field once the element is expanded', () => {
    const built = onItems();
    expect(
      codes(validateAnalysis(withElements, built, builtinFieldKinds)),
    ).toEqual([]);

    const query = compileAnalysis(
      withElements,
      built,
      builtinFieldKinds,
      context,
    );
    expect(query.elements).toEqual([{ path: 'items' }]);
    expect(query.groupBy?.[0]).toMatchObject({ field: 'sku' });
  });

  it('refuses a root field wherever the counting unit is an element', () => {
    // Each of these is the same mistake in a different place, and Wow refuses
    // all four: the range is what root fields are for.
    expect(
      check({ groups: [{ type: 'TERMS', field: 'warehouse', alias: 'sku' }] }),
    ).toEqual(['analysis.field.outside-scope']);
    expect(
      check({
        metrics: [{ type: 'ANY', alias: 'orders', field: 'amount' }],
      }),
    ).toEqual(['analysis.field.outside-scope']);
    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      }),
    ).toEqual(['analysis.field.outside-scope']);
    expect(
      check({
        metrics: [
          {
            type: 'COUNT',
            alias: 'orders',
            filter: {
              op: 'and',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'WH-1' }],
            },
          },
        ],
      }),
    ).toEqual(['analysis.field.outside-scope']);
  });

  it('keeps the range on the root fields', () => {
    // The root filter runs before any expansion, so it names root fields and
    // only root fields — an element field there is unknown to it.
    expect(
      check({
        filter: {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'WH-1' }],
        },
      }),
    ).toEqual([]);
    expect(
      check({
        filter: {
          op: 'and',
          children: [{ field: 'items.sku', operator: 'EQ', value: 'A-1' }],
        },
      }),
    ).toEqual(['filter.field.unknown']);
  });
});
