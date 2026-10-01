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
  AggregationDateUnit,
  AggregationGroupType,
  QueryValueKind,
  SensitivityLevel,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  defineView,
  validateDefinition,
  type DefineViewSpec,
  type Issue,
} from '../src/index.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';
import { recordConfig } from './fixtures.js';

/**
 * A model with one of each thing a descriptor says: text, a category with
 * values, a number, a flag, a moment, an array of entries, a deprecated
 * path and two protected ones.
 */
function descriptor(): QueryModelDescriptor {
  const base = ordersDescriptor();
  return {
    ...base,
    version: 'sha256:define-1',
    fields: [
      describedField('id'),
      describedField('status', {
        enum: [
          { value: 'PENDING', description: 'Pending' },
          { value: 'SHIPPED' },
          { value: 'CANCELLED' },
        ],
        description: 'Order status',
      }),
      describedField('amount', { types: ['DECIMAL'] }),
      describedField('paid', { types: ['BOOLEAN'] }),
      describedField('createdAt', {
        types: ['INTEGER'],
        semantic: { type: 'TEMPORAL_EPOCH' },
      }),
      describedField('legacy', { deprecated: { message: 'use status' } }),
      describedField('email', {
        sensitivity: { level: SensitivityLevel.DISPLAY, comparable: true },
      }),
      describedField('secret', {
        sensitivity: {
          level: SensitivityLevel.CONFIDENTIAL,
          comparable: false,
        },
        filter: { operators: [] },
        sort: { paged: false, cursor: false },
      }),
      describedField('lines', {
        types: ['OBJECT'],
        kind: QueryValueKind.ARRAY,
      }),
      describedField('lines.sku', { scope: 'lines' }),
      describedField('lines.qty', { scope: 'lines', types: ['INTEGER'] }),
      describedField('noted', {
        sort: { paged: false, cursor: false },
      }),
    ],
    elements: [{ path: 'lines', filter: true, aggregate: true }],
  };
}

function define(spec: Partial<DefineViewSpec> = {}) {
  return defineView(descriptor(), {
    id: 'orders',
    source: 'orders',
    title: 'Orders',
    fields: { id: 'Order' },
    ...spec,
  });
}

const codes = (found: readonly Issue[]) => found.map(entry => entry.code);

describe('defineView', () => {
  it('shows the fields listed, in the order listed, and no other', () => {
    const definition = define({
      fields: { amount: 'Amount', id: 'Order', status: 'Status' },
    });
    expect(definition.fields.map(field => field.name)).toEqual([
      'amount',
      'id',
      'status',
    ]);
    expect(definition.kind).toBe('data');
    expect(definition.described).toMatchObject({
      version: 'sha256:define-1',
      findings: [],
      // What sorts and aggregates is left to the source (D67).
      open: { sort: ['amount', 'id', 'status'] },
    });
  });

  it('reads each kind off the descriptor', () => {
    const { fields } = define({
      fields: {
        id: 'Order',
        amount: 'Amount',
        paid: 'Paid',
        createdAt: 'Created',
        status: 'Status',
      },
    });
    expect(fields.map(field => [field.name, field.kind])).toEqual([
      ['id', 'string'],
      ['amount', 'number'],
      ['paid', 'boolean'],
      ['createdAt', 'datetime'],
      ['status', 'enum'],
    ]);
  });

  it('keeps a moment’s seconds in a table cell where the host asks', () => {
    const { fields } = define({
      fields: {
        id: 'Order',
        createdAt: { label: 'Created', timePrecision: 'second' },
      },
    });
    expect(fields[1]).toMatchObject({
      name: 'createdAt',
      kind: 'datetime',
      timePrecision: 'second',
    });
  });

  it('lists a category’s values, the host’s first in its words, one hidden', () => {
    const [status] = define({
      fields: {
        status: {
          label: 'Status',
          options: {
            SHIPPED: { label: 'Shipped', tone: 'success' },
            CANCELLED: false,
          },
        },
      },
    }).fields;
    expect(status.options).toEqual([
      { value: 'SHIPPED', label: 'Shipped', tone: 'success' },
      { value: 'PENDING', label: 'Pending' },
    ]);
  });

  it('refuses a value the descriptor does not list', () => {
    const definition = define({
      fields: { status: { label: 'Status', options: { LOST: 'Lost' } } },
    });
    expect(codes(definition.described?.findings ?? [])).toEqual([
      'definition.option.undescribed',
    ]);
  });

  it('labels an unlabelled field by its description, and notes it', () => {
    const definition = define({ fields: { status: {} } });
    expect(definition.fields[0].label).toBe('Order status');
    expect(definition.described?.findings).toEqual([
      expect.objectContaining({
        code: 'definition.field.unlabelled',
        severity: 'note',
      }),
    ]);
  });

  it('sorts where the path sorts, unless the host says not', () => {
    const { fields, described } = define({
      fields: {
        id: 'Order',
        amount: { label: 'Amount', sortable: false },
        noted: { label: 'Noted', sortable: true },
      },
    });
    expect(fields.map(field => field.sortable)).toEqual([
      true,
      undefined,
      undefined,
    ]);
    expect(codes(described?.findings ?? [])).toEqual([
      'definition.field.sort-wider',
    ]);
  });

  it('takes a subset of the path’s operators and summaries, and refuses more', () => {
    const narrow = descriptor();
    narrow.fields = narrow.fields.map(field =>
      field.path === 'amount'
        ? {
            ...field,
            filter: { operators: ['EQ', 'GT'] as never },
            aggregate: { ...field.aggregate!, functions: ['SUM'] },
          }
        : field,
    );
    const definition = defineView(narrow, {
      id: 'orders',
      source: 'orders',
      title: 'Orders',
      fields: {
        amount: {
          label: 'Amount',
          operators: ['EQ', 'LT'],
          summary: ['SUM', 'AVG'],
        },
      },
    });
    expect(definition.fields[0].operators).toEqual(['EQ', 'LT']);
    expect(definition.described?.findings).toEqual([
      expect.objectContaining({
        code: 'definition.field.operator-wider',
        params: { field: 'amount', operator: 'LT' },
      }),
      expect.objectContaining({
        code: 'definition.field.summary-wider',
        params: { field: 'amount', fn: 'AVG' },
      }),
    ]);
  });

  it('leaves out a path the descriptor lacks, and says so', () => {
    const definition = define({ fields: { id: 'Order', gone: 'Gone' } });
    expect(definition.fields.map(field => field.name)).toEqual(['id']);
    expect(definition.described?.findings).toEqual([
      expect.objectContaining({
        code: 'definition.field.undescribed',
        params: { field: 'gone' },
        severity: 'error',
      }),
    ]);
  });

  it('keeps a deprecated path with a reason, and warns without one', () => {
    expect(
      codes(define({ fields: { legacy: 'Legacy' } }).described?.findings ?? []),
    ).toEqual(['definition.field.deprecated']);
    const kept = define({
      fields: { legacy: { label: 'Legacy', deprecated: { message: 'old' } } },
    });
    expect(kept.described?.findings).toEqual([]);
    expect(kept.fields[0].deprecated).toEqual({ message: 'old' });
  });

  it('keeps a sensitive value out of analyses, and a confidential one out of every comparison', () => {
    const definition = define({
      fields: { id: 'Order', email: 'Email', secret: 'Secret' },
    });
    const [, email, secret] = definition.fields;
    expect(email.operators).toBeUndefined();
    expect(secret.operators).toEqual([]);
    expect(secret.sortable).toBeUndefined();
    expect(definition.analysis?.fields.map(field => field.field)).toEqual([
      'id',
    ]);
  });

  it('builds an array’s entries nested, matched by entry, and aggregated over', () => {
    const definition = define({
      fields: {
        lines: {
          label: 'Lines',
          elements: { sku: 'SKU', qty: 'Quantity' },
          elementTitle: 'sku',
        },
      },
    });
    const [lines] = definition.fields;
    expect(lines.kind).toBe('elementMatch');
    expect(lines.elementTitle).toBe('sku');
    expect(lines.elements?.map(field => [field.name, field.kind])).toEqual([
      ['sku', 'string'],
      ['qty', 'number'],
    ]);
    // An entry's field orders nothing on its own.
    expect(lines.elements?.some(field => field.sortable)).toBe(false);
    expect(definition.analysis?.elements).toEqual([
      expect.objectContaining({
        path: 'lines',
        aggregations: [
          expect.objectContaining({ field: 'sku' }),
          expect.objectContaining({ field: 'qty' }),
        ],
      }),
    ]);
    const matched = define({
      fields: { lines: { operators: ['ELEMENT_MATCH'], elements: {} } },
    });
    expect(codes(matched.described?.findings ?? [])).toEqual([
      'definition.field.unlabelled',
    ]);
  });

  it('writes a search box over paths the descriptor has', () => {
    const definition = define({
      fields: {
        keyword: { label: 'Search', search: { fields: ['id', 'nope'] } },
      },
    });
    expect(definition.fields[0]).toMatchObject({
      name: 'keyword',
      label: 'Search',
      kind: 'search',
      searchFields: ['id', 'nope'],
    });
    expect(codes(definition.described?.findings ?? [])).toEqual([
      'definition.field.undescribed',
    ]);
  });

  it('takes records from the descriptor: its identity, paged, as a table', () => {
    expect(define().record).toEqual({
      rowKey: 'id',
      paging: 'paged',
      layouts: ['table'],
    });
    expect(define({ record: false }).record).toBeUndefined();
    const cursorOnly = descriptor();
    cursorOnly.record = { ...cursorOnly.record, paging: ['CURSOR'] as never };
    const definition = defineView(cursorOnly, {
      id: 'orders',
      source: 'orders',
      title: 'Orders',
      fields: { id: 'Order' },
      record: { paging: 'paged' },
    });
    expect(codes(definition.described?.findings ?? [])).toEqual([
      'definition.record.paging-wider',
    ]);
  });

  it('analyses a moment by the calendar, earliest and latest, and narrows as asked', () => {
    const definition = define({
      fields: {
        createdAt: {
          label: 'Created',
          analysis: { dateUnits: [AggregationDateUnit.DAY] },
        },
        amount: {
          label: 'Amount',
          analysis: { groups: [AggregationGroupType.TERMS, 'NOPE' as never] },
        },
        id: { label: 'Order', analysis: false },
      },
      analysis: { having: false },
    });
    const [createdAt, amount] = definition.analysis?.fields ?? [];
    expect(createdAt).toMatchObject({
      field: 'createdAt',
      groups: ['DATE_HISTOGRAM', 'DATE_PART'],
      functions: ['MIN', 'MAX'],
      dateUnits: ['DAY'],
    });
    expect(amount.groups).toEqual(['TERMS']);
    expect(definition.analysis?.fields).toHaveLength(2);
    expect(definition.analysis?.having).toBeUndefined();
    expect(definition.analysis?.count).toBe(true);
    expect(definition.described?.findings).toEqual([
      expect.objectContaining({
        code: 'definition.field.analysis-wider',
        params: { field: 'amount', what: 'NOPE' },
      }),
    ]);
    expect(define({ analysis: false }).analysis).toBeUndefined();
  });

  it('is admitted with what it found, and its time field checked', () => {
    const definition = define({
      fields: { id: 'Order', gone: 'Gone', createdAt: 'Created' },
      timeField: 'createdAt',
    });
    expect(definition.timeField).toBe('createdAt');
    expect(codes(validateDefinition(definition, builtinFieldKinds))).toEqual([
      'definition.field.undescribed',
    ]);
    expect(
      codes(
        validateDefinition(
          define({
            fields: { id: 'Order' },
            timeField: 'id',
            views: [
              {
                id: 'all',
                title: 'All',
                timeField: 'gone',
                config: recordConfig(),
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ).filter(code => code.startsWith('definition.timeField')),
    ).toEqual([
      'definition.timeField.not-time',
      'definition.timeField.unknown',
    ]);
  });
});
