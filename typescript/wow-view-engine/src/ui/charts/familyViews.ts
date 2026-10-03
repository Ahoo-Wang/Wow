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

import type { ComponentType } from 'react';
import type { ChartData } from '../../analysis/index.js';
import type { ChartDataOf } from '../../analysis/familyRules.js';
import type { ChartFamily, ChartSpec } from '../../model/index.js';
import { Boxplot } from './Boxplot.js';
import { Candlestick } from './Candlestick.js';
import { Cartesian } from './Cartesian.js';
import type { FamilyProps } from './family.js';
import { Funnel } from './Funnel.js';
import { Gauge } from './Gauge.js';
import { GeoMap } from './GeoMap.js';
import { Heatmap } from './Heatmap.js';
import { Hierarchy } from './Hierarchy.js';
import { MetricCard } from './MetricCard.js';
import { PieSlices } from './PieSlices.js';
import { Profiles } from './Profiles.js';
import type { ChartReading, ReadingContext } from './reading.js';
import {
  readCartesian,
  readFunnel,
  readHeatmap,
  readMetric,
  readPie,
  readScatter,
  readTreemap,
  readWaterfall,
} from './readingBasics.js';
import {
  readBoxplot,
  readCalendar,
  readCandlestick,
  readFlow,
  readGauge,
  readHierarchy,
  readMap,
  readProfiles,
  readThemeRiver,
} from './readingStatistics.js';
import { ScatterPoints } from './ScatterPoints.js';
import {
  boxplotSentence,
  calendarSentence,
  candlestickSentence,
  cartesianSentence,
  funnelSentence,
  gaugeSentence,
  heatmapSentence,
  mapSentence,
  partsSentence,
  pieSentence,
  profileSentence,
  riverSentence,
  sankeySentence,
  scatterSentence,
  treemapSentence,
  trendSentence,
  waterfallSentence,
} from './sentence.js';
import { TimeCharts } from './TimeCharts.js';
import { Treemap } from './Treemap.js';
import { Waterfall } from './Waterfall.js';

/**
 * What `/ui` does with a chart of one family: the component that draws it,
 * its readable table and its sentence — everything that used to be a
 * `switch` over `ChartData`'s family (R2-94). The kernel's own row of a
 * family is `FAMILY_RULES` (`analysis/familyRules.ts`), keyed alike; the two
 * cannot be one table, since the kernel may not import `/ui` and this one
 * draws with ECharts. `test/chartFamilies.test.ts` holds both to every
 * family of the model.
 */
export interface FamilyView<F extends ChartFamily> {
  /** The component that draws it, handed the same props every family is. */
  draw: ComponentType<FamilyProps<ChartDataOf<F>>>;
  /** Its readable table: a row per drawn datum (`readChart`). */
  read(
    data: ChartDataOf<F>,
    spec: ChartSpec | undefined,
    ctx: ReadingContext,
  ): ChartReading;
  /** What it says after its name (`chartSentence`); none without a number. */
  sentence(
    data: ChartDataOf<F>,
    spec: ChartSpec | undefined,
    ctx: ReadingContext,
  ): string | undefined;
  /**
   * Whether a result of no groups leaves it nothing to draw
   * (`emptyWithoutGroups`): every family that draws a mark per group. The
   * metric card and the gauge read one number, and a missing one is their
   * 「—」.
   */
  emptyWithoutGroups: boolean;
}

/**
 * Every family's view. The compiler holds it to `ChartFamily`: a family
 * added to the model without a row here does not build.
 */
export const FAMILY_VIEWS = Object.freeze({
  cartesian: {
    draw: Cartesian,
    read: readCartesian,
    sentence: cartesianSentence,
    emptyWithoutGroups: true,
  },
  pie: {
    draw: PieSlices,
    read: readPie,
    sentence: pieSentence,
    emptyWithoutGroups: true,
  },
  heatmap: {
    draw: Heatmap,
    read: readHeatmap,
    sentence: heatmapSentence,
    emptyWithoutGroups: true,
  },
  scatter: {
    draw: ScatterPoints,
    read: readScatter,
    sentence: scatterSentence,
    emptyWithoutGroups: true,
  },
  funnel: {
    draw: Funnel,
    read: readFunnel,
    sentence: funnelSentence,
    emptyWithoutGroups: true,
  },
  metric: {
    draw: MetricCard,
    read: readMetric,
    sentence: trendSentence,
    emptyWithoutGroups: false,
  },
  waterfall: {
    draw: Waterfall,
    read: readWaterfall,
    sentence: waterfallSentence,
    emptyWithoutGroups: true,
  },
  treemap: {
    draw: Treemap,
    read: readTreemap,
    sentence: treemapSentence,
    emptyWithoutGroups: true,
  },
  boxplot: {
    draw: Boxplot,
    read: readBoxplot,
    sentence: boxplotSentence,
    emptyWithoutGroups: true,
  },
  candlestick: {
    draw: Candlestick,
    read: readCandlestick,
    sentence: candlestickSentence,
    emptyWithoutGroups: true,
  },
  gauge: {
    draw: Gauge,
    read: readGauge,
    sentence: gaugeSentence,
    emptyWithoutGroups: false,
  },
  radar: {
    draw: Profiles,
    read: readProfiles,
    sentence: profileSentence,
    emptyWithoutGroups: true,
  },
  parallel: {
    draw: Profiles,
    read: readProfiles,
    sentence: profileSentence,
    emptyWithoutGroups: true,
  },
  sunburst: {
    draw: Hierarchy,
    read: readHierarchy,
    sentence: partsSentence,
    emptyWithoutGroups: true,
  },
  tree: {
    draw: Hierarchy,
    read: readHierarchy,
    sentence: partsSentence,
    emptyWithoutGroups: true,
  },
  sankey: {
    draw: Hierarchy,
    read: readFlow,
    sentence: sankeySentence,
    emptyWithoutGroups: true,
  },
  calendar: {
    draw: TimeCharts,
    read: readCalendar,
    sentence: calendarSentence,
    emptyWithoutGroups: true,
  },
  themeRiver: {
    draw: TimeCharts,
    read: readThemeRiver,
    sentence: riverSentence,
    emptyWithoutGroups: true,
  },
  map: {
    draw: GeoMap,
    read: readMap,
    sentence: mapSentence,
    emptyWithoutGroups: true,
  },
} satisfies { [F in ChartFamily]: FamilyView<F> });

/**
 * The view of the family `data` belongs to. Its members take that family's
 * data alone, which TypeScript cannot pair with `data` through a lookup by
 * its `type`; `FAMILY_VIEWS` being checked row by row is what makes the
 * widening sound.
 */
export function viewOf(data: ChartData): FamilyView<ChartFamily> {
  return FAMILY_VIEWS[data.type] as FamilyView<ChartFamily>;
}
