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

import { Checkbox } from '../../components/ui/checkbox.js';
import { TableBody, TableCell, TableRow } from '../../components/ui/table.js';
import { cn } from '../../lib/utils.js';
import type { RecordTableProps } from '../recordReactTypes.js';
import { getRecordKey } from '../recordValidation.js';
import { RecordRendererBoundary } from '../RecordRendererBoundary.js';
import { RecordCell } from './RecordCell.js';
import { RecordQueryStatus } from './RecordQueryStatus.js';
import type { RecordTableModel } from './recordTableTypes.js';

export function RecordTableBody({
  definition,
  instance,
  appliedFilter,
  extensions,
  refresh,
  selectable,
  querying,
  queryError,
  onQueryRetry,
  model,
  availableWidth,
}: Pick<
  RecordTableProps,
  | 'definition'
  | 'instance'
  | 'appliedFilter'
  | 'extensions'
  | 'refresh'
  | 'selectable'
  | 'querying'
  | 'queryError'
  | 'onQueryRetry'
> & { model: RecordTableModel; availableWidth: number }) {
  const {
    table,
    byId,
    visibleColumns,
    withFiller,
    columnStyle,
    columnClassName,
    layout: { compact },
  } = model;
  return (
    <TableBody>
      <RecordQueryStatus
        querying={querying}
        queryError={queryError}
        onQueryRetry={onQueryRetry}
        empty={!table.getRowModel().rows.length}
        columnSpan={withFiller(visibleColumns).length + (selectable ? 1 : 0)}
        availableWidth={availableWidth}
      />
      {table.getRowModel().rows.map(row => (
        <TableRow
          key={row.id}
          data-state={row.getIsSelected() ? 'selected' : undefined}
        >
          {selectable && (
            <TableCell data-pinned="start" style={{ left: 0 }}>
              <Checkbox
                aria-label={`选择记录 ${String(getRecordKey(row.original, definition.record.rowKey))}`}
                checked={row.getIsSelected()}
                onCheckedChange={checked => row.toggleSelected(checked)}
              />
            </TableCell>
          )}
          {withFiller(row.getVisibleCells()).map(cell => {
            if (!cell) return <TableCell key="space" aria-hidden="true" />;
            const column = byId.get(cell.column.id)!;
            return (
              <TableCell
                key={cell.id}
                className={cn(
                  'fve:overflow-hidden fve:whitespace-normal fve:break-words',
                  columnClassName(cell.column),
                )}
                style={columnStyle(cell.column)}
                data-pinned={cell.column.getIsPinned() || undefined}
              >
                <RecordRendererBoundary
                  label={
                    column.title ??
                    (column.kind === 'actions' ? '行操作' : column.field)
                  }
                  resetKey={[
                    row.original,
                    column,
                    definition,
                    instance,
                    appliedFilter,
                    extensions,
                  ]}
                >
                  <RecordCell
                    column={column}
                    record={row.original}
                    rowKey={getRecordKey(
                      row.original,
                      definition.record.rowKey,
                    )}
                    index={row.index}
                    definition={definition}
                    instance={instance}
                    appliedFilter={appliedFilter}
                    extensions={extensions}
                    refresh={refresh}
                    compact={compact}
                  />
                </RecordRendererBoundary>
              </TableCell>
            );
          })}
        </TableRow>
      ))}
    </TableBody>
  );
}
