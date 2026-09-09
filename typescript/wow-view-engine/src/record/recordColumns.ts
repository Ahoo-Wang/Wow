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

import type { RecordColumn, RecordColumnPinning } from './recordModel.js';

export function getRecordColumnPinning(
  column: RecordColumn,
  rowKey = 'id',
): RecordColumnPinning {
  if (column.kind === 'actions') return 'right';
  if (column.field === rowKey) return 'left';
  return column.pinned ?? false;
}

/** Returns display order without rewriting persisted preferences or omitting hidden columns. */
export function orderRecordColumns(
  columns: readonly RecordColumn[],
  rowKey = 'id',
): RecordColumn[] {
  function priority(column: RecordColumn): number {
    if (column.kind === 'actions') return 4;
    if (column.field === rowKey) return 0;
    const pinned = getRecordColumnPinning(column, rowKey);
    return pinned === 'left' ? 1 : pinned === 'right' ? 3 : 2;
  }
  return [...columns].sort((left, right) => priority(left) - priority(right));
}

export const RECORD_COLUMN_MIN_WIDTH = 64;

export const RECORD_COLUMN_MAX_WIDTH = 960;

export const RECORD_COLUMN_DEFAULT_WIDTH = 180;
