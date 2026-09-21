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
  const issues = validateViewConfigBase(
    [...scope.fields.values()],
    config,
    kinds,
    limits,
  );

  issues.push(...validateElements(config, scope, kinds, limits));
  issues.push(...validateGroups(config, scope));
  issues.push(...validateMetrics(config, capability, scope, kinds, limits));
  issues.push(...validateAliases(config));
  issues.push(...validateHaving(config, capability, limits));
  issues.push(...validateSortAndColumns(config));
  issues.push(...validateLimits(config, capability, limits));
  issues.push(...validateChart(config));
  return issues;
}
