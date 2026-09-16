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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import type { FieldDefinition } from './field.js';
import type { RecordViewConfig, PagingMode, RecordLayout } from './record.js';
import type { ViewConfig } from './config.js';

/**
 * What a dataset offers. Definitions are code: they come from the Wow
 * aggregate's query schema and ship with the application, so they may use the
 * Wow enums directly.
 */
export type ViewDefinition = DataViewDefinition | DashboardDefinition;

export interface DataViewDefinition {
  /** Must not contain ':': system view ids are composed with it. */
  id: string;
  title: string;
  kind: 'data';
  /** Key passed to `resolveSource`. */
  source: string;
  fields: FieldDefinition[];
  record?: RecordCapability;
  analysis?: AnalysisCapability;
  /** System views declared in code; they deploy with the definition. */
  views?: SystemView[];
}

/** Dashboards own no data; the definition is their catalogue entry. */
export interface DashboardDefinition {
  id: string;
  title: string;
  kind: 'dashboard';
  views?: SystemView[];
}

/**
 * A baseline view shipped with the definition. Everyone sees it, nobody
 * overwrites it, anyone may save a copy.
 */
export interface SystemView {
  /** Unique within the definition and free of ':'. */
  id: string;
  title: string;
  config: ViewConfig;
}

/** Instance ids of code-declared system views start with this segment. */
export const SYSTEM_INSTANCE_ID_PREFIX = 'system';

/** Separator of the composed `system:<definition>:<view>` instance id. */
export const SYSTEM_INSTANCE_ID_SEPARATOR = ':';

export interface RecordCapability {
  /** Field holding each row's identity. */
  rowKey: string;
  /** Decides whether the runtime calls `source.paged` or `source.cursor`. */
  paging: PagingMode;
  layouts: RecordLayout[];
  defaults?: Partial<RecordViewConfig>;
  /** Renderer keys for business actions. */
  actions?: { toolbar?: string; row?: string; bulk?: string };
}

export interface AnalysisCapability {
  count: boolean;
  fields: AggregationFieldCapability[];
  /** Expandable array paths: element fields and their aggregations. */
  elements?: AnalysisElementCapability[];
  /** Allows BINARY expressions and DERIVED metrics. */
  expressions?: boolean;
  having?: boolean;
  limits?: AnalysisLimits;
}

export interface AnalysisElementCapability {
  path: string;
  fields: FieldDefinition[];
  aggregations: AggregationFieldCapability[];
}

/** How one field may be aggregated; shared by root and element fields. */
export interface AggregationFieldCapability {
  /** Element fields are referenced as `path.field`. */
  field: string;
  groups: AggregationGroupType[];
  functions: AggregationFunction[];
  dateUnits?: AggregationDateUnit[];
  any?: boolean;
  distinctCount?: boolean;
  percentile?: boolean;
}

export interface AnalysisLimits {
  maxGroups?: number;
  maxMetrics?: number;
  maxElements?: number;
  maxLimit?: number;
  defaultLimit?: number;
}
