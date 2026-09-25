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
import { drawnProfiles } from './profileOption.js';
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
