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
  AggregationDatePart,
  AggregationGroupType,
  QueryValueKind,
  SearchMode,
  SensitivityLevel,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  defineView,
  MemoryViewStore,
  text,
  validateDefinition,
  ViewEngine,
  type DefineViewSpec,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';
import { testSource, resourcesOf } from './fixtures.js';

/**
 * The review of #3744: each finding, held by the test that failed before
 * its fix.
 */
const spec = (
  fields: DefineViewSpec['fields'],
  more: Partial<DefineViewSpec> = {},
): DefineViewSpec => ({
  id: 'orders',
  source: 'orders',
  title: 'Orders',
  fields,
  ...more,
});

const codes = (found: readonly { code: string }[]) =>
  found.map(entry => entry.code);

describe('defineView, as reviewed', () => {
  it('1: a sort the snapshot offers and a source does not is taken away as a hand-written one is, and said', () => {
    const definition = defineView(ordersDescriptor(), spec({ id: 'Order' }));
    const lacking = ordersDescriptor({
      fields: [describedField('id', { sort: { paged: false, cursor: false } })],
    });
    const { definition: narrowed, findings } = narrowDefinition(
      definition,
      lacking,
      builtinFieldKinds,
    );
    expect(narrowed.fields[0].sortable).toBe(false);
    expect(codes(findings)).toContain('capability.field.unsortable');
    // The row key is `id`: a page order that cannot be kept is an error.
    expect(codes(findings)).toContain('capability.record.row-key-unsortable');
  });

  it('6: a snapshot with no metric offers no analyses rather than refusing the definition, and a source that has some fills them in', () => {
    const none = ordersDescriptor({
      fields: [describedField('id', { aggregate: undefined })],
    });
    none.analysis = { ...none.analysis, metrics: [] };
    const definition = defineView(none, spec({ id: 'Order' }));
    expect(definition.analysis).toBeUndefined();
    expect(
      codes(validateDefinition(definition, builtinFieldKinds)),
    ).not.toContain('definition.analysis.no-metric');
    const narrowed = narrowDefinition(
      definition,
      ordersDescriptor(),
      builtinFieldKinds,
    ).definition;
    expect(narrowed.analysis?.count).toBe(true);
  });

  it('7: with no descriptor of its source, a definition offers the snapshot’s comparisons and calendar parts', () => {
    const snapshot = ordersDescriptor({
      fields: [
        describedField('id', { filter: { operators: ['EQ'] as never } }),
        describedField('createdAt', {
          types: ['INTEGER'],
          semantic: { type: 'TEMPORAL_EPOCH' },
        }),
        describedField('lines', {
          types: ['OBJECT'],
          kind: QueryValueKind.ARRAY,
        }),
        describedField('lines.sku', { scope: 'lines' }),
      ],
      elements: [{ path: 'lines', filter: true, aggregate: true }],
    });
    snapshot.analysis = {
      ...snapshot.analysis,
      dateParts: [AggregationDatePart.HOUR_OF_DAY],
    };
    const definition = defineView(
      snapshot,
      spec({
        id: 'Order',
        createdAt: 'Created',
        lines: {
          label: 'Lines',
          elements: {
            sku: 'SKU',
            q: { label: 'Search lines', search: { fields: ['sku'] } },
          },
        },
      }),
    );
    const [id, , lines] = definition.fields;
    expect(id.operators).toEqual(['EQ']);
    // A search box is the host's to ask for: offered as declared where the
    // source says nothing, narrowed where it does (below, and on MongoDB).
    expect(
      lines.elements?.find(entry => entry.name === 'q')?.operators,
    ).toBeUndefined();
    expect(
      narrowDefinition(
        definition,
        snapshot,
        builtinFieldKinds,
      ).definition.fields[2].elements?.find(entry => entry.name === 'q')
        ?.operators,
    ).toEqual([]);
    expect(
      definition.analysis?.fields.find(entry => entry.field === 'createdAt')
        ?.dateParts,
    ).toEqual(['HOUR_OF_DAY']);
    // A source that says more: the kind's comparisons, the search.
    const elastic = narrowDefinition(
      definition,
      {
        ...snapshot,
        fields: snapshot.fields.map(field =>
          field.path === 'id' ? describedField('id') : field,
        ),
        elements: [
          {
            path: 'lines',
            filter: true,
            aggregate: true,
            search: { modes: [SearchMode.TERMS], fields: ['lines.sku'] },
          },
        ],
      },
      builtinFieldKinds,
    ).definition;
    // Every comparison of its kind, as this source admits them all.
    expect(elastic.fields[0].operators).toBeUndefined();
    expect(
      elastic.fields[2].elements?.find(entry => entry.name === 'q')?.operators,
    ).not.toEqual([]);
  });

  it('8: a masked value listed by its alias is never offered to analyses', () => {
    const masked = ordersDescriptor({
      fields: [
        describedField('id'),
        describedField('email', {
          aliases: ['mail'],
          sensitivity: { level: SensitivityLevel.DISPLAY, comparable: true },
        }),
      ],
    });
    const definition = defineView(masked, spec({ id: 'Order', mail: 'Mail' }));
    const analysed = (fields: readonly { field: string }[] = []) =>
      fields.map(entry => entry.field);
    expect(analysed(definition.analysis?.fields)).toEqual(['id']);
    expect(
      analysed(
        narrowDefinition(definition, masked, builtinFieldKinds).definition
          .analysis?.fields,
      ),
    ).toEqual(['id']);
  });

  it('9: a time field named by an alias is read under its path', () => {
    const aliased = ordersDescriptor({
      fields: [
        describedField('id'),
        describedField('createdAt', {
          types: ['INTEGER'],
          semantic: { type: 'TEMPORAL_EPOCH' },
          aliases: ['created'],
        }),
      ],
    });
    const definition = defineView(
      aliased,
      spec(
        { id: 'Order', created: 'Created' },
        {
          timeField: 'created',
          views: [
            {
              id: 'by-day',
              title: 'By day',
              timeField: 'created',
              config: {
                kind: 'record',
                filter: { op: 'and', children: [] },
                filterMode: 'simple',
                refresh: { interval: null },
                sort: [],
                pageSize: 20,
                layout: 'table',
                summaries: [],
                table: { columns: [{ field: 'id' }] },
                card: { title: 'id', fields: [] },
              },
            },
          ],
        },
      ),
    );
    const narrowed = narrowDefinition(
      definition,
      aliased,
      builtinFieldKinds,
    ).definition;
    expect(narrowed.timeField).toBe('createdAt');
    expect(narrowed.views?.[0].timeField).toBe('createdAt');
  });

  it('11: a field only one variant of an entry has is read off that variant', () => {
    const variants = ordersDescriptor({
      fields: [
        describedField('lines', {
          types: ['OBJECT'],
          kind: QueryValueKind.ARRAY,
        }),
      ],
      elements: [{ path: 'lines', filter: true, aggregate: true }],
      variants: {
        element: 'lines',
        discriminator: 'type',
        values: [
          {
            value: 'Shipped',
            fields: [describedField('weight', { types: ['INTEGER'] })],
          },
        ],
      },
    });
    const definition = defineView(
      variants,
      spec({ lines: { label: 'Lines', elements: { weight: 'Weight' } } }),
    );
    expect(definition.described?.findings).toEqual([]);
    expect(definition.fields[0].elements?.[0].kind).toBe('number');
  });

  it('12: keeps the order of a list of values, and a value named like a prototype member', () => {
    const numbered = ordersDescriptor({
      fields: [
        describedField('priority', {
          types: ['INTEGER'],
          enum: [{ value: 1 }, { value: 2 }, { value: 10 }],
        }),
        describedField('state', {
          enum: [{ value: 'toString' }, { value: 'OPEN' }],
        }),
      ],
    });
    const [priority, state] = defineView(
      numbered,
      spec({
        priority: {
          label: 'Priority',
          options: [
            [10, 'Urgent'],
            [2, 'Soon'],
            [1, 'Later'],
          ],
        },
        state: { label: 'State', options: { OPEN: 'Open' } },
      }),
    ).fields;
    expect(priority.options?.map(option => option.value)).toEqual([10, 2, 1]);
    expect(state.options?.map(option => option.value)).toEqual([
      'OPEN',
      'toString',
    ]);
  });

  it('13: calls a host’s catalogue as its method, on the host', () => {
    const base = defineView(
      ordersDescriptor(),
      spec({ id: text('orders.id') }, { title: text('orders.title') }),
    );
    const host = {
      words: { 'orders.title': 'Orders', 'orders.id': 'Order' } as Record<
        string,
        string
      >,
      text(key: string) {
        return this.words[key];
      },
    };
    const engine = new ViewEngine({
      resources: resourcesOf([base], () => testSource()),
      store: new MemoryViewStore(),
      text: host.text.bind(host),
    });
    // The definition keeps its key; the words the engine started with are
    // read off the host's catalogue, called as its method.
    expect(engine.definitions.get('orders')?.title).toBe(text('orders.title'));
    expect(engine.startingWord('orders.title')).toBe('Orders');
    const unbound = {
      resources: resourcesOf([base], () => testSource()),
      store: new MemoryViewStore(),
      words: host.words,
      text(key: string) {
        return (this as unknown as typeof host).words[key];
      },
    };
    expect(new ViewEngine(unbound).startingWord('orders.title')).toBe('Orders');
  });

  it('says what a source lacks of what the snapshot offered, as it does for a hand-written definition', () => {
    const snapshot = ordersDescriptor({
      fields: [
        describedField('id'),
        describedField('amount', {
          types: ['DECIMAL'],
          aggregate: {
            ...describedField('amount').aggregate!,
            groups: [
              AggregationGroupType.TERMS,
              AggregationGroupType.HISTOGRAM,
            ],
          },
        }),
      ],
    });
    const definition = defineView(
      snapshot,
      spec({ id: 'Order', amount: 'Amount' }),
    );
    const lesser = {
      ...snapshot,
      fields: snapshot.fields.map(field =>
        field.path === 'amount'
          ? {
              ...field,
              aggregate: {
                ...field.aggregate!,
                groups: [AggregationGroupType.TERMS],
              },
            }
          : field,
      ),
    };
    const { definition: narrowed, findings } = narrowDefinition(
      definition,
      lesser,
      builtinFieldKinds,
    );
    expect(
      narrowed.analysis?.fields.find(entry => entry.field === 'amount')?.groups,
    ).toEqual(['TERMS']);
    expect(codes(findings)).toContain('capability.analysis.field-narrowed');
  });
});
