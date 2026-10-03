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
import { formatShare } from './axis.js';
import { stackPlan } from './cartesianPlan.js';
import { drawnStages, dropText } from './funnelOption.js';
import { derivedName } from './markWords.js';
import type { ChartReading, ReadingContext } from './reading.js';
import { nameOf, number } from './readingParts.js';
import { drawnTiles } from './treemapOption.js';
import { drawnBars } from './waterfallOption.js';

/**
 * The readable tables of the first eight families — the cartesian chart,
 * the pie, the heatmap, the scatter, the funnel, the card, the waterfall and
 * the treemap; the D41 families' are `readingStatistics.ts`. `readChart`
 * reaches each through the family's row of `FAMILY_VIEWS`.
 */

/** `text` for a value the kernel filled in on the axis `alias`, said so. */
function noted(
  text: string,
  filled: boolean,
  ctx: ReadingContext,
  alias: string | undefined,
): string {
  return filled && ctx.filled ? ctx.filled(alias, text) : text;
}

export function readCartesian(
  data: Extract<ChartData, { type: 'cartesian' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const cartesian = spec?.cartesian;
  // A series is measured against the axis its spec put it on, so it prints
  // in that axis's format: a ratio on a `percent` axis reads "25%" on the
  // ticks and must not read "0.25" here.
  const bySeries = new Map(
    (cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  const formatOf = (metric: string) =>
    bySeries.get(metric)?.axis === 'right'
      ? cartesian?.yAxis?.right?.format
      : cartesian?.yAxis?.left?.format;
  const category = ctx.column(cartesian?.x);
  // Stacked to 100% the marks are shares, and the table says both halves,
  // as the tooltip does: what the part is, and what part of its stack it is
  // — without the share, a screen reader heard a stack of values that the
  // picture drew as equal heights (2026-09-23 audit).
  const { shareAt } = stackPlan(data, spec);
  const derived = data.derived ?? [];
  const cell = (series: (typeof data.series)[number], index: number) => {
    const value = noted(
      number(
        data.points[index]?.values[series.key],
        ctx,
        formatOf(series.metric),
        series.metric,
      ),
      data.points[index]?.filled?.includes(series.key) === true,
      ctx,
      cartesian?.x,
    );
    const share = shareAt(series.key, index);
    return share === undefined
      ? value
      : `${value} · ${formatShare(share, ctx.locale)}`;
  };
  return {
    name: nameOf(
      ctx,
      data.chart,
      // A pivot draws one series per split value; what it measures is still
      // the one metric, named once.
      [...new Set(data.series.map(series => series.metric))].map(metric =>
        ctx.column(metric),
      ),
      category,
    ),
    header: [
      category ?? ctx.messages.label('label.chart.column.category'),
      ...data.series.map(series =>
        series.other === true
          ? ctx.messages.label('label.chart.other')
          : series.value === undefined
            ? (ctx.column(series.metric) ?? series.label)
            : (ctx.seriesName ?? ctx.label)(cartesian?.splitBy, series.value),
      ),
      // A derived line's column says it was computed, as its tooltip row
      // does: a screen reader hears 「7 期移动平均（算出的）」, never a
      // number that reads as one the rows measured (D33 batch B).
      ...derived.map(line =>
        derivedName(
          ctx.messages,
          line,
          data.series.length > 1 ? ctx.column(line.metric) : undefined,
        ),
      ),
    ],
    rows: data.points.map((point, index) => [
      ctx.label(cartesian?.x, point.x),
      ...data.series.map(series => cell(series, index)),
      ...derived.map(line => {
        const value = line.values[index];
        return line.kind === 'cumulative-share' && typeof value === 'number'
          ? formatShare(value, ctx.locale)
          : number(value, ctx, formatOf(line.metric), line.metric);
      }),
    ]),
  };
}

export function readPie(
  data: Extract<ChartData, { type: 'pie' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const category = ctx.column(spec?.pie?.category);
  const value = ctx.column(spec?.pie?.value);
  return {
    name: nameOf(ctx, 'pie', [value], category),
    header: [
      category ?? ctx.messages.label('label.chart.column.category'),
      value ?? ctx.messages.label('label.chart.column.value'),
    ],
    rows: data.slices.map(slice => [
      slice.other === true
        ? ctx.messages.label('label.chart.other')
        : (ctx.seriesName ?? ctx.label)(spec?.pie?.category, slice.category),
      number(slice.value, ctx, undefined, spec?.pie?.value),
    ]),
  };
}

export function readHeatmap(
  data: Extract<ChartData, { type: 'heatmap' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const heatmap = spec?.heatmap;
  const x = ctx.column(heatmap?.x);
  const y = ctx.column(heatmap?.y);
  return {
    name: nameOf(
      ctx,
      'heatmap',
      [ctx.column(heatmap?.value)],
      [y, x]
        .filter((axis): axis is string => axis !== undefined)
        .join(ctx.messages.label('label.filter.join')) || undefined,
    ),
    // The corner cell names the row axis; every other header is one x value,
    // which is exactly what the drawn grid puts along its bottom.
    header: [
      y ?? ctx.messages.label('label.chart.column.category'),
      ...data.xs.map(value => ctx.label(heatmap?.x, value)),
    ],
    rows: data.ys.map((value, row) => [
      ctx.label(heatmap?.y, value),
      ...data.xs.map((_, cell) =>
        number(data.cells[row]?.[cell], ctx, undefined, heatmap?.value),
      ),
    ]),
  };
}

export function readScatter(
  data: Extract<ChartData, { type: 'scatter' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const scatter = spec?.scatter;
  const x = ctx.column(scatter?.x);
  const y = ctx.column(scatter?.y);
  // The third dimension is a radius, so it is drawn whenever the kernel
  // shaped one — and read only then, rather than a column of ones.
  const sized = data.points.some(point => point.size !== undefined);
  return {
    name: nameOf(ctx, 'scatter', [x, y], ctx.column(scatter?.category)),
    header: [
      ctx.column(scatter?.category) ??
        ctx.messages.label('label.chart.column.category'),
      x ?? ctx.messages.label('label.chart.column.x'),
      y ?? ctx.messages.label('label.chart.column.y'),
      ...(sized ? [ctx.column(scatter?.size) ?? ''] : []),
    ],
    rows: data.points.map(point => [
      ctx.label(scatter?.category, point.category),
      number(point.x, ctx, undefined, scatter?.x),
      number(point.y, ctx, undefined, scatter?.y),
      ...(sized ? [number(point.size, ctx, undefined, scatter?.size)] : []),
    ]),
  };
}

export function readFunnel(
  data: Extract<ChartData, { type: 'funnel' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const stages = spec?.funnel?.stages;
  const category = stages?.from === 'group' ? stages.category : undefined;
  const drawn = drawnStages(data, {
    spec,
    label: ctx.label,
    column: ctx.column,
    locale: ctx.locale,
  });
  const none = ctx.messages.label('label.summary.unavailable');
  const largest = ctx.messages.label('label.chart.funnel.largest-drop');
  return {
    name: nameOf(
      ctx,
      'funnel',
      [stages?.from === 'group' ? ctx.column(stages.value) : undefined],
      ctx.column(category),
    ),
    header: [
      ctx.column(category) ?? ctx.messages.label('label.chart.column.stage'),
      // A cumulative stage is not the number the table shows, so its column
      // says what it is, as the drawing's heading does.
      ctx.messages.label(
        data.cumulative
          ? 'label.chart.column.cumulative'
          : 'label.chart.column.value',
      ),
      // Both conversions and the drop, as the drawing and its tooltip say
      // them; the first stage has no stage before it to lose from.
      ctx.messages.label('label.chart.column.conversion.previous'),
      ctx.messages.label('label.chart.column.conversion.first'),
      ctx.messages.label('label.chart.column.drop'),
    ],
    rows: drawn.map(stage => [
      stage.name,
      stage.text,
      stage.conversion ?? none,
      stage.share ?? none,
      stage.drop === undefined
        ? none
        : stage.drop.largest
          ? `${dropText(stage.drop)} · ${largest}`
          : dropText(stage.drop),
    ]),
  };
}

/**
 * The card already says its value and its comparison in words, so the reading
 * carries what only the drawing knows: the target behind the progress bar,
 * the compared-against number the signed delta was derived from, and every
 * point of the sparkline.
 */
export function readMetric(
  data: Extract<ChartData, { type: 'metric' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const card = spec?.metric;
  const rows: string[][] = [];
  if (data.compare)
    rows.push([
      ctx.messages.label('label.chart.column.compare'),
      number(data.compare.value, ctx, card?.format, card?.metric),
    ]);
  if (data.target !== undefined)
    rows.push([
      ctx.messages.label('label.chart.column.target'),
      number(data.target, ctx, card?.format, card?.metric),
    ]);
  for (const point of data.trend ?? [])
    rows.push([
      ctx.label(card?.trend?.x, point.x),
      noted(
        number(point.value, ctx, card?.format, card?.metric),
        point.filled === true,
        ctx,
        card?.trend?.x,
      ),
    ]);
  return {
    name: nameOf(ctx, 'metric', [ctx.column(card?.metric)]),
    header: [
      // The rows are the sparkline's periods, so the column is named by the
      // dimension they are periods of — 「创建时间」, not 「类别」.
      (data.trend?.length ? ctx.column(card?.trend?.x) : undefined) ??
        ctx.messages.label('label.chart.column.category'),
      ctx.column(card?.metric) ??
        ctx.messages.label('label.chart.column.value'),
    ],
    rows,
  };
}

/**
 * A waterfall's steps, each with its change and the running total it
 * arrives at, and the closing total last — the bars in the order drawn.
 */
export function readWaterfall(
  data: Extract<ChartData, { type: 'waterfall' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const waterfall = spec?.waterfall;
  const category = ctx.column(waterfall?.x);
  const total = ctx.messages.label('label.chart.total');
  const bars = drawnBars(data, {
    spec,
    label: ctx.label,
    words: {
      total,
      increase: ctx.messages.label('label.chart.waterfall.increase'),
      decrease: ctx.messages.label('label.chart.waterfall.decrease'),
      running: ctx.messages.label('label.chart.column.running'),
    },
  });
  return {
    name: nameOf(ctx, 'waterfall', [ctx.column(waterfall?.value)], category),
    header: [
      category ?? ctx.messages.label('label.chart.column.category'),
      ctx.messages.label('label.chart.column.change'),
      ctx.messages.label('label.chart.column.running'),
    ],
    rows: bars.map(bar => [
      bar.name,
      bar.kind === 'total' ? '' : bar.whole,
      bar.running ?? bar.whole,
    ]),
  };
}

/**
 * A treemap's tiles, in the order drawn, each with its number and its
 * share of the whole; a nested one names its outer tile first. A group with
 * no area is not drawn, and not read here either: the drawing says how
 * many it left out, and the table layout has every row.
 */
export function readTreemap(
  data: Extract<ChartData, { type: 'treemap' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const treemap = spec?.treemap;
  const parent = data.nested ? treemap?.parent : undefined;
  const category = ctx.column(treemap?.category);
  const tiles = drawnTiles(data, {
    spec,
    label: ctx.label,
    locale: ctx.locale,
  });
  return {
    name: nameOf(
      ctx,
      'treemap',
      [ctx.column(treemap?.value)],
      [ctx.column(parent), category]
        .filter((axis): axis is string => axis !== undefined)
        .join(ctx.messages.label('label.filter.join')) || undefined,
    ),
    header: [
      ...(parent === undefined
        ? []
        : [
            ctx.column(parent) ??
              ctx.messages.label('label.chart.column.category'),
          ]),
      category ?? ctx.messages.label('label.chart.column.category'),
      ctx.column(treemap?.value) ??
        ctx.messages.label('label.chart.column.value'),
      ctx.messages.label('label.chart.column.share'),
    ],
    rows: tiles.map(tile => [
      ...(parent === undefined ? [] : [ctx.label(parent, tile.row[parent])]),
      tile.name,
      tile.text,
      tile.share,
    ]),
  };
}
