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
  type CandlestickSpec,
  type RecordData,
} from '../model/index.js';
import { num } from './chartRows.js';
import { freeAlias } from './defaults.js';
import { forwardInTime } from './timeAxis.js';

/** The four numbers of a candle, by the slot each fills. */
export type Ohlc = Omit<CandlestickSpec, 'x'>;

/** The slots of a candle, as a K line reads them: open, high, low, close. */
export const OHLC_SLOTS = [
  'open',
  'high',
  'low',
  'close',
] as const satisfies readonly (keyof Ohlc)[];

/**
 * What one metric is, as a candle reads it: which of the four it can be,
 * and what it is taken of — its field and its condition as text, so two
 * metrics are of one field under one condition exactly when the two keys
 * are equal. An opening and a closing value also carry the time they are
 * ordered by: a candle whose open is the earliest by one clock and whose
 * close the latest by another opens and closes on two different days.
 */
function candlePart(
  metric: AnalysisMetric,
): { of: string; slot: keyof Ohlc; order?: string } | undefined {
  if (metric.type === 'DERIVED' || metric.type === 'COUNT') return undefined;
  const scope = JSON.stringify(metric.filter ?? null);
  if (metric.type === 'FIRST' || metric.type === 'LAST')
    return {
      of: `${metric.field}|${scope}`,
      slot: metric.type === 'FIRST' ? 'open' : 'close',
      order: metric.orderBy ?? '',
    };
  if (
    metric.type === 'NUMERIC' &&
    // A config from a store may hold a metric without its expression.
    metric.expression?.type === 'FIELD' &&
    (metric.function === 'MIN' || metric.function === 'MAX')
  )
    return {
      of: `${metric.expression.field}|${scope}`,
      slot: metric.function === 'MAX' ? 'high' : 'low',
    };
  return undefined;
}

/**
 * Every complete set of four numbers the metrics hold, one per field (and
 * condition) that has them: its opening value (`FIRST`), its highest
 * (`MAX`), its lowest (`MIN`) and its closing value (`LAST`) ordered by
 * the same time as the open. Only `quantities` are read: the earliest of a
 * date is a moment, which no candle measures.
 *
 * In the order the metrics name their fields, so the first set is the one
 * the analyst added first.
 */
export function ohlcSets(
  metrics: readonly AnalysisMetric[],
  quantities: ReadonlySet<string> = new Set(
    metrics.map(metric => metric.alias),
  ),
): Ohlc[] {
  const byField = new Map<
    string,
    Partial<Ohlc> & { opens: { alias: string; order: string }[] }
  >();
  const closes: { of: string; alias: string; order: string }[] = [];
  for (const metric of metrics) {
    if (!quantities.has(metric.alias)) continue;
    const part = candlePart(metric);
    if (!part) continue;
    const entry = byField.get(part.of) ?? { opens: [] };
    if (part.slot === 'open')
      entry.opens.push({ alias: metric.alias, order: part.order ?? '' });
    else if (part.slot === 'close')
      closes.push({
        of: part.of,
        alias: metric.alias,
        order: part.order ?? '',
      });
    else entry[part.slot] ??= metric.alias;
    byField.set(part.of, entry);
  }
  const sets: Ohlc[] = [];
  for (const [of, { opens, high, low }] of byField) {
    if (high === undefined || low === undefined) continue;
    for (const open of opens) {
      const close = closes.find(
        entry => entry.of === of && entry.order === open.order,
      );
      if (!close) continue;
      sets.push({ open: open.alias, high, low, close: close.alias });
      break;
    }
  }
  return sets;
}

/**
 * Whether the four slots of a spec are a set as `ohlcSets` reads one: the
 * `FIRST`, `MAX`, `MIN` and `LAST` of one field under one condition, the
 * two ends ordered by one time. A candle drawn from anything else — two
 * fields, an average for a close — would draw a shape that says nothing.
 */
export function isOhlcSet(
  spec: Ohlc,
  metricOf: (alias: string) => AnalysisMetric | undefined,
): boolean {
  const parts = OHLC_SLOTS.map(slot => {
    const metric = metricOf(spec[slot]);
    return metric && candlePart(metric);
  });
  if (parts.some(part => part === undefined)) return false;
  const [open, high, low, close] = parts as NonNullable<
    (typeof parts)[number]
  >[];
  return (
    open.slot === 'open' &&
    high.slot === 'high' &&
    low.slot === 'low' &&
    close.slot === 'close' &&
    open.order === close.order &&
    parts.every(part => part?.of === open.of)
  );
}

/**
 * The metrics a candle of `metric`'s field still lacks (「补齐 K 线的四个
 * 数」): of its opening value, its highest, its lowest and its closing
 * value, the ones it is not itself — each under the metric's own condition,
 * the two ends ordered by the metric's own time where it names one, named
 * by a free alias beside `taken`. `undefined` for a metric that is none of
 * the four of a field.
 */
export function ohlcMetrics(
  metric: AnalysisMetric,
  taken: readonly string[],
): AnalysisMetric[] | undefined {
  const own = candlePart(metric);
  if (!own) return undefined;
  const field =
    metric.type === 'FIRST' || metric.type === 'LAST'
      ? metric.field
      : metric.type === 'NUMERIC' && metric.expression.type === 'FIELD'
        ? metric.expression.field
        : undefined;
  if (field === undefined || metric.type === 'DERIVED') return undefined;
  const filter = metric.filter === undefined ? {} : { filter: metric.filter };
  const orderBy =
    (metric.type === 'FIRST' || metric.type === 'LAST') &&
    metric.orderBy !== undefined
      ? { orderBy: metric.orderBy }
      : {};
  const stem = fieldAliasSegment(field);
  const used = [...taken];
  const alias = (slot: keyof Ohlc) => {
    const next = freeAlias(`${stem}_${slot}`, used);
    used.push(next);
    return next;
  };
  const expression = { type: 'FIELD', field } as const;
  return OHLC_SLOTS.filter(slot => slot !== own.slot).map(
    (slot): AnalysisMetric => {
      switch (slot) {
        case 'open':
          return {
            type: 'FIRST',
            alias: alias(slot),
            field,
            ...orderBy,
            ...filter,
          };
        case 'close':
          return {
            type: 'LAST',
            alias: alias(slot),
            field,
            ...orderBy,
            ...filter,
          };
        case 'high':
          return {
            type: 'NUMERIC',
            alias: alias(slot),
            function: 'MAX',
            expression,
            ...filter,
          };
        case 'low':
          return {
            type: 'NUMERIC',
            alias: alias(slot),
            function: 'MIN',
            expression,
            ...filter,
          };
      }
    },
  );
}

/**
 * The four numbers a candlestick draws (`fitChartSlots`): the set the spec
 * names while every alias of it is still one of the sets the metrics hold,
 * the first set otherwise — a candle is four numbers of one field, so a set
 * is kept or replaced whole, never patched. Empty slots, which validation
 * names, when there is none.
 */
export function ohlcSet(spec: Ohlc | undefined, sets: readonly Ohlc[]): Ohlc {
  const kept = sets.find(
    set => spec && OHLC_SLOTS.every(slot => set[slot] === spec[slot]),
  );
  return kept ?? sets[0] ?? { open: '', high: '', low: '', close: '' };
}

/** One candle: a period and its four numbers. */
export interface Candle {
  /** The bucket the candle stands for. */
  x: unknown;
  open: number;
  high: number;
  low: number;
  close: number;
  /**
   * Which way the period went, close against open: a candle is coloured by
   * it in the host's rise/fall convention, and read out by it.
   */
  direction: 'rise' | 'fall' | 'flat';
}

export interface CandlestickData {
  type: 'candlestick';
  candles: Candle[];
  /**
   * How many periods have no candle: a row missing one of the four — a
   * period with no value of the field has no open, no close, nothing. They
   * stay in the table; the drawing says how many it left out.
   */
  omitted: number;
}

/**
 * A candlestick's candles, one per row, earliest first as every time axis
 * runs. A row that lacks one of the four is no candle and is counted
 * instead; a period nobody traded is no candle either, so no hole is
 * filled — a candle is a measurement, and a flat one would say the price
 * stood still.
 */
export function shapeCandlestick(
  spec: CandlestickSpec,
  rows: readonly RecordData[],
): CandlestickData {
  const candles: Candle[] = [];
  let omitted = 0;
  for (const row of forwardInTime(rows, entry => entry[spec.x])) {
    const values = OHLC_SLOTS.map(slot => num(row, spec[slot]));
    if (values.some(value => value === null)) {
      omitted += 1;
      continue;
    }
    const [open, high, low, close] = values as number[];
    candles.push({
      x: row[spec.x],
      open,
      high,
      low,
      close,
      direction: close > open ? 'rise' : close < open ? 'fall' : 'flat',
    });
  }
  return { type: 'candlestick', candles, omitted };
}
