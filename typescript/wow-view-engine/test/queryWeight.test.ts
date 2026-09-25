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
import { filter, type AggregationQuery } from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type FilterNode,
  type Issue,
  type RuntimeLimits,
} from '../src/index.js';
import { validateDataConfig } from '../src/runtime/execute.js';
import { aggregationWeight, queryWeight } from '../src/runtime/queryWeight.js';
import {
  analysisConfig,
  nextTask,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { ordersDescriptor, read } from './fixtures/descriptor.js';

describe('the weight of a compiled query, as the guard counts it', () => {
  it('counts every node of every filter, and the longest list', () => {
    expect(
      queryWeight([
        filter.and([
          filter.eq('a', 1),
          filter.or([
            { op: 'IN', field: 'b', values: [1, 2, 3] } as never,
            filter.ids(['x', 'y']),
          ]),
        ]),
      ]),
    ).toEqual({ nodes: 5, values: 3 });
  });

  it('counts an element predicate inside its match', () => {
    expect(
      queryWeight([filter.elementMatch('items', filter.eq('sku', 'a'))]),
    ).toEqual({ nodes: 2, values: 0 });
  });

  it('counts an aggregation: its filter, each element, each narrowing metric, then its having', () => {
    const query = {
      filter: filter.eq('a', 1),
      elements: [{ path: 'items' }],
      metrics: [
        { type: 'COUNT', alias: 'all', filter: { op: 'MATCH_ALL' } },
        {
          type: 'COUNT',
          alias: 'some',
          filter: { op: 'IN', field: 'b', values: [1, 2] },
        },
      ],
      having: {
        type: 'AND',
        operands: [
          { type: 'IN', metric: 'some', values: [1, 2, 3, 4] },
          { type: 'BETWEEN', metric: 'all', low: 1, high: 2 },
        ],
      },
    } as unknown as AggregationQuery;

    // 1 filter + 1 element (MATCH_ALL) + 1 metric filter + 3 having nodes.
    expect(aggregationWeight(query)).toEqual({ nodes: 6, values: 4 });
  });
});

/**
 * `count` groups of two conditions each — three nodes apiece once compiled
 * (a group of one compiles to its condition alone) — since one group may
 * not name a field twice.
 */
function conditions(count: number): FilterNode[] {
  return Array.from({ length: count }, (_, index) => ({
    op: 'and' as const,
    children: [
      { field: 'warehouse', operator: 'EQ' as const, value: `W${index}` },
      { field: 'status', operator: 'EQ' as const, value: `S${index}` },
    ],
  }));
}

function admitted(
  config: Parameters<typeof validateDataConfig>[1],
  limits: Partial<RuntimeLimits> = {},
): string[] {
  return validateDataConfig(
    {
      definition: ordersDefinition(),
      kinds: builtinFieldKinds,
      limits: { ...DEFAULT_RUNTIME_LIMITS, ...limits },
    },
    config,
  ).map((found: Issue) => found.code);
}

describe('admission weighs the compiled query (C3)', () => {
  it("refuses a record view over the source's node budget before it is sent", () => {
    // A root `or` of 43 groups compiles to 130 nodes, of 42 to 127.
    const over = recordConfig({
      filterMode: 'advanced',
      filter: { op: 'or', children: conditions(43) },
    });
    const under = recordConfig({
      filterMode: 'advanced',
      filter: { op: 'or', children: conditions(42) },
    });

    expect(admitted(over)).toContain('runtime.query.too-many-nodes');
    expect(admitted(under)).not.toContain('runtime.query.too-many-nodes');
  });

  it("refuses a condition over the source's value budget", () => {
    const listed = (count: number) =>
      recordConfig({
        filter: {
          op: 'and',
          children: [
            {
              field: 'warehouse',
              operator: 'IN',
              value: Array.from({ length: count }, (_, i) => `W${i}`),
            },
          ],
        },
      });

    expect(admitted(listed(1_001))).toContain('runtime.query.too-many-values');
    expect(admitted(listed(1_000))).toEqual([]);
    expect(admitted(listed(3), { maxFilterValues: 2 })).toContain(
      'runtime.query.too-many-values',
    );
  });

  it('weighs an analysis as a whole query', () => {
    const config = analysisConfig({
      filterMode: 'advanced',
      filter: { op: 'or', children: conditions(40) },
      metrics: [
        {
          type: 'COUNT',
          alias: 'orders',
          filter: { op: 'or', children: conditions(4) },
        },
      ],
    });

    // 121 filter nodes + 13 metric filter nodes: over 128 together, though
    // neither is alone.
    expect(admitted(config)).toContain('runtime.query.too-many-nodes');
  });

  it("reads the budgets from the source's descriptor, and sends nothing over them", async () => {
    const base = ordersDescriptor();
    const descriptor = {
      ...base,
      limits: { ...base.limits, maxFilterNodes: 3, maxFilterValues: 2 },
    };
    const source = testSource({
      describe: () => Promise.resolve(read(descriptor)),
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'heavy',
            definitionId: 'orders',
            title: 'Heavy',
            scope: 'personal',
            revision: '1',
            config: recordConfig({
              filterMode: 'advanced',
              filter: { op: 'or', children: conditions(2) },
            }),
          },
        ],
      }),
      resolveSource: () => source,
    });

    const runtime = await engine.open('heavy');
    await nextTask();

    expect(runtime.getSnapshot().issues).toContainEqual({
      code: 'runtime.query.too-many-nodes',
      severity: 'error',
      path: ['filter'],
      params: { count: 7, max: 3 },
    });
    expect(source.paged).not.toHaveBeenCalled();
  });
});
