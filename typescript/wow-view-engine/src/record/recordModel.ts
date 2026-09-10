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

import type {
  FieldSort,
  FilterExpression,
  FilterOperator,
  QueryApi,
} from '@ahoo-wang/fetcher-wow';
import type {
  FilterCompilerRegistry,
  FilterConfiguration,
  FilterEditorReference,
  FilterFieldDefinition,
} from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';

import type { ViewHost } from './ViewHost.js';

export type RecordData = Record<string, unknown>;
export type RecordKey = string | number;
export type RendererReference = FilterEditorReference;
export interface ViewFieldDefinition extends FilterFieldDefinition {
  sortable?: boolean;
  cellRenderer?: RendererReference;
  /** Shared display format for numeric cells and summaries; computation keeps raw values. */
  numberFormat?: Intl.NumberFormatOptions & { locale?: string };
  /** Restricts numeric summaries; other field types cannot summarize. */
  summaryFunctions?: readonly RecordSummaryFunction[];
}
/** Source metadata shared by all instances. Paths are relative to the returned record. */
export interface ViewDefinition {
  id: string;
  title: string;
  sourceId: string;
  rowKey: string;
  allowedLayouts: readonly RecordPresentation['layout'][];
  /** Shared datetime timezone for filters and cells; omitted uses the local runtime timezone. */
  timeZone?: string;
  fields: readonly ViewFieldDefinition[];
  defaultPresentation?: DeepReadonly<RecordPresentationDefaults>;
  allowedOperators?: readonly FilterOperator[];
  filterEditors?: Partial<Record<FilterOperator, FilterEditorReference>>;
  recordActions?: {
    global?: RendererReference;
    toolbar?: RendererReference;
    row?: RendererReference;
  };
}
export type ViewScope =
  { type: 'personal' } | { type: 'public'; source: 'system' | 'shared' };
export type SaveAsScope =
  { type: 'personal' } | { type: 'public'; source: 'shared' };
export type RecordColumnPinning = 'left' | 'right' | false;
interface RecordColumnBase {
  id: string;
  title?: string;
  /** Explicit pixel width. Omitted, unpinned strings without enum options grow up to 480px. */
  width?: number;
  visible?: boolean;
  /** Preference for ordinary fields; row-key and action columns have mandatory sides. */
  pinned?: RecordColumnPinning;
  renderer?: RendererReference;
}
export type RecordColumn =
  | (RecordColumnBase & {
      kind: 'field';
      field: string;
      /** Selected numeric metrics; omitted or empty disables summaries. */
      summary?: readonly RecordSummaryFunction[];
    })
  | (RecordColumnBase & { kind: 'actions' });
export interface RecordTableConfig {
  columns: RecordColumn[];
}
export type RecordCardFieldConfig = Pick<
  Extract<RecordColumn, { kind: 'field' }>,
  'id' | 'field' | 'title' | 'renderer'
>;
export interface RecordCardConfig {
  title: RecordCardFieldConfig;
  cover?: { field: string };
  fields: RecordCardFieldConfig[];
  actions?: { visible?: boolean; renderer?: RendererReference };
}
export interface RecordPresentationDefaults {
  table?: RecordTableConfig;
  card?: RecordCardConfig;
}
export interface RecordTablePresentation {
  layout: 'table';
  table: RecordTableConfig;
  card?: RecordCardConfig;
}
export interface RecordCardPresentation {
  layout: 'card';
  card: RecordCardConfig;
  table?: RecordTableConfig;
}
export type RecordPresentation =
  RecordTablePresentation | RecordCardPresentation;
export interface RecordViewConfig {
  sort: FieldSort[];
  pagination: { mode: 'paged' | 'cursor'; size: number };
  filters: FilterConfiguration;
  presentation: RecordPresentation;
}
/** Metadata shared by saved view kinds; query and presentation belong to their kind. */
export interface ViewInstanceMetadata {
  id: string;
  definitionId: string;
  title: string;
  scope: ViewScope;
  revision?: string;
}
/** This release implements record instances. Other view kinds add their own config contracts. */
export interface RecordViewInstance extends ViewInstanceMetadata {
  kind: 'record';
  config: RecordViewConfig;
}
export type ViewInstance = RecordViewInstance;
export interface ViewInstanceList {
  instances: ViewInstance[];
  defaultInstanceId: string | null;
}
export interface ViewInstancePermissions {
  save: boolean;
  saveAsPersonal: boolean;
  saveAsShared: boolean;
  /** Defaults to false; system instances can never be deleted. */
  delete?: boolean;
  /** Defaults to false; system instance names are immutable. */
  rename?: boolean;
}
/** Immutable UI capabilities; subscribe through ViewEngine.subscribe. */
export interface ViewCapabilities {
  readonly reorder: boolean;
  readonly instances: Readonly<
    Record<
      string,
      {
        readonly permissions: Readonly<ViewInstancePermissions>;
        readonly reload: boolean;
        /** The original versioned delete can be replayed after an unknown outcome. */
        readonly retryDelete: boolean;
      }
    >
  >;
}
/** Advertise only implemented query modes; at least one record query is required. */
export type RecordQuerySource = (
  Pick<QueryApi<RecordData>, 'paged'> | Pick<QueryApi<RecordData>, 'cursor'>
) &
  Partial<Pick<QueryApi<RecordData>, 'paged' | 'cursor' | 'aggregate'>>;
export interface ViewEngineOptions {
  /** Headless filter capabilities fixed for this engine lifetime; ViewPage uses extensions.filters. */
  filterCompilers?: FilterCompilerRegistry;
  definitionId: string;
  host: ViewHost;
  definition?: ViewDefinition;
  instances?: ViewInstanceList;
}
export interface RecordSession {
  readonly baseline: DeepReadonly<ViewInstance>;
  readonly instance: DeepReadonly<ViewInstance>;
  readonly dirty: boolean;
  readonly filterDraft: DeepReadonly<FilterConfiguration>;
  /** Last applied editor tree, including intentionally unset controls. */
  readonly filterBaseline: DeepReadonly<FilterConfiguration>;
  /** Validity of local editor buffers not represented in the Wow expression. */
  readonly filterValid: boolean;
  readonly filterPending: boolean;
  /** Compiled query scope. Null blocks reads until the component configuration is valid. */
  readonly appliedFilter: DeepReadonly<FilterExpression> | null;
  readonly page: number;
  readonly cursor: string | null;
  readonly nextCursor: string | null;
  readonly rows: DeepReadonly<readonly RecordData[]>;
  readonly total: number | null;
  readonly pageSummary: RecordSummaryResult;
  readonly allSummary: RecordSummaryResult;
  readonly selectedRowKeys: readonly RecordKey[];
  readonly queryStatus: 'idle' | 'loading' | 'success' | 'error';
  /** A background read keeps the last successful rows usable. */
  readonly refreshing: boolean;
  readonly queryError: string | null;
  readonly writeStatus:
    'idle' | 'saving' | 'creating' | 'deleting' | 'renaming';
  readonly writeError: string | null;
  readonly requiresReload: boolean;
}
export interface ViewEngineState {
  readonly status: 'idle' | 'loading' | 'ready' | 'error';
  readonly error: string | null;
  readonly definition: DeepReadonly<ViewDefinition> | null;
  readonly instanceIds: readonly string[];
  readonly selectedInstanceId: string | null;
  readonly sessions: Readonly<Record<string, RecordSession>>;
  /** Local recovery contexts whose source is absent from the authoritative instance list. */
  readonly pendingCreates: Readonly<Record<string, RecordSession>>;
}
export type RecordSummaryFunction = 'SUM' | 'AVG' | 'MIN' | 'MAX';
/** A query metric independent of a table column or other presentation settings. */
export interface RecordSummaryMetric {
  id: string;
  field: string;
  function: RecordSummaryFunction;
}
export interface RecordSummaryResult {
  readonly status: 'idle' | 'loading' | 'success' | 'error';
  readonly values: Readonly<
    Record<
      string,
      Readonly<Partial<Record<RecordSummaryFunction, number | null>>>
    >
  >;
  readonly error: string | null;
}
