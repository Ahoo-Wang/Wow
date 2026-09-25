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
  switch (CHART_FAMILY[chart.type]) {
    case 'cartesian':
      return chart.cartesian?.series[0]?.metric;
    case 'pie':
      return chart.pie?.value;
    case 'heatmap':
      return chart.heatmap?.value;
    case 'scatter':
      return chart.scatter?.x;
    case 'funnel':
      return chart.funnel?.stages.from === 'group'
        ? chart.funnel.stages.value
        : chart.funnel?.stages.items[0]?.metric;
    case 'metric':
      return chart.metric?.metric;
    case 'waterfall':
      return chart.waterfall?.value;
    case 'treemap':
      return chart.treemap?.value;
    case 'boxplot':
      return chart.boxplot?.median;
    case 'gauge':
      return chart.gauge?.metric;
    case 'radar':
      return chart.radar?.metrics[0];
    case 'parallel':
      return chart.parallel?.metrics[0];
    case 'calendar':
      return chart.calendar?.value;
    case 'map':
      return chart.map?.value;
    case 'themeRiver':
      return chart.themeRiver?.value;
    case 'sunburst':
    case 'tree':
    case 'sankey':
      return chart[CHART_FAMILY[chart.type] as 'sunburst' | 'tree' | 'sankey']
        ?.value;
    default:
      return undefined;
  }
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
 * A cartesian chart draws a list: the lead joins it at the front when it is
 * not already drawn, and is the one series of a pivot. A family with nothing
 * written yet draws every metric, which already includes it.
 */
export function switchChartType(chart: ChartSpec, type: ChartType): ChartSpec {
  const next: ChartSpec = { ...chart, type };
  const lead = leadMetric(chart);
  if (lead === undefined || lead === '' || type === chart.type) return next;
  switch (CHART_FAMILY[type]) {
    case 'cartesian': {
      const spec = chart.cartesian;
      if (!spec) return next;
      if (spec.splitBy !== undefined)
        return {
          ...next,
          cartesian: {
            ...spec,
            series: [{ ...spec.series[0], metric: lead }],
          },
        };
      if (spec.series.some(series => series.metric === lead)) return next;
      return {
        ...next,
        cartesian: { ...spec, series: [{ metric: lead }, ...spec.series] },
      };
    }
    case 'pie':
      return chart.pie
        ? { ...next, pie: { ...chart.pie, value: lead } }
        : { ...next, pie: { category: '', value: lead } };
    case 'heatmap':
      return chart.heatmap
        ? { ...next, heatmap: { ...chart.heatmap, value: lead } }
        : { ...next, heatmap: { x: '', y: '', value: lead } };
    case 'scatter':
      return chart.scatter && chart.scatter.y !== lead
        ? { ...next, scatter: { ...chart.scatter, x: lead } }
        : next;
    case 'funnel':
      return chart.funnel?.stages.from === 'group'
        ? {
            ...next,
            funnel: {
              ...chart.funnel,
              stages: { ...chart.funnel.stages, value: lead },
            },
          }
        : next;
    case 'metric':
      return {
        ...next,
        metric: { ...chart.metric, metric: lead },
      };
    case 'waterfall':
      return {
        ...next,
        waterfall: { x: '', ...chart.waterfall, value: lead },
      };
    case 'treemap':
      return {
        ...next,
        treemap: { category: '', ...chart.treemap, value: lead },
      };
    case 'gauge':
      return { ...next, gauge: { ...chart.gauge, metric: lead } };
    case 'map':
      return { ...next, map: { region: '', ...chart.map, value: lead } };
    case 'calendar':
      return {
        ...next,
        calendar: { date: '', ...chart.calendar, value: lead },
      };
    case 'themeRiver':
      return {
        ...next,
        themeRiver: { x: '', splitBy: '', ...chart.themeRiver, value: lead },
      };
    case 'sunburst':
    case 'tree':
    case 'sankey': {
      const family = CHART_FAMILY[type];
      return {
        ...next,
        [family]: { levels: [], ...chart[family], value: lead },
      };
    }
    case 'radar':
    case 'parallel': {
      // The lead joins the axes at the front, as it joins a cartesian
      // chart's series; a family never visited draws every metric.
      const family = CHART_FAMILY[type];
      const spec = chart[family];
      if (!spec || spec.metrics.includes(lead)) return next;
      return {
        ...next,
        [family]: { ...spec, metrics: [lead, ...spec.metrics] },
      };
    }
    default:
      // A box is five numbers of one field, not one metric carried over.
      return next;
  }
}
