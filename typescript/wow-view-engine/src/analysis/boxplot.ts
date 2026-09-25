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
  fieldAliasSegment,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type BoxplotSpec,
  type RecordData,
} from '../model/index.js';
import { num } from './chartRows.js';
import { freeAlias } from './defaults.js';
import { forwardInTime, timeGroup } from './timeAxis.js';

/** The five numbers of a box, by the slot each fills. */
export type FiveNumbers = Omit<BoxplotSpec, 'category'>;

/** The slots of a box, from its lowest to its highest. */
export const FIVE_NUMBER_SLOTS = [
  'low',
  'q1',
  'median',
  'q3',
  'high',
] as const satisfies readonly (keyof FiveNumbers)[];

/**
 * The percentiles a box's quartiles and median stand at, as the tray adds
 * them (`fiveNumberMetrics`). A box drawn from other percentiles — a P10 to
 * P90 box — is read by them: the tooltip and the reading table name every
 * number by its column, never as 「四分位」.
 */
export const BOX_PERCENTILES = [25, 50, 75] as const;

/**
 * What one metric is, as a box reads it: which number of the five it can
 * be, and what it is taken of — its expression and its condition, as
 * text, so two metrics are of one field under one condition exactly when
 * the two keys are equal.
 */
function boxPart(
  metric: AnalysisMetric,
): { of: string; kind: 'MIN' | 'MAX' | 'PERCENTILE'; at: number } | undefined {
  const filter = metric.type === 'DERIVED' ? undefined : metric.filter;
  const scope = JSON.stringify(filter ?? null);
  if (metric.type === 'PERCENTILE')
    return {
      of: `${JSON.stringify(metric.expression)}|${scope}`,
      kind: 'PERCENTILE',
      at: metric.percentile,
    };
  if (
    metric.type === 'NUMERIC' &&
    (metric.function === 'MIN' || metric.function === 'MAX')
  )
    return {
      of: `${JSON.stringify(metric.expression)}|${scope}`,
      kind: metric.function,
      at: metric.function === 'MIN' ? 0 : 100,
    };
  return undefined;
}

/**
 * Every complete set of five numbers the metrics hold, one per field (and
 * condition) that has them: its `MIN`, its `MAX` and three `PERCENTILE`s —
 * the lowest the lower quartile, the highest the upper, and of the ones
 * between, the one nearest 50 the median. Only `quantities` are read: the
 * earliest and the latest of a date are moments, which no box measures.
 *
 * In the order the metrics name their fields, so the first set is the one
 * the analyst added first.
 */
export function fiveNumberSets(
  metrics: readonly AnalysisMetric[],
  quantities: ReadonlySet<string> = new Set(
    metrics.map(metric => metric.alias),
  ),
): FiveNumbers[] {
  const byField = new Map<
    string,
    {
      low?: string;
      high?: string;
      percentiles: { alias: string; at: number }[];
    }
  >();
  for (const metric of metrics) {
    if (!quantities.has(metric.alias)) continue;
    const part = boxPart(metric);
    if (!part) continue;
    const entry = byField.get(part.of) ?? { percentiles: [] };
    if (part.kind === 'MIN') entry.low ??= metric.alias;
    else if (part.kind === 'MAX') entry.high ??= metric.alias;
    else if (!entry.percentiles.some(one => one.at === part.at))
      entry.percentiles.push({ alias: metric.alias, at: part.at });
    byField.set(part.of, entry);
  }
  const sets: FiveNumbers[] = [];
  for (const { low, high, percentiles } of byField.values()) {
    if (low === undefined || high === undefined || percentiles.length < 3)
      continue;
    const sorted = [...percentiles].sort((a, b) => a.at - b.at);
    const inner = sorted.slice(1, -1);
    const median = inner.reduce((best, one) =>
      Math.abs(one.at - 50) < Math.abs(best.at - 50) ? one : best,
    );
    sets.push({
      low,
      q1: sorted[0].alias,
      median: median.alias,
      q3: sorted[sorted.length - 1].alias,
      high,
    });
  }
  return sets;
}

/**
 * Whether the five slots of a spec are a set as `fiveNumberSets` reads
 * one: the `MIN`, three percentiles rising and the `MAX`, all of one field
 * under one condition. A box drawn from anything else — two fields, an
 * average for a median — would draw a shape that says nothing.
 */
export function isFiveNumberSet(
  spec: FiveNumbers,
  metricOf: (alias: string) => AnalysisMetric | undefined,
): boolean {
  const parts = FIVE_NUMBER_SLOTS.map(slot => {
    const metric = metricOf(spec[slot]);
    return metric && boxPart(metric);
  });
  if (parts.some(part => part === undefined)) return false;
  const [low, q1, median, q3, high] = parts as NonNullable<
    (typeof parts)[number]
  >[];
  return (
    low.kind === 'MIN' &&
    high.kind === 'MAX' &&
    [q1, median, q3].every(part => part.kind === 'PERCENTILE') &&
    q1.at < median.at &&
    median.at < q3.at &&
    parts.every(part => part?.of === low.of)
  );
}

/**
 * The metrics a box of `metric`'s field still lacks (「补齐箱线图的五个数」):
 * of its `MIN`, its 25th, 50th and 75th percentiles and its `MAX`, the ones
 * it is not itself — each under the metric's own condition, named by a free
 * alias beside `taken`. `undefined` for a metric that measures no field's
 * values: a count, a distinct count, any one value, a formula.
 */
export function fiveNumberMetrics(
  metric: AnalysisMetric,
  taken: readonly string[],
): AnalysisMetric[] | undefined {
  if (
    metric.type !== 'PERCENTILE' &&
    !(metric.type === 'NUMERIC' && metric.expression.type === 'FIELD')
  )
    return undefined;
  const { expression, filter } = metric;
  const own = boxPart(metric);
  const stem =
    expression.type === 'FIELD' ? fieldAliasSegment(expression.field) : 'value';
  const used = [...taken];
  const alias = (suffix: string) => {
    const next = freeAlias(`${stem}_${suffix}`, used);
    used.push(next);
    return next;
  };
  const scoped = filter === undefined ? {} : { filter };
  const parts: (
    { kind: 'MIN' | 'MAX' } | { kind: 'PERCENTILE'; at: number }
  )[] = [
    { kind: 'MIN' },
    ...BOX_PERCENTILES.map(at => ({ kind: 'PERCENTILE' as const, at })),
    { kind: 'MAX' },
  ];
  return parts
    .filter(
      part =>
        !(
          own &&
          own.kind === part.kind &&
          (part.kind !== 'PERCENTILE' || own.at === part.at)
        ),
    )
    .map((part): AnalysisMetric =>
      part.kind === 'PERCENTILE'
        ? {
            type: 'PERCENTILE',
            alias: alias(`p${part.at}`),
            expression,
            percentile: part.at,
            ...scoped,
          }
        : {
            type: 'NUMERIC',
            alias: alias(part.kind.toLowerCase()),
            function: part.kind,
            expression,
            ...scoped,
          },
    );
}

/**
 * The five numbers a boxplot draws (`fitChartSlots`): the set the spec
 * names while every alias of it is still one of the sets the metrics hold,
 * the first set otherwise — a box is five numbers of one field, so a set
 * is kept or replaced whole, never patched. Empty slots, which validation
 * names, when there is none.
 */
export function boxSet(
  spec: FiveNumbers | undefined,
  sets: readonly FiveNumbers[],
): FiveNumbers {
  const kept = sets.find(
    set => spec && FIVE_NUMBER_SLOTS.every(slot => set[slot] === spec[slot]),
  );
  return kept ?? sets[0] ?? { low: '', q1: '', median: '', q3: '', high: '' };
}

/** One box: a group and its five numbers, lowest first. */
export interface BoxplotBox {
  /** The group value the box stands for. */
  group: unknown;
  low: number;
  q1: number;
  median: number;
  q3: number;
  high: number;
}

export interface BoxplotData {
  type: 'boxplot';
  boxes: BoxplotBox[];
  /**
   * How many groups have no box: a row missing one of the five — a group
   * with no value of the field has no lowest, no median, nothing. They stay
   * in the table; the drawing says how many it left out.
   */
  omitted: number;
  /**
   * Whether the quartiles and the median are approximate: the source
   * estimates percentiles (`AnalysisCapability.approximate`, which the
   * analysis table's 「≈」 reads too).
   */
  approximate: boolean;
}

/**
 * A boxplot's boxes, one per row: the five numbers its row measured. A box
 * over time runs earliest first, as every time axis does; any other
 * dimension keeps the rows' order, which is the view's sort. A row that
 * lacks one of the five is no box and is counted instead.
 */
export function shapeBoxplot(
  spec: BoxplotSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  approximate = true,
): BoxplotData {
  const ordered = timeGroup(config, spec.category)
    ? forwardInTime(rows, row => row[spec.category])
    : rows;
  const boxes: BoxplotBox[] = [];
  let omitted = 0;
  for (const row of ordered) {
    const values = FIVE_NUMBER_SLOTS.map(slot => num(row, spec[slot]));
    if (values.some(value => value === null)) {
      omitted += 1;
      continue;
    }
    const [low, q1, median, q3, high] = values as number[];
    boxes.push({ group: row[spec.category], low, q1, median, q3, high });
  }
  return { type: 'boxplot', boxes, omitted, approximate };
}
