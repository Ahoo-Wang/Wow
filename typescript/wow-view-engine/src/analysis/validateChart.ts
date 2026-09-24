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
  modeHsl,
  modeLab,
  modeLch,
  modeOklab,
  modeOklch,
  modeP3,
  modeRgb,
  parse,
  // Not a React hook, whatever the name looks like: it registers a colour
  // space with the parser. Aliased so the hook rules read it as what it is.
  useMode as registerMode,
} from 'culori/fn';
import {
  CHART_FAMILY,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type ChartSpec,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import {
  consumesAll,
  group,
  measure,
  metric,
  type ChartContext,
} from './chartRefs.js';
import { referenceIssues } from './validateReferences.js';

/**
 * Metrics the projection may add up across rows: a pie's "other" slice and a
 * metric card's trend headline are both sums of queried buckets, which only
 * means something for a metric that adds. AVG, MIN, MAX, DISTINCT_COUNT and a
 * percentile cannot be re-aggregated from their parts. A funnel's stages are
 * counts of what entered and remained, so they are these too.
 */
export function isAdditiveMetric(metric: AnalysisMetric | undefined): boolean {
  if (!metric) return false;
  if (metric.type === 'COUNT') return true;
  return metric.type === 'NUMERIC' && metric.function === 'SUM';
}

/**
 * The colour spaces `parse` is taught to read. `culori/fn` is the
 * tree-shakable entry: it ships no mode registered, and a mode's syntaxes
 * only become parseable once it is passed to `useMode`. These seven are
 * every syntax a stylesheet writes a colour in.
 */
registerMode(modeRgb);
registerMode(modeHsl);
registerMode(modeLab);
registerMode(modeLch);
registerMode(modeOklab);
registerMode(modeOklch);
registerMode(modeP3);

/** A theme slot, which is how the palette itself is named. */
const VARIABLE_COLOR = /^var\(--[\w-]+\)$/;

/**
 * Whether a saved colour is one the renderer may pass on. A chart colour
 * reaches the page as a colour — the drawing is handed it made concrete, the
 * legend's dot wears it as a style — so a config from a store gets to name a
 * colour and nothing else. The renderer applies the same predicate and falls
 * back to the palette, so an unvalidated spec draws in a slot rather than in
 * whatever it said.
 *
 * Two shapes pass. A `var(--slot)` reference, which is how this package's own
 * palette is written and which no parser resolves; and anything `culori`
 * parses, which decides validity rather than a character class: a named
 * colour (case-insensitively, as CSS reads them), `#rgb` through `#rrggbbaa`,
 * `rgb()`/`rgba()`, `hsl()`/`hsla()`, `lab()`, `lch()`, `oklab()`, `oklch()`
 * and `color()` over the spaces the registered modes name — `srgb`,
 * `display-p3` and the rest. A spelling outside that set is refused even
 * where it would have painted, `currentcolor` among them; the hand-written
 * regexes this replaced did the opposite, taking `banana`, `rgb(foo)` and
 * `color(nope)` for colours and leaving the series they named unpainted.
 */
export function isChartColor(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const text = value.trim();
  return VARIABLE_COLOR.test(text) || parse(text) !== undefined;
}

/**
 * One finding per colour the theme would refuse to paint with, and one for a
 * `colors` that is no map of them. Only `undefined` means "none pinned": a
 * string, a number, `null` or an array is a config that lost its shape, and
 * reading it as "not provided" hid that.
 */
function colors(chart: ChartSpec, path: IssuePath): Issue[] {
  const colors_ = chart.colors;
  if (colors_ === undefined) return [];
  if (typeof colors_ !== 'object' || colors_ === null || Array.isArray(colors_))
    return [issue('chart.colors.malformed', [...path, 'colors'])];
  return Object.entries(colors_)
    .filter(([, value]) => !isChartColor(value))
    .map(([key]) => issue('chart.colors.invalid', [...path, 'colors', key]));
}

/**
 * Charts reference aliases, so their rules are about what the query actually
 * produces. The strictest one is that a chart must consume every group: an
 * unconsumed dimension leaves several rows per coordinate, and AVG, percentile
 * and DISTINCT_COUNT cannot be re-aggregated over them in the projection.
 *
 * `moments` are the metrics whose value is a moment (`momentMetrics`): a
 * slot a mark measures refuses one (`chart.metric.moment`), and so does a
 * card's comparison, target and number format over one. `validateAnalysis`
 * passes them from the scope; left out, nothing is a moment.
 *
 * These are the rules of a chart that is drawn: `validateAnalysis` asks
 * them only while the layout is the chart and its type can draw the shape
 * (D20; `chartUnfit`). A finding names a group or a metric by its alias, in
 * `alias` or `metric`, because an alias is all this layer has; a surface
 * says it as the column is headed (`analysisIssueNamer` in `/ui`).
 */
export function validateChart(
  config: AnalysisViewConfig,
  moments: ReadonlySet<string> = new Set(),
): Issue[] {
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
    moments,
    chart,
    path,
  };

  return [...colors(chart, path), ...byFamily(context, config)];
}

/** The rules of the family the chart currently is. */
function byFamily(context: ChartContext, config: AnalysisViewConfig): Issue[] {
  switch (context.chart.type) {
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
    case 'waterfall':
      return waterfall(context);
    case 'treemap':
      return treemap(context);
  }
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
    ...measure(context, spec.value, [...path, 'value']),
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
    ...measure(context, spec.value, [...path, 'value']),
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
    ...measure(context, spec.x, [...path, 'x']),
    ...measure(context, spec.y, [...path, 'y']),
    ...(spec.size === undefined
      ? []
      : measure(context, spec.size, [...path, 'size'])),
  ];
  if (spec.x === spec.y)
    issues.push(issue('chart.scatter.same-metrics', [...path, 'y']));
  issues.push(...consumesAll(context, [spec.category]));
  return issues;
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
 * tiles are parts of a whole: both measure only what adds up, as a funnel
 * does (`counted`), and say so in a finding of their own.
 */
function summed(
  context: ChartContext,
  alias: string,
  path: IssuePath,
  code: 'chart.waterfall.not-additive' | 'chart.treemap.not-additive',
): Issue[] {
  const measured = measure(context, alias, path);
  if (measured.length > 0) return measured;
  return isAdditiveMetric(context.metrics.get(alias))
    ? []
    : [issue(code, path, { metric: alias })];
}

function waterfall(context: ChartContext): Issue[] {
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

function treemap(context: ChartContext): Issue[] {
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
  // that ran into one that is refused.
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
