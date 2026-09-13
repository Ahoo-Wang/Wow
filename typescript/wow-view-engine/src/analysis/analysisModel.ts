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
  DerivedExpressionType,
  HavingExpressionType,
  ComparisonOperator,
  AggregationDateUnit,
  AggregationExpressionType,
  AggregationExpressionOperator,
  AggregationFunction,
  AggregationGroup,
  AggregationGroupType,
  AggregationMetric,
  AggregationQuery,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import type {
  FilterConfiguration,
  FilterFieldDefinition,
  FilterEditorReference,
  FilterComponentProperties,
  FilterCompilerRegistry,
  FilterValidationError,
} from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
export type AnalysisNumericExpression =
  | { type: AggregationExpressionType.FIELD; field: string }
  | { type: AggregationExpressionType.CONSTANT; value: number | string }
  | {
      type: AggregationExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: AnalysisNumericExpression;
      right: AnalysisNumericExpression;
    };
export interface AnalysisFeatures {
  distinctCount?: boolean;
  percentile?: boolean;
  metricFilters?: boolean;
  derived?: boolean;
  having?: boolean;
  missingKey?: boolean;
  dense?: boolean;
}
export type AnalysisDerivedExpression =
  | { type: DerivedExpressionType.METRIC_REF; metricId: string }
  | { type: DerivedExpressionType.CONSTANT; value: number | string }
  | {
      type: DerivedExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: AnalysisDerivedExpression;
      right: AnalysisDerivedExpression;
    };
export type AnalysisHavingExpression =
  | {
      id: string;
      type: HavingExpressionType.CONDITION;
      metricId: string;
      operator: ComparisonOperator;
      value: number | string;
    }
  | {
      id: string;
      type: HavingExpressionType.BETWEEN;
      metricId: string;
      lower: number | string;
      upper: number | string;
    }
  | {
      id: string;
      type: HavingExpressionType.IN;
      metricId: string;
      values: (number | string)[];
    }
  | {
      id: string;
      type: HavingExpressionType.IS_NULL;
      metricId: string;
      negated?: boolean;
    }
  | {
      id: string;
      type: HavingExpressionType.AND | HavingExpressionType.OR;
      operands: AnalysisHavingExpression[];
    };
export interface AnalysisScopeDefinition {
  id: string;
  label: string;
  /** Each path and filter field is relative to the preceding element. */
  elements: { path: string; fields: FilterFieldDefinition[] }[];
  fields: FilterFieldDefinition[];
  capability: Pick<AnalysisCapability, 'fields' | 'count' | 'expressions'>;
}
export interface AnalysisComponentConfig {
  id: string;
  component: FilterEditorReference;
  field?: string;
  expression?: AnalysisNumericExpression;
  derivedExpression?: AnalysisDerivedExpression;
  filters?: FilterConfiguration;
  alias: string;
  title: string;
  props: FilterComponentProperties;
  /** Dimension display field; compiled as ANY, separate from numeric measures. */
  label?: { field: string; alias: string; title: string };
}
export interface AnalysisCapability {
  features?: AnalysisFeatures;
  fields: {
    field: string;
    groups: AggregationGroupType[];
    functions: AggregationFunction[];
    dateUnits?: AggregationDateUnit[];
    any?: boolean;
    distinctCount?: boolean;
    percentile?: boolean;
    unit?: string;
    numberFormat?: Intl.NumberFormatOptions & { locale?: string };
  }[];
  count: boolean;
  expressions?: boolean;
  scopes?: AnalysisScopeDefinition[];
  limits?: {
    maxGroups?: number;
    maxMetrics?: number;
    maxSort?: number;
    defaultLimit?: number;
    maxLimit?: number;
  };
}
export interface AnalysisViewConfig {
  filters: FilterConfiguration;
  having?: AnalysisHavingExpression;
  scope?: { id: string; filters: FilterConfiguration[] };
  dimensions: AnalysisComponentConfig[];
  metrics: AnalysisComponentConfig[];
  sort: { alias: string; direction: SortDirection }[];
  /** Incomplete editor text remains recoverable and cannot compile. */
  limit: number | string;
  presentation: AnalysisPresentation;
}
/** Custom compilers return exactly one contribution. Core reconstructs and validates it. */
export interface AnalysisCompiler {
  readonly roles: readonly AnalysisResultColumn['role'][];
  compile(
    config: DeepReadonly<AnalysisComponentConfig>,
    context: DeepReadonly<AnalysisComponentCompileContext>,
  ): AggregationGroup | AggregationMetric;
}
export type AnalysisCompilerRegistry = Readonly<
  Record<string, AnalysisCompiler>
>;
export interface AnalysisCompileContext {
  fields: readonly FilterFieldDefinition[];
  capability: DeepReadonly<AnalysisCapability>;
  timeZone?: string;
  allowedOperators?: readonly FilterOperator[];
  filterCompilers?: FilterCompilerRegistry;
  compilers?: AnalysisCompilerRegistry;
}
export interface AnalysisComponentCompileContext extends AnalysisCompileContext {
  readonly role: AnalysisResultColumn['role'];
}
export interface AnalysisResultColumn {
  id: string;
  alias: string;
  title: string;
  role: 'dimension' | 'metric';
  valueType: 'string' | 'number' | 'boolean' | 'datetime';
  nullable: boolean;
  labelFor?: string;
  aggregation?:
    | 'COUNT'
    | 'ANY'
    | 'DISTINCT_COUNT'
    | 'PERCENTILE'
    | 'DERIVED'
    | AggregationFunction;
  /** Display only: typed values remain the query and row identities. */
  options?: { value: string | number | boolean; label: string }[];
  numberFormat?: Intl.NumberFormatOptions & { locale?: string };
  unit?: string;
  group?: {
    type: AggregationGroupType;
    interval?: number;
    unit?: AggregationDateUnit;
    timeZone?: string;
  };
  format?: string;
  /** Persisted display width in CSS pixels. */
  width?: number;
}
export interface AnalysisPlan {
  query: AggregationQuery;
  schema: AnalysisResultColumn[];
  timeZone?: string;
}
export interface AnalysisCompileResult {
  plan?: AnalysisPlan;
  errors: FilterValidationError[];
}
export type AnalysisRow = Record<string, string | number | boolean | null>;
export interface AnalysisResultValidation {
  rows?: AnalysisRow[];
  errors: FilterValidationError[];
}

/** Wow aggregation contract: ordered element expansion chain limit. */
export const MAX_ANALYSIS_ELEMENTS = 5;
