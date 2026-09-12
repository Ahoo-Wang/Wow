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

import type { ComponentType, ReactNode } from 'react';
import type { FieldSort, FilterExpression } from '@ahoo-wang/fetcher-wow';
import type { FilterExtensions } from '../filter/filterReactTypes.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  RecordColumn,
  RecordCardConfig,
  RecordPresentation,
  RecordData,
  RecordKey,
  RecordSummaryResult,
  RendererReference,
  RecordViewDefinition,
  ViewFieldDefinition,
  RecordViewInstance,
  RecordSession,
} from '../contracts/viewModel.js';

export interface RecordToolbarRenderContext {
  readonly definition: DeepReadonly<RecordViewDefinition>;
  readonly session: DeepReadonly<RecordSession>;
  readonly defaultContent: ReactNode;
  readonly appliedFilter: DeepReadonly<FilterExpression> | null;
  readonly querying: boolean;
  readonly selectedRowKeys: readonly RecordKey[];
  clearSelection(): void;
  setColumns(columns: RecordColumn[]): void;
  setLayout(layout: RecordPresentation['layout']): void;
  setCardConfig(card: RecordCardConfig): void;
  refresh(): Promise<void>;
}
export interface RecordPaginationRenderContext {
  readonly definition: DeepReadonly<RecordViewDefinition>;
  readonly session: DeepReadonly<RecordSession>;
  readonly defaultContent: ReactNode;
  readonly mode: 'paged' | 'cursor';
  readonly page: number;
  readonly pageSize: number;
  readonly pageCount: number | null;
  readonly canNext: boolean;
  readonly canPrevious: boolean;
  readonly canChangePageSize: boolean;
  setPage(index: number): Promise<void>;
  setPageSize(size: number): Promise<void>;
  nextPage(): Promise<void>;
  previousPage(): Promise<void>;
}

export interface RecordActionsContext {
  readonly definition: DeepReadonly<RecordViewDefinition>;
  readonly instance: DeepReadonly<RecordViewInstance>;
  /** Applied query scope. Null means the component configuration has not compiled successfully. */
  readonly filter: DeepReadonly<FilterExpression> | null;
  readonly sort: DeepReadonly<readonly FieldSort[]>;
  readonly options: DeepReadonly<RendererReference['options']>;
  /** Bound to this instance, even if navigation changes while an action is running. */
  refresh(): Promise<void>;
}
export interface GlobalActionsRendererProps extends RecordActionsContext {
  selectedRowKeys: readonly RecordKey[];
  querying: boolean;
}
/** Toolbar operations receive the current page selection and the applied query scope. */
export type ToolbarActionsRendererProps = GlobalActionsRendererProps;
export interface RowActionsRendererProps extends RecordActionsContext {
  readonly record: DeepReadonly<RecordData>;
  rowKey: RecordKey;
}
export interface CellRendererProps {
  readonly value: unknown;
  readonly record: DeepReadonly<RecordData>;
  readonly rowKey: RecordKey;
  readonly index: number;
  readonly field: DeepReadonly<ViewFieldDefinition>;
  readonly column: DeepReadonly<RecordColumn>;
  readonly definition: DeepReadonly<RecordViewDefinition>;
  readonly instance: DeepReadonly<RecordViewInstance>;
  readonly options: DeepReadonly<RendererReference['options']>;
}
export interface RecordExtensions extends FilterExtensions {
  cells?: Readonly<Record<string, ComponentType<CellRendererProps>>>;
  globalActions?: Readonly<
    Record<string, ComponentType<GlobalActionsRendererProps>>
  >;
  toolbarActions?: Readonly<
    Record<string, ComponentType<ToolbarActionsRendererProps>>
  >;
  rowActions?: Readonly<Record<string, ComponentType<RowActionsRendererProps>>>;
}
export interface RecordTableProps {
  definition: DeepReadonly<RecordViewDefinition>;
  instance: DeepReadonly<RecordViewInstance>;
  /** Runtime query scope, supplied by the engine rather than inferred from saved configuration. */
  appliedFilter: DeepReadonly<FilterExpression> | null;
  rows: DeepReadonly<readonly RecordData[]>;
  extensions?: RecordExtensions;
  querying?: boolean;
  /** Query failure is distinct from a successful empty result. Existing rows are retained. */
  queryError?: string | null;
  onQueryRetry?(): void;
  /** Independent loaded-page and applied-filter results, rendered together. */
  pageSummary?: RecordSummaryResult;
  allSummary?: RecordSummaryResult;
  onSummaryRetry?(): void;
  selectable?: boolean;
  selectedRowKeys: readonly RecordKey[];
  onSelectionChange(keys: RecordKey[]): void;
  onColumnsChange(columns: RecordColumn[]): void;
  onSortChange(sort: FieldSort[]): void;
  refresh(): Promise<void>;
  className?: string;
}
export interface RecordColumnSettingsProps {
  definition: RecordViewDefinition;
  columns: readonly RecordColumn[];
  onChange(columns: RecordColumn[]): void;
  disabled?: boolean;
}

export interface RecordCardRenderContext {
  readonly definition: DeepReadonly<RecordViewDefinition>;
  readonly instance: DeepReadonly<RecordViewInstance>;
  readonly record: DeepReadonly<RecordData>;
  readonly rowKey: RecordKey;
  readonly index: number;
  readonly selected: boolean;
  readonly defaultContent: ReactNode;
  /** Bound to this instance; does not repeat business writes. */
  refresh(): Promise<void>;
}
export type RecordCardListProps = Pick<
  RecordTableProps,
  | 'definition'
  | 'instance'
  | 'appliedFilter'
  | 'rows'
  | 'extensions'
  | 'querying'
  | 'queryError'
  | 'onQueryRetry'
  | 'selectable'
  | 'selectedRowKeys'
  | 'onSelectionChange'
  | 'refresh'
  | 'className'
> & { renderCard?(context: RecordCardRenderContext): ReactNode };
export interface RecordCardSettingsProps {
  definition: DeepReadonly<RecordViewDefinition>;
  card: DeepReadonly<RecordCardConfig>;
  /** Synchronous application; throw to retain the draft and show a local error. */
  onChange(card: RecordCardConfig): void;
  disabled?: boolean;
}
