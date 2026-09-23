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
  DEFAULT_RUNTIME_LIMITS,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type Issue,
  type RuntimeLimits,
} from '../model/index.js';
import {
  issue,
  validateViewConfigBase,
  type FieldKindRegistry,
} from '../filter/index.js';
import { analysisScope } from './capability.js';
import { chartUnfit } from './fitCharts.js';
import { momentMetrics } from './metricFormat.js';
import { validateChart } from './validateChart.js';
import { validateShape } from './validateShape.js';
import { validateAliases } from './validateAliases.js';
import { validateElements } from './validateElements.js';
import { validateGroups } from './validateGroups.js';
import { validateMetrics } from './validateMetrics.js';
import { validateHaving } from './validateHaving.js';
import { validateSortAndColumns } from './validateSort.js';
import { validateLimits } from './validateLimits.js';

export interface ValidateAnalysisOptions {
  limits?: RuntimeLimits;
}

/**
 * Admits an analysis config against its definition.
 *
 * Every rule here mirrors one the Wow aggregation factories enforce by
 * throwing. Checking them first turns a crash during compilation into a
 * fixable issue on the配置.
 *
 * This file is the order the rule sets run in; each set lives in its own file
 * beside it — the skeleton, the tree budget shared by expressions and having,
 * aliases, the element domain, groups, metrics, having, sort with columns,
 * and the declared limits.
 */
export function validateAnalysis(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  kinds: FieldKindRegistry,
  options: ValidateAnalysisOptions = {},
): Issue[] {
  const limits = options.limits ?? DEFAULT_RUNTIME_LIMITS;
  const capability = definition.analysis;
  if (!capability)
    return [
      issue('analysis.capability.missing', [], { definition: definition.id }),
    ];

  // The rules below index into the config freely, so a wrong skeleton is
  // reported once, here, and nothing else runs over it.
  const shape = validateShape(config);
  if (shape.length > 0) return shape;

  const scope = analysisScope(definition, capability, config);
  // The range is the root filter: it runs before any expansion, so it names
  // the definition's own fields and never an element's. An element's entries
  // are reached from there through an `elementMatch` condition, which is the
  // one shape Wow accepts at the root.
  const issues = validateViewConfigBase(
    scope.rootFields,
    config,
    kinds,
    limits,
  );

  issues.push(...validateElements(config, scope, kinds, limits));
  issues.push(...validateGroups(config, scope, kinds));
  issues.push(...validateMetrics(config, capability, scope, kinds, limits));
  issues.push(...validateAliases(config));
  const moments = momentMetrics(config.metrics, scope.fields);
  issues.push(...validateHaving(config, capability, limits, moments));
  issues.push(...validateSortAndColumns(config));
  issues.push(...validateLimits(config, capability, limits));
  issues.push(...chartFindings(config, moments));
  return issues;
}

/**
 * The chart's findings, which are the chart's alone: how a result is looked
 * at is not the question (D20), so nothing about the chart ever keeps the
 * question from running or from being saved while it is not what is drawn.
 *
 * - Under the table the chart is not asked at all. The table draws any
 *   shape — it is always a way out — and a chart saved beside it is fitted
 *   to the shape the moment the layout turns to it (`setLayout`), so there
 *   is nothing to say about it until then. Before, a third dimension made
 *   the saved bars report `chart.group.unconsumed`, and that blocked the
 *   table's query too.
 * - Under a chart whose type cannot draw the shape at all (`chartUnfit`, the
 *   picker's own reading), the result is drawn as its table, and one note
 *   says so and why: 「回到表格并说一句」. The chart is the author's and
 *   stays; when the shape comes back to one it draws, so does the chart.
 * - Under a chart that can draw the shape, its rules are the chart's
 *   rules (`validateChart`), and one that is set up wrong is refused.
 */
function chartFindings(
  config: AnalysisViewConfig,
  moments: ReadonlySet<string>,
): Issue[] {
  if (config.layout !== 'chart') return [];
  const unfit = chartUnfit(config, moments);
  if (unfit)
    return [
      issue(
        'chart.as-table',
        ['chart', 'type'],
        { type: config.chart.type, reason: unfit },
        'note',
      ),
    ];
  return validateChart(config, moments);
}
