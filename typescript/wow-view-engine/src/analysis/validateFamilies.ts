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

import type {
  AnalysisViewConfig,
  AxisSpec,
  Issue,
  IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import {
  consumesAll,
  group,
  measure,
  metric,
  type ChartContext,
} from './chartRefs.js';
import { FIVE_NUMBER_SLOTS, isFiveNumberSet } from './boxplot.js';
import { referenceIssues } from './validateReferences.js';
import { isAdditiveMetric, readsOffSums } from './additive.js';

/**
 * The rules of each family the D41 levels and time charts do not cover
 * (those are `validateLevels.ts`): what `validateChart` runs through the
 * family's row of `FAMILY_RULES` (`familyRules.ts`), once the chart names a
 * known type and carries its family's sub-object.
 */

/**
 * A box is five numbers of one field: its lowest, three percentiles rising
 * and its highest, under one condition (`isFiveNumberSet`). Each is a
 * quantity the result has; together they must be such a set, or the box
 * would draw a shape that says nothing.
 */
export function boxplotIssues(context: ChartContext): Issue[] {
  const spec = context.chart.boxplot;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'boxplot'];
  const issues = group(context, spec.category, [...path, 'category']);
  const measured = FIVE_NUMBER_SLOTS.flatMap(slot =>
    measure(context, spec[slot], [...path, slot]),
  );
  issues.push(...measured);
  if (
    measured.length === 0 &&
    !isFiveNumberSet(spec, alias => context.metrics.get(alias))
  )
    issues.push(issue('chart.boxplot.not-five-numbers', path));
  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

/**
 * A gauge is one number on a scale: it groups by nothing, as a card
 * without a trend does, and its scale runs from a lower end to a higher.
 */
export function gaugeIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.gauge;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'gauge'];
  const issues = measure(context, spec.metric, [...path, 'metric']);
  if (config.groups.length > 0)
    issues.push(issue('chart.gauge.needs-no-group', context.path));
  for (const end of ['min', 'max', 'target'] as const) {
    const value = spec[end];
    if (value !== undefined && !Number.isFinite(value))
      issues.push(issue('chart.gauge.not-a-number', [...path, end]));
  }
  if (
    spec.min !== undefined &&
    spec.max !== undefined &&
    Number.isFinite(spec.min) &&
    Number.isFinite(spec.max) &&
    !(spec.min < spec.max)
  )
    issues.push(issue('chart.gauge.empty-scale', [...path, 'max']));
  return issues;
}

/**
 * A radar or parallel axes: one axis per metric, three at least, each a
 * quantity the result has and named once — two axes of one metric are one
 * number drawn twice.
 */
export function profileIssues(context: ChartContext): Issue[] {
  const family = context.chart.type === 'radar' ? 'radar' : 'parallel';
  const spec = context.chart[family];
  if (!spec) return [];
  const path: IssuePath = [...context.path, family];
  const issues = group(context, spec.category, [...path, 'category']);
  spec.metrics.forEach((alias, index) =>
    issues.push(...measure(context, alias, [...path, 'metrics', index])),
  );
  const codes =
    family === 'radar'
      ? {
          few: 'chart.radar.too-few-metrics',
          twice: 'chart.radar.duplicate-metric',
        }
      : {
          few: 'chart.parallel.too-few-metrics',
          twice: 'chart.parallel.duplicate-metric',
        };
  if (spec.metrics.length < 3)
    issues.push(issue(codes.few, [...path, 'metrics']));
  if (new Set(spec.metrics).size !== spec.metrics.length)
    issues.push(issue(codes.twice, [...path, 'metrics']));
  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

export function cartesianIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.cartesian;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'cartesian'];
  const issues = group(context, spec.x, [...path, 'x']);

  if (spec.splitBy !== undefined) {
    issues.push(...group(context, spec.splitBy, [...path, 'splitBy']));
    if (spec.splitBy === spec.x)
      issues.push(issue('chart.splitBy.same-as-x', [...path, 'splitBy']));
    // A pivot turns one metric into a series per value; two would collide.
    // None draws nothing, which is a shape with no quantity to measure.
    if (spec.series.length > 1)
      issues.push(issue('chart.splitBy.needs-one-series', [...path, 'series']));
  }

  spec.series.forEach((series, index) => {
    issues.push(
      ...measure(context, series.metric, [...path, 'series', index, 'metric']),
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

  // A share of a stack is a part of a sum, and only a metric that adds up
  // has one: an average's 「占比」 is no share of anything.
  if (spec.percentStack === true) {
    const alone = spec.series.find(
      series => !isAdditiveMetric(context.metrics.get(series.metric)),
    );
    if (alone)
      issues.push(
        issue(
          'chart.cartesian.percent-not-additive',
          [...path, 'percentStack'],
          {
            metric: alone.metric,
          },
        ),
      );
  }

  issues.push(...referenceIssues(spec, path));
  for (const side of ['left', 'right'] as const)
    issues.push(...axisIssues(spec.yAxis?.[side], [...path, 'yAxis', side]));

  issues.push(
    ...consumesAll(
      context,
      spec.splitBy === undefined ? [spec.x] : [spec.x, spec.splitBy],
    ),
  );
  return issues;
}

export function pieIssues(context: ChartContext): Issue[] {
  const spec = context.chart.pie;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'pie'];
  // A slice is a share of the whole, and the merged 「其他」 the sum of the
  // slices it swallowed: both only mean something for a metric that adds up
  // (D33 Q56, settling Q9).
  const issues = [
    ...group(context, spec.category, [...path, 'category']),
    ...summed(
      context,
      spec.value,
      [...path, 'value'],
      'chart.pie.not-additive',
    ),
  ];

  // A NaN or fractional count would pass `< 2` and then slice nothing,
  // collapsing every category into "other".
  if (
    spec.maxSlices !== undefined &&
    (!Number.isInteger(spec.maxSlices) || spec.maxSlices < 2)
  )
    issues.push(issue('chart.pie.maxSlices-too-small', [...path, 'maxSlices']));

  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

export function heatmapIssues(context: ChartContext): Issue[] {
  const spec = context.chart.heatmap;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'heatmap'];
  const issues = [
    ...group(context, spec.x, [...path, 'x']),
    ...group(context, spec.y, [...path, 'y']),
    ...measure(context, spec.value, [...path, 'value']),
  ];
  if (spec.x === spec.y)
    issues.push(issue('chart.heatmap.same-axes', [...path, 'y']));
  issues.push(...consumesAll(context, [spec.x, spec.y]));
  return issues;
}

export function scatterIssues(context: ChartContext): Issue[] {
  const spec = context.chart.scatter;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'scatter'];
  const issues = [
    ...group(context, spec.category, [...path, 'category']),
    ...measure(context, spec.x, [...path, 'x']),
    ...measure(context, spec.y, [...path, 'y']),
    ...(spec.size === undefined
      ? []
      : measure(context, spec.size, [...path, 'size'])),
  ];
  if (spec.x === spec.y)
    issues.push(issue('chart.scatter.same-metrics', [...path, 'y']));
  issues.push(
    ...axisIssues(spec.xAxis, [...path, 'xAxis']),
    ...axisIssues(spec.yAxis, [...path, 'yAxis']),
  );
  issues.push(...consumesAll(context, [spec.category]));
  return issues;
}

/**
 * A value axis's scale is one this package steps: evenly or by powers of
 * ten (D33 batch E). Whether the values fit a log scale is the result's to
 * say, not the config's — the renderer draws it linear where they do not.
 */
function axisIssues(axis: AxisSpec | undefined, path: IssuePath): Issue[] {
  const scale = axis?.scale;
  return scale === undefined || scale === 'linear' || scale === 'log'
    ? []
    : [issue('chart.axis.scale-unknown', [...path, 'scale'])];
}

/**
 * What a funnel stage measures: a quantity, and one that adds up. A funnel
 * is how many entered and how many remained, and its conversion is one
 * stage over another — which says nothing of an average, a distinct count,
 * a percentile, the smallest, the largest or any one value, and a funnel
 * over a dimension may add later stages into earlier ones (`cumulative`).
 * Same rule as a pie's merged slice and a trend's headline.
 */
function counted(
  context: ChartContext,
  alias: string,
  path: IssuePath,
): Issue[] {
  const measured = measure(context, alias, path);
  if (measured.length > 0) return measured;
  return isAdditiveMetric(context.metrics.get(alias))
    ? []
    : [issue('chart.funnel.not-additive', path, { metric: alias })];
}

/**
 * A waterfall's steps are added up into its running total, and a treemap's
 * tiles and a pie's slices are parts of a whole: each measures only what
 * adds up, as a funnel does (`counted`), and says so in a finding of its own.
 */
function summed(
  context: ChartContext,
  alias: string,
  path: IssuePath,
  code:
    | 'chart.waterfall.not-additive'
    | 'chart.treemap.not-additive'
    | 'chart.pie.not-additive',
): Issue[] {
  const measured = measure(context, alias, path);
  if (measured.length > 0) return measured;
  return isAdditiveMetric(context.metrics.get(alias))
    ? []
    : [issue(code, path, { metric: alias })];
}

export function waterfallIssues(context: ChartContext): Issue[] {
  const spec = context.chart.waterfall;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'waterfall'];
  return [
    ...group(context, spec.x, [...path, 'x']),
    ...summed(
      context,
      spec.value,
      [...path, 'value'],
      'chart.waterfall.not-additive',
    ),
    ...consumesAll(context, [spec.x]),
  ];
}

export function treemapIssues(context: ChartContext): Issue[] {
  const spec = context.chart.treemap;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'treemap'];
  const issues = [
    ...group(context, spec.category, [...path, 'category']),
    ...summed(
      context,
      spec.value,
      [...path, 'value'],
      'chart.treemap.not-additive',
    ),
  ];
  if (spec.parent !== undefined) {
    issues.push(...group(context, spec.parent, [...path, 'parent']));
    if (spec.parent === spec.category)
      issues.push(issue('chart.treemap.same-levels', [...path, 'parent']));
  }
  issues.push(
    ...consumesAll(
      context,
      spec.parent === undefined
        ? [spec.category]
        : [spec.category, spec.parent],
    ),
  );
  return issues;
}

export function funnelIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
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
        ...counted(context, item.metric, [...path, 'items', index, 'metric']),
      ),
    );
    // Stage-per-metric funnels read one row, so a group would multiply it.
    if (config.groups.length > 0)
      issues.push(issue('chart.funnel.metrics-need-no-group', context.path));
    return issues;
  }

  const stages = spec.stages;
  issues.push(...group(context, stages.category, [...path, 'category']));
  // A stage is a step of a process, named: a date bucket or a number band
  // is a scale cut into pieces, and "conversion" from one day to the next
  // is no conversion. Its keys are not text either, and a stage is read
  // back by its name (`stageValues`), so it could not be ordered at all.
  const staged = config.groups.find(group_ => group_.alias === stages.category);
  if (staged && staged.type !== 'TERMS')
    issues.push(
      issue('chart.funnel.stages-need-category', [...path, 'category']),
    );
  issues.push(...counted(context, stages.value, [...path, 'value']));
  if (stages.order.length < 2)
    issues.push(issue('chart.funnel.too-few-stages', [...path, 'order']));
  if (new Set(stages.order).size !== stages.order.length)
    issues.push(issue('chart.funnel.duplicate-stage', [...path, 'order']));
  issues.push(...consumesAll(context, [stages.category]));
  return issues;
}

export function cardIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.metric;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'metric'];
  const issues = metric(context, spec.metric, [...path, 'metric']);

  if (spec.compare)
    issues.push(
      ...measure(context, spec.compare.metric, [...path, 'compare', 'metric']),
    );
  // A moment headline is written out as it is: a comparison, a target and a
  // number format are all about a quantity.
  if (context.moments.has(spec.metric))
    for (const member of ['compare', 'target', 'format'] as const)
      if (spec[member] !== undefined)
        issues.push(
          issue('chart.metric.moment', [...path, member], {
            alias: spec.metric,
          }),
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
    // Named by the dimension it must use, which the analysis has: the one
    // it names instead may be no dimension at all.
    issues.push(
      issue('chart.metric.trend-alias-mismatch', [...path, 'trend', 'x'], {
        alias: dateGroups[0].alias,
      }),
    );

  // Read as the whole, the headline over a trend is the totals row when that
  // query ran, and otherwise the sum of the buckets — which only means
  // something for a metric that adds. Same rule as a pie's merged slice, for
  // the headline and for the value it is compared against. Read as its last
  // period the headline is one bucket, but the rule holds in both readings:
  // the reading is a display switch, and flipping it must never turn a card
  // that ran into one that is refused. A ratio of sums reads off sums in
  // both readings (`readsOffSums`, D38): each bucket its own, the whole its.
  const additive = (alias: string, at: IssuePath): Issue[] =>
    context.metrics.has(alias) &&
    !readsOffSums(context.metrics.get(alias), name => context.metrics.get(name))
      ? [issue('chart.metric.trend-not-additive', at, { metric: alias })]
      : [];
  issues.push(...additive(spec.metric, [...path, 'metric']));
  if (spec.compare)
    issues.push(
      ...additive(spec.compare.metric, [...path, 'compare', 'metric']),
    );
  return issues;
}
