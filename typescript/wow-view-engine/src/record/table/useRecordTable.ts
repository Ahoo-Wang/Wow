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

import type { CSSProperties } from 'react';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import {
  functionalUpdate,
  useTable,
  type ColumnDef,
} from '@tanstack/react-table';
import { type RecordData } from '../../contracts/viewModel.js';
import {
  RECORD_COLUMN_DEFAULT_WIDTH,
  RECORD_COLUMN_MAX_WIDTH,
  RECORD_COLUMN_MIN_WIDTH,
  orderRecordColumns,
} from '../recordColumns.js';
import type { RecordTableProps } from '../recordReactTypes.js';
import { getRecordKey, readRecordValue } from '../recordValidation.js';
import { getRecordTableLayout } from '../recordTableLayout.js';
import {
  recordTableFeatures,
  type RecordTableColumn,
  type RecordTableModel,
} from './recordTableTypes.js';

export function useRecordTable({
  definition,
  instance,
  rows,
  selectable = false,
  selectedRowKeys,
  onSelectionChange,
  onColumnsChange,
  onSortChange,
  availableWidth,
}: Pick<
  RecordTableProps,
  | 'definition'
  | 'instance'
  | 'rows'
  | 'selectable'
  | 'selectedRowKeys'
  | 'onSelectionChange'
  | 'onColumnsChange'
  | 'onSortChange'
> & { availableWidth: number }): RecordTableModel {
  const presentation = instance.config.presentation;
  if (presentation.layout !== 'table')
    throw new Error('RecordTable 需要 table 布局');
  const columns = orderRecordColumns(
    presentation.table.columns,
    definition.record.rowKey,
  );
  const byId = new Map(
    columns.map(column => [JSON.stringify(column.id), column]),
  );
  const columnDefs: ColumnDef<typeof recordTableFeatures, RecordData>[] =
    columns.map(column => ({
      id: JSON.stringify(column.id),
      ...(column.kind === 'field'
        ? {
            accessorFn: (record: RecordData) =>
              readRecordValue(record, column.field),
          }
        : {}),
      enableSorting:
        column.kind === 'field' &&
        definition.fields.some(
          field => field.field === column.field && field.sortable === true,
        ),
      minSize: RECORD_COLUMN_MIN_WIDTH,
      maxSize: RECORD_COLUMN_MAX_WIDTH,
      size: column.width ?? RECORD_COLUMN_DEFAULT_WIDTH,
    }));
  const sortColumnIds = new Map<string, string>();
  for (const column of columns)
    if (column.kind === 'field' && !sortColumnIds.has(column.field))
      sortColumnIds.set(column.field, JSON.stringify(column.id));
  const sorting = instance.config.sort.map(sort => ({
    id: sortColumnIds.get(sort.field) ?? JSON.stringify([sort.field]),
    desc: sort.direction === SortDirection.DESC,
  }));
  const sortFields = new Map(
    sorting.map((sort, index) => [sort.id, instance.config.sort[index].field]),
  );
  for (const column of columns)
    if (column.kind === 'field')
      sortFields.set(JSON.stringify(column.id), column.field);
  const layout = getRecordTableLayout({
    columns,
    fields: definition.fields,
    rowKey: definition.record.rowKey,
    selectable,
    availableWidth,
  });
  const { columnSizing, columnPinning, fillerWidth } = layout;
  const rowSelection = Object.fromEntries(
    selectedRowKeys.map(key => [JSON.stringify(key), true as const]),
  );
  const table = useTable({
    features: recordTableFeatures,
    columns: columnDefs,
    data: rows,
    getRowId: record =>
      JSON.stringify(getRecordKey(record, definition.record.rowKey)),
    manualSorting: true,
    sortDescFirst: false,
    enableMultiSort: true,
    enableRowSelection: selectable,
    enableSubRowSelection: false,
    columnResizeMode: 'onEnd',
    state: {
      sorting,
      rowSelection,
      columnSizing,
      columnPinning,
      columnOrder: columns.map(column => JSON.stringify(column.id)),
      columnVisibility: Object.fromEntries(
        columns.map(column => [
          JSON.stringify(column.id),
          column.visible !== false,
        ]),
      ),
    },
    onSortingChange: updater =>
      onSortChange(
        functionalUpdate(updater, sorting).map(sort => ({
          field: sortFields.get(sort.id)!,
          direction: sort.desc ? SortDirection.DESC : SortDirection.ASC,
        })),
      ),
    onRowSelectionChange: updater => {
      const next = functionalUpdate(updater, rowSelection);
      const keys = new Map(
        rows.map(record => {
          const key = getRecordKey(record, definition.record.rowKey);
          return [JSON.stringify(key), key] as const;
        }),
      );
      onSelectionChange(
        [...keys].filter(([id]) => next[id]).map(([, key]) => key),
      );
    },
    onColumnSizingChange: updater => {
      const resizing = table.state.columnResizing;
      const next = functionalUpdate(updater, columnSizing);
      // The transient delta can lag mouseup inside TanStack's batch; compare the final sizes.
      if (
        resizing.isResizingColumn &&
        resizing.columnSizingStart.length &&
        resizing.columnSizingStart.every(
          ([id, width]) =>
            Math.round((next[id] ?? width) * 100) === Math.round(width * 100),
        )
      )
        return;
      const changed = columns.map(column => {
        const width = Math.min(
          RECORD_COLUMN_MAX_WIDTH,
          Math.max(
            RECORD_COLUMN_MIN_WIDTH,
            next[JSON.stringify(column.id)] ?? RECORD_COLUMN_DEFAULT_WIDTH,
          ),
        );
        // TanStack rounds mouse resize output to two decimals, including zero-distance drags.
        return Math.round(width * 100) ===
          Math.round(columnSizing[JSON.stringify(column.id)] * 100)
          ? column
          : { ...column, width };
      });
      if (changed.some((column, index) => column !== columns[index]))
        onColumnsChange(changed);
    },
  });
  const visibleColumns = [
    ...table.getStartVisibleLeafColumns(),
    ...table.getCenterVisibleLeafColumns(),
    ...table.getEndVisibleLeafColumns(),
  ];
  const firstEnd = visibleColumns.findIndex(
    column => column.getIsPinned() === 'end',
  );
  // Unused space stays before right-pinned columns when every field has an explicit width.
  function withFiller<T>(items: T[]): (T | null)[] {
    if (fillerWidth < 1) return items;
    const index = firstEnd < 0 ? items.length : firstEnd;
    return [...items.slice(0, index), null, ...items.slice(index)];
  }
  function columnClassName(column: RecordTableColumn): string | undefined {
    const configured = byId.get(column.id)!;
    const numeric =
      configured.kind === 'field' &&
      definition.fields.some(
        field => field.field === configured.field && field.type === 'number',
      );
    return numeric ? 'fve:text-right fve:tabular-nums' : undefined;
  }
  // Colgroup owns widths; only pinned offsets need per-cell inline styles.
  function columnStyle(column: RecordTableColumn): CSSProperties | undefined {
    const pinned = column.getIsPinned();
    if (!pinned) return undefined;
    return pinned === 'start'
      ? { left: column.getStart('start') + (selectable ? 48 : 0) }
      : { right: column.getAfter('end') };
  }
  return {
    table,
    byId,
    sortColumnIds,
    visibleColumns,
    layout,
    columnStyle,
    columnClassName,
    withFiller,
  };
}
