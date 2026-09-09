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
  createFilterConfiguration,
  newFilterNode,
} from '../src/filter/filterCore.js';
import { describe, expect, it, vi } from 'vitest';
import { FilterOperator, SortDirection } from '@ahoo-wang/fetcher-wow';
import type {
  ViewDefinition,
  ViewInstance,
} from '../src/record/recordModel.js';
import {
  validateViewDefinition,
  validateViewInstance,
  readRecordValue,
  validateRecordRows,
} from '../src/record/recordValidation.js';

const definition: ViewDefinition = {
  id: 'orders',
  title: '订单',
  sourceId: 'orders',
  rowKey: 'id',
  fields: [{ field: 'amount', label: '金额', type: 'number', sortable: true }],
};
const instance: ViewInstance = {
  id: 'all',
  definitionId: 'orders',
  title: '全部',
  kind: 'record',
  scope: { type: 'personal' },
  config: {
    filters: createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
    sort: [],
    pagination: { mode: 'paged', size: 20 },
    presentation: {
      layout: 'table',
      table: { columns: [{ id: 'amount', kind: 'field', field: 'amount' }] },
    },
  },
};
describe('record boundaries', () => {
  it('validates one definition timezone for all fields', () => {
    for (const timeZone of [undefined, 'UTC', 'Asia/Shanghai', '+08:00'])
      expect(() =>
        validateViewDefinition({ ...definition, timeZone }),
      ).not.toThrow();
    for (const timeZone of ['', 'Bad/Zone', 8, null])
      expect(() =>
        validateViewDefinition({ ...definition, timeZone }),
      ).toThrow();
  });
  it('validates numeric display options when loading field definitions', () => {
    for (const numberFormat of [
      null,
      'currency',
      { locale: 'bad_locale' },
      { style: 'currency' },
      { maximumFractionDigits: -1 },
    ])
      expect(() =>
        validateViewDefinition({
          ...definition,
          fields: [
            { field: 'amount', label: '金额', type: 'number', numberFormat },
          ],
        }),
      ).toThrow();
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [{ ...definition.fields[0], type: 'string', numberFormat: {} }],
      }),
    ).toThrow('只有数值字段支持数值格式');
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [
          {
            field: 'amount',
            label: '金额',
            type: 'number',
            numberFormat: { style: 'currency', currency: 'CNY' },
          },
        ],
      }),
    ).not.toThrow();
  });
  it('validates display groups and accepts repeated AND fields at the instance boundary', () => {
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [{ ...definition.fields[0], group: '订单信息' }],
      }),
    ).not.toThrow();
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [{ ...definition.fields[0], group: 123 }],
      }),
    ).toThrow('字段分组');
    expect(() =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            filters: createFilterConfiguration({
              ...newFilterNode(FilterOperator.AND),
              operands: [
                {
                  ...newFilterNode(FilterOperator.GTE, 'amount'),
                  props: { value: 1 },
                },
                {
                  ...newFilterNode(FilterOperator.LTE, 'amount'),
                  props: { value: 10 },
                },
              ],
            }),
          },
        },
        definition,
      ),
    ).not.toThrow();
  });
  it('validates the table action reference from remote definitions', () => {
    expect(() =>
      validateViewDefinition({
        ...definition,
        recordActions: { table: { name: 'batch', options: { limit: 10 } } },
      }),
    ).not.toThrow();
    expect(() =>
      validateViewDefinition({
        ...definition,
        recordActions: { table: { name: '' } },
      }),
    ).toThrow();
  });
  it('accepts explicit pin positions and rejects malformed remote column configuration', () => {
    const validate = (pinned: unknown) =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            presentation: {
              layout: 'table',
              table: {
                columns: [
                  { id: 'amount', kind: 'field', field: 'amount', pinned },
                ],
              },
            },
          },
        },
        definition,
      );
    for (const pinned of [undefined, false, 'left', 'right'])
      expect(() => validate(pinned)).not.toThrow();
    for (const pinned of [true, null, 'none', 'start', 0, {}])
      expect(() => validate(pinned)).toThrow(/固定/);
  });
  it('accepts record definitions and configured fields, rejecting foreign IDs and capabilities', () => {
    expect(() => validateViewDefinition(definition)).not.toThrow();
    expect(() => validateViewInstance(instance, definition)).not.toThrow();
    expect(() =>
      validateViewInstance(
        { ...instance, definitionId: 'foreign' },
        definition,
      ),
    ).toThrow();
    expect(() =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            sort: [{ field: 'missing', direction: SortDirection.ASC }],
          },
        },
        definition,
      ),
    ).toThrow();
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [...definition.fields, definition.fields[0]],
      }),
    ).toThrow();
  });
  it('rejects unsafe widths, duplicate columns, missing actions and invalid filters', () => {
    for (const columns of [
      [{ id: 'a', kind: 'field', field: 'amount', width: NaN }],
      [{ id: 'a', kind: 'field', field: 'amount', width: 10 }],
      [
        { id: 'a', kind: 'field', field: 'amount' },
        { id: 'a', kind: 'field', field: 'amount' },
      ],
      [{ id: 'actions', kind: 'actions' }],
    ])
      expect(() =>
        validateViewInstance(
          {
            ...instance,
            config: {
              ...instance.config,
              presentation: { layout: 'table', table: { columns } },
            },
          },
          definition,
        ),
      ).toThrow();
    expect(() =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            filters: createFilterConfiguration({
              ...newFilterNode(FilterOperator.EQ, 'missing'),
              props: { value: 1 },
            }),
          },
        },
        definition,
      ),
    ).toThrow();
  });
  it('preserves null/zero values and uses only own properties with exact path segments', () => {
    expect(readRecordValue({ a: [{ n: null }, { n: 0 }] }, 'a.0.n')).toBeNull();
    expect(readRecordValue({ a: [{ n: null }, { n: 0 }] }, 'a.1.n')).toBe(0);
    expect(readRecordValue({ a: [{ n: 1 }] }, 'a.0x.n')).toBeUndefined();
    expect(readRecordValue({}, 'toString')).toBeUndefined();
    expect(() =>
      validateRecordRows([{ id: 0 }, { id: '0' }], 'id'),
    ).not.toThrow();
    for (const rows of [
      [{ id: 0 }, { id: 0 }],
      [{}],
      [{ id: null }],
      [{ id: Infinity }],
    ])
      expect(() => validateRecordRows(rows, 'id')).toThrow();
  });
});

it('rejects the former persisted query shape instead of accepting two filter sources', () => {
  const legacy = {
    ...instance,
    config: { ...instance.config, filter: { op: FilterOperator.MATCH_ALL } },
  };
  expect(() => validateViewInstance(legacy, definition)).toThrow(/组件配置/);
});

it('rejects unaddressable resource IDs while retaining ordinary Unicode and reserved characters', () => {
  for (const id of ['', ' ', '.', '..', '\ud800']) {
    expect(() => validateViewDefinition({ ...definition, id })).toThrow();
    expect(() =>
      validateViewInstance({ ...instance, id }, definition),
    ).toThrow();
  }
  for (const id of ['订单 /%?#', '%2e%2e', 'orders.v1']) {
    const named = { ...definition, id };
    expect(() => validateViewDefinition(named)).not.toThrow();
    expect(() =>
      validateViewInstance({ ...instance, id, definitionId: id }, named),
    ).not.toThrow();
  }
});

it.each([
  ['sparse array', () => new Array(1)],
  ['extra array property', () => Object.assign([1], { extra: 2 })],
  ['symbol property', () => ({ [Symbol('hidden')]: 1 })],
  [
    'non-enumerable property',
    () => Object.defineProperty({}, 'hidden', { value: 1 }),
  ],
  ['undefined property', () => ({ missing: undefined })],
])(
  'rejects %s in definition and instance extension options',
  (_name, value) => {
    const reference = { name: 'custom', options: { value: value() } };
    expect(() =>
      validateViewDefinition({
        ...definition,
        fields: [{ ...definition.fields[0], editor: reference }],
      }),
    ).toThrow();
    expect(() =>
      validateViewInstance(
        {
          ...instance,
          config: {
            ...instance.config,
            presentation: {
              layout: 'table',
              table: {
                columns: [
                  {
                    id: 'amount',
                    kind: 'field',
                    field: 'amount',
                    renderer: reference,
                  },
                ],
              },
            },
          },
        },
        definition,
      ),
    ).toThrow();
  },
);

it.each([{}, [1]])(
  'rejects extension option accessors without invoking them (%j)',
  value => {
    const getter = vi.fn(() => 1);
    Object.defineProperty(value, Array.isArray(value) ? '0' : 'field', {
      get: getter,
      enumerable: true,
    });
    expect(() =>
      validateViewDefinition({
        ...definition,
        recordActions: { table: { name: 'custom', options: { value } } },
      }),
    ).toThrow();
    expect(getter).not.toHaveBeenCalled();
  },
);
