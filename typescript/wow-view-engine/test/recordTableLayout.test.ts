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

import { expect, it } from 'vitest';
import { getRecordTableLayout } from '../src/record/recordTableLayout.js';
import type {
  RecordColumn,
  ViewFieldDefinition,
} from '../src/record/recordModel.js';

it('keeps business space usable and never merges numeric metrics into the summary label', () => {
  const columns: RecordColumn[] = [
    { id: 'id', kind: 'field', field: 'id', width: 210 },
    { id: 'customer', kind: 'field', field: 'customer' },
    {
      id: 'amount',
      kind: 'field',
      field: 'amount',
      width: 150,
      summary: ['SUM'],
    },
    { id: 'status', kind: 'field', field: 'status', width: 130 },
    { id: 'actions', kind: 'actions', width: 110 },
  ];
  const fields: ViewFieldDefinition[] = [
    { field: 'id', label: 'ID', type: 'string' },
    { field: 'customer', label: 'Customer', type: 'string' },
    { field: 'amount', label: 'Amount', type: 'number' },
    {
      field: 'status',
      label: 'Status',
      type: 'string',
      options: [{ value: 'open', label: 'Open' }],
    },
  ];
  const input = { columns, fields, rowKey: 'id', selectable: true };
  const wide = getRecordTableLayout({ ...input, availableWidth: 2000 });
  expect(wide.columnSizing['"customer"']).toBe(480);
  expect(wide.contentWidth).toBe(1128);
  expect(wide.fillerWidth).toBe(872);
  const narrow = getRecordTableLayout({ ...input, availableWidth: 356 });
  expect(narrow.columnSizing['"id"']).toBe(116);
  expect(narrow.columnSizing['"actions"']).toBe(64);
  expect(narrow.summaryLabelWidth).toBe(164);
  expect(narrow.summaryLabelSpan).toBe(2);
  expect(narrow.insufficientWidth).toBe(false);
  expect(columns[0].width).toBe(210);
  const pinned = columns.map(column =>
    column.id === 'customer' || column.id === 'amount'
      ? { ...column, pinned: 'left' as const }
      : column,
  );
  const metrics = getRecordTableLayout({
    ...input,
    columns: pinned,
    availableWidth: 900,
  });
  expect(metrics.summaryLabelColumnCount).toBe(2);
  expect(metrics.summaryLabelWidth).toBe(438);
  const compact = getRecordTableLayout({
    ...input,
    columns: pinned,
    availableWidth: 356,
  });
  expect(compact.columnPinning.start).toEqual(['"id"']);
  expect(compact.relaxedPinning).toBe(true);
});
