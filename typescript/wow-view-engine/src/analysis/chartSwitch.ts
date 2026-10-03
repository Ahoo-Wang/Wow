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

import {
  CHART_FAMILY,
  type ChartSpec,
  type ChartType,
} from '../model/index.js';
import { familyRules } from './familyRules.js';

/**
 * The metric a chart is about: the one its first mark measures.
 *
 * A cartesian chart's first series, a pie's or a heatmap's value, a
 * scatter's horizontal measure, a funnel's stage value (or its first stage),
 * a card's headline, a waterfall's steps, a treemap's areas, a box's median,
 * a gauge's needle, a radar's or parallel axes' first axis. Undefined when
 * the family has not been filled yet.
 */
export function leadMetric(chart: ChartSpec): string | undefined {
  return familyRules(chart.type)?.lead(chart);
}

/**
 * The chart as `type` draws it, measuring what the chart being left measured.
 *
 * Picking another type changes how the numbers are drawn, not which numbers
 * (the user's 2026-09-23 decision, audit P0-10): a bar chart of 「金额的总和」
 * turned into a pie used to become a pie of 「记录数」, because a family
 * never visited fills its value slot with the first metric, and one visited
 * before kept whatever it measured then. So the lead metric is carried into
 * the new family's slot; everything else the family had — a pie's donut, a
 * card's target, a funnel's order — stays as it was, and `fitChartSlots`
 * still judges the result, so a metric the new family cannot measure (a
 * moment, or one that does not add up under a card's trend) falls back there
 * as before.
 *
 * The legend's setting goes with the family it was set for: another family
 * starts from 「自动」.
 *
 * A cartesian chart draws a list: the lead joins it at the front when it is
 * not already drawn, and is the one series of a pivot. A family with nothing
 * written yet draws every metric, which already includes it.
 */
export function switchChartType(chart: ChartSpec, type: ChartType): ChartSpec {
  // The legend was set for the family being left: 「无」 on one bar series
  // said nothing was lost, and carried to a pie it hid the only key to the
  // slices, which wear their shares and no names (second review
  // R2-P1-2). Another family starts from 「自动」, which shows a legend
  // where its reading needs one; within a family it is kept.
  // `undefined` rather than left out: a redraw hands this to
  // `updateChart` as a patch, which takes a member given as `undefined` out
  // and keeps one left out.
  const next: ChartSpec =
    CHART_FAMILY[type] === CHART_FAMILY[chart.type] ||
    chart.legend === undefined
      ? { ...chart, type }
      : { ...chart, type, legend: undefined };
  const lead = leadMetric(chart);
  if (lead === undefined || lead === '' || type === chart.type) return next;
  return familyRules(type)?.carry(chart, next, lead) ?? next;
}
