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
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/**
 * Metrics the projection may add up across rows: a pie's "other" slice and a
 * metric card's trend headline are both sums of queried buckets, which only
 * means something for a metric that adds. AVG, MIN, MAX, DISTINCT_COUNT and a
 * percentile cannot be re-aggregated from their parts.
 */
export function isAdditiveMetric(metric: AnalysisMetric | undefined): boolean {
  if (!metric) return false;
  if (metric.type === 'COUNT') return true;
  return metric.type === 'NUMERIC' && metric.function === 'SUM';
}

interface ChartContext {
  groups: Set<string>;
  metrics: Map<string, AnalysisMetric>;
  chart: ChartSpec;
  path: IssuePath;
}

/**
 * Charts reference aliases, so their rules are about what the query actually
 * produces. The strictest one is that a chart must consume every group: an
 * unconsumed dimension leaves several rows per coordinate, and AVG, percentile
 * and DISTINCT_COUNT cannot be re-aggregated over them in the projection.
 */
export function validateChart(config: AnalysisViewConfig): Issue[] {
  const path: IssuePath = ['chart'];
  const chart = config.chart;
  // `validateAnalysis` refuses a missing chart before reaching here, but this
  // is exported on its own and a config from a store may have none.
  if (typeof chart !== 'object' || chart === null)
    return [issue('analysis.config.malformed', path)];
  const family = CHART_FAMILY[chart.type];
  if (!family) return [issue('chart.type.unknown', [...path, 'type'])];
  if (chart[family] === undefined)
    return [issue('chart.family.missing', path, { type: chart.type, family })];

  const context: ChartContext = {
    groups: new Set(config.groups.map(group => group.alias)),
    metrics: new Map(config.metrics.map(metric => [metric.alias, metric])),
    chart,
    path,
  };

  switch (chart.type) {
    case 'bar':
    case 'line':
    case 'area':
    case 'combo':
      return cartesian(context, config);
    case 'pie':
      return pie(context);
    case 'heatmap':
      return heatmap(context);
    case 'scatter':
      return scatter(context);
    case 'funnel':
      return funnel(context, config);
    case 'metric':
      return metricCard(context, config);
  }
}

function group(context: ChartContext, alias: string, path: IssuePath): Issue[] {
  return context.groups.has(alias)
    ? []
    : [issue('chart.group.unknown', path, { alias })];
}

function metric(
  context: ChartContext,
  alias: string,
  path: IssuePath,
): Issue[] {
  return context.metrics.has(alias)
    ? []
    : [issue('chart.metric.unknown', path, { alias })];
}

/** Every group alias must appear, or the chart cannot address its own rows. */
function consumesAll(
  context: ChartContext,
  consumed: readonly string[],
): Issue[] {
  const used = new Set(consumed);
  const missing = [...context.groups].filter(alias => !used.has(alias));
  return missing.length === 0
    ? []
    : [
        issue('chart.group.unconsumed', context.path, {
          groups: missing.join(', '),
        }),
      ];
}

function cartesian(context: ChartContext, config: AnalysisViewConfig): Issue[] {
  const spec = context.chart.cartesian;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'cartesian'];
  const issues = group(context, spec.x, [...path, 'x']);

  if (spec.splitBy !== undefined) {
    issues.push(...group(context, spec.splitBy, [...path, 'splitBy']));
    if (spec.splitBy === spec.x)
      issues.push(issue('chart.splitBy.same-as-x', [...path, 'splitBy']));
    // A pivot turns one metric into a series per value; two would collide.
    if (spec.series.length !== 1)
      issues.push(issue('chart.splitBy.needs-one-series', [...path, 'series']));
  }

  spec.series.forEach((series, index) => {
    issues.push(
      ...metric(context, series.metric, [...path, 'series', index, 'metric']),
    );
    if (config.chart.type === 'combo' && series.type === undefined)
      issues.push(
        issue('chart.combo.series-type-missing', [
          ...path,
          'series',
          index,
          'type',
        ]),
      );
  });

  const axes = new Set(spec.series.map(series => series.axis ?? 'left'));
  (spec.referenceLines ?? []).forEach((line, index) => {
    if (!axes.has(line.axis))
      issues.push(
        issue('chart.referenceLine.empty-axis', [
          ...path,
          'referenceLines',
          index,
          'axis',
        ]),
      );
  });

  issues.push(
    ...consumesAll(
      context,
      spec.splitBy === undefined ? [spec.x] : [spec.x, spec.splitBy],
    ),
  );
  return issues;
}

function pie(context: ChartContext): Issue[] {
  const spec = context.chart.pie;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'pie'];
  const issues = [
    ...group(context, spec.category, [...path, 'category']),
    ...metric(context, spec.value, [...path, 'value']),
  ];

  if (spec.maxSlices !== undefined) {
    // A NaN or fractional count would pass `< 2` and then slice nothing,
    // collapsing every category into "other".
    if (!Number.isInteger(spec.maxSlices) || spec.maxSlices < 2)
      issues.push(
        issue('chart.pie.maxSlices-too-small', [...path, 'maxSlices']),
      );
    // The merged slice is the sum of the remainder, which only works for a
    // metric that adds up.
    if (!isAdditiveMetric(context.metrics.get(spec.value)))
      issues.push(
        issue('chart.pie.maxSlices-not-additive', [...path, 'maxSlices'], {
          metric: spec.value,
        }),
      );
  }

  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

function heatmap(context: ChartContext): Issue[] {
  const spec = context.chart.heatmap;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'heatmap'];
  const issues = [
    ...group(context, spec.x, [...path, 'x']),
    ...group(context, spec.y, [...path, 'y']),
    ...metric(context, spec.value, [...path, 'value']),
  ];
  if (spec.x === spec.y)
    issues.push(issue('chart.heatmap.same-axes', [...path, 'y']));
  issues.push(...consumesAll(context, [spec.x, spec.y]));
  return issues;
}

function scatter(context: ChartContext): Issue[] {
  const spec = context.chart.scatter;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'scatter'];
  const issues = [
    ...group(context, spec.category, [...path, 'category']),
    ...metric(context, spec.x, [...path, 'x']),
    ...metric(context, spec.y, [...path, 'y']),
    ...(spec.size === undefined
      ? []
      : metric(context, spec.size, [...path, 'size'])),
  ];
  if (spec.x === spec.y)
    issues.push(issue('chart.scatter.same-metrics', [...path, 'y']));
  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

function funnel(context: ChartContext, config: AnalysisViewConfig): Issue[] {
  const spec = context.chart.funnel;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'funnel', 'stages'];
  const issues: Issue[] = [];

  if (spec.stages.from === 'metrics') {
    const items = spec.stages.items;
    if (items.length < 2)
      issues.push(issue('chart.funnel.too-few-stages', path));
    items.forEach((item, index) =>
      issues.push(
        ...metric(context, item.metric, [...path, 'items', index, 'metric']),
      ),
    );
    // Stage-per-metric funnels read one row, so a group would multiply it.
    if (config.groups.length > 0)
      issues.push(issue('chart.funnel.metrics-need-no-group', context.path));
    return issues;
  }

  const stages = spec.stages;
  issues.push(...group(context, stages.category, [...path, 'category']));
  issues.push(...metric(context, stages.value, [...path, 'value']));
  if (stages.order.length < 2)
    issues.push(issue('chart.funnel.too-few-stages', [...path, 'order']));
  if (new Set(stages.order).size !== stages.order.length)
    issues.push(issue('chart.funnel.duplicate-stage', [...path, 'order']));
  issues.push(...consumesAll(context, [stages.category]));
  return issues;
}

function metricCard(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.metric;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'metric'];
  const issues = metric(context, spec.metric, [...path, 'metric']);

  if (spec.compare)
    issues.push(
      ...metric(context, spec.compare.metric, [...path, 'compare', 'metric']),
    );

  if (spec.trend === undefined) {
    if (config.groups.length > 0)
      issues.push(issue('chart.metric.needs-no-group', context.path));
    return issues;
  }

  // A sparkline needs exactly the time dimension it draws, and nothing else.
  const dateGroups = config.groups.filter(
    group_ => group_.type === 'DATE_HISTOGRAM',
  );
  if (config.groups.length !== 1 || dateGroups.length !== 1)
    issues.push(issue('chart.metric.trend-needs-one-date-group', context.path));
  else if (dateGroups[0].alias !== spec.trend.x)
    issues.push(
      issue('chart.metric.trend-alias-mismatch', [...path, 'trend', 'x'], {
        alias: spec.trend.x,
      }),
    );

  // The headline over a trend is the totals row when that query ran, and
  // otherwise the sum of the buckets — which only means something for a
  // metric that adds. Same rule as a pie's merged slice, for the headline and
  // for the value it is compared against.
  const additive = (alias: string, at: IssuePath): Issue[] =>
    context.metrics.has(alias) && !isAdditiveMetric(context.metrics.get(alias))
      ? [issue('chart.metric.trend-not-additive', at, { metric: alias })]
      : [];
  issues.push(...additive(spec.metric, [...path, 'metric']));
  if (spec.compare)
    issues.push(
      ...additive(spec.compare.metric, [...path, 'compare', 'metric']),
    );
  return issues;
}
