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
  AggregationDatePart,
  DateDiffUnit,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationFunction,
  AggregationGroupType,
  DerivedExpression,
  HavingExpression,
} from '@ahoo-wang/wow-client';
import type { DataViewConfigBase } from './config.js';
import type { ChartSpec } from './chart.js';
import type { FilterTree } from './filter.js';
import type { LiteralEnums } from './json.js';
import type { SortDirection } from './record.js';

/**
 * The Wow aggregation enums as the string literals a config stores. Naming
 * them here keeps the protocol in this layer: everything above reaches for
 * these rather than for `@ahoo-wang/wow-client`.
 */
export type AnalysisGroupType = `${AggregationGroupType}`;
export type AnalysisFunction = `${AggregationFunction}`;
export type AnalysisDateUnit = `${AggregationDateUnit}`;
export type AnalysisDatePart = `${AggregationDatePart}`;
export type AnalysisDateDiffUnit = `${DateDiffUnit}`;

/**
 * Every unit a time between two moments is measured in (`DATE_DIFF`), in
 * the order a choice among them is offered: the hour first, since 「付款到
 * 发货几小时」 is the question it answers most, then the day, the minute
 * and the second. Each has a fixed length — a day is exactly 24 hours.
 */
export const ANALYSIS_DATE_DIFF_UNITS = [
  'HOUR',
  'DAY',
  'MINUTE',
  'SECOND',
] as const satisfies readonly AnalysisDateDiffUnit[];
export type AnalysisExpressionOperator = `${AggregationExpressionOperator}`;

/**
 * Every date unit Wow buckets by, coarsest first — the order a choice among
 * them is offered in. A stored unit is checked against this list where no
 * definition is at hand to ask (a board's time grouping, D22 F); a test holds
 * it to Wow's enum.
 */
export const ANALYSIS_DATE_UNITS = [
  'YEAR',
  'QUARTER',
  'MONTH',
  'WEEK',
  'DAY',
  'HOUR',
  'MINUTE',
  'SECOND',
] as const satisfies readonly AnalysisDateUnit[];

/**
 * Every calendar part Wow groups by (`DATE_PART`), in the order a choice
 * among them is offered: the weekly and daily cycles first, since 「哪天几点」
 * is the question they answer, then the month's and the year's. A test holds
 * it to Wow's enum.
 */
export const ANALYSIS_DATE_PARTS = [
  'DAY_OF_WEEK',
  'HOUR_OF_DAY',
  'DAY_OF_MONTH',
  'MONTH_OF_YEAR',
] as const satisfies readonly AnalysisDatePart[];

/**
 * The keys a calendar part groups into, first and last: an ISO weekday runs
 * 1 (Monday) to 7 (Sunday), an hour 0 to 23 on the group's wall clock, a day
 * of the month 1 to 31 and a month 1 to 12. Every key between them is one of
 * the part's, so a part's axis is this run whatever the rows held.
 */
export const DATE_PART_DOMAINS = {
  DAY_OF_WEEK: [1, 7],
  HOUR_OF_DAY: [0, 23],
  DAY_OF_MONTH: [1, 31],
  MONTH_OF_YEAR: [1, 12],
} as const satisfies Record<AnalysisDatePart, readonly [number, number]>;

/**
 * The calendar parts a field's capability offers a `DATE_PART` dimension, in
 * the order a choice among them reads (`ANALYSIS_DATE_PARTS`): the declared
 * ones, every one when it declares none (`AggregationFieldCapability.dateParts`),
 * and none when it offers no `DATE_PART` at all.
 */
export function datePartsOf(
  capability: DatePartOffer | undefined,
): AnalysisDatePart[] {
  if (!capability?.groups.includes('DATE_PART')) return [];
  const declared = capability.dateParts;
  return ANALYSIS_DATE_PARTS.filter(
    part => declared === undefined || declared.includes(part),
  );
}

/** What `datePartsOf` reads: a capability, or the editor's option of one. */
export interface DatePartOffer {
  groups: readonly string[];
  dateParts?: readonly string[];
}

/** Every field an expression reads, in the order it reads them. */
export function expressionFieldsOf(expression: AnalysisExpression): string[] {
  switch (expression?.type) {
    case 'FIELD':
      return [expression.field];
    case 'BINARY':
      return [
        ...expressionFieldsOf(expression.left),
        ...expressionFieldsOf(expression.right),
      ];
    case 'DATE_DIFF':
      return [expression.from, expression.to];
    default:
      return [];
  }
}

/**
 * The fields a dimension reads: its one field, or every field of the
 * expression a band of a computed number is cut from (N3).
 */
export function groupFieldsOf(group: AnalysisGroup): string[] {
  return group.field !== undefined
    ? [group.field]
    : 'expression' in group
      ? expressionFieldsOf(group.expression)
      : [];
}

/**
 * The units a capability measures a time between two moments in
 * (`AnalysisCapability.dateDiffUnits`): the declared ones in the offered
 * order, every one when it declares none, and none where it offers no
 * computed expressions at all.
 */
export function dateDiffUnitsOf(
  capability:
    { expressions?: boolean; dateDiffUnits?: readonly string[] } | undefined,
): AnalysisDateDiffUnit[] {
  if (capability?.expressions !== true) return [];
  const declared = capability.dateDiffUnits;
  return ANALYSIS_DATE_DIFF_UNITS.filter(
    unit => declared === undefined || declared.includes(unit),
  );
}

/**
 * What a dimension or a metric is called on screen (D20 显示名), when the
 * analyst gave it a name: the column header, the legend and the reading say
 * this instead of what the field and the summary would compose. It is the
 * view's — Wow never sees it — and the alias stays the query's key.
 */
export interface AnalysisNamed {
  label?: string;
}

/**
 * Isomorphic to Wow's `AggregationGroup`: fields are validated against the
 * definition and enums are stored as literals.
 */
export type AnalysisGroup = AnalysisNamed &
  (
    | { type: 'TERMS'; field: string; alias: string; missingKey?: string }
    | { type: 'HISTOGRAM'; field: string; alias: string; interval: number }
    | {
        /**
         * Bands of a number computed per record (N3), such as the hours
         * from payment to shipment: 「0–2 小时」「2–4 小时」…. It names no
         * field of its own — `field` is absent — and its bands follow the
         * expression's unit.
         */
        type: 'HISTOGRAM';
        expression: AnalysisExpression;
        alias: string;
        interval: number;
        field?: undefined;
      }
    | {
        type: 'DATE_HISTOGRAM';
        field: string;
        alias: string;
        unit: AnalysisDateUnit;
        timeZone?: string;
        dense?: boolean;
      }
    | {
        /**
         * One calendar part of a time field — the weekday, the hour — so
         * records of different weeks or days fall into one group: 「哪天几点」.
         * Its keys are the part's integers (`DATE_PART_DOMAINS`), read in
         * `timeZone`, else the engine's zone, as a date histogram is cut.
         */
        type: 'DATE_PART';
        field: string;
        alias: string;
        part: AnalysisDatePart;
        timeZone?: string;
        dense?: boolean;
      }
  );

/** Isomorphic to Wow's `AggregationExpression`. */
export type AnalysisExpression =
  | { type: 'FIELD'; field: string }
  | { type: 'CONSTANT'; value: number }
  | {
      /**
       * The time from one moment to another, `to − from`, in `unit` (N3):
       * 付款到发货几小时. Negative when `to` is earlier; no value where either
       * moment is missing. Both are time fields the analysis may bucket by
       * date (`DATE_HISTOGRAM`), each read in its own encoding.
       */
      type: 'DATE_DIFF';
      from: string;
      to: string;
      unit: AnalysisDateDiffUnit;
    }
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
export type AnalysisMetric = AnalysisNamed &
  (
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
        /**
         * The field's value on the group's earliest (`FIRST`, 期初值) or
         * latest (`LAST`, 期末值) record by `orderBy` — the model's event
         * time when left out at the root (the descriptor's
         * `analysis.firstLastOrderBy`); inside an element it must be named.
         * Only records with both a value and an `orderBy` count, and those
         * the metric's `filter` keeps.
         */
        type: 'FIRST' | 'LAST';
        alias: string;
        field: string;
        orderBy?: string;
        filter?: FilterTree;
      }
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
    | {
        type: 'DERIVED';
        alias: string;
        expression: AnalysisDerivedExpression;
        /**
         * How its number reads (D38): the view's, never sent to Wow. Left
         * out, a plain number with two decimals.
         */
        format?: DerivedFormat;
      }
  );

/**
 * How a derived metric's number reads (D38), as a base metric's reads off
 * its field: a ratio as a percent — the value is the ratio, 0.259 reads
 * 25.9%, never multiplied by a hundred first — money in a currency, or a
 * number with so many decimals. `decimals` is the fixed count of digits
 * after the point (0 to `MAX_DERIVED_DECIMALS`); left out, 1 for a percent
 * and 2 otherwise. A currency left out is the one its operands are in —
 * GMV ÷ orders is in GMV's — and must be said where they are in none or in
 * two.
 */
export type DerivedFormat =
  | { style: 'number'; decimals?: number }
  | { style: 'percent'; decimals?: number }
  | { style: 'currency'; currency?: string; decimals?: number };

/** The three ways a derived number reads, in the order a picker offers them. */
export const DERIVED_FORMAT_STYLES = [
  'number',
  'percent',
  'currency',
] as const satisfies readonly DerivedFormat['style'][];

/** The most digits after the point a derived number is written with. */
export const MAX_DERIVED_DECIMALS = 6;

/**
 * The metric types that measure one field, in the order a field offers them
 * (D20 汇总方式): a function of its values, how many distinct values it
 * holds, a percentile of them, its value on the earliest or the latest
 * record (期初值／期末值), or any one of them. The record count names
 * no field, and a derived metric names other metrics. Declared beside the
 * union so a `satisfies` refuses a type the metric does not have, and the
 * summary a tray card offers is derived from this rather than listed again.
 */
export const FIELD_METRIC_TYPES = [
  'NUMERIC',
  'DISTINCT_COUNT',
  'PERCENTILE',
  'FIRST',
  'LAST',
  'ANY',
] as const satisfies readonly AnalysisMetric['type'][];

export type FieldMetricType = (typeof FIELD_METRIC_TYPES)[number];

/**
 * The metric types whose value is one record's value of a field rather than
 * a number computed over the group: any one (`ANY`), the earliest record's
 * (`FIRST`) and the latest's (`LAST`). Wow lets neither a derived metric nor
 * 「只保留」 read them, and a totals row has no whole of 「任一值」.
 */
export const VALUE_METRIC_TYPES = [
  'ANY',
  'FIRST',
  'LAST',
] as const satisfies readonly AnalysisMetric['type'][];

export type ValueMetric = Extract<
  AnalysisMetric,
  { type: (typeof VALUE_METRIC_TYPES)[number] }
>;

/** Whether a metric is one record's value (`VALUE_METRIC_TYPES`). */
export function isValueMetric(metric: AnalysisMetric): metric is ValueMetric {
  return (VALUE_METRIC_TYPES as readonly string[]).includes(metric.type);
}

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
  /**
   * The order and widths of the table's columns — an override, not an
   * allow-list. Every group and metric alias is a column; those listed come
   * first in this order, the rest follow, groups before metrics, in the
   * config's order. Empty means the config's order throughout.
   */
  columns: AnalysisColumn[];
  /**
   * Whether the table draws a totals row. The row is the ungrouped
   * aggregation, never derived from the group rows — which a grouped
   * analysis asks for either way (`compileAnalysisTotals`), so this says
   * only whether it is drawn.
   */
  totals?: boolean;
}

export type AnalysisLayout = 'table' | 'chart';

/** Table and chart are stored together, like the two record layouts. */
export interface AnalysisViewConfig extends DataViewConfigBase {
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

/**
 * The members that make up the *question*, which runs again on its own a
 * moment after it changes (「改了就跑」, D20): what is counted, by what, kept
 * by what, in what order and how many. The range (`filter`) is not among
 * them — the conditions wait for Apply, and while they wait nothing else
 * runs either (`runtime/autoApply.ts`). Nor is a presentation member: it
 * never asks the source. `autoRunMembers` in `config.ts` reads this per kind.
 */
export const ANALYSIS_AUTO_RUN_MEMBERS = [
  'elements',
  'groups',
  'metrics',
  'having',
  'sort',
  'limit',
  'table',
] as const satisfies readonly (keyof AnalysisViewConfig)[];
