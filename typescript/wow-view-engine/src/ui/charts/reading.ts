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
import type { ChartSpec, ChartType, ValueFormat } from '../../model/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { formatValue } from './axis.js';
import type { CategoryLabel } from './family.js';

/**
 * A chart as text: a name for the drawing and the same numbers it draws,
 * laid out as a table.
 *
 * The drawing is one image with a name (`role="img"`), so nothing inside the
 * `<svg>` reaches a screen reader — which is the whole point, since what was
 * inside it was axis ticks and an empty `<title>`. This carries the answer
 * instead, and it is built from `ChartData`, the very projection the marks
 * are drawn from, so the two cannot drift: a value on screen with no row
 * here would mean a family drew something the kernel did not shape.
 */
export interface ChartReading {
  /** What is drawn, in one line; the drawing's accessible name. */
  name: string;
  /** The readable table's column headers. */
  header: readonly string[];
  /** One row per drawn datum, every cell already text. */
  rows: readonly (readonly string[])[];
}

/** What a reading needs besides the data: wording and the two labellers. */
export interface ReadingContext {
  messages: MessageFormatters;
  /** A category value as its column shows it. */
  label: CategoryLabel;
  /** An alias as its column is titled, or the alias itself. */
  column: (alias: string | undefined) => string | undefined;
}

export function readChart(
  data: ChartData,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  switch (data.type) {
    case 'cartesian':
      return readCartesian(data, spec, ctx);
    case 'pie':
      return readPie(data, spec, ctx);
    case 'heatmap':
      return readHeatmap(data, spec, ctx);
    case 'scatter':
      return readScatter(data, spec, ctx);
    case 'funnel':
      return readFunnel(data, spec, ctx);
    case 'metric':
      return readMetric(data, spec, ctx);
  }
}

/**
 * `{type}: {measures} by {category}`, and without the tail when nothing
 * names a category — a metric card is one number, not one number per
 * anything. With neither, the family's own name is still better than the
 * `role="application"` and the empty `<title>` this replaced.
 */
function nameOf(
  ctx: ReadingContext,
  type: ChartType,
  measures: readonly (string | undefined)[],
  category?: string,
): string {
  const kind = ctx.messages.label(`label.chart.type.${type}`, undefined, type);
  const named = measures.filter(
    (measure): measure is string => measure !== undefined,
  );
  if (named.length === 0) return kind;
  const params = {
    type: kind,
    measures: named.join(ctx.messages.label('label.filter.join')),
  };
  return category === undefined
    ? ctx.messages.label('label.chart.figure.plain', params)
    : ctx.messages.label('label.chart.figure', { ...params, category });
}

/** A measured number as the chart's own axis would print it. */
function number(
  value: number | null | undefined,
  ctx: ReadingContext,
  format?: ValueFormat,
): string {
  return value === null || value === undefined
    ? ctx.messages.label('label.summary.unavailable')
    : formatValue(value, format);
}

function readCartesian(
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
        series.value === undefined
          ? series.label
          : ctx.label(cartesian?.splitBy, series.value),
      ),
    ],
    rows: data.points.map(point => [
      ctx.label(cartesian?.x, point.x),
      ...data.series.map(series =>
        number(point.values[series.key], ctx, formatOf(series.metric)),
      ),
    ]),
  };
}

function readPie(
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
        : ctx.label(spec?.pie?.category, slice.category),
      number(slice.value, ctx),
    ]),
  };
}

function readHeatmap(
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
      ...data.xs.map((_, cell) => number(data.cells[row]?.[cell], ctx)),
    ]),
  };
}

function readScatter(
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
      number(point.x, ctx),
      number(point.y, ctx),
      ...(sized ? [number(point.size, ctx)] : []),
    ]),
  };
}

function readFunnel(
  data: Extract<ChartData, { type: 'funnel' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const stages = spec?.funnel?.stages;
  const category = stages?.from === 'group' ? stages.category : undefined;
  const converts = data.stages.some(stage => stage.conversion !== undefined);
  return {
    name: nameOf(
      ctx,
      'funnel',
      [stages?.from === 'group' ? ctx.column(stages.value) : undefined],
      ctx.column(category),
    ),
    header: [
      ctx.column(category) ?? ctx.messages.label('label.chart.column.stage'),
      ctx.messages.label('label.chart.column.value'),
      ...(converts
        ? [ctx.messages.label('label.chart.column.conversion')]
        : []),
    ],
    rows: data.stages.map(stage => [
      category === undefined ? stage.label : ctx.label(category, stage.label),
      number(stage.value, ctx),
      ...(converts
        ? [
            stage.conversion === undefined
              ? ctx.messages.label('label.summary.unavailable')
              : formatValue(stage.conversion, 'percent'),
          ]
        : []),
    ]),
  };
}

/**
 * The card already says its value and its comparison in words, so the reading
 * carries what only the drawing knows: the target behind the progress bar,
 * the compared-against number the signed delta was derived from, and every
 * point of the sparkline.
 */
function readMetric(
  data: Extract<ChartData, { type: 'metric' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const card = spec?.metric;
  const rows: string[][] = [];
  if (data.compare)
    rows.push([
      ctx.messages.label('label.chart.column.compare'),
      number(data.compare.value, ctx, card?.format),
    ]);
  if (data.target !== undefined)
    rows.push([
      ctx.messages.label('label.chart.column.target'),
      number(data.target, ctx, card?.format),
    ]);
  for (const point of data.trend ?? [])
    rows.push([
      ctx.label(card?.trend?.x, point.x),
      number(point.value, ctx, card?.format),
    ]);
  return {
    name: nameOf(ctx, 'metric', [ctx.column(card?.metric)]),
    header: [
      ctx.messages.label('label.chart.column.category'),
      ctx.column(card?.metric) ??
        ctx.messages.label('label.chart.column.value'),
    ],
    rows,
  };
}
