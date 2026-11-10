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
  approximateMetrics,
  numberFormatOf,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type NumberFormat,
  type RecordData,
  type RuntimeLimits,
  AggregationGroupTypes,
  AggregationMetricTypes,
} from '../model/index.js';
import { inCurrency, joinCurrencies } from '../model/currency.js';
import type { FieldKindRegistry } from '../filter/index.js';
import { analysisScope } from './capability.js';
import { companionsReading } from './currency.js';
import { metricReach, withoutOutOfReach } from './metricWindow.js';
import { hostTimeZone, ongoingBucket } from './timeAxis.js';
import { shapeChart, type ChartData, type ShapeContext } from './chart.js';
import { analysisProbeLimit } from './compile.js';
import { chartUnfit } from './fitCharts.js';
import {
  formulaFormat,
  metricFieldOf,
  metricFormats,
  metricFunctionOf,
  metricMeasure,
  momentMetrics,
  readsAsItsField,
} from './metricFormat.js';
import { metricCondition } from './metricCondition.js';
import { expressionText } from './formula.js';
import { narrowsGroups } from './top.js';
import {
  bucketOf,
  formulaLabel,
  metricCurrencies,
  numericOf,
  valueOf,
  type AnalysisColumnView,
  type ColumnCurrency,
} from './columns.js';

export interface AnalysisProjection {
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
  /**
   * The metric types the source estimates (`approximateMetrics`): what a
   * title marks 「≈」, a derived metric's references included.
   */
  approximate?: readonly string[];
  /**
   * The rows of one more query, grouped by a split chart's axis alone, when
   * the split ran past the palette and its metric adds up (`foldsSplit`,
   * D33 Q56): each category's whole, which 「其他」 is measured against.
   */
  splitWhole?: RecordData[];
  /** Shaped for the configured chart family; absent when it cannot be drawn. */
  chart?: ChartData;
}

/**
 * Every alias the result holds, groups first. It is the column order the
 * table's list does not override and the source of `AnalysisProjection.schema`,
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

/**
 * The totals row without the metrics the whole range has no value of: an
 * `ANY` is one record's value, which under 「合计」 reads as the whole's.
 * Every other aggregate the ungrouped query answers is the whole's own —
 * a derived one is computed from the operands' wholes, which is right too.
 */
function wholeOf(row: RecordData, config: AnalysisViewConfig): RecordData {
  const wholeless = config.metrics
    .filter(metric => metric.type === AggregationMetricTypes.ANY)
    .map(metric => metric.alias);
  if (wholeless.length === 0) return row;
  return Object.fromEntries(
    Object.entries(row).filter(([alias]) => !wholeless.includes(alias)),
  );
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
 *
 * `limits` are the runtime's the query was compiled under: whether a probe
 * row was asked for depends on `maxAnalysisRows` (`analysisProbeLimit`).
 */
export function projectAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
  totals?: readonly RecordData[],
  kinds?: FieldKindRegistry,
  context: ShapeContext = {},
  limits?: Pick<RuntimeLimits, 'maxAnalysisRows'>,
): AnalysisProjection {
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
  const approximate = approximateMetrics(definition.analysis);
  const roles = new Map<string, 'group' | 'metric'>([
    ...config.groups.map(
      group => [group.alias, 'group'] as [string, 'group' | 'metric'],
    ),
    ...config.metrics.map(
      metric => [metric.alias, 'metric'] as [string, 'group' | 'metric'],
    ),
  ]);
  const sourceField = new Map<string, string>([
    ...config.groups.flatMap(group =>
      group.field === undefined ? [] : [[group.alias, group.field] as const],
    ),
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
  // A calendar part's keys are the part's own integers — 1 is a Monday, not
  // a moment in 1970 — so it reads as its part (`datePart`), never as the
  // time field it was taken from.
  const valued = new Set([
    ...[...groups.values()]
      .filter(group => group.type !== AggregationGroupTypes.DATE_PART)
      .map(group => group.alias),
    ...config.metrics.filter(readsAsItsField).map(metric => metric.alias),
  ]);

  // Every metric's format, operands before the derived metrics reading them.
  const formats = metricFormats(config.metrics, name => byName.get(name));

  // Money in a currency each record holds: a row whose records hold several
  // is no number — its sum adds unlike amounts — so its value goes, and
  // the companions stay to say why (`currencyCompanions`). A column all of
  // whose rows are in one currency is written in it, axis and card
  // included; one whose rows differ is read row by row (`rowCurrency`).
  const currencies = metricCurrencies(definition, config);
  // A metric whose own dates miss the question's counted nothing of the
  // span it names: null, not a 0 that reads as a period with no sales.
  const reach = metricReach(config, {
    now: context.now,
    timeZone: context.timeZone ?? hostTimeZone(),
  });
  const settle = (rows: readonly RecordData[]) =>
    withoutOutOfReach(rows, reach).map(row => settleRow(row, currencies));
  const kept = cutShort(definition, config, settle(result), limits);
  const cut = { ...kept, rows: undatedLast(config, kept.rows) };
  const ongoingOf = (alias: string) => {
    const ongoing = ongoingBucket(
      config,
      alias,
      cut.rows.map(row => row[alias]),
      context.timeZone ?? hostTimeZone(),
      context.now,
    );
    return ongoing ? { ongoing: ongoing.at } : {};
  };
  const settledTotals = totals && settle(totals);
  const overall =
    settledTotals && settledTotals.length > 0 ? settledTotals[0] : undefined;
  const splitWhole = context.splitWhole && settle(context.splitWhole);
  const withCurrency = (
    alias: string,
    format: NumberFormat | undefined,
  ): NumberFormat | undefined => {
    const currency = currencies.get(alias);
    if (!currency?.writes) return format;
    const reading = joinCurrencies(
      [...cut.rows, ...(overall ? [overall] : [])]
        .filter(row => typeof row[alias] === 'number')
        .map(row => companionsReading(row, currency.companions)),
    );
    return reading?.type === 'one' ? inCurrency(format, reading.code) : format;
  };

  const describe = (alias: string): AnalysisColumnView[] => {
    const role = roles.get(alias);
    if (!role) return [];
    const source = sourceField.get(alias);
    const field = source === undefined ? undefined : byName.get(source);
    const declaredColumn = declared.get(alias);
    const metric = byAlias.get(alias);
    const named = (groups.get(alias) ?? metric)?.label;
    const condition = metric && conditionOf(metric);
    // A band of a computed number reads in its expression's unit (N3).
    const computed = groups.get(alias);
    const expression =
      computed?.field === undefined && computed && 'expression' in computed
        ? computed.expression
        : undefined;
    const numberFormat = metric
      ? withCurrency(alias, formats.get(alias))
      : expression
        ? formulaFormat(expression, name => numberFormatOf(byName.get(name)))
        : numberFormatOf(field);
    return [
      {
        alias,
        label:
          named ??
          (metric && formulaLabel(metric, byName, byAlias, conditionOf)) ??
          (expression &&
            expressionText(
              expression,
              name => byName.get(name)?.label ?? name,
            )) ??
          field?.label ??
          source ??
          alias,
        role,
        ...(named === undefined ? {} : { named: true }),
        ...(metric ? { fn: metricFunctionOf(metric) } : {}),
        // Said only where the source said what it estimates; otherwise a
        // reader falls back to `DEFAULT_APPROXIMATE_METRICS` by function.
        ...(metric && definition.analysis?.approximate
          ? { approximate: approximate.includes(metric.type) }
          : {}),
        ...(metric && definition.analysis?.metricSort === false
          ? { sortable: false as const }
          : {}),
        ...(condition ? { condition } : {}),
        width: declaredColumn?.width,
        numberFormat,
        ...(currencies.has(alias) ? { currency: currencies.get(alias) } : {}),
        ...(metric && field ? numericOf(metric, field) : {}),
        ...(field && valued.has(alias) ? valueOf(field) : {}),
        ...bucketOf(groups.get(alias)),
        ...(groups.has(alias) ? ongoingOf(alias) : {}),
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

  return {
    columns: order.flatMap(describe),
    schema: resultSchema(config).flatMap(describe),
    approximate,
    ...cut,
    ...(overall ? { overall } : {}),
    ...(splitWhole ? { splitWhole } : {}),
    ...(overall && config.table.totals && config.groups.length > 0
      ? { totals: wholeOf(overall, config) }
      : {}),
    ...(narrowsGroups(config) && config.groups.length > 0
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
            ...(splitWhole ? { splitWhole } : {}),
            approximate,
            cutShort: cut.truncated || cut.atLimit !== undefined,
            valueOrder: listedValues(config.groups, byName),
          }),
        }
      : {}),
  };
}

/**
 * The rows the reader asked for, and what is known about the ones below them
 * — see `AnalysisProjection.truncated` and `AnalysisProjection.atLimit`.
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
/**
 * The rows with the group of records that have no date last, where the rows
 * run in time — sorted by a date dimension, or not sorted at all — as a time
 * axis draws it (`forwardInTime`). A date dimension keeps no sentinel key
 * (Wow allows one on a single string field only), so the records with no
 * date come back as a bucket of their own with no key, wherever the source
 * put it: second in the table, last on the chart, and the two read as
 * different results (second review R2-P1-4). A table sorted by a metric
 * keeps that order: there the bucket stands where its number puts it.
 */
function undatedLast(
  config: AnalysisViewConfig,
  rows: RecordData[],
): RecordData[] {
  const first = config.sort[0];
  const group =
    first === undefined
      ? config.groups[0]
      : config.groups.find(one => one.alias === first.alias);
  if (group?.type !== AggregationGroupTypes.DATE_HISTOGRAM) return rows;
  const undated = (row: RecordData) =>
    row[group.alias] === null || row[group.alias] === undefined;
  return rows.some(undated)
    ? [...rows.filter(row => !undated(row)), ...rows.filter(undated)]
    : rows;
}

function cutShort(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
  limits: Pick<RuntimeLimits, 'maxAnalysisRows'> | undefined,
): { rows: RecordData[]; truncated: boolean; atLimit?: number } {
  const rows = [...result];
  // An analysis with no groups asks one question and gets one row back, so a
  // limit of one is met by every successful answer and cuts nothing short.
  // Only a grouping can lose rows to a ceiling.
  if (config.groups.length === 0) return { rows, truncated: false };
  const limit = config.limit;
  if (!Number.isInteger(limit) || limit < 1) return { rows, truncated: false };

  const kept = rows.slice(0, limit);
  if (analysisProbeLimit(definition, config, limits) > limit)
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
 * A row with every metric whose records are in several currencies taken
 * out: a sum of yuan and yen is no amount, so it reaches no cell, bar or
 * card as one. The companions stay, so a reader is told why it is blank.
 */
function settleRow(
  row: RecordData,
  currencies: ReadonlyMap<string, ColumnCurrency>,
): RecordData {
  let settled = row;
  for (const [alias, currency] of currencies) {
    if (typeof row[alias] !== 'number') continue;
    if (companionsReading(row, currency.companions)?.type !== 'mixed') continue;
    if (settled === row) settled = { ...row };
    settled[alias] = null;
  }
  return settled;
}

/**
 * Root fields plus the fields of every expanded element, by their paths.
 *
 * The capability is required, as it is in `compileAnalysis` and
 * `defaultAnalysisConfig`: an analysis view of a definition that offers none
 * is a programming error, and admission reports it long before a result
 * arrives here.
 */
/**
 * The values each group's field lists, in the host's order, by the group's
 * alias (`ShapeContext.valueOrder`); a group over a field that lists none is
 * left out.
 */
function listedValues(
  groups: readonly AnalysisGroup[],
  byName: ReadonlyMap<string, FieldDefinition>,
): Record<string, (string | number)[]> {
  return Object.fromEntries(
    groups.flatMap(group => {
      const options =
        group.field === undefined
          ? undefined
          : byName.get(group.field)?.options;
      return options && options.length > 0
        ? [[group.alias, options.map(option => option.value)]]
        : [];
    }),
  );
}

/** `listedValues` over the definition's analysis scope for `config`. */
export function valueOrderOf(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
): Record<string, (string | number)[]> {
  return listedValues(config.groups, scopeFields(definition, config));
}

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
