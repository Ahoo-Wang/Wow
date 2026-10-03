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
  CHART_TYPES,
  DEFAULT_APPROXIMATE_METRICS,
  type AnalysisViewConfig,
  type ChartFamily,
  type ChartSpec,
  type ChartType,
  type Issue,
  type RecordData,
} from '../model/index.js';
import type { ChartData, ShapeContext } from './chart.js';
import type { ChartContext } from './chartRefs.js';
import { shapeBoxplot } from './boxplot.js';
import { shapeCandlestick } from './candlestick.js';
import { shapeCartesian } from './cartesian.js';
import {
  shapeHeatmap,
  shapePie,
  shapeScatter,
  withUnfinished,
} from './chartShapes.js';
import {
  boxplotSlots,
  calendarSlots,
  candlestickSlots,
  cardSlots,
  cartesianSlots,
  funnelSlots,
  gaugeSlots,
  heatmapSlots,
  levelSlots,
  mapSlots,
  pieSlots,
  profileSlots,
  riverSlots,
  scatterSlots,
  treemapSlots,
  waterfallSlots,
  type SlotShape,
} from './familySlots.js';
import { shapeFunnel } from './funnel.js';
import { shapeGauge } from './gauge.js';
import { shapeHierarchy, shapeSankey } from './hierarchy.js';
import { shapeMap } from './map.js';
import { metricCard } from './metricCard.js';
import { shapeParallel, shapeRadar } from './profiles.js';
import { unfinishedBucket } from './timeAxis.js';
import { shapeCalendar, shapeThemeRiver } from './timeCharts.js';
import { shapeTreemap } from './treemap.js';
import {
  boxplotIssues,
  cardIssues,
  cartesianIssues,
  funnelIssues,
  gaugeIssues,
  heatmapIssues,
  pieIssues,
  profileIssues,
  scatterIssues,
  treemapIssues,
  waterfallIssues,
} from './validateFamilies.js';
import {
  calendarIssues,
  candlestickIssues,
  levelled,
  mapIssues,
  themeRiverIssues,
} from './validateLevels.js';
import { shapeWaterfall } from './waterfall.js';

/** The data a family's shaping yields: the members of `ChartData` it draws. */
export type ChartDataOf<F extends ChartFamily> = ChartData extends infer D
  ? D extends { type: infer T }
    ? F extends T
      ? D
      : never
    : never
  : never;

/** What `shapeChart` hands a family's shaping besides its chart. */
export interface ShapeInput {
  config: AnalysisViewConfig;
  rows: readonly RecordData[];
  /** The one row of the ungrouped totals query, when it ran. */
  totals: RecordData | undefined;
  context: ShapeContext;
  /** The engine's zone, or the host's (`ShapeContext.timeZone`). */
  timeZone: string;
}

/**
 * What the analysis kernel does with a chart of one family: everything that
 * used to be a `switch` over the chart's type or family, one row a family
 * (R2-94). What a family *is* — its options pages, its legend and value
 * labels, the shapes it can draw — is the public `CHART_FAMILIES`
 * (`chartFamilies.ts`), a leaf every kernel file may read; this is what the
 * kernel *runs* for it, and it imports each family's code, so it sits above
 * that code and the dispatchers sit above it. `/ui` keeps its own row a
 * family, keyed alike (`FAMILY_VIEWS`, `ui/charts/familyViews.ts`).
 *
 * A family is added here and there, and the compiler names every other
 * place that has to learn it (docs/design/ui/analysis.md「加一个图型族」).
 */
export interface FamilyRules<F extends ChartFamily> {
  /** The metric its first mark measures (`leadMetric`); none while unfilled. */
  lead(chart: ChartSpec): string | undefined;
  /**
   * `next`, the chart switched to a type of this family, with `lead` — what
   * the chart being left measured — carried into its slot
   * (`switchChartType`).
   */
  carry(chart: ChartSpec, next: ChartSpec, lead: string): ChartSpec;
  /** The chart with this family's slots filled from the shape (`fitChartSlots`). */
  fit(chart: ChartSpec, shape: SlotShape): ChartSpec;
  /** Its data shaped from the rows (`shapeChart`); none while unfilled. */
  shape(chart: ChartSpec, input: ShapeInput): ChartDataOf<F> | undefined;
  /**
   * Its rules (`validateChart`), asked once the type is known and the
   * family's sub-object is there.
   */
  validate(context: ChartContext, config: AnalysisViewConfig): Issue[];
  /**
   * The dimension one of its slots is bound to the values of — a map's
   * region, the one a funnel's stages are the ordered values of — which a
   * split must not keep over another field (`splitBy`); none for most.
   */
  boundTo(chart: ChartSpec): string | undefined;
}

/** A family none of whose slots is bound to a dimension's values. */
const UNBOUND = (): undefined => undefined;

/** A box is five numbers of one field, not one metric carried over. */
const KEPT = (_chart: ChartSpec, next: ChartSpec): ChartSpec => next;

/** Every dimension a level, the lead their size. */
function levelsFamily(
  family: 'sunburst' | 'tree' | 'sankey',
): Pick<FamilyRules<'sunburst'>, 'lead' | 'carry' | 'fit'> {
  return {
    lead: chart => chart[family]?.value,
    carry: (chart, next, lead) => ({
      ...next,
      [family]: { levels: [], ...chart[family], value: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      [family]: levelSlots(chart[family], shape),
    }),
  };
}

/** An axis a metric, the lead the first of them. */
function profileFamily(
  family: 'radar' | 'parallel',
): Pick<FamilyRules<'radar'>, 'lead' | 'carry' | 'fit'> {
  return {
    lead: chart => chart[family]?.metrics[0],
    // The lead joins the axes at the front, as it joins a cartesian
    // chart's series; a family never visited draws every metric.
    carry: (chart, next, lead) => {
      const spec = chart[family];
      if (!spec || spec.metrics.includes(lead)) return next;
      return {
        ...next,
        [family]: { ...spec, metrics: [lead, ...spec.metrics] },
      };
    },
    fit: (chart, shape) => ({
      ...chart,
      [family]: profileSlots(chart[family], shape),
    }),
  };
}

/**
 * Every family's rules. The compiler holds it to `ChartFamily`: a family
 * added to the model without a row here does not build.
 */
export const FAMILY_RULES = Object.freeze({
  cartesian: {
    lead: chart => chart.cartesian?.series[0]?.metric,
    carry: (chart, next, lead) => {
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
    },
    fit: (chart, shape) => ({
      ...chart,
      cartesian: cartesianSlots(chart.cartesian, shape, chart.type),
    }),
    shape: (chart, { config, rows, context, timeZone }) =>
      chart.cartesian &&
      withUnfinished(
        shapeCartesian(
          chart.type,
          chart.cartesian,
          config,
          rows,
          timeZone,
          context.cutShort,
          context.splitWhole,
          context.now,
        ),
        data =>
          data.timeline === true
            ? unfinishedBucket(
                config,
                chart.cartesian?.x,
                data.points.map(point => point.x),
                timeZone,
                context.now,
              )
            : undefined,
      ),
    validate: cartesianIssues,
    boundTo: UNBOUND,
  },
  pie: {
    lead: chart => chart.pie?.value,
    carry: (chart, next, lead) =>
      chart.pie
        ? { ...next, pie: { ...chart.pie, value: lead } }
        : { ...next, pie: { category: '', value: lead } },
    fit: (chart, shape) => ({ ...chart, pie: pieSlots(chart.pie, shape) }),
    shape: (chart, { rows }) => chart.pie && shapePie(chart.pie, rows),
    validate: pieIssues,
    boundTo: UNBOUND,
  },
  heatmap: {
    lead: chart => chart.heatmap?.value,
    carry: (chart, next, lead) =>
      chart.heatmap
        ? { ...next, heatmap: { ...chart.heatmap, value: lead } }
        : { ...next, heatmap: { x: '', y: '', value: lead } },
    fit: (chart, shape) => ({
      ...chart,
      heatmap: heatmapSlots(chart.heatmap, shape),
    }),
    shape: (chart, { config, rows, context, timeZone }) =>
      chart.heatmap &&
      shapeHeatmap(chart.heatmap, config, rows, timeZone, context.now),
    validate: heatmapIssues,
    boundTo: UNBOUND,
  },
  scatter: {
    lead: chart => chart.scatter?.x,
    carry: (chart, next, lead) =>
      chart.scatter && chart.scatter.y !== lead
        ? { ...next, scatter: { ...chart.scatter, x: lead } }
        : next,
    fit: (chart, shape) => ({
      ...chart,
      scatter: scatterSlots(chart.scatter, shape),
    }),
    shape: (chart, { rows }) =>
      chart.scatter && shapeScatter(chart.scatter, rows),
    validate: scatterIssues,
    boundTo: UNBOUND,
  },
  funnel: {
    lead: chart =>
      chart.funnel?.stages.from === 'group'
        ? chart.funnel.stages.value
        : chart.funnel?.stages.items[0]?.metric,
    carry: (chart, next, lead) =>
      chart.funnel?.stages.from === 'group'
        ? {
            ...next,
            funnel: {
              ...chart.funnel,
              stages: { ...chart.funnel.stages, value: lead },
            },
          }
        : next,
    fit: (chart, shape) => ({
      ...chart,
      funnel: funnelSlots(chart.funnel, shape),
    }),
    shape: (chart, { rows }) => chart.funnel && shapeFunnel(chart.funnel, rows),
    validate: funnelIssues,
    boundTo: chart =>
      chart.funnel?.stages.from === 'group'
        ? chart.funnel.stages.category
        : undefined,
  },
  metric: {
    lead: chart => chart.metric?.metric,
    carry: (chart, next, lead) => ({
      ...next,
      metric: { ...chart.metric, metric: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      metric: cardSlots(chart.metric, shape),
    }),
    shape: (chart, { config, rows, totals, context, timeZone }) =>
      chart.metric &&
      metricCard(chart.metric, config, rows, totals, {
        timeZone,
        now: context.now,
        cutShort: context.cutShort,
      }),
    validate: cardIssues,
    boundTo: UNBOUND,
  },
  waterfall: {
    lead: chart => chart.waterfall?.value,
    carry: (chart, next, lead) => ({
      ...next,
      waterfall: { x: '', ...chart.waterfall, value: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      waterfall: waterfallSlots(chart.waterfall, shape),
    }),
    shape: (chart, { config, rows }) =>
      chart.waterfall && shapeWaterfall(chart.waterfall, config, rows),
    validate: waterfallIssues,
    boundTo: UNBOUND,
  },
  treemap: {
    lead: chart => chart.treemap?.value,
    carry: (chart, next, lead) => ({
      ...next,
      treemap: { category: '', ...chart.treemap, value: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      treemap: treemapSlots(chart.treemap, shape),
    }),
    shape: (chart, { rows }) =>
      chart.treemap && shapeTreemap(chart.treemap, rows),
    validate: treemapIssues,
    boundTo: UNBOUND,
  },
  boxplot: {
    lead: chart => chart.boxplot?.median,
    carry: KEPT,
    fit: (chart, shape) => ({
      ...chart,
      boxplot: boxplotSlots(chart.boxplot, shape),
    }),
    shape: (chart, { config, rows, context }) =>
      chart.boxplot &&
      shapeBoxplot(
        chart.boxplot,
        config,
        rows,
        (context.approximate ?? DEFAULT_APPROXIMATE_METRICS).includes(
          'PERCENTILE',
        ),
      ),
    validate: boxplotIssues,
    boundTo: UNBOUND,
  },
  candlestick: {
    lead: chart => chart.candlestick?.close,
    carry: KEPT,
    fit: (chart, shape) => ({
      ...chart,
      candlestick: candlestickSlots(chart.candlestick, shape),
    }),
    shape: (chart, { rows }) =>
      chart.candlestick && shapeCandlestick(chart.candlestick, rows),
    validate: candlestickIssues,
    boundTo: UNBOUND,
  },
  gauge: {
    lead: chart => chart.gauge?.metric,
    carry: (chart, next, lead) => ({
      ...next,
      gauge: { ...chart.gauge, metric: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      gauge: gaugeSlots(chart.gauge, shape),
    }),
    shape: (chart, { rows }) => chart.gauge && shapeGauge(chart.gauge, rows),
    validate: gaugeIssues,
    boundTo: UNBOUND,
  },
  radar: {
    ...profileFamily('radar'),
    shape: (chart, { config, rows }) =>
      chart.radar && shapeRadar(chart.radar, config, rows),
    validate: profileIssues,
    boundTo: UNBOUND,
  },
  parallel: {
    ...profileFamily('parallel'),
    shape: (chart, { config, rows }) =>
      chart.parallel && shapeParallel(chart.parallel, config, rows),
    validate: profileIssues,
    boundTo: UNBOUND,
  },
  sunburst: {
    ...levelsFamily('sunburst'),
    shape: (chart, { config, rows }) =>
      chart.sunburst &&
      shapeHierarchy('sunburst', chart.sunburst, config, rows),
    validate: levelled,
    boundTo: UNBOUND,
  },
  tree: {
    ...levelsFamily('tree'),
    shape: (chart, { config, rows }) =>
      chart.tree && shapeHierarchy('tree', chart.tree, config, rows),
    validate: levelled,
    boundTo: UNBOUND,
  },
  sankey: {
    ...levelsFamily('sankey'),
    shape: (chart, { config, rows }) =>
      chart.sankey && shapeSankey(chart.sankey, config, rows),
    validate: levelled,
    boundTo: UNBOUND,
  },
  calendar: {
    lead: chart => chart.calendar?.value,
    carry: (chart, next, lead) => ({
      ...next,
      calendar: { date: '', ...chart.calendar, value: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      calendar: calendarSlots(chart.calendar, shape),
    }),
    shape: (chart, { config, rows, context, timeZone }) =>
      chart.calendar &&
      withUnfinished(
        shapeCalendar(chart.calendar, config, rows, timeZone),
        data =>
          unfinishedBucket(
            config,
            chart.calendar?.date,
            data.days.map(day => day.at),
            timeZone,
            context.now,
          ),
      ),
    validate: calendarIssues,
    boundTo: UNBOUND,
  },
  themeRiver: {
    lead: chart => chart.themeRiver?.value,
    carry: (chart, next, lead) => ({
      ...next,
      themeRiver: { x: '', splitBy: '', ...chart.themeRiver, value: lead },
    }),
    fit: (chart, shape) => ({
      ...chart,
      themeRiver: riverSlots(chart.themeRiver, shape),
    }),
    shape: (chart, { config, rows, context, timeZone }) =>
      chart.themeRiver &&
      withUnfinished(
        shapeThemeRiver(chart.themeRiver, config, rows, timeZone),
        data =>
          unfinishedBucket(
            config,
            chart.themeRiver?.x,
            data.times,
            timeZone,
            context.now,
          ),
      ),
    validate: themeRiverIssues,
    boundTo: UNBOUND,
  },
  map: {
    lead: chart => chart.map?.value,
    carry: (chart, next, lead) => ({
      ...next,
      map: { region: '', ...chart.map, value: lead },
    }),
    fit: (chart, shape) => ({ ...chart, map: mapSlots(chart.map, shape) }),
    shape: (chart, { rows }) => chart.map && shapeMap(chart.map, rows),
    validate: mapIssues,
    boundTo: chart => chart.map?.region,
  },
} satisfies { [F in ChartFamily]: FamilyRules<F> });

/**
 * The rules of the family a chart type belongs to, or none for a type this
 * package does not have — a stored config may name one, and every caller
 * answers it as its `switch` answered an unknown type.
 */
export function familyRules(
  type: ChartType,
): FamilyRules<ChartFamily> | undefined {
  return CHART_TYPES.includes(type)
    ? FAMILY_RULES[CHART_FAMILY[type]]
    : undefined;
}
