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
  MAX_CHART_LEVELS,
  type AnalysisViewConfig,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { consumesAll, group, measure, type ChartContext } from './chartRefs.js';
import { isAdditiveMetric } from './validateChart.js';
import { isOhlcSet, OHLC_SLOTS } from './candlestick.js';

/**
 * A candle is four numbers of one field over one period (N1): its one
 * dimension is a date bucket, and its four slots the opening value, the
 * highest, the lowest and the closing value of one field under one
 * condition (`isOhlcSet`), each a quantity the result has.
 */
export function candlestickIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.candlestick;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'candlestick'];
  const issues = group(context, spec.x, [...path, 'x']);
  const along = config.groups.find(entry => entry.alias === spec.x);
  if (along && along.type !== 'DATE_HISTOGRAM')
    issues.push(issue('chart.candlestick.needs-date', [...path, 'x']));
  const measured = OHLC_SLOTS.flatMap(slot =>
    measure(context, spec[slot], [...path, slot]),
  );
  issues.push(...measured);
  if (
    measured.length === 0 &&
    !isOhlcSet(spec, alias => context.metrics.get(alias))
  )
    issues.push(issue('chart.candlestick.not-ohlc', path));
  issues.push(...consumesAll(context, [spec.x]));
  return issues;
}

/**
 * A map shades regions: its one dimension names them (`TERMS`), a bucket of
 * a scale names none; its shade a quantity the result has; the map it names,
 * when it names one, is a name.
 */
export function mapIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.map;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'map'];
  const issues = group(context, spec.region, [...path, 'region']);
  const region = config.groups.find(entry => entry.alias === spec.region);
  if (region && region.type !== 'TERMS')
    issues.push(issue('chart.map.needs-region', [...path, 'region']));
  issues.push(...measure(context, spec.value, [...path, 'value']));
  if (
    spec.map !== undefined &&
    (typeof spec.map !== 'string' || spec.map.trim() === '')
  )
    issues.push(issue('chart.map.name-invalid', [...path, 'map']));
  issues.push(...consumesAll(context, [spec.region]));
  return issues;
}

/**
 * A calendar lays out days: its one dimension is a date bucket by day, and
 * its shade a quantity the result has.
 */
export function calendarIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.calendar;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'calendar'];
  const issues = group(context, spec.date, [...path, 'date']);
  const dated = config.groups.find(entry => entry.alias === spec.date);
  if (dated && !(dated.type === 'DATE_HISTOGRAM' && dated.unit === 'DAY'))
    issues.push(issue('chart.calendar.needs-day', [...path, 'date']));
  issues.push(...measure(context, spec.value, [...path, 'value']));
  issues.push(...consumesAll(context, [spec.date]));
  return issues;
}

/**
 * A theme river runs along a date bucket, its streams the values of the
 * other dimension, their widths a metric that adds up — the streams are
 * stacked into one river.
 */
export function themeRiverIssues(
  context: ChartContext,
  config: AnalysisViewConfig,
): Issue[] {
  const spec = context.chart.themeRiver;
  if (!spec) return [];
  const path: IssuePath = [...context.path, 'themeRiver'];
  const issues = [
    ...group(context, spec.x, [...path, 'x']),
    ...group(context, spec.splitBy, [...path, 'splitBy']),
  ];
  const along = config.groups.find(entry => entry.alias === spec.x);
  if (along && along.type !== 'DATE_HISTOGRAM')
    issues.push(issue('chart.themeRiver.needs-date', [...path, 'x']));
  if (spec.x === spec.splitBy)
    issues.push(issue('chart.themeRiver.same-axes', [...path, 'splitBy']));
  const measured = measure(context, spec.value, [...path, 'value']);
  issues.push(...measured);
  if (
    measured.length === 0 &&
    !isAdditiveMetric(context.metrics.get(spec.value))
  )
    issues.push(
      issue('chart.themeRiver.not-additive', [...path, 'value'], {
        metric: spec.value,
      }),
    );
  issues.push(...consumesAll(context, [spec.x, spec.splitBy]));
  return issues;
}

/** Each family's findings of a levelled chart, spelled out for the catalogue. */
const LEVEL_CODES = {
  sunburst: {
    few: 'chart.sunburst.too-few-levels',
    many: 'chart.sunburst.too-many-levels',
    twice: 'chart.sunburst.same-levels',
    adds: 'chart.sunburst.not-additive',
  },
  tree: {
    few: 'chart.tree.too-few-levels',
    many: 'chart.tree.too-many-levels',
    twice: 'chart.tree.same-levels',
    adds: 'chart.tree.not-additive',
  },
  sankey: {
    few: 'chart.sankey.too-few-levels',
    many: 'chart.sankey.too-many-levels',
    twice: 'chart.sankey.same-levels',
    adds: 'chart.sankey.not-additive',
  },
} as const;

/**
 * A sunburst, a tree or a sankey: two to `MAX_CHART_LEVELS` levels, each a
 * dimension the result has, none twice, every dimension one of them; and a
 * size that adds up — a parent is its parts' sum, a node its bands'.
 */
export function levelled(context: ChartContext): Issue[] {
  const family = context.chart.type as 'sunburst' | 'tree' | 'sankey';
  const spec = context.chart[family];
  if (!spec) return [];
  const codes = LEVEL_CODES[family];
  const path: IssuePath = [...context.path, family];
  const issues: Issue[] = [];
  spec.levels.forEach((alias, index) =>
    issues.push(...group(context, alias, [...path, 'levels', index])),
  );
  if (spec.levels.length < 2)
    issues.push(issue(codes.few, [...path, 'levels']));
  if (spec.levels.length > MAX_CHART_LEVELS)
    issues.push(issue(codes.many, [...path, 'levels']));
  if (new Set(spec.levels).size !== spec.levels.length)
    issues.push(issue(codes.twice, [...path, 'levels']));
  const measured = measure(context, spec.value, [...path, 'value']);
  issues.push(...measured);
  if (
    measured.length === 0 &&
    !isAdditiveMetric(context.metrics.get(spec.value))
  )
    issues.push(issue(codes.adds, [...path, 'value'], { metric: spec.value }));
  issues.push(...consumesAll(context, spec.levels));
  return issues;
}
