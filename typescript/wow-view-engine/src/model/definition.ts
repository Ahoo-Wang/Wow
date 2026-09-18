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
  /**
   * The groups a picker lists fields under, in this order, each naming the
   * fields it holds. A field no group names is listed before them all. A
   * group naming a field the definition does not declare, or one another
   * group already names, is a definition error, so a typo is caught at
   * admission rather than shown as a field gone missing.
   */
  fieldGroups?: FieldGroupDefinition[];
  record?: RecordCapability;
  analysis?: AnalysisCapability;
  /** System views declared in code; they deploy with the definition. */
  views?: SystemView[];
}

/** One group of a field picker: a stable id, a label, and its fields in order. */
export interface FieldGroupDefinition {
  id: string;
  label: string;
  /** Names of the definition's root fields, in the order the picker lists them. */
  fields: string[];
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

/**
 * Instance id of a code-declared system view. Neither part may contain the
 * separator, which `validateDefinition` enforces, so the composition is
 * unambiguous and can be taken apart again.
 */
export function systemInstanceId(definitionId: string, viewId: string): string {
  return [SYSTEM_INSTANCE_ID_PREFIX, definitionId, viewId].join(
    SYSTEM_INSTANCE_ID_SEPARATOR,
  );
}

/** Reads back a composed id, or `null` when the id belongs to a store. */
export function parseSystemInstanceId(
  id: string,
): { definitionId: string; viewId: string } | null {
  const parts = id.split(SYSTEM_INSTANCE_ID_SEPARATOR);
  if (parts.length !== 3 || parts[0] !== SYSTEM_INSTANCE_ID_PREFIX) return null;
  return { definitionId: parts[1], viewId: parts[2] };
}

/** True for any id in the namespace a `ViewStore` must not issue. */
export function isSystemInstanceId(id: string): boolean {
  return id.startsWith(
    `${SYSTEM_INSTANCE_ID_PREFIX}${SYSTEM_INSTANCE_ID_SEPARATOR}`,
  );
}

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

/**
 * How an element's fields may be aggregated. The path names a field that
 * declares `elements`; what those elements hold is declared there, and only
 * the aggregation capability is analysis's to state.
 */
export interface AnalysisElementCapability {
  path: string;
  aggregations: AggregationFieldCapability[];
}

/** How one field may be aggregated; shared by root and element fields. */
export interface AggregationFieldCapability {
  /**
   * The field this aggregates, named as its declaration names it: a root
   * field by its own name, an element field by its name within the element.
   * A *config* refers to an element field as `path.field`, because it points
   * at one from outside; a capability sits beside the path already.
   */
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
