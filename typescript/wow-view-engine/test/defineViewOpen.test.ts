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

import {
  AggregationGroupType,
  SearchMode,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  defineView,
  MemoryViewStore,
  ViewEngine,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';
import { recordConfig, testSource, resourcesOf } from './fixtures.js';

/**
 * D67, revised: a descriptor's facts are the model's, its capabilities the
 * store's. A definition built from one store's snapshot takes whatever the
 * store it runs on grants where the host narrowed nothing — so one
 * definition offers on Elasticsearch what MongoDB does not have.
 */
function store(
  options: {
    searchesLines?: boolean;
    sortsNoted?: boolean;
    histogram?: boolean;
  } = {},
): QueryModelDescriptor {
  const base = ordersDescriptor();
  const amountGroups = options.histogram
    ? [AggregationGroupType.TERMS, AggregationGroupType.HISTOGRAM]
    : [AggregationGroupType.TERMS];
  return {
    ...base,
    fields: [
      describedField('id'),
      describedField('amount', {
        types: ['DECIMAL'],
        aggregate: {
          ...describedField('amount').aggregate!,
          groups: amountGroups,
        },
      }),
      describedField('noted', {
        sort: { paged: options.sortsNoted ?? false, cursor: false },
      }),
      describedField('lines', { types: ['OBJECT'] }),
      describedField('lines.sku', { scope: 'lines' }),
      describedField('lines.title', { scope: 'lines' }),
    ],
    elements: [
      {
        path: 'lines',
        filter: true,
        aggregate: true,
        ...(options.searchesLines
          ? {
              search: {
                modes: [SearchMode.TERMS, SearchMode.PHRASE],
                fields: ['lines.sku', 'lines.title'],
              },
            }
          : {}),
      },
    ],
  };
}

/** Built from the MongoDB-like snapshot: no line search, `noted` unsorted. */
const MONGO = store();
const ELASTIC = store({
  searchesLines: true,
  sortsNoted: true,
  histogram: true,
});

const orders = defineView(MONGO, {
  id: 'orders',
  source: 'orders',
  title: 'Orders',
  fields: {
    id: 'Order',
    amount: 'Amount',
    noted: 'Noted',
    lines: {
      label: 'Lines',
      elements: {
        sku: 'SKU',
        title: 'Title',
        q: { label: 'Search lines', search: { fields: ['title', 'sku'] } },
      },
    },
  },
});

const narrowed = (descriptor: QueryModelDescriptor) =>
  narrowDefinition(orders, descriptor, builtinFieldKinds).definition;

const field = (definition: typeof orders, name: string) =>
  definition.fields.find(entry => entry.name === name);

describe('a definition built from one store’s snapshot', () => {
  it('runs on the snapshot’s capabilities where its source says none', () => {
    expect(field(orders, 'noted')?.sortable).toBeUndefined();
    expect(
      orders.analysis?.fields.find(entry => entry.field === 'amount')?.groups,
    ).toEqual(['TERMS']);
  });

  it('offers a search inside the entries where the store searches them, and not where it does not', () => {
    const search = (definition: typeof orders) =>
      field(definition, 'lines')?.elements?.find(entry => entry.name === 'q');
    expect(search(narrowed(ELASTIC))?.operators).not.toEqual([]);
    expect(search(narrowed(MONGO))?.operators).toEqual([]);
  });

  it('sorts and aggregates as the store it runs on grants', () => {
    const elastic = narrowed(ELASTIC);
    expect(field(elastic, 'noted')?.sortable).toBe(true);
    expect(
      elastic.analysis?.fields.find(entry => entry.field === 'amount')?.groups,
    ).toEqual(['TERMS', 'HISTOGRAM']);
    expect(field(narrowed(MONGO), 'noted')?.sortable).toBeUndefined();
  });

  it('keeps the host’s narrowing within what the store grants', () => {
    const narrow = defineView(MONGO, {
      id: 'orders',
      source: 'orders',
      title: 'Orders',
      fields: {
        noted: { label: 'Noted', sortable: false },
        amount: {
          label: 'Amount',
          analysis: { groups: [AggregationGroupType.TERMS] },
        },
      },
    });
    const elastic = narrowDefinition(
      narrow,
      ELASTIC,
      builtinFieldKinds,
    ).definition;
    expect(field(elastic, 'noted')?.sortable).toBeUndefined();
    expect(
      elastic.analysis?.fields.find(entry => entry.field === 'amount')?.groups,
    ).toEqual(['TERMS']);
  });

  it('is what an engine runs a view on, over each source’s descriptor', async () => {
    const open = async (descriptor: QueryModelDescriptor) => {
      const engine = new ViewEngine({
        resources: resourcesOf(
          [
            {
              ...orders,
              views: [
                {
                  id: 'all',
                  title: 'All',
                  config: recordConfig({
                    sort: [],
                    table: { columns: [{ field: 'id' }] },
                    card: { title: 'id', fields: [] },
                  }),
                },
              ],
            },
          ],
          () =>
            testSource({ describe: () => Promise.resolve(read(descriptor)) }),
        ),
        store: new MemoryViewStore(),
      });
      const runtime = await engine.open('system:orders:all');
      const sortable = field(
        runtime.definition as typeof orders,
        'noted',
      )?.sortable;
      runtime.dispose();
      return sortable;
    };
    expect(await open(ELASTIC)).toBe(true);
    expect(await open(MONGO)).toBeUndefined();
  });
});
