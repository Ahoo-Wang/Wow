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

import {
  epochUnitOf,
  isDateCell,
  type AnalysisDateUnit,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type NumberFormat,
  type RecordData,
  type EpochTimeUnit,
} from '../model/index.js';
import type { FieldKindRegistry } from '../filter/index.js';
import { analysisScope } from './capability.js';
import { shapeChart, type ChartData, type ShapeContext } from './chart.js';
import { analysisProbeLimit } from './compile.js';
import { chartUnfit } from './fitCharts.js';
import {
  formulaFormat,
  metricFieldOf,
  metricFormat,
  metricFunctionOf,
  metricMeasure,
  momentMetrics,
  readsAsItsField,
  type MetricFunction,
} from './metricFormat.js';
import { metricCondition, type MetricCondition } from './metricCondition.js';
import {
  metricReferenceText,
  derivedText,
  expressionText,
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
   * For a group, or a metric whose value is one of the field's
   * (`readsAsItsField` — its earliest, its latest, a percentile, any one):
   * the field's kind, renderer key and choices, so its values show as the
   * field's do, a date as a date. Other metrics are numbers, whatever they
   * were computed from.
   */
  kind?: string;
  cell?: string;
  options?: readonly FieldOption[];
  /** A field kept in epoch seconds; milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
  /** For a date histogram group: the width of the buckets its keys start. */
  dateUnit?: AnalysisDateUnit;
  /** For a date histogram group: the zone its buckets were cut in. */
  timeZone?: string;
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
}

export interface AnalysisView {
  /**
   * The table's columns: every alias the result holds, those `table.columns`
   * names first and in its order, then the rest as `schema` lists them. The
   * list orders and sizes columns; it never decides which exist.
   */
  columns: AnalysisColumnView[];
  /**
   * Every alias the result holds, described as `columns` are, in the order
   * the question asks them — groups, then metrics — whatever order the table
   * was dragged into. A chart names its categories through these, and a
   * reading of the result says them in this order.
   * `projectAnalysis` always sets it; a view built by hand may leave it out.
   */
  schema?: AnalysisColumnView[];
  /** The groups on screen: at most `limit` of them, the probe row dropped. */
  rows: RecordData[];
  /**
   * Whether groups exist that `rows` does not hold. Known, not guessed: the
   * query asked Wow for one row more than the limit (`analysisProbeLimit`),
   * and this says that row came back. It never reaches `rows` — the reader
   * asked for `limit` groups and gets `limit` groups.
   *
   * It matters because every share, percentage and pie slice on a cut-short
   * result is computed over a prefix, and a complete-looking table is the one
   * thing a reader cannot check for themselves.
   */
  truncated: boolean;
  /**
   * The row limit the result exactly filled **when no probe was possible**.
   *
   * The configured limit already sits on the ceiling (`analysisProbeLimit`),
   * so there is no row left to ask for and "came back exactly full" is all
   * there is — ambiguous, because a grouping of exactly that size looks the
   * same as one cut down to it. It is reported as "may have been cut short"
   * for that reason, and never as a fact.
   *
   * Absent everywhere else, `truncated` having the answer there: the two are
   * never both set.
   */
  atLimit?: number;
  /**
   * Present only when `table.totals` asked for it, the result has a
   * dimension, and its query succeeded. Without a dimension the one row *is*
   * every record in the range, and a totals row under it said the same
   * numbers twice (2026-09-23 audit).
   *
   * Every aggregate of the whole range is right for it — a sum, an average
   * of every record, a distinct count, a percentile, the earliest and the
   * latest — bar `ANY` (`wholeOf`), which is one record's value: over the
   * whole range it belongs to nobody, and under 「合计」 it would read as the
   * whole's. It is left out, and its cell is blank.
   */
  totals?: RecordData;
  /**
   * 「只保留」 was in force: groups the having dropped are in `totals` and in
   * no row, so a table says so where its totals are rather than leave the
   * two to disagree unexplained. Absent without a dimension, where there is
   * nothing to drop.
   */
  narrowed?: true;
  /**
   * The ungrouped answer — every record in the range, one row — when the
   * config asked for it (`asksForWhole`) and its query succeeded. What a
   * chart reads as "the whole": the headline of a metric card over a trend.
   * `totals` is the same row when the table was asked to draw it.
   */
  overall?: RecordData;
  /** Shaped for the configured chart family; absent when it cannot be drawn. */
  chart?: ChartData;
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

/**
 * Every alias the result holds, groups first. It is the column order the
 * table's list does not override and the source of `AnalysisView.schema`,
 * and nothing else: it was once
 * exported as "what a returned row is validated against", which nothing has
 * ever done — rows come back from Wow and are projected, never checked.
 */
function resultSchema(config: AnalysisViewConfig): string[] {
  return [
    ...config.groups.map(group => group.alias),
    ...config.metrics.map(metric => metric.alias),
  ];
}

/** How the values of a field show, for a column that holds them. */
function valueOf(
  field: FieldDefinition,
): Pick<AnalysisColumnView, 'kind' | 'cell' | 'options' | 'timeUnit'> {
  const timeUnit = epochUnitOf(field);
  return {
    kind: field.kind,
    cell: field.cell ?? field.kind,
    ...(field.options ? { options: field.options } : {}),
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
/**
 * The totals row without the metrics the whole range has no value of: an
 * `ANY` is one record's value, which under 「合计」 reads as the whole's.
 * Every other aggregate the ungrouped query answers is the whole's own —
 * a derived one is computed from the operands' wholes, which is right too.
 */
function wholeOf(row: RecordData, config: AnalysisViewConfig): RecordData {
  const wholeless = config.metrics
    .filter(metric => metric.type === 'ANY')
    .map(metric => metric.alias);
  if (wholeless.length === 0) return row;
  return Object.fromEntries(
    Object.entries(row).filter(([alias]) => !wholeless.includes(alias)),
  );
}

function bucketOf(
  group: AnalysisGroup | undefined,
): Pick<
  AnalysisColumnView,
  'dateUnit' | 'timeZone' | 'interval' | 'missingKey'
> {
  if (group?.type === 'HISTOGRAM') return { interval: group.interval };
  if (group?.type === 'TERMS')
    return group.missingKey === undefined
      ? {}
      : { missingKey: group.missingKey };
  if (group?.type !== 'DATE_HISTOGRAM') return {};
  return {
    dateUnit: group.unit,
    ...(group.timeZone === undefined ? {} : { timeZone: group.timeZone }),
  };
}

/**
 * Turns aggregation rows into table columns plus whatever the chart family
 * needs. A DERIVED metric is an ordinary column: the backend computed it.
 *
 * `kinds` read a metric's own condition (`AnalysisColumnView.condition`);
 * without them a conditioned column cannot say what it counts, and says
 * nothing rather than guess.
 *
 * `context` is the engine's zone and the moment the question was asked, the
 * chart is shaped under (`ShapeContext`): a day bucket is stepped in the
 * engine's zone, not the host's, and a trend card's last period is the last
 * one over by the time it was asked.
 */
export function projectAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
  totals?: readonly RecordData[],
  kinds?: FieldKindRegistry,
  context: ShapeContext = {},
): AnalysisView {
  const declared = new Map(
    config.table.columns.map(column => [column.alias, column]),
  );
  // `table.columns` is an override of order and width, not an allow-list
  // (2026-09-23 audit P0-1). A metric or dimension added in the tray is part
  // of the answer the moment the query runs, so it is a column whether or
  // not the list has heard of it: appended after the listed ones, groups
  // before metrics, as the question names them. An allow-list made "add a
  // dimension" draw two 华东 rows with nothing on screen telling them apart.
  // A listed alias the result no longer holds describes to nothing below.
  const order = [
    ...new Set([
      ...config.table.columns.map(column => column.alias),
      ...resultSchema(config),
    ]),
  ];

  const byAlias = new Map<string, AnalysisMetric>(
    config.metrics.map(metric => [metric.alias, metric]),
  );
  const roles = new Map<string, 'group' | 'metric'>([
    ...config.groups.map(
      group => [group.alias, 'group'] as [string, 'group' | 'metric'],
    ),
    ...config.metrics.map(
      metric => [metric.alias, 'metric'] as [string, 'group' | 'metric'],
    ),
  ]);
  const sourceField = new Map<string, string>([
    ...config.groups.map(group => [group.alias, group.field] as const),
    ...config.metrics.flatMap(metric => {
      const field = metricFieldOf(metric);
      return field === undefined
        ? ([] as const)
        : ([[metric.alias, field]] as const);
    }),
  ]);
  // The analysis scope, not the raw field list: an element field is addressed
  // as `items.sku`, which no root field is named, so a grouping or metric over
  // one used to be labelled by its alias.
  const byName = scopeFields(definition, config);
  const conditionOf = (metric: AnalysisMetric) =>
    metricCondition(metric, [...byName.values()], kinds);
  const groups = new Map<string, AnalysisGroup>(
    config.groups.map(group => [group.alias, group]),
  );
  // The columns whose values are the field's own: a dimension's keys, and a
  // metric that is one of the field's values (`readsAsItsField`) — the
  // latest of a datetime is a datetime, not an epoch in milliseconds.
  const valued = new Set([
    ...groups.keys(),
    ...config.metrics.filter(readsAsItsField).map(metric => metric.alias),
  ]);

  const describe = (alias: string): AnalysisColumnView[] => {
    const role = roles.get(alias);
    if (!role) return [];
    const source = sourceField.get(alias);
    const field = source === undefined ? undefined : byName.get(source);
    const declaredColumn = declared.get(alias);
    const metric = byAlias.get(alias);
    const named = (groups.get(alias) ?? metric)?.label;
    const condition = metric && conditionOf(metric);
    const numberFormat = metric
      ? metricFormat(
          metric,
          field ??
            (isFormula(metric)
              ? {
                  numberFormat: formulaFormat(
                    metric.expression,
                    name => byName.get(name)?.numberFormat,
                  ),
                }
              : undefined),
        )
      : field?.numberFormat;
    return [
      {
        alias,
        label:
          named ??
          (metric && formulaLabel(metric, byName, byAlias, conditionOf)) ??
          field?.label ??
          source ??
          alias,
        role,
        ...(named === undefined ? {} : { named: true }),
        ...(metric ? { fn: metricFunctionOf(metric) } : {}),
        ...(condition ? { condition } : {}),
        width: declaredColumn?.width,
        numberFormat,
        ...(field && valued.has(alias) ? valueOf(field) : {}),
        ...bucketOf(groups.get(alias)),
        // What its number is a quantity of: the unit its format declares,
        // or the field itself where none is (`metricMeasure`).
        ...(metric
          ? {
              measure: metricMeasure(
                metricFunctionOf(metric),
                numberFormat,
                source ?? alias,
              ),
            }
          : {}),
      },
    ];
  };

  const cut = cutShort(definition, config, result);
  const overall = totals && totals.length > 0 ? totals[0] : undefined;

  return {
    columns: order.flatMap(describe),
    schema: resultSchema(config).flatMap(describe),
    ...cut,
    ...(overall ? { overall } : {}),
    ...(overall && config.table.totals && config.groups.length > 0
      ? { totals: wholeOf(overall, config) }
      : {}),
    ...(config.having !== undefined && config.groups.length > 0
      ? { narrowed: true as const }
      : {}),
    // The probe row is not one of the groups the reader asked for, so the
    // chart is shaped from the rows that survive the cut: a pie's shares and
    // a "other" tail are over what is on screen and nothing else. A chart
    // whose type cannot draw the shape (`chartUnfit`) is drawn as the table
    // and shapes nothing: three dimensions under bars would leave several
    // rows at one point, and averages cannot be added back up over them.
    ...(config.layout === 'chart' &&
    chartUnfit(config, momentMetrics(config.metrics, byName)) === null
      ? {
          chart: shapeChart(config, cut.rows, overall, {
            ...context,
            cutShort: cut.truncated || cut.atLimit !== undefined,
          }),
        }
      : {}),
  };
}

/**
 * The rows the reader asked for, and what is known about the ones below them
 * — see `AnalysisView.truncated` and `AnalysisView.atLimit`.
 *
 * The query asked for one row more than the limit, so a result longer than
 * the limit is the probe coming back: more groups exist, and the extra row is
 * dropped rather than shown. A result no longer than the limit is the whole
 * grouping, whatever its length.
 *
 * Where the probe was impossible — the configured limit already on the
 * ceiling — only "exactly full" is left, and that is the one case reported as
 * a maybe.
 *
 * The limit is read as the untrusted number it is. `validateAnalysis` refuses
 * anything but a positive integer, but this function is exported and a host
 * may project a config nothing admitted; a missing or nonsensical limit means
 * nothing is known about what was left out, which is not the same as knowing
 * nothing was.
 */
function cutShort(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
): { rows: RecordData[]; truncated: boolean; atLimit?: number } {
  const rows = [...result];
  // An analysis with no groups asks one question and gets one row back, so a
  // limit of one is met by every successful answer and cuts nothing short.
  // Only a grouping can lose rows to a ceiling.
  if (config.groups.length === 0) return { rows, truncated: false };
  const limit = config.limit;
  if (!Number.isInteger(limit) || limit < 1) return { rows, truncated: false };

  const kept = rows.slice(0, limit);
  if (analysisProbeLimit(definition, config) > limit)
    return { rows: kept, truncated: result.length > limit };
  // No probe: the limit is the ceiling. A source that answered past a ceiling
  // it cannot have honoured still filled the limit, and the reader still sees
  // exactly `limit` rows.
  return {
    rows: kept,
    truncated: false,
    ...(result.length >= limit ? { atLimit: limit } : {}),
  };
}

/**
 * Root fields plus the fields of every expanded element, by their paths.
 *
 * The capability is required, as it is in `compileAnalysis` and
 * `defaultAnalysisConfig`: an analysis view of a definition that offers none
 * is a programming error, and admission reports it long before a result
 * arrives here.
 */
function scopeFields(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
): ReadonlyMap<string, FieldDefinition> {
  const capability = definition.analysis;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no analysis capability`,
    );
  return analysisScope(definition, capability, config).fields;
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
function formulaLabel(
  metric: AnalysisMetric,
  byName: ReadonlyMap<string, FieldDefinition>,
  byAlias: ReadonlyMap<string, AnalysisMetric>,
  conditionOf: (metric: AnalysisMetric) => MetricCondition | undefined,
): string | undefined {
  const fieldLabel = (field: string) => byName.get(field)?.label ?? field;
  if (isFormula(metric))
    return expressionText(metric.expression, fieldLabel, true);
  if (metric.type !== 'DERIVED') return undefined;
  return derivedText(metric.expression, alias => {
    const referenced = byAlias.get(alias);
    if (!referenced) return alias;
    // A name the analyst gave is the whole name; a derived operand is its own
    // text, references already marked; anything else is a summary of a field
    // or a formula, marked for the UI to word as its own column is worded —
    // its own condition included.
    if (referenced.label !== undefined) return referenced.label;
    if (referenced.type === 'DERIVED')
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
