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
import { gaugeText, reachedShare } from './gaugeOption.js';
import { drawnFlow, drawnParts } from './hierarchyOption.js';
import { drawnProfiles } from './profileOption.js';
import { drawnStreams } from './timeOption.js';
import {
  nameOf,
  number,
  type ChartReading,
  type ReadingContext,
} from './reading.js';

/*
 * The statistical families read as text (D41): a boxplot's five numbers,
 * a gauge's number and its scale, a radar's or parallel axes' profiles —
 * each off the same `ChartData` the drawing is, as every reading is
 * (`readChart`).
 */

/**
 * A boxplot's boxes, in the order drawn: each group's five numbers, each
 * column headed by its metric — 「实付的第 25 百分位」, not a 「下四分位」
 * the metric may not be; a percentile's header carries its 「≈」.
 */
export function readBoxplot(
  data: Extract<ChartData, { type: 'boxplot' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const boxplot = spec?.boxplot;
  const category = ctx.column(boxplot?.category);
  const five = ['low', 'q1', 'median', 'q3', 'high'] as const;
  return {
    name: nameOf(ctx, 'boxplot', [ctx.column(boxplot?.median)], category),
    header: [
      category ?? ctx.messages.label('label.chart.column.category'),
      ...five.map(
        slot =>
          ctx.column(boxplot?.[slot]) ??
          ctx.messages.label('label.chart.column.value'),
      ),
    ],
    rows: data.boxes.map(box => [
      ctx.label(boxplot?.category, box.group),
      ...five.map(slot => number(box[slot], ctx, undefined, boxplot?.[slot])),
    ]),
  };
}

/**
 * A gauge's number, and what only the dial knows: the target, how much of
 * it is reached, and the scale's two ends.
 */
export function readGauge(
  data: Extract<ChartData, { type: 'gauge' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const gauge = spec?.gauge;
  const text = (value: number | null) =>
    value === null
      ? ctx.messages.label('label.summary.unavailable')
      : gaugeText(value, { spec, label: ctx.label, locale: ctx.locale });
  const measured =
    ctx.column(gauge?.metric) ?? ctx.messages.label('label.chart.column.value');
  const share = reachedShare(data, ctx.locale);
  return {
    name: nameOf(ctx, 'gauge', [ctx.column(gauge?.metric)]),
    header: [ctx.messages.label('label.chart.column.category'), measured],
    rows: [
      [measured, text(data.value)],
      ...(data.target === undefined
        ? []
        : [
            [
              ctx.messages.label('label.chart.column.target'),
              text(data.target),
            ],
          ]),
      ...(share === undefined
        ? []
        : [[ctx.messages.label('label.chart.column.reached'), share]]),
      [ctx.messages.label('label.chart.column.scale-min'), text(data.min)],
      [ctx.messages.label('label.chart.column.scale-max'), text(data.max)],
    ],
  };
}

/**
 * A radar's shapes or parallel axes' lines, in the order drawn: each
 * group's number on every axis, each column headed by its metric.
 */
export function readProfiles(
  data: Extract<ChartData, { type: 'radar' | 'parallel' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const own = data.type === 'radar' ? spec?.radar : spec?.parallel;
  const category = ctx.column(own?.category);
  const drawn = drawnProfiles(data, {
    spec,
    label: ctx.label,
    seriesName: ctx.seriesName,
  });
  return {
    name: nameOf(
      ctx,
      data.type,
      data.metrics.map(metric => ctx.column(metric)),
      category,
    ),
    header: [
      category ?? ctx.messages.label('label.chart.column.category'),
      ...data.metrics.map(
        metric =>
          ctx.column(metric) ?? ctx.messages.label('label.chart.column.value'),
      ),
    ],
    rows: data.profiles.map((profile, index) => [
      drawn[index]?.name ?? '',
      ...data.metrics.map((metric, at) =>
        number(profile.values[at], ctx, undefined, metric),
      ),
    ]),
  };
}

/**
 * A sunburst's or a tree's innermost parts, in the order drawn: every
 * level's value, the number and its share of the whole — the rows the
 * table layout has, each under its parents.
 */
export function readHierarchy(
  data: Extract<ChartData, { type: 'sunburst' | 'tree' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const own = data.type === 'sunburst' ? spec?.sunburst : spec?.tree;
  const levels = own?.levels ?? [];
  const leaves = drawnParts(data, {
    spec,
    label: ctx.label,
    locale: ctx.locale,
  }).filter(part => part.leaf);
  const titles = levels.map(
    alias =>
      ctx.column(alias) ?? ctx.messages.label('label.chart.column.category'),
  );
  return {
    name: nameOf(
      ctx,
      data.type,
      [ctx.column(own?.value)],
      titles.join(ctx.messages.label('label.filter.join')) || undefined,
    ),
    header: [
      ...titles,
      ctx.column(own?.value) ?? ctx.messages.label('label.chart.column.value'),
      ctx.messages.label('label.chart.column.share'),
    ],
    rows: leaves.map(part => [
      ...levels.map(alias => ctx.label(alias, part.row[alias])),
      part.text,
      part.share,
    ]),
  };
}

/** A sankey's bands, largest first: where from, where to, how much. */
export function readFlow(
  data: Extract<ChartData, { type: 'sankey' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const sankey = spec?.sankey;
  const { bands } = drawnFlow(data, { spec, label: ctx.label });
  return {
    name: nameOf(
      ctx,
      'sankey',
      [ctx.column(sankey?.value)],
      (sankey?.levels ?? [])
        .map(alias => ctx.column(alias))
        .filter((title): title is string => title !== undefined)
        .join(' → ') || undefined,
    ),
    header: [
      ctx.messages.label('label.chart.column.from'),
      ctx.messages.label('label.chart.column.to'),
      ctx.column(sankey?.value) ??
        ctx.messages.label('label.chart.column.value'),
    ],
    rows: bands.map(band => [band.from, band.to, band.text]),
  };
}

/** A calendar's days, earliest first: the day and its number. */
export function readCalendar(
  data: Extract<ChartData, { type: 'calendar' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const calendar = spec?.calendar;
  const day = ctx.column(calendar?.date);
  return {
    name: nameOf(ctx, 'calendar', [ctx.column(calendar?.value)], day),
    header: [
      day ?? ctx.messages.label('label.chart.column.category'),
      ctx.column(calendar?.value) ??
        ctx.messages.label('label.chart.column.value'),
    ],
    rows: data.days.map(entry => [
      ctx.label(calendar?.date, entry.at),
      number(entry.value, ctx, undefined, calendar?.value),
    ]),
  };
}

/** A theme river's buckets, earliest first: every stream's number at each. */
export function readThemeRiver(
  data: Extract<ChartData, { type: 'themeRiver' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const river = spec?.themeRiver;
  const streams = drawnStreams(data, {
    spec,
    label: ctx.label,
    seriesName: ctx.seriesName,
    other: ctx.messages.label('label.chart.other'),
  });
  const along = ctx.column(river?.x);
  return {
    name: nameOf(
      ctx,
      'themeRiver',
      [ctx.column(river?.value)],
      [along, ctx.column(river?.splitBy)]
        .filter((title): title is string => title !== undefined)
        .join(ctx.messages.label('label.filter.join')) || undefined,
    ),
    header: [
      along ?? ctx.messages.label('label.chart.column.category'),
      ...streams.map(stream => stream.name),
    ],
    rows: data.times.map((time, at) => [
      ctx.label(river?.x, time),
      ...streams.map((_stream, index) =>
        number(data.values[at]?.[index] ?? 0, ctx, undefined, river?.value),
      ),
    ]),
  };
}

/** A map's measured regions, largest first: the region and its number. */
export function readMap(
  data: Extract<ChartData, { type: 'map' }>,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const map = spec?.map;
  const region = ctx.column(map?.region);
  return {
    name: nameOf(ctx, 'map', [ctx.column(map?.value)], region),
    header: [
      region ?? ctx.messages.label('label.chart.column.category'),
      ctx.column(map?.value) ?? ctx.messages.label('label.chart.column.value'),
    ],
    rows: data.regions.map(entry => [
      ctx.label(map?.region, entry.group),
      number(entry.value, ctx, undefined, map?.value),
    ]),
  };
}
