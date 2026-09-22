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

import type { ChartFamily, ChartType } from '../model/index.js';
import { CHART_FAMILY } from '../model/index.js';

/**
 * What a chart family is, in one place (phase-2 review E1). A family is the
 * unit a new kind of chart is added as, so everything the kernels and the
 * options panel ask of it by family is a row here rather than a `switch` in
 * each of them: which pages its options have, whether it has a legend and
 * value labels, and what shape of result it can draw.
 *
 * The fit is the forward reading of `validateChart`'s rules: a family is
 * offered for a shape exactly when the slots `fitChartSlots` fills for it
 * pass validation. That agreement is a test over every shape
 * (test/chartFamilies.test.ts「one rule, read forward and after the fact」);
 * the day the two drift, it fails rather than a tile being offered that
 * then refuses to draw.
 */

/** The three pages a chart's options are laid out on. */
export type OptionsTab = 'data' | 'display' | 'axes';

/**
 * Why a chart type cannot draw the shape on hand, as a catalogue key. Each
 * names what the shape lacks, or has too much of, in the analyst's words,
 * because that is what the greyed tile says under itself (D20 屏 I).
 */
export type ChartUnfit =
  | 'chart.fit.needs-dimension'
  | 'chart.fit.needs-one-dimension'
  | 'chart.fit.needs-two-dimensions'
  | 'chart.fit.too-many-dimensions'
  | 'chart.fit.needs-two-metrics'
  | 'chart.fit.needs-no-dimension';

/** The facts of a result's shape a family's fit reads. */
export interface ShapeFacts {
  /** How many dimensions. */
  groups: number;
  /** How many metrics. */
  metrics: number;
  /** One dimension, and it is a date bucket. */
  dated: boolean;
  /** At least one metric may be added up across rows. */
  additive: boolean;
}

export interface ChartFamilyTraits {
  /** The pages of its options panel. */
  tabs: readonly OptionsTab[];
  /** Whether it draws a legend worth placing. */
  legend: boolean;
  /** Whether it can write the values on its marks. */
  labels: boolean;
  /** Why it cannot draw this shape, or `null` when it can. */
  unfit(shape: ShapeFacts): ChartUnfit | null;
}

/**
 * Every family's traits. The fits, in words: a cartesian chart puts one
 * dimension on its axis and may split by a second, so it needs one and takes
 * at most two (a third would leave several rows per point, which AVG and
 * DISTINCT_COUNT cannot be added back up over — D20 left that a table's
 * job rather than drop a dimension silently); a pie needs exactly one; a
 * heatmap two; a scatter plots two metrics per value of one dimension; a
 * funnel's stages are the values of one dimension or, with none, the
 * metrics themselves; a card is one number, or a sparkline over the one
 * date dimension when its headline adds up.
 */
export const CHART_FAMILIES: Readonly<Record<ChartFamily, ChartFamilyTraits>> =
  Object.freeze({
    cartesian: {
      tabs: ['data', 'display', 'axes'],
      legend: true,
      labels: true,
      unfit: ({ groups }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 2
            ? 'chart.fit.too-many-dimensions'
            : null,
    },
    pie: {
      tabs: ['data', 'display'],
      legend: true,
      labels: true,
      unfit: ({ groups }) =>
        groups === 1 ? null : 'chart.fit.needs-one-dimension',
    },
    heatmap: {
      tabs: ['data', 'display'],
      legend: false,
      labels: true,
      unfit: ({ groups }) =>
        groups === 2 ? null : 'chart.fit.needs-two-dimensions',
    },
    scatter: {
      tabs: ['data'],
      legend: false,
      labels: false,
      unfit: ({ groups, metrics }) =>
        groups === 0
          ? 'chart.fit.needs-dimension'
          : groups > 1
            ? 'chart.fit.needs-one-dimension'
            : metrics < 2
              ? 'chart.fit.needs-two-metrics'
              : null,
    },
    funnel: {
      tabs: ['data', 'display'],
      legend: false,
      labels: false,
      unfit: ({ groups, metrics }) =>
        groups === 1 || (groups === 0 && metrics >= 2)
          ? null
          : groups === 0
            ? 'chart.fit.needs-two-metrics'
            : 'chart.fit.needs-one-dimension',
    },
    metric: {
      tabs: ['data', 'display'],
      legend: false,
      labels: false,
      unfit: ({ groups, dated, additive }) =>
        groups === 0 || (dated && additive)
          ? null
          : 'chart.fit.needs-no-dimension',
    },
  });

/** The traits of the family a chart type belongs to. */
export function familyOf(type: ChartType): ChartFamilyTraits {
  return CHART_FAMILIES[CHART_FAMILY[type]];
}
