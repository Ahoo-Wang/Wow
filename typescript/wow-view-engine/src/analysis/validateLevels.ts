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
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { consumesAll, group, measure, type ChartContext } from './chartRefs.js';
import { isAdditiveMetric } from './validateChart.js';

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
