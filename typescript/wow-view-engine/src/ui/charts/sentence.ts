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

/**
 * A chart in one sentence, said after its name (analysis-echarts.md 2.3):
 * what a sighted reader takes in at a glance before reading any number —
 * how many groups, which is highest and which lowest, and over a time axis
 * where it starts, where it ends and which way it went. 「共 12 组，最高 华东
 * ¥1.2万，最低 西南 ¥980」. The table beside it (`ChartReadingTable`) has
 * every number; this is the reading a screen reader hears first.
 *
 * Built from `ChartData`, as the table is, so it says what is drawn: a
 * filled-in 0 is no measured group and is not the lowest, and 「其他」 is
 * named as the chart names it. A metric card says its number in words on
 * its own face, and has no sentence; neither has a chart of no number.
 */
export function chartSentence(
  data: ChartData,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  switch (data.type) {
    case 'cartesian':
      return cartesianSentence(data, spec, ctx);
    case 'pie':
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
    case 'heatmap': {
      const heatmap = spec?.heatmap;
      const cells = data.ys.flatMap((y, row) =>
        data.xs.map((x, column) => ({
          name: `${ctx.label(heatmap?.y, y)} · ${ctx.label(heatmap?.x, x)}`,
          value: data.cells[row]?.[column] ?? null,
          alias: heatmap?.value,
        })),
      );
      return extremes(
        ctx,
        cells.filter(cell => cell.value !== null).length,
        cells,
      );
    }
    case 'scatter':
      return scatterSentence(data, spec, ctx);
    case 'funnel':
      return funnelSentence(data, spec, ctx);
    case 'waterfall':
      return extremes(
        ctx,
        data.steps.length,
        data.steps.map(step => ({
          name: ctx.label(spec?.waterfall?.x, step.x),
          value: step.value,
          alias: spec?.waterfall?.value,
        })),
      );
    case 'treemap': {
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
    case 'boxplot': {
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
    case 'gauge': {
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
    case 'radar':
    case 'parallel':
      return data.profiles.length === 0
        ? undefined
        : ctx.messages.label('label.chart.sentence.profiles', {
            count: data.profiles.length,
            metrics: data.metrics.length,
          });
    case 'sunburst':
    case 'tree': {
      const leaves = drawnParts(data, {
        spec,
        label: ctx.label,
        locale: ctx.locale,
      }).filter(part => part.leaf);
      const value = (data.type === 'sunburst' ? spec?.sunburst : spec?.tree)
        ?.value;
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
    case 'sankey': {
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
    case 'calendar':
      return extremes(
        ctx,
        data.days.length,
        data.days.map(day => ({
          name: ctx.label(spec?.calendar?.date, day.at),
          value: day.value,
          alias: spec?.calendar?.value,
        })),
      );
    case 'themeRiver': {
      const river = spec?.themeRiver;
      const count = data.times.length;
      if (count < 2) return undefined;
      const whole = (at: number) =>
        (data.values[at] ?? []).reduce((sum, value) => sum + value, 0);
      return ctx.messages.label('label.chart.sentence.themeRiver', {
        count,
        streams: data.streams.length,
        first: ctx.label(river?.x, data.times[0]),
        last: ctx.label(river?.x, data.times[count - 1]),
        trend: ctx.messages.label(
          `label.chart.sentence.${direction(whole(0), whole(count - 1))}`,
        ),
      });
    }
    case 'map':
      return extremes(
        ctx,
        data.regions.length,
        data.regions.map(region => ({
          name: ctx.label(spec?.map?.region, region.group),
          value: region.value,
          alias: spec?.map?.value,
        })),
      );
    case 'metric':
      return undefined;
  }
}

/** One measured number of the chart, and what it is called. */
interface Named {
  name: string;
  value: number | null;
  /** The column it prints as. */
  alias?: string;
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

function highLow(
  items: readonly Named[],
):
  | { high: Named & { value: number }; low: Named & { value: number } }
  | undefined {
  let high: (Named & { value: number }) | undefined;
  let low: (Named & { value: number }) | undefined;
  for (const item of items) {
    if (typeof item.value !== 'number') continue;
    const measured = { ...item, value: item.value };
    if (!high || measured.value > high.value) high = measured;
    if (!low || measured.value < low.value) low = measured;
  }
  return high && low ? { high, low } : undefined;
}

function said(
  ctx: ReadingContext,
  { high, low }: NonNullable<ReturnType<typeof highLow>>,
) {
  return {
    high: high.name,
    highValue: ctx.label(high.alias, high.value),
    low: low.name,
    lowValue: ctx.label(low.alias, low.value),
  };
}

/**
 * A cartesian chart: its groups and their extremes, each named by its
 * category and — with several series — the series; over a time axis, from
 * its first bucket to its last and which way the whole went between the two
 * (every series added up).
 */
function cartesianSentence(
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
  const items = data.points.flatMap(point =>
    data.series.map(entry => ({
      name: several
        ? `${ctx.label(cartesian?.x, point.x)} · ${seriesName(entry)}`
        : ctx.label(cartesian?.x, point.x),
      value: point.filled?.includes(entry.key)
        ? null
        : (point.values[entry.key] ?? null),
      alias: entry.metric,
    })),
  );
  const bounds = highLow(items);
  if (!bounds) return undefined;
  const count = data.points.length;
  if (data.timeline !== true || count < 2)
    return ctx.messages.label('label.chart.sentence', {
      count,
      ...said(ctx, bounds),
    });
  const first = data.points[0];
  const last = data.points[count - 1];
  return ctx.messages.label('label.chart.sentence.time', {
    count,
    first: ctx.label(cartesian?.x, first.x),
    last: ctx.label(cartesian?.x, last.x),
    trend: ctx.messages.label(
      `label.chart.sentence.${direction(
        standing(first.values, data.series),
        standing(last.values, data.series),
      )}`,
    ),
    ...said(ctx, bounds),
  });
}

/** Where the chart stands at one point: its series added up. */
function standing(
  values: Readonly<Record<string, number | null>>,
  series: readonly { key: string }[],
): number {
  return series.reduce((sum, entry) => sum + (values[entry.key] ?? 0), 0);
}

/**
 * Which way a line went from its first point to its last: up or down past a
 * twentieth of where it started, level otherwise.
 */
export function direction(from: number, to: number): 'up' | 'down' | 'flat' {
  const change = to - from;
  const scale = Math.abs(from) || Math.abs(to);
  if (scale === 0 || Math.abs(change) <= scale * 0.05) return 'flat';
  return change > 0 ? 'up' : 'down';
}

/** A scatter: how many points, and the span each axis's metric runs over. */
function scatterSentence(
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
function funnelSentence(
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
