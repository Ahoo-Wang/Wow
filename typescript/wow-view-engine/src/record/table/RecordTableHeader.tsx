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

import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import { Checkbox } from '../../components/ui/checkbox.js';
import { TableHead, TableHeader, TableRow } from '../../components/ui/table.js';
import { cn } from '../../lib/utils.js';
import {
  RECORD_COLUMN_MAX_WIDTH,
  RECORD_COLUMN_MIN_WIDTH,
} from '../recordColumns.js';
import type { RecordTableProps } from '../recordReactTypes.js';
import type { RecordTableModel } from './recordTableTypes.js';

export function RecordTableHeader({
  definition,
  selectable,
  model,
}: Pick<RecordTableProps, 'definition' | 'selectable'> & {
  model: RecordTableModel;
}) {
  const {
    table,
    byId,
    sortColumnIds,
    withFiller,
    columnStyle,
    columnClassName,
    layout: { compact },
  } = model;
  const { sorting } = table.state;
  return (
    <TableHeader>
      {table.getHeaderGroups().map(group => (
        <TableRow key={group.id}>
          {selectable && (
            <TableHead scope="col" data-pinned="start" style={{ left: 0 }}>
              <Checkbox
                aria-label="选择当前页"
                checked={table.getIsAllPageRowsSelected()}
                indeterminate={
                  table.getIsSomePageRowsSelected() &&
                  !table.getIsAllPageRowsSelected()
                }
                disabled={!table.getRowModel().rows.length}
                onCheckedChange={checked =>
                  table.toggleAllPageRowsSelected(checked)
                }
              />
            </TableHead>
          )}
          {withFiller(group.headers).map(header => {
            if (!header) return <TableHead key="space" aria-hidden="true" />;
            const column = byId.get(header.column.id)!;
            const field =
              column.kind === 'field'
                ? definition.fields.find(field => field.field === column.field)
                : undefined;
            const title =
              column.title ??
              field?.label ??
              (column.kind === 'field' ? column.field : '操作');
            const sortingColumn =
              column.kind === 'field'
                ? table.getColumn(sortColumnIds.get(column.field)!)
                : undefined;
            const direction = sortingColumn?.getIsSorted();
            const Icon =
              direction === 'asc'
                ? ArrowUpIcon
                : direction === 'desc'
                  ? ArrowDownIcon
                  : ArrowUpDownIcon;
            return (
              <TableHead
                key={header.id}
                scope="col"
                className={cn(
                  'fve:relative fve:whitespace-normal fve:pr-3',
                  columnClassName(header.column),
                )}
                style={columnStyle(header.column)}
                data-pinned={header.column.getIsPinned() || undefined}
                aria-sort={
                  direction === 'asc'
                    ? 'ascending'
                    : direction === 'desc'
                      ? 'descending'
                      : undefined
                }
              >
                {header.column.getCanSort() ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    className={cn(
                      'fve:h-auto fve:w-full fve:min-w-0 fve:px-0 fve:py-1',
                      field?.type === 'number'
                        ? 'fve:justify-end'
                        : 'fve:justify-start',
                    )}
                    aria-label={`${title}排序：${direction === 'asc' ? '升序' : direction === 'desc' ? '降序' : '未排序'}`}
                    title="点击排序，按住 Shift 添加排序"
                    onClick={sortingColumn?.getToggleSortingHandler()}
                  >
                    <span className="fve:min-w-0 fve:whitespace-normal fve:break-words fve:text-left">
                      {title}
                    </span>
                    <Icon data-icon="inline-end" />
                    {direction && sorting.length > 1 && (
                      <span aria-hidden="true">
                        {sortingColumn!.getSortIndex() + 1}
                      </span>
                    )}
                  </Button>
                ) : (
                  <span className="fve:break-words">{title}</span>
                )}
                {!(
                  compact &&
                  (column.kind === 'actions' ||
                    column.field === definition.record.rowKey)
                ) && (
                  <div
                    role="separator"
                    aria-label={`调整${title}列宽`}
                    aria-orientation="vertical"
                    aria-valuemin={RECORD_COLUMN_MIN_WIDTH}
                    aria-valuemax={RECORD_COLUMN_MAX_WIDTH}
                    aria-valuenow={header.getSize()}
                    tabIndex={0}
                    className={cn(
                      'fve:absolute fve:inset-y-0 fve:right-0 fve:w-2 fve:cursor-col-resize fve:touch-none fve:border-border fve:select-none fve:hover:bg-accent fve:focus-visible:outline-2 fve:focus-visible:outline-ring',
                      // Left-pinned cells already draw their right separator; only add the moving guide during resizing.
                      (header.column.getIsPinned() !== 'start' ||
                        header.column.getIsResizing()) &&
                        'fve:border-r',
                    )}
                    onMouseDown={header.getResizeHandler()}
                    onTouchStart={header.getResizeHandler()}
                    onKeyDown={event => {
                      const delta =
                        event.key === 'ArrowLeft'
                          ? -10
                          : event.key === 'ArrowRight'
                            ? 10
                            : 0;
                      if (delta) {
                        event.preventDefault();
                        table.setColumnSizing(old => ({
                          ...old,
                          [header.column.id]: header.getSize() + delta,
                        }));
                      }
                    }}
                    style={
                      header.column.getIsResizing()
                        ? {
                            transform: `translateX(${table.state.columnResizing.deltaOffset ?? 0}px)`,
                          }
                        : undefined
                    }
                  />
                )}
              </TableHead>
            );
          })}
        </TableRow>
      ))}
    </TableHeader>
  );
}
