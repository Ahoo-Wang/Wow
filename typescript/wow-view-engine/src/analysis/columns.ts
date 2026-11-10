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

/**
 * What a column of an analysis result is: its view (`AnalysisColumnView`),
 * and how its title, its values, its buckets, its number and its currency are
 * read off the definition and the config — the parts `projectAnalysis` puts
 * together for every alias the result holds, and that a reader holding only
 * the columns (`momentColumns`, `measureColumns`, `rowCurrency`) reads back.
 */

import {
  labelRemoteOf,
  epochUnitOf,
  isDateCell,
  type CurrencyReading,
  type FieldNumeric,
  type AggregationDatePartName,
  type AggregationDateUnitName,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type NumberFormat,
  type RecordData,
  type EpochTimeUnit,
  AggregationGroupTypes,
  AggregationMetricTypes,
} from '../model/index.js';
import {
  companionsReading,
  currencyCompanions,
  derivedOperands,
} from './currency.js';
import {
  metricFieldOf,
  metricFunctionOf,
  metricMeasure,
  type MetricFunction,
} from './metricFormat.js';
import type { MetricCondition } from './metricCondition.js';
import {
  metricReferenceText,
  derivedText,
  expressionText,
  isDuration,
  isFormula,
} from './formula.js';

/** A column of the result table; groups come first, then metrics. */
export interface AnalysisColumnView {
  alias: string;
  /**
   * The field this column is computed from, as the definition names it. For a
   * metric it is half a header: the other half is `fn`, and only a catalogue
   * can put the two in the reader's own order.
   */
  label: string;
  role: 'group' | 'metric';
  /**
   * For a metric: which summary it is, so a header can read 「金额的平均」
   * rather than the alias `amount_1`. Two metrics over one field differ here
   * and nowhere else, which is exactly why the header cannot be the label.
   */
  fn?: MetricFunction;
  /**
   * The analyst named this column (`AnalysisNamed.label`): `label` is the
   * whole title, and `fn` stays only for how the numbers read.
   */
  named?: true;
  /**
   * For a metric with a condition of its own: the records it counts are
   * only some of them, and the header says so (D20 显示名) — 「金额的总和 ·
   * 已发运」 — unless the analyst named the column, and its description
   * says the whole condition either way.
   */
  condition?: MetricCondition;
  width?: number;
  /**
   * How this column's numbers print. A group shows values of its field, so it
   * takes the field's own format; a metric takes `metricFormat`, because what
   * an aggregate is decides how it reads, not what it was computed from.
   */
  numberFormat?: NumberFormat;
  /**
   * What the number is by the source's semantics (`FieldNumeric`), where
   * that — and not the author's `numberFormat` — decides how it reads: a
   * file then holds the number itself, and money its currency beside it.
   */
  numeric?: FieldNumeric;
  /**
   * For a group, or a metric whose value is one of the field's
   * (`readsAsItsField` — its earliest, its latest, a percentile, any one):
   * the field's kind, renderer key and choices, so its values show as the
   * field's do, a date as a date. Other metrics are numbers, whatever they
   * were computed from.
   */
  kind?: string;
  cell?: string;
  options?: readonly FieldOption[];
  /**
   * For a `reference` field: the remote its ids are read as names from,
   * so a cell reads `ViewRuntime.labels` rather than the id.
   */
  remote?: string;
  /** A field kept in epoch seconds; milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
  /** For a date histogram group: the width of the buckets its keys start. */
  dateUnit?: AggregationDateUnitName;
  /** For a date histogram group: the zone its buckets were cut in. */
  timeZone?: string;
  /**
   * For a date histogram group: the key of its bucket still under way when
   * the question was asked (`ShapeContext.now`) — this month, while it runs.
   * A table writes it 「2026年9月（进行中）」, as a chart marks the end of its
   * axis: half a month read as a month is a drop that never happened.
   */
  ongoing?: unknown;
  /**
   * For a calendar part group: which part its keys are (the ISO weekday 1 to
   * 7, the hour 0 to 23…), so a key reads 「周一」 or 「20时」 rather than a
   * bare integer, and an axis runs through the part's whole domain
   * (`DATE_PART_DOMAINS`).
   */
  datePart?: AggregationDatePartName;
  /**
   * For a value group that keeps a bucket of the records with no value: the
   * key that bucket comes back under (`AnalysisGroup.missingKey`). The
   * engine's sentinel (`DEFAULT_MISSING_KEY`) is a key and not a label, so
   * the interface names that bucket in its own words; a key the analyst
   * wrote is their name for it.
   */
  missingKey?: string;
  /**
   * For a number histogram group: the width of the bands its keys start, so
   * a key reads as the band 「¥0～500」 rather than its lower bound alone.
   */
  interval?: number;
  /**
   * For a metric: what its number is a quantity of (`metricMeasure`) — the
   * one token a combo chart puts two metrics on one axis by.
   */
  measure?: string;
  /**
   * For a metric: whether the source estimates it rather than computes it
   * (`AnalysisCapability.approximate`); an estimated column wears 「≈」 and
   * says why. Absent where the capability says nothing, and read then as
   * `DEFAULT_APPROXIMATE_METRICS` reads it (`isApproximate`).
   */
  approximate?: boolean;
  /**
   * For a metric of money in a currency each record holds: how each row
   * tells its currency (`rowCurrency`). A column whose rows are all in one
   * currency already carries it in `numberFormat`.
   */
  currency?: ColumnCurrency;
  /**
   * `false` for a metric the rows cannot be ordered by
   * (`AnalysisCapability.metricSort`); absent otherwise.
   */
  sortable?: false;
}

/**
 * How a metric column of money in a currency each record holds is read, row
 * by row: the companions telling each row's currency (`CurrencyCompanion`)
 * — its own, or a derived metric's operands' — and whether its number is
 * written in that currency. A ratio of two amounts (a derived metric with
 * no currency format) is in none, yet still no number where its operands
 * mix currencies.
 */
export interface ColumnCurrency {
  /** Per companion, the aliases of its `ANY` and its `DISTINCT_COUNT`. */
  companions: readonly { code: string; count: string }[];
  writes: boolean;
}

/**
 * What one row of a result says about a column's currency: the one its
 * records are in, several, or nothing known (`companionsReading`).
 */
export function rowCurrency(
  row: RecordData | undefined,
  column: Pick<AnalysisColumnView, 'currency'>,
): CurrencyReading | undefined {
  return column.currency && companionsReading(row, column.currency.companions);
}

/**
 * The metric columns of a result that are moments — the earliest, the latest
 * of a date — by alias: those the projection gave a date's reading. It is
 * `momentMetrics` read off the result rather than the definition, so a
 * reader holding only the rows (the result block, the options panel) says
 * the same thing the kernels said.
 */
export function momentColumns(
  columns: readonly AnalysisColumnView[],
): Set<string> {
  return new Set(
    columns
      .filter(column => column.role === 'metric' && isDateCell(column.cell))
      .map(column => column.alias),
  );
}

/**
 * What each metric column's number is a quantity of (`metricMeasure`), by
 * alias: `metricMeasures` read off the result rather than the definition,
 * from the summary a column says it is and the format it prints in — so the
 * options panel and the redraw put a series on the axis the kernel would.
 */
export function measureColumns(
  columns: readonly AnalysisColumnView[],
): Map<string, string> {
  return new Map(
    columns
      .filter(column => column.role === 'metric')
      .map(column => [
        column.alias,
        column.measure ??
          metricMeasure(column.fn, column.numberFormat, column.alias),
      ]),
  );
}

/** How the values of a field show, for a column that holds them. */
export function valueOf(field: FieldDefinition) {
  const timeUnit = epochUnitOf(field);
  return {
    kind: field.kind,
    cell: field.cell ?? field.kind,
    ...(field.options ? { options: field.options } : {}),
    ...labelRemoteOf(field),
    ...(timeUnit ? { timeUnit } : {}),
  };
}

/**
 * A histogram's keys are bucket starts. A number histogram's interval says
 * how wide each band is. A date histogram's unit says how wide, and the
 * zone, when the group named one, the clock they were cut by. Without one the
 * engine's zone cut them (see `compileAnalysis`), which is the zone they are
 * shown in anyway.
 */
export function bucketOf(
  group: AnalysisGroup | undefined,
): Pick<
  AnalysisColumnView,
  'dateUnit' | 'timeZone' | 'interval' | 'missingKey' | 'datePart'
> {
  if (group?.type === AggregationGroupTypes.DATE_PART)
    return { datePart: group.part };
  if (group?.type === AggregationGroupTypes.HISTOGRAM)
    return { interval: group.interval };
  if (group?.type === AggregationGroupTypes.TERMS)
    return group.missingKey === undefined
      ? {}
      : { missingKey: group.missingKey };
  if (group?.type !== AggregationGroupTypes.DATE_HISTOGRAM) return {};
  return {
    dateUnit: group.unit,
    ...(group.timeZone === undefined ? {} : { timeZone: group.timeZone }),
  };
}

/**
 * The source's semantics a metric column reads by: its field's, where the
 * author gave the field no format, for a metric in the field's unit — a
 * sum of money is money. A count is in none.
 */
export function numericOf(
  metric: AnalysisMetric,
  field: FieldDefinition,
): Pick<AnalysisColumnView, 'numeric'> {
  if (field.numberFormat !== undefined || !field.numeric) return {};
  switch (metricFunctionOf(metric)) {
    case AggregationMetricTypes.COUNT:
    case AggregationMetricTypes.DISTINCT_COUNT:
    case AggregationMetricTypes.DERIVED:
      return {};
    default:
      return { numeric: field.numeric };
  }
}

/**
 * Each metric of money in a currency each record holds, by alias, with the
 * companions telling its currency: its own, or for a derived metric, those
 * of the metrics it is computed from (`derivedOperands`). A derived metric
 * is written in that currency only where its format says it is money.
 */
export function metricCurrencies(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
): Map<string, ColumnCurrency> {
  const { companions } = currencyCompanions(definition, config);
  if (companions.length === 0) return new Map();
  const own = new Map(companions.map(found => [found.metric, found]));
  const byAlias = new Map(config.metrics.map(metric => [metric.alias, metric]));
  const found = new Map<string, ColumnCurrency>();
  for (const metric of config.metrics) {
    const told = derivedOperands(metric, byAlias).flatMap(alias => {
      const companion = own.get(alias);
      return companion
        ? [{ code: companion.code, count: companion.count }]
        : [];
    });
    if (told.length === 0) continue;
    found.set(metric.alias, {
      companions: told,
      writes:
        metric.type !== AggregationMetricTypes.DERIVED ||
        (metric.format?.style === 'currency' &&
          metric.format.currency === undefined),
    });
  }
  return found;
}

/**
 * A formula or a derived metric said as its author would — 「(金额 − 成本)」,
 * 「金额总和 ÷ 客户数」 — which is the only name either has: no field stands
 * behind a formula, and a derived metric reads other metrics by alias.
 *
 * A formula is bracketed, because its name is the `{field}` a summary is
 * composed around: 「金额 − 成本的总和」 reads as 金额 minus the sum of
 * 成本 (2026-09-23 audit), 「(金额 − 成本)的总和」 as what was computed.
 */
export function formulaLabel(
  metric: AnalysisMetric,
  byName: ReadonlyMap<string, FieldDefinition>,
  byAlias: ReadonlyMap<string, AnalysisMetric>,
  conditionOf: (metric: AnalysisMetric) => MetricCondition | undefined,
): string | undefined {
  const fieldLabel = (field: string) => byName.get(field)?.label ?? field;
  if (isFormula(metric) || isDuration(metric))
    return expressionText(metric.expression, fieldLabel, true);
  if (metric.type !== AggregationMetricTypes.DERIVED) return undefined;
  return derivedText(metric.expression, alias => {
    const referenced = byAlias.get(alias);
    if (!referenced) return alias;
    // A name the analyst gave is the whole name; a derived operand is its own
    // text, references already marked; anything else is a summary of a field
    // or a formula, marked for the UI to word as its own column is worded —
    // its own condition included.
    if (referenced.label !== undefined) return referenced.label;
    if (referenced.type === AggregationMetricTypes.DERIVED)
      return formulaLabel(referenced, byName, byAlias, conditionOf) ?? alias;
    const source = metricFieldOf(referenced);
    return metricReferenceText(
      metricFunctionOf(referenced),
      formulaLabel(referenced, byName, byAlias, conditionOf) ??
        (source === undefined ? alias : fieldLabel(source)),
      conditionOf(referenced),
    );
  });
}
