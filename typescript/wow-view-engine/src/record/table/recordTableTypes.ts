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
import {
  columnOrderingFeature,
  columnPinningFeature,
  columnResizingFeature,
  columnSizingFeature,
  columnVisibilityFeature,
  rowSelectionFeature,
  rowSortingFeature,
  tableFeatures,
  type Column,
  type ReactTable,
} from '@tanstack/react-table';
import type { RecordColumn, RecordData } from '../../contracts/viewModel.js';
import type { getRecordTableLayout } from '../recordTableLayout.js';

export const recordTableFeatures = tableFeatures({
  columnOrderingFeature,
  columnPinningFeature,
  columnResizingFeature,
  columnSizingFeature,
  columnVisibilityFeature,
  rowSelectionFeature,
  rowSortingFeature,
});

export type RecordTableColumn = Column<typeof recordTableFeatures, RecordData>;

/** Table-local adapter contract shared by the header, body and summary renderers. */
export interface RecordTableModel {
  table: ReactTable<typeof recordTableFeatures, RecordData>;
  byId: ReadonlyMap<string, RecordColumn>;
  sortColumnIds: ReadonlyMap<string, string>;
  visibleColumns: RecordTableColumn[];
  layout: ReturnType<typeof getRecordTableLayout>;
  columnStyle(column: RecordTableColumn): CSSProperties | undefined;
  columnClassName(column: RecordTableColumn): string | undefined;
  withFiller<T>(items: T[]): (T | null)[];
}
