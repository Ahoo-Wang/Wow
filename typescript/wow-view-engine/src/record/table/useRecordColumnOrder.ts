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

import { useListOrder } from '../../lib/useListOrder.js';
import type { RecordColumn } from '../../contracts/viewModel.js';
import {
  getRecordColumnPinning,
  orderRecordColumns,
} from '../recordColumns.js';
import type { RecordColumnSettingsProps } from '../recordReactTypes.js';

/** Table-specific order and pinned-region constraints. */
export function useRecordColumnOrder({
  definition,
  columns: configuredColumns,
  onChange,
  disabled,
}: RecordColumnSettingsProps) {
  const columns = orderRecordColumns(
    configuredColumns,
    definition.record.rowKey,
  );
  function isLocked(column: RecordColumn): boolean {
    return (
      column.kind === 'actions' || column.field === definition.record.rowKey
    );
  }
  function titleOf(column: RecordColumn): string {
    return (
      column.title ??
      (column.kind === 'field'
        ? (definition.fields.find(field => field.field === column.field)
            ?.label ?? column.field)
        : '操作')
    );
  }
  const order = useListOrder({
    items: columns,
    onChange,
    disabled,
    titleOf,
    canMove: (column, other) =>
      getRecordColumnPinning(column, definition.record.rowKey) ===
        getRecordColumnPinning(other, definition.record.rowKey) &&
      isLocked(column) === isLocked(other),
  });
  return { ...order, columns, isLocked, titleOf };
}
