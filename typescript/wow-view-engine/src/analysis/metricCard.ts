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
  AnalysisDerivedExpression,
  AnalysisViewConfig,
  RecordData,
} from '../model/index.js';
import { absenceReader, num } from './chartRows.js';
import {
  appliedWindow,
  bucketSpan,
  forwardInTime,
  timeGroup,
  withoutHoles,
  type DateGroup,
  type TimeWindow,
} from './timeAxis.js';
import { metricReach } from './metricWindow.js';
import { isAdditiveMetric, readsOffSums } from './validateChart.js';

export interface MetricCardData {
  type: 'metric';
  /**
   * The headline. A number, except for a moment (`momentMetrics`) a source
   * answered as text — the latest of a day kept as `2026-09-18` — which is
   * written out as its column reads it rather than dropped for not being
   * one; nothing is measured, compared or aimed at over a moment.
   */
  value: number | string | null;
  /**
   * The headline against another metric. `unmatched` where the question's
   * dates cut either metric's own dates short, or miss one altogether
   * (`metricReach`): 「本月至今」 against 「上月同期」 under a filter of one
   * day is one day against nothing, and the delta is null rather than a
   * change that never happened.
   */
  compare?: {
    value: number | null;
    delta: number | null;
    unmatched?: true;
  };
  target?: number;
  /**
   * The sparkline's points; `filled` where the kernel filled a bucket the
   * rows lack with 0 rather than measured it (decisions.md D21, Q14).
   */
  trend?: { x: unknown; value: number | null; filled?: true }[];
  /**
   * Over a trend read as its last period (`MetricTrend.headline` left out or
   * `last`): which period the headline is, and how it moved from the one
   * before. Absent in the `whole` reading and without a trend.
   */
  period?: MetricPeriod;
  /** The headline is the whole range, over a trend read as `whole`. */
  whole?: true;
  /**
   * Over a trend read as its last period: the rows stopped at the view's
   * limit before the latest period, so which period is the latest — and
   * its number — is not known. The headline is null and no period is said,
   * rather than the last bucket that fitted read as the latest (2026-09-26
   * review: 400 days from 2025-01-01 headlined 2026-02-04). The limit is
   * the one the rows stopped at.
   */
  cut?: { limit: number };
}

/**
 * The period a trend card's headline is, as its bucket key — a key the
 * card prints the way its column does.
 */
export interface MetricPeriod {
  /** The bucket the headline is. */
  at: unknown;
  /**
   * The part of `at` the headline covers, where the question's own dates
   * cut the bucket short — a month under a filter of one day is that day,
   * under 「过去 7 天」 those seven days — as instants `[from, to)` to be
   * read in `zone`. Absent where the headline is the whole bucket, which
   * its key names. The card names this span, never the bucket: a month
   * label over one day's number is the wrong period (2026-09-26 review).
   */
  span?: { from: number; to: number; zone: string };
  /** The date unit the buckets are cut in, so the card can name one. */
  unit: AnalysisDateUnit;
  /**
   * `at` had not ended when the question was asked either — it is the only
   * bucket there was — so the headline is the period so far.
   */
  partial?: true;
  /**
   * The latest bucket, left out of the headline because it had not ended
   * when the question was asked: a day half over compared with a whole one
   * reads as a fall every morning.
   */
  skipped?: unknown;
  /**
   * The period right before `at` and its value; absent when the trend holds
   * no bucket just before it (the range starts at `at`).
   */
  previous?: { at: unknown; value: number | null };
  /**
   * The headline against `previous`: the difference, and the difference as
   * a share of the previous value — null when either number is missing, or
   * the share when the previous value is 0.
   */
  change?: { delta: number; ratio: number | null } | null;
  /**
   * There is a period right before `at`, but the question's dates cut one
   * of the two short and not the other the same way — nine days of this
   * month against the whole of the last — so the two are not compared and
   * `previous` and `change` are absent. A change across unequal spans reads
   * as a fall, or a rise, that never happened.
   */
  unmatched?: true;
}

/**
 * Without a trend the query is ungrouped, so its one row is the headline.
 *
 * With one, the rows are the buckets of the sparkline, and the headline is
 * what `MetricTrend.headline` says it is. Read as its last period — the
 * default — it is the last bucket that had ended when the question was
 * asked, beside its change from the bucket before (`lastPeriod`). Read as
 * the whole, it is the totals row when its query ran — the ungrouped
 * aggregation, right for any metric — and otherwise the buckets added up,
 * which validation admits only for a metric read off sums: one that adds,
 * or a ratio of sums, divided from the summed operands (D38).
 *
 * Compare and target read the same span as the headline: the same period's
 * row, or the whole. So a comparison with another metric is this period's
 * against that metric's this period, and a target is a goal for one period.
 */
export function metricCard(
  spec: NonNullable<AnalysisViewConfig['chart']['metric']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  totals: RecordData | undefined,
  context: MetricContext,
): MetricCardData {
  const trend = spec.trend;
  const axis = trend && timeGroup(config, trend.x);
  // The sparkline is a time axis too, and validation holds `trend.x` to a
  // date bucket.
  const buckets = trend ? trendRows(trend.x, axis, config, rows, context) : [];
  const whole = trend?.headline === 'whole';
  const cut =
    trend &&
    !whole &&
    context.cutShort === true &&
    !latestFirst(config, trend.x);
  const last =
    trend && axis && !whole && !cut
      ? lastPeriod(spec.metric, trend.x, axis, buckets, {
          ...context,
          window: askedWindow(config, axis.field, context),
        })
      : undefined;
  const headline: RecordData = !trend
    ? (rows[0] ?? {})
    : cut
      ? {}
      : whole || !axis
        ? (totals ?? summed(config, rows))
        : (last?.row ?? {});

  const value = num(headline, spec.metric);
  const compare = spec.compare ? num(headline, spec.compare.metric) : null;
  const reach = spec.compare
    ? metricReach(config, context)
    : new Map<string, unknown>();
  const unmatched =
    spec.compare !== undefined &&
    (reach.has(spec.metric) || reach.has(spec.compare.metric));
  const written = headline[spec.metric];
  return {
    type: 'metric',
    value: value ?? (typeof written === 'string' ? written : null),
    ...(spec.compare
      ? {
          compare: unmatched
            ? { value: compare, delta: null, unmatched: true as const }
            : {
                value: compare,
                delta: deltaOf(value, compare, spec.compare.mode),
              },
        }
      : {}),
    ...(spec.target === undefined ? {} : { target: spec.target }),
    ...(trend
      ? {
          trend: buckets.map(bucket => {
            const value = num(bucket, spec.metric);
            return FILLED.has(bucket) && value !== null
              ? { x: bucket[trend.x], value, filled: true as const }
              : { x: bucket[trend.x], value };
          }),
        }
      : {}),
    ...(last ? { period: last.period } : {}),
    ...(trend && whole ? { whole: true as const } : {}),
    ...(cut ? { cut: { limit: config.limit } } : {}),
  };
}

/**
 * Whether the rows run latest first on the trend's axis — the view sorted
 * by it descending before anything else — so a limit cuts the earliest
 * buckets off and the latest is always among the rows. Any other order,
 * cut short, may have left the latest out.
 */
function latestFirst(config: AnalysisViewConfig, x: string): boolean {
  const first = Array.isArray(config.sort) ? config.sort[0] : undefined;
  return first?.alias === x && first.direction === 'DESC';
}

/**
 * What the question's own conditions pin on the axis's field, read when it
 * was asked; nothing without that moment.
 */
function askedWindow(
  config: AnalysisViewConfig,
  field: string,
  context: MetricContext,
): { from: number | null; to: number | null } | undefined {
  return context.now
    ? appliedWindow(config.filter, field, context.now, context.timeZone)
    : undefined;
}

/** What a card is shaped against beside its rows. */
interface MetricContext {
  timeZone: string;
  /** When the question was asked; every bucket has ended when left out. */
  now: Date | undefined;
  /** The rows are the first groups of more (`ShapeContext.cutShort`). */
  cutShort?: boolean;
}

/**
 * The rows `trendRows` made up for a bucket the result lacks, by identity:
 * a sparkline point drawn from one is filled, not measured.
 */
const FILLED = new WeakSet<RecordData>();

/**
 * The trend's buckets, earliest first and without holes as every time axis
 * runs: a quiet day is a dip to 0, not a line drawn straight past it. A
 * hole is a row of its own, each metric 0 where it adds and the bucket is
 * known to have had no records (`absenceReader`), nothing otherwise — so a
 * hole read as the headline's period says the number it draws.
 *
 * The run reaches out to the card's own window (`cardWindow`), not only
 * between the buckets that came back: a card over 「近 7 天」 is seven days,
 * and with orders on the last day alone the six before it are six known
 * zeros — the one right before is what 「较前一日」 compares with (D39).
 */
function trendRows(
  x: string,
  axis: DateGroup | undefined,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  context: MetricContext,
): RecordData[] {
  const { timeZone } = context;
  const forward = forwardInTime(rows, row => row[x]);
  if (!axis) return forward;
  // A histogram the source filled (`dense`) answers an empty bucket with
  // counts 0 and value metrics null: known empty, so what adds is 0 there
  // too, filled — the cartesian projection's reading (`shapeCartesian`).
  if (axis.dense === true) return forward.map(row => knownEmpty(row, config));
  const absent = absenceReader(config, rows);
  return withoutHoles(
    forward,
    row => row[x],
    axis,
    timeZone,
    key => {
      const empty = absent({ [x]: key });
      const hole: RecordData = { [x]: key };
      FILLED.add(hole);
      for (const metric of config.metrics)
        hole[metric.alias] = empty && isAdditiveMetric(metric) ? 0 : null;
      return hole;
    },
    cardWindow(axis, config, rows, context),
  );
}

/**
 * The window a trend card's buckets run to, when every bucket of it the
 * rows lack is known to have had no records: what its conditions pin on the
 * axis's field — its own 「近 7 天」, or the window a board anchored it to,
 * which reaches it as a condition too — read at the moment it was asked,
 * and never past that moment's bucket, since a day that has not come yet
 * had no records only so far.
 *
 * Known empty is the whole result's to say (D14): no 「只保留」 to have
 * dropped a bucket by its numbers, and fewer rows than the limit, so none
 * was cut off an end. Otherwise, or with no moment to read the conditions
 * at, or no lower bound to start from, there is no window, and the run
 * stays between the buckets that came back.
 */
function cardWindow(
  axis: DateGroup,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  context: MetricContext,
): TimeWindow | undefined {
  const { now, timeZone } = context;
  if (!now || config.having) return undefined;
  if (!Number.isInteger(config.limit) || rows.length >= config.limit)
    return undefined;
  const { from, to } = appliedWindow(config.filter, axis.field, now, timeZone);
  if (from === null) return undefined;
  const asked = now.getTime() + 1;
  return { from, to: to === null ? asked : Math.min(to, asked) };
}

/** `row` with every metric that adds and came back null read as a filled 0. */
function knownEmpty(row: RecordData, config: AnalysisViewConfig): RecordData {
  const empty = config.metrics.filter(
    metric => isAdditiveMetric(metric) && row[metric.alias] === null,
  );
  if (empty.length === 0) return row;
  const filled: RecordData = { ...row };
  for (const metric of empty) filled[metric.alias] = 0;
  FILLED.add(filled);
  return filled;
}

/**
 * The last period of a trend: the latest bucket that had ended when the
 * question was asked, and its change from the bucket right before it.
 *
 * A period still under way is left out of the headline, because it is not
 * yet the number it will be: today compared with yesterday at nine in the
 * morning reads as a fall every morning, and a card that cries wolf is one
 * nobody reads. It still draws on the sparkline, and the card says it was
 * left out (`skipped`). Only when no bucket had ended — the range holds
 * just the current one — is the headline the period so far (`partial`).
 *
 * A period is the part of its bucket the question's dates hold
 * (`bucketSpan` with the `window`): a month under a filter of one day is
 * that day, over when the day is, and named by it (`span`). Two periods
 * are compared only when the dates cut neither short, or cut both to the
 * same length at the same place in their buckets — never nine days
 * against a month (`unmatched`).
 *
 * The bucket before counts only when it is the period right before — the
 * one whose end is the headline's start. `withoutHoles` puts it there
 * unless it could not place the holes, and then the neighbour in the rows
 * may be days earlier, which is no "previous period".
 */
function lastPeriod(
  metric: string,
  x: string,
  axis: DateGroup,
  buckets: readonly RecordData[],
  context: MetricContext & {
    window: { from: number | null; to: number | null } | undefined;
  },
): { row: RecordData; period: MetricPeriod } | undefined {
  const timed = buckets.flatMap(row => {
    const whole = bucketSpan(axis, row[x], context.timeZone);
    const span = bucketSpan(
      axis,
      row[x],
      context.timeZone,
      context.now,
      context.window,
    );
    return span && whole ? [{ row, span, whole }] : [];
  });
  if (timed.length === 0) return undefined;
  let ended = timed.length - 1;
  while (ended >= 0 && !timed[ended].span.ended) ended -= 1;
  const index = ended === -1 ? timed.length - 1 : ended;
  const at = timed[index];
  const before = timed[index - 1];
  const adjacent =
    before && before.whole.to === at.whole.from ? before : undefined;
  const matched = adjacent && sameCut(adjacent, at) ? adjacent : undefined;
  const value = num(at.row, metric);
  const was = matched ? num(matched.row, metric) : null;
  const { span } = at;
  return {
    row: at.row,
    period: {
      at: at.row[x],
      ...(span.clipped
        ? { span: { from: span.from, to: span.to, zone: span.zone } }
        : {}),
      unit: axis.unit,
      ...(ended === -1 ? { partial: true as const } : {}),
      ...(index < timed.length - 1
        ? { skipped: timed[timed.length - 1].row[x] }
        : {}),
      ...(adjacent && !matched ? { unmatched: true as const } : {}),
      ...(matched
        ? {
            previous: { at: matched.row[x], value: was },
            change:
              value === null || was === null
                ? null
                : {
                    delta: value - was,
                    ratio: was === 0 ? null : (value - was) / Math.abs(was),
                  },
          }
        : {}),
    },
  };
}

/**
 * Whether the dates cut two buckets alike: neither at all, or both from
 * the same distance into the bucket to the same length.
 */
function sameCut(one: CutSpan, other: CutSpan): boolean {
  if (!one.span.clipped && !other.span.clipped) return true;
  return (
    one.span.from - one.whole.from === other.span.from - other.whole.from &&
    one.span.to - one.span.from === other.span.to - other.span.from
  );
}

interface CutSpan {
  span: { from: number; to: number; clipped?: true };
  whole: { from: number };
}

/**
 * How long after the question was asked a trend card's headline stops
 * being its last period, in milliseconds — or undefined when it never
 * will: no trend, a card read as the whole, or no bucket under way.
 *
 * The card skips the period still under way when it is asked (`lastPeriod`),
 * and that is a statement about a moment: at midnight today is over, the
 * headline should be today and the day under way tomorrow. A page left open
 * across it went on saying yesterday (2026-09-23 audit) — the rows are the
 * ones asked for then, today's only partly counted, so the card cannot just
 * move along them. The runtime asks again when this has run out
 * (`RuntimeStoreHost.expiresAt`), and the answer is the card of the new day.
 */
export function periodRollover(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  context: { timeZone: string; now: Date },
): number | undefined {
  const spec = config.chart.type === 'metric' ? config.chart.metric : undefined;
  const trend = spec?.trend;
  if (!trend || trend.headline === 'whole') return undefined;
  const axis = timeGroup(config, trend.x);
  if (!axis) return undefined;
  const buckets = trendRows(trend.x, axis, config, rows, context);
  const window = askedWindow(config, axis.field, context);
  for (let index = buckets.length - 1; index >= 0; index -= 1) {
    const span = bucketSpan(
      axis,
      buckets[index][trend.x],
      context.timeZone,
      context.now,
      window,
    );
    if (span) return span.left;
  }
  return undefined;
}

/**
 * The buckets added up, per additive metric, and a ratio of sums computed
 * from those sums as the source computes it from a row's (D38) — never the
 * buckets' ratios added. A metric that is neither is left out, so it reads
 * as null rather than as a number that means nothing.
 */
function summed(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): RecordData {
  const row: RecordData = {};
  const metricOf = (alias: string) =>
    config.metrics.find(metric => metric.alias === alias);
  // In declaration order, so a ratio reads operands already summed.
  for (const metric of config.metrics) {
    if (isAdditiveMetric(metric))
      row[metric.alias] = rows.reduce((sum, bucket) => {
        const value = num(bucket, metric.alias);
        return value === null ? sum : sum + value;
      }, 0);
    else if (metric.type === 'DERIVED' && readsOffSums(metric, metricOf))
      row[metric.alias] = evaluate(metric.expression, row);
  }
  return row;
}

/** A derived expression over one row's numbers; null where one is missing or a divisor is 0. */
function evaluate(
  expression: AnalysisDerivedExpression,
  row: RecordData,
): number | null {
  switch (expression.type) {
    case 'METRIC_REF':
      return num(row, expression.metric);
    case 'CONSTANT':
      return expression.value;
    case 'BINARY': {
      const left = evaluate(expression.left, row);
      const right = evaluate(expression.right, row);
      if (left === null || right === null) return null;
      switch (expression.operator) {
        case 'ADD':
          return left + right;
        case 'SUBTRACT':
          return left - right;
        case 'MULTIPLY':
          return left * right;
        case 'DIVIDE':
          return right === 0 ? null : left / right;
        default:
          return null;
      }
    }
    default:
      return null;
  }
}

function deltaOf(
  value: number | null,
  compare: number | null,
  mode: 'delta' | 'percent',
): number | null {
  if (value === null || compare === null) return null;
  if (mode === 'delta') return value - compare;
  return compare === 0 ? null : (value - compare) / compare;
}
