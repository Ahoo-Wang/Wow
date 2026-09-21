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
 * What an analysis may reach once the element paths a capability names are
 * expanded: `analysisScope` and `qualify` decide which fields exist inside a
 * scope, and validation follows them.
 */

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  analysisScope,
  builtinFieldKinds,
  compileAnalysis,
  emptyFilter,
  qualify,
  validateAnalysis,
  DEFAULT_RUNTIME_LIMITS,
  type FilterTree,
} from '../src/index.js';
import {
  analysisCapability as capability,
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';

describe('element scope', () => {
  // What an element holds is the definition's to say; the capability names
  // which of those paths this analysis may expand, and how they aggregate.
  const withElements = definition({
    // The array declares what it holds; the capability only names which
    // arrays this analysis may expand, and how their fields aggregate.
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

  // An element filter gates which entries the expansion lets through. It has
  // no editor either, so the same rule as a metric's filter applies: having
  // written one, it must actually narrow something.
  it('refuses an element filter with no conditions', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({ elements: [{ path: 'items', filter: emptyFilter() }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.elementFilter.empty']);
  });

  it('refuses an element condition with no value', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({
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
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.elementFilter.incomplete']);
  });

  it('paths an empty element filter at the filter itself', () => {
    expect(
      validateAnalysis(
        withElements,
        config({ elements: [{ path: 'items', filter: emptyFilter() }] }),
        builtinFieldKinds,
      ).map(found => found.path),
    ).toContainEqual(['elements', 0, 'filter']);
  });

  it('admits an element filter that names a value', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({
            elements: [
              {
                path: 'items',
                filter: {
                  op: 'and',
                  children: [
                    { field: 'items.sku', operator: 'EQ', value: 'A-1' },
                  ],
                },
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('leaves an element without a filter alone', () => {
    // No filter at all is how "expand every entry" is said; only a filter
    // that was written and says nothing is wrong.
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({ elements: [{ path: 'items' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
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
      codes(
        validateAnalysis(
          nested,
          config({ elements: [{ path: 'items', filter: onTags }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(
      codes(
        validateAnalysis(
          nested,
          config({
            elements: [{ path: 'items' }],
            metrics: [{ type: 'COUNT', alias: 'orders', filter: onTags }],
          }),
          builtinFieldKinds,
        ),
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

    expect(
      codes(
        validateAnalysis(withElements, config(overrides), builtinFieldKinds),
      ),
    ).toEqual(['filter.tree.too-deep']);
    expect(
      codes(
        validateAnalysis(withElements, config(overrides), builtinFieldKinds, {
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
    expect(without.declaredPaths.has('items')).toBe(true);

    const scope = analysisScope(withElements, capability_, {
      elements: [{ path: 'items' }],
    });
    expect(scope.fields.get('items.sku')?.label).toBe('SKU');
    expect(scope.aggregations.has('items.sku')).toBe(true);
  });

  it('groups by an element field once the element is expanded', () => {
    const built = config({
      elements: [{ path: 'items' }],
      groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'sku', series: [{ metric: 'orders' }] },
      },
    });
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
    expect(query.groupBy?.[0]).toMatchObject({ field: 'items.sku' });
  });
});
