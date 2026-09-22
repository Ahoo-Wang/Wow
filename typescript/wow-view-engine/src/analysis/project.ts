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
  AnalysisDateUnit,
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
  FieldOption,
  NumberFormat,
  RecordData,
} from '../model/index.js';
import { analysisScope } from './capability.js';
import { shapeChart, type ChartData } from './chart.js';
import { analysisProbeLimit } from './compile.js';
import {
  metricFormat,
  metricFunctionOf,
  type MetricFunction,
} from './metricFormat.js';
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
   * For a metric: which summary it is, so a header can read 「金额 的 平均」
   * rather than the alias `amount_1`. Two metrics over one field differ here
   * and nowhere else, which is exactly why the header cannot be the label.
   */
  fn?: MetricFunction;
  /**
   * The analyst named this column (`AnalysisNamed.label`): `label` is the
   * whole title, and `fn` stays only for how the numbers read.
   */
  named?: true;
  width?: number;
  /**
   * How this column's numbers print. A group shows values of its field, so it
   * takes the field's own format; a metric takes `metricFormat`, because what
   * an aggregate is decides how it reads, not what it was computed from.
   */
  numberFormat?: NumberFormat;
  /**
   * For a group, or an `ANY` whose value is one of the field's: the field's
   * kind, renderer key and choices, so its values show as the field's do.
   * Other metrics are numbers, whatever they were computed from.
   */
  kind?: string;
  cell?: string;
  options?: readonly FieldOption[];
  /** For a date histogram group: the width of the buckets its keys start. */
  dateUnit?: AnalysisDateUnit;
  /** For a date histogram group: the zone its buckets were cut in. */
  timeZone?: string;
}

export interface AnalysisView {
  /** The table's columns: those `table.columns` picks, in its order. */
  columns: AnalysisColumnView[];
  /**
   * Every alias the result holds, described as `columns` are, whatever the
   * table picks. A chart names its categories through these: it may group by
   * a column the table leaves out, whose values would otherwise show raw.
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
  /** Present only when `table.totals` asked for it and its query succeeded. */
  totals?: RecordData;
  /** Shaped for the configured chart family; absent when it cannot be drawn. */
  chart?: ChartData;
}

/**
 * Every alias the result holds, groups first. It is the default column order
 * and the source of `AnalysisView.schema`, and nothing else: it was once
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
 * The field a column is computed from, when it has one.
 *
 * Every metric that reads a single field names it: `ANY` directly, and the
 * three expression-carrying kinds through a `FIELD` expression. Only `NUMERIC`
 * used to be looked up, so a `DISTINCT_COUNT` of customers or a p95 of latency
 * fell back to its alias and lost both its label and its number format.
 */
function sourceFieldOf(metric: AnalysisMetric): string | undefined {
  if (metric.type === 'ANY') return metric.field;
  if (
    metric.type === 'NUMERIC' ||
    metric.type === 'DISTINCT_COUNT' ||
    metric.type === 'PERCENTILE'
  )
    return metric.expression?.type === 'FIELD'
      ? metric.expression.field
      : undefined;
  return undefined;
}

/** How the values of a field show, for a column that holds them. */
function valueOf(
  field: FieldDefinition,
): Pick<AnalysisColumnView, 'kind' | 'cell' | 'options'> {
  return {
    kind: field.kind,
    cell: field.cell ?? field.kind,
    ...(field.options ? { options: field.options } : {}),
  };
}

/**
 * A date histogram's keys are bucket starts: the unit says how wide, and the
 * zone, when the group named one, the clock they were cut by. Without one the
 * engine's zone cut them (see `compileAnalysis`), which is the zone they are
 * shown in anyway.
 */
function bucketOf(
  group: AnalysisGroup | undefined,
): Pick<AnalysisColumnView, 'dateUnit' | 'timeZone'> {
  if (group?.type !== 'DATE_HISTOGRAM') return {};
  return {
    dateUnit: group.unit,
    ...(group.timeZone === undefined ? {} : { timeZone: group.timeZone }),
  };
}

/**
 * Turns aggregation rows into table columns plus whatever the chart family
 * needs. A DERIVED metric is an ordinary column: the backend computed it.
 */
export function projectAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  result: readonly RecordData[],
  totals?: readonly RecordData[],
): AnalysisView {
  const declared = new Map(
    config.table.columns.map(column => [column.alias, column]),
  );
  const order =
    config.table.columns.length > 0
      ? config.table.columns.map(column => column.alias)
      : resultSchema(config);

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
      const field = sourceFieldOf(metric);
      return field === undefined
        ? ([] as const)
        : ([[metric.alias, field]] as const);
    }),
  ]);
  // The analysis scope, not the raw field list: an element field is addressed
  // as `items.sku`, which no root field is named, so a grouping or metric over
  // one used to be labelled by its alias.
  const byName = scopeFields(definition, config);
  const groups = new Map<string, AnalysisGroup>(
    config.groups.map(group => [group.alias, group]),
  );
  const valued = new Set([
    ...groups.keys(),
    ...config.metrics
      .filter(metric => metric.type === 'ANY')
      .map(metric => metric.alias),
  ]);

  const describe = (alias: string): AnalysisColumnView[] => {
    const role = roles.get(alias);
    if (!role) return [];
    const source = sourceField.get(alias);
    const field = source === undefined ? undefined : byName.get(source);
    const declaredColumn = declared.get(alias);
    const metric = byAlias.get(alias);
    const named = (groups.get(alias) ?? metric)?.label;
    return [
      {
        alias,
        label:
          named ??
          (metric && formulaLabel(metric, byName, byAlias)) ??
          field?.label ??
          source ??
          alias,
        role,
        ...(named === undefined ? {} : { named: true }),
        ...(metric ? { fn: metricFunctionOf(metric) } : {}),
        width: declaredColumn?.width,
        numberFormat: metric
          ? metricFormat(metric, field)
          : field?.numberFormat,
        ...(field && valued.has(alias) ? valueOf(field) : {}),
        ...bucketOf(groups.get(alias)),
      },
    ];
  };

  const cut = cutShort(definition, config, result);

  return {
    columns: order.flatMap(describe),
    schema: resultSchema(config).flatMap(describe),
    ...cut,
    ...(totals && totals.length > 0 ? { totals: totals[0] } : {}),
    // The probe row is not one of the groups the reader asked for, so the
    // chart is shaped from the rows that survive the cut: a pie's shares and
    // a "other" tail are over what is on screen and nothing else.
    ...(config.layout === 'chart'
      ? { chart: shapeChart(config, cut.rows, totals?.[0]) }
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
 * A formula or a derived metric said as its author would — 「金额 − 成本」,
 * 「金额合计 ÷ 客户数」 — which is the only name either has: no field stands
 * behind a formula, and a derived metric reads other metrics by alias.
 */
function formulaLabel(
  metric: AnalysisMetric,
  byName: ReadonlyMap<string, FieldDefinition>,
  byAlias: ReadonlyMap<string, AnalysisMetric>,
): string | undefined {
  const fieldLabel = (field: string) => byName.get(field)?.label ?? field;
  if (isFormula(metric)) return expressionText(metric.expression, fieldLabel);
  if (metric.type !== 'DERIVED') return undefined;
  return derivedText(metric.expression, alias => {
    const referenced = byAlias.get(alias);
    if (!referenced) return alias;
    // A name the analyst gave is the whole name; a derived operand is its own
    // text, references already marked; anything else is a summary of a field
    // or a formula, marked for the UI to word as its own column is worded.
    if (referenced.label !== undefined) return referenced.label;
    if (referenced.type === 'DERIVED')
      return formulaLabel(referenced, byName, byAlias) ?? alias;
    const source = sourceFieldOf(referenced);
    return metricReferenceText(
      metricFunctionOf(referenced),
      formulaLabel(referenced, byName, byAlias) ??
        (source === undefined ? alias : fieldLabel(source)),
    );
  });
}
