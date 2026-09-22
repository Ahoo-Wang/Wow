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

import { AggregationGroupType } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  collapsed,
  expanded,
  nextExpansion,
  withElements,
} from '../src/analysis/index.js';
import type { AnalysisViewConfig } from '../src/model/index.js';
import {
  analysisCapability as capability,
  analysisDefinition as definition,
  analysisKernelConfig as config,
} from './fixtures/analysis.js';

/** Orders holding items holding batches: a two-level chain. */
const chained = definition({
  fields: [
    ...definition().fields,
    {
      name: 'items',
      label: 'Items',
      kind: 'array',
      elements: [
        { name: 'sku', label: 'SKU', kind: 'string' },
        { name: 'qty', label: 'Qty', kind: 'number' },
        {
          name: 'batches',
          label: 'Batches',
          kind: 'array',
          elements: [{ name: 'lot', label: 'Lot', kind: 'string' }],
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
          { field: 'sku', groups: [AggregationGroupType.TERMS], functions: [] },
          { field: 'qty', groups: [], functions: ['SUM' as never] },
        ],
      },
      {
        path: 'batches',
        aggregations: [
          { field: 'lot', groups: [AggregationGroupType.TERMS], functions: [] },
        ],
      },
    ],
  },
});
const chain = chained.analysis!;

describe('withElements', () => {
  it('takes the dimensions and metrics of the old unit with it, and starts the metrics again', () => {
    const rescoped = withElements(
      config(),
      expanded([], 'items'),
      chained,
      chain,
    );
    // The warehouse is the order's; inside an item it names nothing.
    expect(rescoped.elements).toEqual([{ path: 'items' }]);
    expect(rescoped.groups).toEqual([]);
    // The count survives: an item can be counted as an order can.
    expect(rescoped.metrics).toMatchObject([{ type: 'COUNT' }]);
  });

  it('keeps what still names a field of the new unit, and drops a condition that does not', () => {
    const inside: AnalysisViewConfig = config({
      elements: [{ path: 'items' }],
      groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
      metrics: [
        {
          type: 'NUMERIC',
          alias: 'qty',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'items.qty' },
          filter: {
            op: 'and',
            children: [{ field: 'warehouse', operator: 'EQ', value: 'SH' }],
          },
        },
        { type: 'COUNT', alias: 'n' },
      ],
    });
    const deeper = withElements(
      inside,
      expanded(inside.elements!, 'batches'),
      chained,
      chain,
    );
    expect(deeper.elements).toEqual([{ path: 'items' }, { path: 'batches' }]);
    // The sku and the quantity are the item's, not the batch's.
    expect(deeper.groups).toEqual([]);
    expect(deeper.metrics).toEqual([{ type: 'COUNT', alias: 'n' }]);

    // Back out to the item: the item's own question stands, but the
    // condition on the order's warehouse leaves the metric.
    const back = withElements(
      inside,
      collapsed(inside.elements!, 1),
      chained,
      chain,
    );
    expect(back.groups).toEqual(inside.groups);
    expect(back.metrics).toEqual([
      {
        type: 'NUMERIC',
        alias: 'qty',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'items.qty' },
      },
      { type: 'COUNT', alias: 'n' },
    ]);
  });

  it('starts from the first thing the unit can count when nothing is countable', () => {
    const uncountable = definition({
      ...chained,
      analysis: { ...chain, count: false },
    });
    const rescoped = withElements(
      config({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'amount',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      }),
      expanded([], 'items'),
      uncountable,
      uncountable.analysis!,
    );
    expect(rescoped.metrics).toEqual([
      {
        type: 'NUMERIC',
        alias: 'items_qty_sum',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'items.qty' },
      },
    ]);
  });

  it('drops a derived metric whose operands left', () => {
    const rescoped = withElements(
      config({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'amount',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
          { type: 'COUNT', alias: 'n' },
          {
            type: 'DERIVED',
            alias: 'avg',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE' as never,
              left: { type: 'METRIC_REF', metric: 'amount' },
              right: { type: 'METRIC_REF', metric: 'n' },
            },
          },
        ],
      }),
      expanded([], 'items'),
      chained,
      chain,
    );
    expect(rescoped.metrics).toEqual([{ type: 'COUNT', alias: 'n' }]);
  });

  it('walks the declared chain one step at a time', () => {
    expect(nextExpansion(['items', 'batches'], [])).toBe('items');
    expect(nextExpansion(['items', 'batches'], [{ path: 'items' }])).toBe(
      'batches',
    );
    expect(
      nextExpansion(
        ['items', 'batches'],
        [{ path: 'items' }, { path: 'batches' }],
      ),
    ).toBeUndefined();
    expect(collapsed([{ path: 'items' }, { path: 'batches' }], 0)).toEqual([]);
    expect(collapsed([{ path: 'items' }, { path: 'batches' }], 1)).toEqual([
      { path: 'items' },
    ]);
  });
});
