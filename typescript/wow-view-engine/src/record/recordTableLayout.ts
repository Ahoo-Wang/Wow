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
  type RecordColumn,
  type ViewFieldDefinition,
} from '../contracts/viewModel.js';
import {
  getRecordColumnPinning,
  RECORD_COLUMN_DEFAULT_WIDTH,
  RECORD_COLUMN_MIN_WIDTH,
} from './recordColumns.js';

/** Pure layout for columns already ordered by orderRecordColumns; no DOM or table-library state. */
export function getRecordTableLayout({
  columns,
  fields,
  rowKey,
  selectable,
  availableWidth,
}: {
  columns: readonly RecordColumn[];
  fields: readonly ViewFieldDefinition[];
  rowKey: string;
  selectable: boolean;
  availableWidth: number;
}) {
  const selectionWidth = selectable ? 48 : 0;
  const columnSizing = Object.fromEntries(
    columns.map(column => [
      JSON.stringify(column.id),
      column.width ?? RECORD_COLUMN_DEFAULT_WIDTH,
    ]),
  );
  const visible = columns.filter(column => column.visible !== false);
  const centerMinimum = visible.some(
    column => column.kind === 'field' && column.field !== rowKey,
  )
    ? 128
    : 0;
  const pinnedWidth = visible.reduce(
    (width, column) =>
      width +
      (getRecordColumnPinning(column, rowKey)
        ? columnSizing[JSON.stringify(column.id)]
        : 0),
    selectionWidth,
  );
  const compact =
    availableWidth > 0 && pinnedWidth + centerMinimum > availableWidth;
  function pinning(column: RecordColumn) {
    if (compact && column.kind === 'field' && column.field !== rowKey)
      return false;
    return getRecordColumnPinning(column, rowKey);
  }
  if (compact) {
    const actions = visible.filter(column => column.kind === 'actions');
    const keys = visible.filter(
      column => column.kind === 'field' && column.field === rowKey,
    );
    const keyBudget =
      (availableWidth -
        selectionWidth -
        actions.length * RECORD_COLUMN_MIN_WIDTH -
        centerMinimum) /
      Math.max(1, keys.length);
    for (const column of actions)
      columnSizing[JSON.stringify(column.id)] = RECORD_COLUMN_MIN_WIDTH;
    for (const column of keys) {
      const id = JSON.stringify(column.id);
      columnSizing[id] = Math.max(
        RECORD_COLUMN_MIN_WIDTH,
        Math.min(columnSizing[id], Math.floor(keyBudget)),
      );
    }
  }
  const fieldByName = new Map(fields.map(field => [field.field, field]));
  const automatic = visible.filter(column => {
    if (
      column.kind !== 'field' ||
      column.width !== undefined ||
      getRecordColumnPinning(column, rowKey)
    )
      return false;
    const field = fieldByName.get(column.field);
    return field?.type === 'string' && !field.options?.length;
  });
  const minimumWidth = visible.reduce(
    (width, column) => width + columnSizing[JSON.stringify(column.id)],
    selectionWidth,
  );
  const extraPerColumn = automatic.length
    ? Math.max(0, availableWidth - minimumWidth) / automatic.length
    : 0;
  for (const column of automatic)
    columnSizing[JSON.stringify(column.id)] = Math.min(
      480,
      RECORD_COLUMN_DEFAULT_WIDTH + extraPerColumn,
    );
  const columnPinning = {
    start: columns
      .filter(column => pinning(column) === 'left')
      .map(column => JSON.stringify(column.id)),
    end: columns
      .filter(column => pinning(column) === 'right')
      .map(column => JSON.stringify(column.id)),
  };
  const displayed = [
    ...visible.filter(column => pinning(column) === 'left'),
    ...visible.filter(column => !pinning(column)),
    ...visible.filter(column => pinning(column) === 'right'),
  ];
  const contentWidth = visible.reduce(
    (width, column) => width + columnSizing[JSON.stringify(column.id)],
    selectionWidth,
  );
  const effectivePinnedWidth = visible.reduce(
    (width, column) =>
      width + (pinning(column) ? columnSizing[JSON.stringify(column.id)] : 0),
    selectionWidth,
  );
  let summaryLabelColumnCount = 0;
  let summaryLabelWidth = selectionWidth;
  for (const column of displayed) {
    if (
      pinning(column) !== 'left' ||
      (column.kind === 'field' && column.summary?.length)
    )
      break;
    summaryLabelColumnCount++;
    summaryLabelWidth += columnSizing[JSON.stringify(column.id)];
  }
  return {
    columnSizing,
    columnPinning,
    compact,
    insufficientWidth:
      compact && availableWidth - effectivePinnedWidth < centerMinimum,
    relaxedPinning:
      compact &&
      visible.some(
        column =>
          column.kind === 'field' &&
          column.field !== rowKey &&
          getRecordColumnPinning(column, rowKey),
      ),
    contentWidth,
    fillerWidth: Math.max(0, availableWidth - contentWidth),
    summaryLabelColumnCount,
    summaryLabelSpan: summaryLabelColumnCount + (selectable ? 1 : 0),
    summaryLabelWidth,
  };
}
