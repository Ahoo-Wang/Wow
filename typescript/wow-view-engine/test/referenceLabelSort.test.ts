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
 * A reference read as names (D354 ⑤): the server sorts and searches the
 * stored id, never the name shown, so it sorts only where the definition
 * asks and says so, a saved sort or a search on it is refused with the fix,
 * and its emptiness is a question of its own.
 */

import {
  filter,
  FilterOperator,
  QueryValueKind,
  QueryValueTypes,
  SortDirection,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  defineView,
  describeFilter,
  projectRecord,
  referenceFieldKind,
  validateDefinition,
  validateRecord,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { recordMessages } from '../src/ui/messages/record.js';
import { definitionMessages } from '../src/ui/messages/definition.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { ordersDefinition, recordConfig } from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const customer: FieldDefinition = {
  name: 'customerId',
  label: 'Customer',
  kind: 'reference',
  remote: 'customers',
};

function withCustomer(
  over: Partial<FieldDefinition> = {},
  more: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [...base.fields, { ...customer, ...over }],
    ...more,
  });
}

function descriptor(): QueryModelDescriptor {
  return ordersDescriptor({
    fields: [
      describedField('id'),
      describedField('customerId'),
      describedField('status'),
    ],
    dynamic: [
      {
        pattern: 'state.tags.{key}',
        types: [QueryValueTypes.INTEGER],
        kind: QueryValueKind.SCALAR,
        filter: { operators: [FilterOperator.EQ, FilterOperator.GT] },
        sort: { paged: false, cursor: false },
        excludedKeys: ['color'],
      },
    ],
  });
}

const define = (fields: Parameters<typeof defineView>[1]['fields']) =>
  defineView(descriptor(), {
    id: 'orders',
    source: 'orders',
    title: 'Orders',
    fields,
  });

const fieldOf = (definition: DataViewDefinition, name: string) =>
  definition.fields.find(field => field.name === name);

describe('defineView on a reference read as names', () => {
  it('offers no sort on it by default, though the descriptor sorts', () => {
    const definition = define({
      id: 'Order',
      customerId: {
        label: 'Customer',
        kind: 'reference',
        more: { remote: 'customers' },
      },
    });
    expect(fieldOf(definition, 'customerId')?.sortable).toBeUndefined();
    // A path read as itself still sorts by default.
    expect(fieldOf(definition, 'id')?.sortable).toBe(true);
  });

  it('sorts by the id where the spec says `sortable: true`', () => {
    const definition = define({
      id: 'Order',
      customerId: {
        label: 'Customer',
        kind: 'reference',
        sortable: true,
        more: { remote: 'customers' },
      },
    });
    expect(fieldOf(definition, 'customerId')?.sortable).toBe(true);
  });

  it('keeps the default for a reference with no remote', () => {
    const definition = define({
      id: 'Order',
      customerId: { label: 'Customer', kind: 'reference' },
    });
    expect(fieldOf(definition, 'customerId')?.sortable).toBe(true);
  });
});

describe('defineView on a dynamic key', () => {
  it("reads its kind off the pattern's types, so it needs no `kind`", () => {
    const definition = define({ id: 'Order', 'state.tags.size': 'Size' });
    expect(fieldOf(definition, 'state.tags.size')).toMatchObject({
      kind: 'number',
    });
    expect(
      definition.described?.findings.map(found => found.code),
    ).not.toContain('definition.field.kind-unknown');
    // A pattern says nothing of sorting.
    expect(fieldOf(definition, 'state.tags.size')?.sortable).toBeUndefined();
  });

  it('finds no kind for a key the pattern excludes and nothing declares', () => {
    const definition = define({ id: 'Order', 'state.tags.color': 'Color' });
    expect(fieldOf(definition, 'state.tags.color')).toBeUndefined();
    expect(definition.described?.findings.map(found => found.code)).toContain(
      'definition.field.undescribed',
    );
  });
});

describe('the column of a reference read as names', () => {
  const columns = (definition: DataViewDefinition) =>
    projectRecord(
      definition,
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'customerId' }] },
      }),
      { total: 0, exact: true, list: [] },
    ).columns;
  const byId = (definition: DataViewDefinition) =>
    columns(definition).find(column => column.field === 'customerId')
      ?.sortsById;

  it('says it sorts by id where it sorts', () => {
    expect(byId(withCustomer({ sortable: true }))).toBe(true);
    expect(
      columns(withCustomer({ sortable: true })).find(
        column => column.field === 'id',
      )?.sortsById,
    ).toBeUndefined();
  });

  it('says nothing where it does not sort, or reads as its id', () => {
    expect(byId(withCustomer())).toBeUndefined();
    expect(
      byId(withCustomer({ sortable: true, remote: undefined })),
    ).toBeUndefined();
  });
});

describe('admitting a sort on a reference read as names', () => {
  const sortBy = (definition: DataViewDefinition) =>
    validateRecord(
      definition,
      recordConfig({
        sort: [{ field: 'customerId', direction: SortDirection.ASC }],
      }),
      builtinFieldKinds,
    );

  it('refuses it where the field does not sort, naming the fix', () => {
    const found = sortBy(withCustomer());
    expect(found).toEqual([
      expect.objectContaining({
        code: 'record.sort.reference-label',
        path: ['sort', 0, 'field'],
        params: { field: 'customerId' },
      }),
    ]);
    expect(recordMessages['record.sort.reference-label']).toContain(
      'customerName',
    );
    expect(zhCN['record.sort.reference-label']).toContain('customerName');
  });

  it('admits it where the definition says it sorts', () => {
    expect(sortBy(withCustomer({ sortable: true }))).toEqual([]);
  });

  it('keeps the plain refusal for a field that holds what it shows', () => {
    const plain = ordersDefinition();
    expect(
      validateRecord(
        plain,
        recordConfig({
          sort: [{ field: 'warehouse', direction: SortDirection.ASC }],
        }),
        builtinFieldKinds,
      ).map(found => found.code),
    ).toEqual(['record.sort.not-sortable']);
  });
});

describe('admitting a search on a reference read as names', () => {
  const searching = (over: Partial<FieldDefinition> = {}) =>
    withCustomer(over, {}).fields.concat({
      name: 'q',
      label: 'Search',
      kind: 'search',
      searchFields: ['warehouse', 'customerId'],
    });

  it('refuses it, naming the field and the fix', () => {
    const found = validateDefinition(
      ordersDefinition({ fields: searching() }),
      builtinFieldKinds,
    ).filter(entry => entry.code === 'definition.search.reference-label');
    expect(found).toEqual([
      expect.objectContaining({
        severity: 'error',
        params: { field: 'q', references: 'customerId' },
      }),
    ]);
    expect(definitionMessages['definition.search.reference-label']).toContain(
      'customerName',
    );
    expect(zhCN['definition.search.reference-label']).toContain('customerName');
  });

  it('admits a reference read as its id', () => {
    expect(
      validateDefinition(
        ordersDefinition({ fields: searching({ remote: undefined }) }),
        builtinFieldKinds,
      ).map(entry => entry.code),
    ).not.toContain('definition.search.reference-label');
  });
});

describe('a reference with no entries', () => {
  const customers: FieldDefinition = {
    ...customer,
    name: 'customerIds',
    label: 'Customers',
  };
  const narrowed = (operators: FilterOperator[]) =>
    narrowDefinition(
      ordersDefinition({ fields: [ordersDefinition().fields[0], customers] }),
      ordersDescriptor({
        fields: [
          describedField('id'),
          describedField('customerIds', {
            kind: QueryValueKind.ARRAY,
            filter: { operators },
          }),
        ],
      }),
      builtinFieldKinds,
    ).definition.fields[1];
  const offered = (field: FieldDefinition) =>
    field.operators ?? referenceFieldKind.operators;

  it('is offered where the descriptor admits it', () => {
    const field = narrowed([
      FilterOperator.IN,
      FilterOperator.NOT_IN,
      FilterOperator.IS_EMPTY,
      FilterOperator.IS_NULL,
    ]);
    expect(offered(field)).toContain(FilterOperator.IS_EMPTY);
  });

  it('is not offered where it does not', () => {
    const field = narrowed([FilterOperator.IN, FilterOperator.NOT_IN]);
    expect(offered(field)).not.toContain(FilterOperator.IS_EMPTY);
  });

  it('asks for no ids, compiles and is described', () => {
    const definition = ordersDefinition({
      fields: [...ordersDefinition().fields, customers],
    });
    const tree: FilterTree = {
      op: 'and',
      children: [
        {
          field: 'customerIds',
          operator: FilterOperator.IS_EMPTY,
          value: { items: [] },
        },
      ],
    };
    expect(
      referenceFieldKind.editor(FilterOperator.IS_EMPTY, customers),
    ).toEqual({ input: 'none' });
    expect(
      compileFilter(definition.fields, tree, builtinFieldKinds, {
        now: new Date(0),
        timeZone: 'UTC',
      }),
    ).toEqual(
      // An empty list, or none at all, as the array kind reads it.
      filter.or([filter.isEmpty('customerIds'), filter.isNull('customerIds')]),
    );
    expect(
      describeFilter(definition.fields, tree, builtinFieldKinds),
    ).toMatchObject([{ relation: 'has-no-entries', value: { kind: 'none' } }]);
  });
});
