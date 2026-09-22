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
  AnalysisCapability,
  AnalysisElement,
  AnalysisMetric,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
} from '../model/index.js';
import { filterFields } from '../filter/index.js';
import { analysisScope, type AnalysisScope } from './capability.js';
import { firstMetric } from './defaults.js';

/**
 * The config re-scoped to a new expansion chain (D20 屏 G).
 *
 * Expanding an array changes the counting unit: after `orders → lines` the
 * dimensions and the metrics can only name the line's fields, and a
 * dimension over the order's warehouse is a question about the wrong thing
 * (Wow refuses it as outside the element scope). So a step into or out of
 * the chain takes with it every dimension and metric that no longer names
 * a field of the new unit — and a metric's own condition that names one —
 * and, when that leaves nothing to measure, starts the metrics again from
 * the first thing the new unit can count, as a fresh analysis would. What
 * still names a field of the unit is kept as it was.
 *
 * The chart, the sort and the table columns follow the survivors the way
 * they follow any change of groups and metrics (`useAnalysisEditor`'s
 * reshape), so this answers only what is grouped and measured.
 */
export function withElements(
  config: AnalysisViewConfig,
  elements: AnalysisElement[],
  definition: DataViewDefinition,
  capability: AnalysisCapability,
): Pick<AnalysisViewConfig, 'elements' | 'groups' | 'metrics'> {
  const scope = analysisScope(definition, capability, { elements });
  const inScope = (field: string) => scope.fields.has(field);
  const groups = config.groups.filter(group => inScope(group.field));
  const kept = config.metrics.filter(metric =>
    measuresInScope(metric, inScope),
  );
  const metrics = kept.map(metric => {
    if (metric.type === 'DERIVED' || !metric.filter) return metric;
    // A condition naming a field outside the new unit asks about the wrong
    // thing; the metric stays, its condition goes.
    return filterFields(metric.filter).every(inScope)
      ? metric
      : withoutFilter(metric);
  });
  const named = new Set([
    ...groups.map(group => group.alias),
    ...metrics.map(metric => metric.alias),
  ]);
  const derivable = metrics.filter(
    metric =>
      metric.type !== 'DERIVED' ||
      derivedRefs(metric.expression).every(alias => named.has(alias)),
  );
  return {
    elements: elements.length === 0 ? [] : elements,
    groups,
    metrics:
      derivable.length > 0
        ? (derivable as AnalysisViewConfig['metrics'])
        : [firstMetric(capability.count, [...scope.aggregations.values()])],
  };
}

/** The chain one level deeper: the declared next step, with no gate yet. */
export function expanded(
  elements: readonly AnalysisElement[],
  path: string,
): AnalysisElement[] {
  return [...elements, { path }];
}

/** The chain cut at `index`: that level and every level inside it leave. */
export function collapsed(
  elements: readonly AnalysisElement[],
  index: number,
): AnalysisElement[] {
  return elements.slice(0, Math.max(0, index));
}

/**
 * The next level the capability declares beyond what is expanded, or none:
 * the chain is one line, so there is at most one thing to expand into.
 */
export function nextExpansion(
  declaredChain: readonly string[],
  elements: readonly AnalysisElement[],
): string | undefined {
  return declaredChain[elements.length];
}

/**
 * The fields the level at `depth` is chosen from, under their own names:
 * the definition's for the first level, the level outside it for the rest.
 * The array a level expands is one of these, and its label is what the
 * level is called.
 */
function holderFields(
  definition: DataViewDefinition,
  scope: AnalysisScope,
  depth: number,
): readonly FieldDefinition[] {
  if (depth === 0) return definition.fields;
  const outer = scope.elements[depth - 1];
  if (!outer) return [];
  return [...outer.fields.values()].map(field => ({
    ...field,
    name: field.name.slice(outer.absolute.length + 1),
  }));
}

/** What the level at `index` is called: its array's label, else its path. */
export function levelLabel(
  definition: DataViewDefinition,
  scope: AnalysisScope,
  index: number,
): string {
  const level = scope.elements[index];
  if (!level) return '';
  return (
    holderFields(definition, scope, index).find(
      field => field.name === level.path,
    )?.label ?? level.path
  );
}

/** The next step of the declared chain, named, or none. */
export function nextLevel(
  definition: DataViewDefinition,
  scope: AnalysisScope,
  elements: readonly AnalysisElement[],
): { path: string; label: string } | undefined {
  const path = nextExpansion(scope.declaredChain, elements);
  if (path === undefined) return undefined;
  const label =
    holderFields(definition, scope, elements.length).find(
      field => field.name === path,
    )?.label ?? path;
  return { path, label };
}

function measuresInScope(
  metric: AnalysisMetric,
  inScope: (field: string) => boolean,
): boolean {
  switch (metric.type) {
    case 'COUNT':
    case 'DERIVED':
      return true;
    case 'ANY':
      return inScope(metric.field);
    default:
      return expressionFields(metric.expression).every(inScope);
  }
}

function expressionFields(
  expression: Extract<AnalysisMetric, { type: 'NUMERIC' }>['expression'],
): string[] {
  switch (expression.type) {
    case 'FIELD':
      return [expression.field];
    case 'BINARY':
      return [
        ...expressionFields(expression.left),
        ...expressionFields(expression.right),
      ];
    default:
      return [];
  }
}

function derivedRefs(
  expression: Extract<AnalysisMetric, { type: 'DERIVED' }>['expression'],
): string[] {
  switch (expression.type) {
    case 'METRIC_REF':
      return [expression.metric];
    case 'BINARY':
      return [
        ...derivedRefs(expression.left),
        ...derivedRefs(expression.right),
      ];
    default:
      return [];
  }
}

function withoutFilter(metric: AnalysisMetric): AnalysisMetric {
  const next = { ...metric } as AnalysisMetric & { filter?: unknown };
  delete next.filter;
  return next;
}
