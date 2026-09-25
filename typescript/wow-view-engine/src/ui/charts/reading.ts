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
import { formatShare, formatValue } from './axis.js';
import { stackPlan } from './cartesianPlan.js';
import {
  conversionHeading,
  stageName,
  type FilledNote,
  type SeriesName,
  type ValueLabel,
} from './family.js';
import { derivedName } from './markWords.js';
import {
  readBoxplot,
  readCalendar,
  readFlow,
  readGauge,
  readHierarchy,
  readMap,
  readProfiles,
  readThemeRiver,
} from './readingStatistics.js';
import { chartSentence } from './sentence.js';
import { drawnTiles } from './treemapOption.js';
import { drawnBars } from './waterfallOption.js';

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
  /**
   * The drawing in one sentence, said after its name (`chartSentence`):
   * how many groups, the highest and the lowest. Absent where there is no
   * number to say, and on a metric card, whose face says it in words.
   */
  sentence?: string;
  /** The readable table's column headers. */
  header: readonly string[];
  /** One row per drawn datum, every cell already text. */
  rows: readonly (readonly string[])[];
}

/** What a reading needs besides the data: wording and the two labellers. */
export interface ReadingContext {
  messages: MessageFormatters;
  /** A value as its column shows it, category and measure alike. */
  label: ValueLabel;
  /** An alias as its column is titled, or the alias itself. */
  column: (alias: string | undefined) => string | undefined;
  /**
   * A series or a slice standing for one group value, as the legend names
   * it (`useSeriesName`); left out, the value as its column reads it.
   */
  seriesName?: SeriesName;
  /** The surface's language, for a number an axis format prints. */
  locale: string | undefined;
  /**
   * A value the chart filled in, as the tooltip says it (`useFilledNote`):
   * the table says the same 「0（这一天没有记录）」 the tooltip does.
   */
  filled?: FilledNote;
}

/** `text` for a value the kernel filled in on the axis `alias`, said so. */
function noted(
  text: string,
  filled: boolean,
  ctx: ReadingContext,
  alias: string | undefined,
): string {
  return filled && ctx.filled ? ctx.filled(alias, text) : text;
}

export function readChart(
  data: ChartData,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const reading = readFamily(data, spec, ctx);
  const sentence = chartSentence(data, spec, ctx);
  return sentence === undefined ? reading : { ...reading, sentence };
}

function readFamily(
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
    case 'waterfall':
      return readWaterfall(data, spec, ctx);
    case 'treemap':
      return readTreemap(data, spec, ctx);
    case 'boxplot':
      return readBoxplot(data, spec, ctx);
    case 'gauge':
      return readGauge(data, spec, ctx);
    case 'radar':
    case 'parallel':
      return readProfiles(data, spec, ctx);
    case 'sunburst':
    case 'tree':
      return readHierarchy(data, spec, ctx);
    case 'sankey':
      return readFlow(data, spec, ctx);
    case 'calendar':
      return readCalendar(data, spec, ctx);
    case 'themeRiver':
      return readThemeRiver(data, spec, ctx);
    case 'map':
      return readMap(data, spec, ctx);
  }
}

/**
 * `{type}: {measures} by {category}`, and without the tail when nothing
 * names a category — a metric card is one number, not one number per
 * anything. With neither, the family's own name is still better than the
 * `role="application"` and the empty `<title>` this replaced.
 */
export function nameOf(
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

/**
 * A measured number, read as its own column reads it — the reading table and
 * the marks beside it are the same numbers, so they are the same text. An
 * axis that pinned a `ValueFormat` still wins: that is an instruction about
 * this axis, and a ratio drawn as 25% must not be read out as 0.25.
 */
export function number(
  value: number | null | undefined,
  ctx: ReadingContext,
  format?: ValueFormat,
  alias?: string,
): string {
  if (value === null || value === undefined)
    return ctx.messages.label('label.summary.unavailable');
  return format === undefined
    ? ctx.label(alias, value)
    : formatValue(value, format, ctx.locale);
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
        : (ctx.seriesName ?? ctx.label)(spec?.pie?.category, slice.category),
      number(slice.value, ctx, undefined, spec?.pie?.value),
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
      ...data.xs.map((_, cell) =>
        number(data.cells[row]?.[cell], ctx, undefined, heatmap?.value),
      ),
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
      number(point.x, ctx, undefined, scatter?.x),
      number(point.y, ctx, undefined, scatter?.y),
      ...(sized ? [number(point.size, ctx, undefined, scatter?.size)] : []),
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
  // One metric for the whole funnel when its stages are values of a
  // dimension; one per stage when each stage is its own metric.
  const measured = (index: number) =>
    stages === undefined
      ? undefined
      : stages.from === 'group'
        ? stages.value
        : stages.items[index]?.metric;
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
      ...(converts
        ? [ctx.messages.label(conversionHeading(spec?.funnel?.conversion))]
        : []),
    ],
    rows: data.stages.map((stage, index) => [
      stageName(stages, index, stage.label, ctx.label, ctx.column),
      number(stage.value, ctx, undefined, measured(index)),
      ...(converts
        ? [
            stage.conversion === undefined
              ? ctx.messages.label('label.summary.unavailable')
              : formatValue(stage.conversion, 'percent', ctx.locale),
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
function readWaterfall(
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
function readTreemap(
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
