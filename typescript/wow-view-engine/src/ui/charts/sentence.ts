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

import type { ChartData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { drawnStages, dropText } from './funnelOption.js';
import { gaugeText, reachedShare } from './gaugeOption.js';
import { drawnFlow, drawnParts } from './hierarchyOption.js';
import type { ReadingContext } from './reading.js';
import { fittedDirection, highLow, said, type Named } from './sentenceParts.js';

/**
 * Each family's sentence (`chartSentence`, which reaches it through the
 * family's row of `FAMILY_VIEWS`): what a sighted reader takes in at a
 * glance, said after the drawing's name. Built from `ChartData`, as the
 * table is, so it says what is drawn; `undefined` where there is no number
 * to say.
 */

/** A pie: its slices and their extremes, 「其他」 named as the chart names it. */
export function pieSentence(
  data: Extract<ChartData, { type: 'pie' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  return extremes(
    ctx,
    data.slices.length,
    data.slices.map(slice => ({
      name:
        slice.other === true
          ? ctx.messages.label('label.chart.other')
          : ctx.label(spec?.pie?.category, slice.category),
      value: slice.value,
      alias: spec?.pie?.value,
    })),
  );
}

/** A heatmap: its measured cells and their extremes, each named row · column. */
export function heatmapSentence(
  data: Extract<ChartData, { type: 'heatmap' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const heatmap = spec?.heatmap;
  const cells = data.ys.flatMap((y, row) =>
    data.xs.map((x, column) => ({
      name: `${ctx.label(heatmap?.y, y)} · ${ctx.label(heatmap?.x, x)}`,
      value: data.cells[row]?.[column] ?? null,
      alias: heatmap?.value,
    })),
  );
  return extremes(ctx, cells.filter(cell => cell.value !== null).length, cells);
}

/** A waterfall: its steps and their extremes. */
export function waterfallSentence(
  data: Extract<ChartData, { type: 'waterfall' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  return extremes(
    ctx,
    data.steps.length,
    data.steps.map(step => ({
      name: ctx.label(spec?.waterfall?.x, step.x),
      value: step.value,
      alias: spec?.waterfall?.value,
    })),
  );
}

/** A treemap: its tiles — the inner ones where it nests — and their extremes. */
export function treemapSentence(
  data: Extract<ChartData, { type: 'treemap' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const treemap = spec?.treemap;
  const tiles = data.tiles.flatMap(tile =>
    tile.tiles
      ? tile.tiles.map(inner => ({
          name: `${ctx.label(treemap?.parent, tile.group)} · ${ctx.label(treemap?.category, inner.group)}`,
          value: inner.value,
          alias: treemap?.value,
        }))
      : [
          {
            name: ctx.label(treemap?.category, tile.group),
            value: tile.value,
            alias: treemap?.value,
          },
        ],
  );
  return extremes(ctx, tiles.length, tiles);
}

/** A boxplot: how many boxes, the highest and the lowest median. */
export function boxplotSentence(
  data: Extract<ChartData, { type: 'boxplot' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const boxplot = spec?.boxplot;
  const bounds = highLow(
    data.boxes.map(box => ({
      name: ctx.label(boxplot?.category, box.group),
      value: box.median,
      alias: boxplot?.median,
    })),
  );
  return bounds
    ? ctx.messages.label('label.chart.sentence.boxplot', {
        count: data.boxes.length,
        ...said(ctx, bounds),
      })
    : undefined;
}

/** A candlestick: its span, where it opened and closed, its high and its low. */
export function candlestickSentence(
  data: Extract<ChartData, { type: 'candlestick' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const candlestick = spec?.candlestick;
  const first = data.candles[0];
  const last = data.candles[data.candles.length - 1];
  if (!first || !last) return undefined;
  const value = (slot: 'open' | 'high' | 'low' | 'close', at: number) =>
    ctx.label(candlestick?.[slot], at);
  return ctx.messages.label('label.chart.sentence.candlestick', {
    count: data.candles.length,
    first: ctx.label(candlestick?.x, first.x),
    last: ctx.label(candlestick?.x, last.x),
    open: value('open', first.open),
    close: value('close', last.close),
    high: value('high', Math.max(...data.candles.map(one => one.high))),
    low: value('low', Math.min(...data.candles.map(one => one.low))),
  });
}

/** A gauge: its number on its scale, or against its target. */
export function gaugeSentence(
  data: Extract<ChartData, { type: 'gauge' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  if (data.value === null) return undefined;
  const text = (value: number) =>
    gaugeText(value, { spec, label: ctx.label, locale: ctx.locale });
  const share = reachedShare(data, ctx.locale);
  return share === undefined || data.target === undefined
    ? ctx.messages.label('label.chart.sentence.gauge', {
        value: text(data.value),
        min: text(data.min),
        max: text(data.max),
      })
    : ctx.messages.label('label.chart.sentence.gauge-target', {
        value: text(data.value),
        target: text(data.target),
        share,
      });
}

/** A radar or parallel axes: how many groups and axes, then each axis's extremes. */
export function profileSentence(
  data: Extract<ChartData, { type: 'radar' | 'parallel' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  return data.profiles.length === 0
    ? undefined
    : ctx.messages.label('label.chart.sentence.profiles', {
        count: data.profiles.length,
        metrics: data.metrics.length,
      }) + profileReadings(data, spec?.[data.type]?.category, ctx);
}

/** A sunburst or a tree: its leaves and their extremes, each named by its path. */
export function partsSentence(
  data: Extract<ChartData, { type: 'sunburst' | 'tree' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const leaves = drawnParts(data, {
    spec,
    label: ctx.label,
    locale: ctx.locale,
  }).filter(part => part.leaf);
  const value = (data.type === 'sunburst' ? spec?.sunburst : spec?.tree)?.value;
  return extremes(
    ctx,
    leaves.length,
    leaves.map(part => ({
      name: part.path,
      value: part.value,
      alias: value,
    })),
  );
}

/** A sankey: how many bands, and the widest. */
export function sankeySentence(
  data: Extract<ChartData, { type: 'sankey' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const { bands } = drawnFlow(data, { spec, label: ctx.label });
  const largest = bands[0];
  return largest
    ? ctx.messages.label('label.chart.sentence.sankey', {
        count: bands.length,
        high: `${largest.from} → ${largest.to}`,
        highValue: largest.text,
      })
    : undefined;
}

/** A calendar: its ended days and their extremes, and the day still under way. */
export function calendarSentence(
  data: Extract<ChartData, { type: 'calendar' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const days = data.days.filter(day => !ongoing(data, day.at));
  const line = extremes(
    ctx,
    days.length,
    days.map(day => ({
      name: ctx.label(spec?.calendar?.date, day.at),
      value: day.value,
      alias: spec?.calendar?.value,
    })),
  );
  return line && line + unfinishedClause(ctx, data, spec?.calendar?.date);
}

/** A theme river: its span, its streams and which way the whole went. */
export function riverSentence(
  data: Extract<ChartData, { type: 'themeRiver' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const river = spec?.themeRiver;
  const times = data.times.flatMap((at, index) =>
    ongoing(data, at) ? [] : [{ at, index }],
  );
  const count = times.length;
  if (count < 2) return undefined;
  const whole = ({ index }: { index: number }) =>
    (data.values[index] ?? []).reduce((sum, value) => sum + value, 0);
  return (
    ctx.messages.label('label.chart.sentence.themeRiver', {
      count,
      streams: data.streams.length,
      first: ctx.label(river?.x, times[0].at),
      last: ctx.label(river?.x, times[count - 1].at),
      trend: ctx.messages.label(
        `label.chart.sentence.${fittedDirection(times.map(whole))}`,
      ),
    }) + unfinishedClause(ctx, data, river?.x)
  );
}

/** A map: its shaded regions and their extremes. */
export function mapSentence(
  data: Extract<ChartData, { type: 'map' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  return extremes(
    ctx,
    data.regions.length,
    data.regions.map(region => ({
      name: ctx.label(spec?.map?.region, region.group),
      value: region.value,
      alias: spec?.map?.value,
    })),
  );
}

/**
 * A metric card's sparkline, in the sentence a time axis has: from its first
 * period to its last, which way it went, its highest and its lowest. The
 * card says its headline number in words on its own face; the line under it
 * was a picture nobody could read, named and nothing more. No trend, or one
 * of a single period, says nothing — the face already has.
 */
export function trendSentence(
  data: Extract<ChartData, { type: 'metric' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const card = spec?.metric;
  const points = data.trend ?? [];
  if (points.length < 2) return undefined;
  const bounds = highLow(
    points.map(point => ({
      name: ctx.label(card?.trend?.x, point.x),
      // A period the card filled in had no records; it is no lowest.
      value: point.filled === true ? null : point.value,
      alias: card?.metric,
    })),
  );
  if (!bounds) return undefined;
  const first = points[0];
  const last = points[points.length - 1];
  return ctx.messages.label('label.chart.sentence.time', {
    count: points.length,
    first: ctx.label(card?.trend?.x, first?.x),
    last: ctx.label(card?.trend?.x, last?.x),
    trend: ctx.messages.label(
      `label.chart.sentence.${fittedDirection(
        points.map(point => point.value ?? 0),
      )}`,
    ),
    ...said(ctx, bounds),
  });
}

/**
 * Where the groups stand on each axis of a radar or parallel axes: every
 * axis is its own scale, so each metric says its own highest and lowest
 * group — 「GMV：最高 华东 ¥52万，最低 西南 ¥8万」 — and none is compared
 * with another (second review R2-P2-8: the sentence said only how many).
 * One group has no highest; it says nothing more.
 */
function profileReadings(
  data: Extract<ChartData, { type: 'radar' | 'parallel' }>,
  category: string | undefined,
  ctx: ReadingContext,
): string {
  if (data.profiles.length < 2) return '';
  const readings = data.metrics.flatMap((metric, index) => {
    const bounds = highLow(
      data.profiles.map(profile => ({
        name: ctx.label(category, profile.group),
        value: profile.values[index] ?? null,
        alias: metric,
      })),
    );
    return bounds
      ? [
          ctx.messages.label('label.chart.sentence.profile-metric', {
            metric: ctx.column(metric) ?? metric,
            ...said(ctx, bounds),
          }),
        ]
      : [];
  });
  return readings.length === 0
    ? ''
    : ctx.messages.label('label.chart.sentence.profile-readings', {
        readings: readings.join(
          ctx.messages.label('label.chart.sentence.profile-join'),
        ),
      });
}

/** 「共 N 组，最高 … ，最低 …」 over the measured numbers; none without one. */
function extremes(
  ctx: ReadingContext,
  count: number,
  items: readonly Named[],
): string | undefined {
  const bounds = highLow(items);
  if (!bounds) return undefined;
  return ctx.messages.label('label.chart.sentence', {
    count,
    ...said(ctx, bounds),
  });
}

/**
 * A cartesian chart: its groups and their extremes, each named by its
 * category and — with several series — the series; over a time axis, from
 * its first bucket to its last and which way the whole went between the two
 * (every series added up).
 */
export function cartesianSentence(
  data: Extract<ChartData, { type: 'cartesian' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const cartesian = spec?.cartesian;
  const several = data.series.length > 1;
  const seriesName = (entry: (typeof data.series)[number]) =>
    entry.other === true
      ? ctx.messages.label('label.chart.other')
      : entry.value === undefined
        ? (ctx.column(entry.metric) ?? entry.label)
        : ctx.label(cartesian?.splitBy, entry.value);
  // One value axis is one scale: the highest and the lowest are compared
  // along it and never across the two, which measure different things —
  // 「最高 GMV ¥250,275，最低 客单价 ¥194」 set a sum beside an average
  // (second review R2-P1-6). The first series' axis leads; the other one,
  // if any, is said in a clause of its own.
  const sideOf = (metric: string) =>
    cartesian?.series.find(one => one.metric === metric)?.axis === 'right'
      ? 'right'
      : 'left';
  const lead = data.series[0] ? sideOf(data.series[0].metric) : 'left';
  const onOther = (entry: (typeof data.series)[number]) =>
    sideOf(entry.metric) !== lead;
  // The period still under way is drawn, and read for nothing: its days so
  // far are no period, and the lowest or the way down they would make is no
  // finding (second review R2-P1-7). The sentence says it left it out.
  const read = data.points.filter(point => !ongoing(data, point.x));
  const itemsOn = (other: boolean) =>
    read.flatMap(point =>
      data.series
        .filter(entry => onOther(entry) === other)
        .map(entry => ({
          name: several
            ? `${ctx.label(cartesian?.x, point.x)} · ${seriesName(entry)}`
            : ctx.label(cartesian?.x, point.x),
          value: point.filled?.includes(entry.key)
            ? null
            : (point.values[entry.key] ?? null),
          alias: entry.metric,
        })),
    );
  const bounds = highLow(itemsOn(false));
  if (!bounds) return undefined;
  const other = highLow(itemsOn(true));
  const skipped = unfinishedClause(ctx, data, cartesian?.x);
  const otherClause = other
    ? ctx.messages.label('label.chart.sentence.other-axis', {
        measures: [
          ...new Set(
            data.series
              .filter(onOther)
              .map(entry => ctx.column(entry.metric) ?? entry.metric),
          ),
        ].join(ctx.messages.label('label.filter.join')),
        ...said(ctx, other),
      })
    : '';
  // The periods are the dated buckets: the records with no date stand last
  // on the axis as 「（空）」, and are no period for 「从…到…」 to end at
  // (second review R2-P1-4: 「从 2024年 Q3 到 ，」).
  const periods =
    data.timeline === true
      ? read.filter(point => point.x !== null && point.x !== undefined)
      : read;
  const count = periods.length;
  if (data.timeline !== true || count < 2)
    return (
      ctx.messages.label('label.chart.sentence', {
        count: data.points.length,
        ...said(ctx, bounds),
      }) +
      otherClause +
      skipped
    );
  const first = periods[0];
  const last = periods[count - 1];
  // The way the lead metric went, its parts added up (a split, a stack) —
  // never two metrics added together, and read along every period rather
  // than its first against its last: a day and a month of the same rows
  // said 「总体下降」 and 「总体上升」, each from its own two ends.
  const leadMetric = data.series[0]?.metric;
  const leading = data.series.filter(entry => entry.metric === leadMetric);
  const trend = ctx.messages.label(
    `label.chart.sentence.${fittedDirection(
      periods.map(point => standing(point.values, leading)),
    )}`,
  );
  const metrics = new Set(data.series.map(entry => entry.metric));
  return (
    ctx.messages.label('label.chart.sentence.time', {
      count,
      first: ctx.label(cartesian?.x, first.x),
      last: ctx.label(cartesian?.x, last.x),
      trend:
        metrics.size > 1 && leadMetric !== undefined
          ? ctx.messages.label('label.chart.sentence.trend-of', {
              measure: ctx.column(leadMetric) ?? leadMetric,
              trend,
            })
          : trend,
      ...said(ctx, bounds),
    }) +
    otherClause +
    skipped
  );
}

/** Whether `at` is the period still under way at the axis's end. */
function ongoing(data: { unfinished?: { at: unknown } }, at: unknown): boolean {
  return data.unfinished !== undefined && data.unfinished.at === at;
}

/** 「2026年9月还没结束，未计入。」, where the chart left a period out. */
function unfinishedClause(
  ctx: ReadingContext,
  data: { unfinished?: { at: unknown } },
  alias: string | undefined,
): string {
  return data.unfinished
    ? ctx.messages.label('label.chart.sentence.unfinished', {
        period: ctx.label(alias, data.unfinished.at),
      })
    : '';
}

/** Where the chart stands at one point: these series added up. */
function standing(
  values: Readonly<Record<string, number | null>>,
  series: readonly { key: string }[],
): number {
  return series.reduce((sum, entry) => sum + (values[entry.key] ?? 0), 0);
}

/** A scatter: how many points, and the span each axis's metric runs over. */
export function scatterSentence(
  data: Extract<ChartData, { type: 'scatter' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const scatter = spec?.scatter;
  if (data.points.length === 0) return undefined;
  const span = (which: 'x' | 'y') => {
    const values = data.points.map(point => point[which]);
    const alias = scatter?.[which];
    return {
      name:
        ctx.column(alias) ?? ctx.messages.label(`label.chart.column.${which}`),
      low: ctx.label(alias, Math.min(...values)),
      high: ctx.label(alias, Math.max(...values)),
    };
  };
  const x = span('x');
  const y = span('y');
  return ctx.messages.label('label.chart.sentence.scatter', {
    count: data.points.length,
    x: x.name,
    xLow: x.low,
    xHigh: x.high,
    y: y.name,
    yLow: y.low,
    yHigh: y.high,
  });
}

/**
 * A funnel's sentence: how many stages, from what first to what last, the
 * whole funnel's conversion, and the step that loses the most — what the
 * drawing says with its taper and its one heavier drop.
 */
export function funnelSentence(
  data: Extract<ChartData, { type: 'funnel' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  const stages = drawnStages(data, {
    spec,
    label: ctx.label,
    column: ctx.column,
    locale: ctx.locale,
  });
  const first = stages[0];
  const last = stages[stages.length - 1];
  if (!first || !last || stages.length < 2) return undefined;
  const whole = ctx.messages.label('label.chart.sentence.funnel', {
    count: stages.length,
    first: first.name,
    firstValue: first.text,
    last: last.name,
    lastValue: last.text,
    overall: last.share ?? ctx.messages.label('label.summary.unavailable'),
  });
  const at = data.largestDrop;
  const to = at === undefined ? undefined : stages[at];
  const from = at === undefined ? undefined : stages[at - 1];
  if (!to?.drop || !from) return whole;
  // Run on as the language runs sentences on: the catalogue's own space.
  return `${whole}${ctx.messages.label('label.chart.sentence.funnel-drop', {
    from: from.name,
    to: to.name,
    drop: to.drop.text,
    rate: to.drop.rate ?? dropText(to.drop),
  })}`;
}
