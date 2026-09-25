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

import { describe, expect, it, vi } from 'vitest';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  MemoryViewStore,
  recordProjection,
  ViewEngine,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  narrowDefinition,
  withCanonicalNames,
} from '../src/capabilities/index.js';
import {
  analysisConfig,
  nextTask,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

/**
 * Orders whose `amount` the service renamed to `total`: it lists `total`,
 * with `amount` as an alias (#3519).
 */
function renamedDescriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    fields: base.fields.map(field =>
      field.path === 'amount'
        ? { ...describedField('total'), aliases: ['amount'] }
        : field,
    ),
  };
}

describe('a field the definition names by an alias', () => {
  it('is renamed to the path, with everything that names it, and noted', () => {
    const declared: DataViewDefinition = {
      ...ordersDefinition(),
      fieldGroups: [{ id: 'money', label: 'Money', fields: ['amount'] }],
    };
    const { definition, findings } = narrowDefinition(
      declared,
      renamedDescriptor(),
      builtinFieldKinds,
    );

    expect(definition.fields.map(field => field.name)).toEqual([
      'id',
      'warehouse',
      'status',
      'total',
    ]);
    expect(definition.fields[3].label).toBe('Amount');
    expect(definition.fieldGroups?.[0].fields).toEqual(['total']);
    expect(definition.analysis?.fields.map(entry => entry.field)).toEqual([
      'warehouse',
      'total',
    ]);
    expect(definition.views?.[0].config).toMatchObject({
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
    });
    expect(definition.narrowing?.renamed).toEqual({ amount: 'total' });
    expect(findings).toEqual([
      {
        code: 'capability.field.alias',
        severity: 'note',
        path: ['fields', 3],
        params: { field: 'amount', path: 'total' },
      },
    ]);
    // A page asks for what the source answers by.
    expect(
      recordProjection(
        definition,
        withCanonicalNames(recordConfig(), { amount: 'total' }),
      ).include,
    ).toContain('total');
  });

  it('reads a config saved under the alias under the path', () => {
    const renamed = { amount: 'total' };
    const record = withCanonicalNames(
      recordConfig({
        filter: {
          op: 'and',
          children: [
            {
              op: 'or',
              children: [{ field: 'amount', operator: 'GT', value: 10 }],
            },
          ],
        },
        sort: [{ field: 'amount', direction: 'DESC' }],
        summaries: [{ field: 'amount', fn: 'SUM' }],
        card: { title: 'id', fields: ['amount'], image: 'amount' },
      }),
      renamed,
    );
    expect(record).toMatchObject({
      filter: {
        children: [
          { children: [{ field: 'total', operator: 'GT', value: 10 }] },
        ],
      },
      sort: [{ field: 'total', direction: 'DESC' }],
      summaries: [{ field: 'total', fn: 'SUM' }],
      table: { columns: [{ field: 'id' }, { field: 'total' }] },
      card: { title: 'id', fields: ['total'], image: 'total' },
    });

    const analysis = withCanonicalNames(
      analysisConfig({
        groups: [
          { alias: 'a', field: 'amount', type: 'HISTOGRAM', interval: 10 },
        ],
        metrics: [
          {
            alias: 'sum',
            type: 'NUMERIC',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: 1 },
            },
            filter: {
              op: 'and',
              children: [{ field: 'amount', operator: 'GT', value: 0 }],
            },
          },
          { alias: 'any', type: 'ANY', field: 'amount' },
        ],
      }),
      renamed,
    );
    expect(analysis).toMatchObject({
      groups: [{ field: 'total' }],
      metrics: [
        {
          expression: { left: { field: 'total' } },
          filter: { children: [{ field: 'total' }] },
        },
        { field: 'total' },
      ],
    });
    // Nothing to rename: the same config back.
    const plain = recordConfig({
      table: { columns: [{ field: 'id' }] },
      card: { title: 'id', fields: [] },
    });
    expect(withCanonicalNames(plain, renamed)).toBe(plain);
    expect(withCanonicalNames(plain, {})).toBe(plain);
  });

  it('opens a view saved under the alias under the path, not dirty, and asks by it', async () => {
    const saved: ViewInstance = {
      id: 'by-amount',
      definitionId: 'orders',
      title: 'By amount',
      scope: 'personal',
      revision: '1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const descriptor = renamedDescriptor();
    const paged = vi.fn(() => Promise.resolve({ total: 0, list: [] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () =>
        testSource({
          paged,
          describe: () => Promise.resolve(read(descriptor)),
        }),
    });

    const runtime = await engine.open('by-amount');
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.issues).toEqual([]);
    expect(state.dirty).toBe(false);
    expect(paged).toHaveBeenCalledWith(
      expect.objectContaining({
        sort: [
          { field: 'total', direction: 'DESC' },
          { field: 'id', direction: 'ASC' },
        ],
      }),
      undefined,
      expect.anything(),
    );
  });

  it('renames an open view when a new version starts naming a field by its path', async () => {
    const saved: ViewInstance = {
      id: 'by-amount',
      definitionId: 'orders',
      title: 'By amount',
      scope: 'personal',
      revision: '1',
      config: recordConfig({ sort: [{ field: 'amount', direction: 'DESC' }] }),
    };
    const describe = vi
      .fn()
      .mockResolvedValueOnce(read(ordersDescriptor()))
      .mockResolvedValueOnce(
        read({ ...renamedDescriptor(), version: 'sha256:orders-2' }),
      );
    const clock = testEnvironment();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () => testSource({ describe }),
      environment: clock.environment,
    });
    const runtime = await engine.open('by-amount');
    await nextTask();

    clock.advance(5 * 60 * 1000);
    runtime.refresh();
    await nextTask();

    const state = runtime.getSnapshot();
    expect(state.draft).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.applied).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.saved?.config).toMatchObject({
      sort: [{ field: 'total', direction: 'DESC' }],
    });
    expect(state.dirty).toBe(false);
  });

  it('matches a search field and an element field by an alias too', () => {
    const declared = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'elementMatch',
          elements: [{ name: 'code', label: 'Code', kind: 'string' }],
        },
        {
          name: 'q',
          label: 'Search',
          kind: 'search',
          searchFields: ['amount'],
        },
      ],
      analysis: {
        count: true,
        fields: [],
        elements: [
          {
            path: 'items',
            aggregations: [
              {
                field: 'code',
                groups: [AggregationGroupType.TERMS],
                functions: [AggregationFunction.SUM],
              },
            ],
          },
        ],
      },
    });
    const base = renamedDescriptor();
    const descriptor: QueryModelDescriptor = {
      ...base,
      fields: [
        ...base.fields,
        describedField('items'),
        {
          ...describedField('items.sku', { scope: 'items' }),
          aliases: ['items.code'],
        },
      ],
      elements: [{ path: 'items', filter: true, aggregate: true }],
      record: {
        ...base.record,
        search: { modes: ['TERMS'] as never, fields: ['total'] },
      },
    };
    const { definition } = narrowDefinition(
      declared,
      descriptor,
      builtinFieldKinds,
    );

    const items = definition.fields.find(field => field.name === 'items');
    expect(items?.elements?.[0].name).toBe('sku');
    expect(
      definition.fields.find(field => field.name === 'q')?.searchFields,
    ).toEqual(['total']);
    expect(definition.analysis?.elements?.[0].aggregations[0].field).toBe(
      'sku',
    );
    expect(definition.narrowing?.renamed).toEqual({
      amount: 'total',
      'items.code': 'items.sku',
    });
  });
});
