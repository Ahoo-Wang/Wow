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
  AggregationExpressionOperator,
  AggregationFunction,
  AggregationGroupType,
  DerivedExpression,
  HavingExpression,
} from '@ahoo-wang/fetcher-wow';
import type { ViewConfigBase } from './config.js';
import type { ChartSpec } from './chart.js';
import type { FilterTree } from './filter.js';
import type { LiteralEnums } from './json.js';
import type { SortDirection } from './record.js';

/**
 * The Wow aggregation enums as the string literals a config stores. Naming
 * them here keeps the protocol in this layer: everything above reaches for
 * these rather than for `@ahoo-wang/fetcher-wow`.
 */
export type AnalysisGroupType = `${AggregationGroupType}`;
export type AnalysisFunction = `${AggregationFunction}`;
export type AnalysisDateUnit = `${AggregationDateUnit}`;
export type AnalysisExpressionOperator = `${AggregationExpressionOperator}`;

/**
 * Isomorphic to Wow's `AggregationGroup`: fields are validated against the
 * definition and enums are stored as literals.
 */
export type AnalysisGroup =
  | { type: 'TERMS'; field: string; alias: string; missingKey?: string }
  | { type: 'HISTOGRAM'; field: string; alias: string; interval: number }
  | {
      type: 'DATE_HISTOGRAM';
      field: string;
      alias: string;
      unit: AnalysisDateUnit;
      timeZone?: string;
      dense?: boolean;
    };

/** Isomorphic to Wow's `AggregationExpression`. */
export type AnalysisExpression =
  | { type: 'FIELD'; field: string }
  | { type: 'CONSTANT'; value: number }
  | {
      type: 'BINARY';
      operator: AnalysisExpressionOperator;
      left: AnalysisExpression;
      right: AnalysisExpression;
    };

/**
 * Isomorphic to Wow's `AggregationMetric`. A metric-level `filter` is what
 * makes a metric-per-stage funnel expressible.
 */
export type AnalysisMetric =
  | { type: 'COUNT'; alias: string; filter?: FilterTree }
  | {
      type: 'NUMERIC';
      alias: string;
      function: AnalysisFunction;
      expression: AnalysisExpression;
      filter?: FilterTree;
    }
  | { type: 'ANY'; alias: string; field: string; filter?: FilterTree }
  | {
      type: 'DISTINCT_COUNT';
      alias: string;
      expression: AnalysisExpression;
      filter?: FilterTree;
    }
  | {
      type: 'PERCENTILE';
      alias: string;
      expression: AnalysisExpression;
      /** Open interval (0, 100), as Wow requires. */
      percentile: number;
      filter?: FilterTree;
    }
  | { type: 'DERIVED'; alias: string; expression: AnalysisDerivedExpression };

/** Wow's having/derived trees only reference aliases and numbers. */
export type AnalysisHavingExpression = LiteralEnums<HavingExpression>;
export type AnalysisDerivedExpression = LiteralEnums<DerivedExpression>;

/** Expansion of an array path; its filter is scoped to the element fields. */
export interface AnalysisElement {
  path: string;
  filter?: FilterTree;
}

export interface AnalysisSort {
  alias: string;
  direction: SortDirection;
}

/**
 * One column of the result table. It has no `pinned`: the analysis table
 * freezes nothing, so a stored pinning was a setting no renderer performed
 * and no control offered — a promise the screen never kept. When the table
 * grows frozen columns it will take the record table's word for it (D19).
 */
export interface AnalysisColumn {
  alias: string;
  width?: number;
}

export interface AnalysisTableSpec {
  /** Defaults to every group and metric alias. */
  columns: AnalysisColumn[];
  /** Runs its own ungrouped aggregation, never derived from the group rows. */
  totals?: boolean;
}

export type AnalysisLayout = 'table' | 'chart';

/** Table and chart are stored together, like the two record layouts. */
export interface AnalysisViewConfig extends ViewConfigBase {
  kind: 'analysis';
  elements?: AnalysisElement[];
  groups: AnalysisGroup[];
  metrics: [AnalysisMetric, ...AnalysisMetric[]];
  having?: AnalysisHavingExpression;
  sort: AnalysisSort[];
  limit: number;
  layout: AnalysisLayout;
  table: AnalysisTableSpec;
  chart: ChartSpec;
}

/**
 * How the result is looked at is not part of the question (D20): the layout
 * and the chart are drawn from the rows that ran, whichever they are, so
 * changing them redraws without asking the source again and counts as no
 * pending edit. They are still saved with the view — a saved chart is the
 * author's — so `dirty` sees them. `table` is not among them: its totals
 * row is a query of its own.
 */
export const ANALYSIS_PRESENTATION_MEMBERS = [
  'layout',
  'chart',
] as const satisfies readonly (keyof AnalysisViewConfig)[];
