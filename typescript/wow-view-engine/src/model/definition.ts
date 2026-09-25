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
} from '@ahoo-wang/wow-client';
import type { AnalysisMetric } from './analysis.js';
import type { FieldDefinition } from './field.js';
import type { Issue } from './issue.js';
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
  /**
   * What one record is, as a reader counts them: 「订单」, 「客户」,
   * 「定价事件」. The analysis says it as its counting unit (「计数单位：订单」)
   * where nothing is expanded. The title names the dataset — 「事件流分析台」
   * — and read there it counted consoles (2026-09-23 audit); left out, the
   * unit is the catalogue's word for a record.
   */
  recordNoun?: string;
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
  /**
   * What narrowing this definition to its source's capability descriptor
   * took away, and against which version (capabilities.md): written by the
   * engine on the definition a view runs on, never by a definition in code.
   * A control reads it to say why it offers what it offers — a phrase
   * search the source only matches as words says so in its placeholder.
   */
  narrowing?: DefinitionNarrowing;
}

/** What one narrowing found; see `DataViewDefinition.narrowing`. */
export interface DefinitionNarrowing {
  /** The descriptor's version the definition was narrowed against. */
  version: string;
  findings: readonly Issue[];
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
  /**
   * The most rows a paged query may reach: page × size.
   *
   * A search-backed source refuses a page whose window runs past a bound of
   * its own — Wow over Elasticsearch answers 400 once `index × size` passes
   * 10 000 — so a pager that divides the total by the size offers pages the
   * source will not serve. Declared, the pager stops at the last page inside
   * it and says why. Left out, the source has no window. A `paged` source
   * only: a cursor is a position, and has no window to run past.
   */
  maxWindow?: number;
  /**
   * The most fields a view's sort may name, not counting the row key every
   * query ends on. A source bounds how many fields one sort takes; left
   * out, a paged view sorts by as many fields as it has and a cursor view
   * by Wow's cursor bound. It only lowers either (`maxSortFields`); a
   * source's descriptor writes its own bound here (capabilities.md 4.3).
   */
  maxSortFields?: number;
  /**
   * Whether a page must carry a condition: the source counts only what a
   * condition narrows (its descriptor's `COUNT_REQUIRES_FILTER`), and a
   * view without one says 「先添加一个条件」 and sends nothing (Q3).
   */
  requiresFilter?: boolean;
  layouts: RecordLayout[];
  /**
   * Fields every fetched row carries whatever the view shows, because the
   * host's own code reads them: a row action that offers "retry" only while
   * `state.isRetryable`, a bulk action, a custom cell.
   *
   * A page asks its source for the fields the view shows and no more
   * (`recordProjection`), so a row handed to an action holds its row key,
   * its visible columns, its card fields and its sort — not the document.
   * What the host reads beyond that is said here, once, beside the fields it
   * names: each must be a declared field a row holds, which admission checks.
   * There is no "fetch everything when there are actions": the document is
   * what made a page of failed executions 808 KB.
   */
  rowFields?: string[];
  defaults?: Partial<RecordViewConfig>;
}

export interface AnalysisCapability {
  count: boolean;
  fields: AggregationFieldCapability[];
  /** Expandable array paths: element fields and their aggregations. */
  elements?: AnalysisElementCapability[];
  /** Allows BINARY expressions and DERIVED metrics. */
  expressions?: boolean;
  having?: boolean;
  /**
   * The metric types 「只保留」 may compare, when `having` is on. Left out,
   * every one; a source's descriptor writes its own here.
   */
  havingMetrics?: AnalysisMetric['type'][];
  /**
   * Whether the result rows may be ordered by a metric. Left out, they may;
   * a source that orders groups only by their keys says `false`.
   */
  metricSort?: boolean;
  /**
   * Whether a date dimension may fill its empty buckets (`dense`). Left
   * out, it may.
   */
  dense?: boolean;
  /**
   * The metric types the source estimates rather than computes exactly: a
   * column of one wears 「≈」 and a boxplot says its quartiles are
   * approximate. Left out, `DEFAULT_APPROXIMATE_METRICS` — Wow estimates
   * percentiles on every store it ships with; a source's descriptor writes
   * its own list here (`analysis.approximate`).
   */
  approximate?: AnalysisMetric['type'][];
  limits?: AnalysisLimits;
}

/**
 * What a source estimates when nobody said otherwise: percentiles, which
 * Wow computes approximately on every store it ships with.
 */
export const DEFAULT_APPROXIMATE_METRICS: readonly AnalysisMetric['type'][] = [
  'PERCENTILE',
];

/**
 * Whether a result column is estimated: what the projection said
 * (`approximate`), else whether its function is one of the defaults.
 */
export function isApproximate(column: {
  approximate?: boolean;
  fn?: string;
}): boolean {
  return (
    column.approximate ??
    (column.fn !== undefined &&
      (DEFAULT_APPROXIMATE_METRICS as readonly string[]).includes(column.fn))
  );
}

/** The metric types a capability says its source estimates. */
export function approximateMetrics(
  capability: Pick<AnalysisCapability, 'approximate'> | undefined,
): readonly AnalysisMetric['type'][] {
  return capability?.approximate ?? DEFAULT_APPROXIMATE_METRICS;
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
  /**
   * Whether a value dimension on it may keep the records missing a value as
   * a group of their own. Left out, wherever it holds one string
   * (`isSingleStringField`); `false` takes that group away.
   */
  missingKey?: boolean;
  /** Whether a metric's own condition may name it. Left out, it may. */
  inMetricFilter?: boolean;
  /** Whether a formula may take it as an operand. Left out, it may. */
  expressionInput?: boolean;
}

export interface AnalysisLimits {
  maxGroups?: number;
  maxMetrics?: number;
  maxElements?: number;
  maxLimit?: number;
  defaultLimit?: number;
}
