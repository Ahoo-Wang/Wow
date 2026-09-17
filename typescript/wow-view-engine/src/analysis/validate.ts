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
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisHavingExpression,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type Issue,
  type IssuePath,
  type RuntimeLimits,
} from '../model/index.js';
import {
  issue,
  validateFilter,
  validateViewConfigBase,
  type FieldKindRegistry,
} from '../filter/index.js';
import { analysisScope, type AnalysisScope } from './capability.js';
import { validateChart } from './validateChart.js';

/** Wow reserves this prefix and accepts single-segment aliases only. */
const RESERVED_ALIAS_PREFIX = '__wow';

export interface ValidateAnalysisOptions {
  limits?: RuntimeLimits;
}

/**
 * Admits an analysis config against its definition.
 *
 * Every rule here mirrors one the Wow aggregation factories enforce by
 * throwing. Checking them first turns a crash during compilation into a
 * fixable issue on the配置.
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

  const scope = analysisScope(definition, capability, config);
  const issues = validateViewConfigBase(
    [...scope.fields.values()],
    config,
    kinds,
    limits,
  );

  issues.push(...validateElements(config, scope, kinds));
  issues.push(...validateGroups(config, scope));
  issues.push(...validateMetrics(config, capability, scope));
  issues.push(...validateAliases(config));
  issues.push(...validateHaving(config));
  issues.push(...validateSortAndColumns(config));
  issues.push(...validateLimits(config, capability, limits));
  issues.push(...validateChart(config));
  return issues;
}

function aliasesOf(config: AnalysisViewConfig) {
  return {
    groups: new Set(config.groups.map(group => group.alias)),
    metrics: new Set(config.metrics.map(metric => metric.alias)),
    nonAnyMetrics: new Set(
      config.metrics
        .filter(metric => metric.type !== 'ANY')
        .map(metric => metric.alias),
    ),
  };
}

function validateAliases(config: AnalysisViewConfig): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();
  const check = (alias: string, path: IssuePath) => {
    if (alias.includes('.'))
      issues.push(issue('analysis.alias.not-a-segment', path, { alias }));
    if (alias.startsWith(RESERVED_ALIAS_PREFIX))
      issues.push(issue('analysis.alias.reserved', path, { alias }));
    if (seen.has(alias))
      issues.push(issue('analysis.alias.duplicate', path, { alias }));
    seen.add(alias);
  };

  config.groups.forEach((group, index) =>
    check(group.alias, ['groups', index, 'alias']),
  );
  config.metrics.forEach((metric, index) =>
    check(metric.alias, ['metrics', index, 'alias']),
  );
  return issues;
}

function validateElements(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
): Issue[] {
  return (config.elements ?? []).flatMap((element, index) => {
    const path: IssuePath = ['elements', index];
    if (!scope.declaredPaths.has(element.path))
      return [
        issue('analysis.element.undeclared', [...path, 'path'], {
          path: element.path,
        }),
      ];
    if (!element.filter) return [];
    // An element filter is scoped to that element's own fields.
    const fields = [...scope.fields.values()].filter(field =>
      field.name.startsWith(`${element.path}.`),
    );
    return validateFilter(fields, element.filter, kinds).map(found => ({
      ...found,
      path: [...path, 'filter', ...found.path],
    }));
  });
}

function validateGroups(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
): Issue[] {
  return config.groups.flatMap((group, index) => {
    const path: IssuePath = ['groups', index];
    const capability = scope.aggregations.get(group.field);
    if (!capability)
      return [
        issue('analysis.field.unknown', [...path, 'field'], {
          field: group.field,
        }),
      ];
    if (!capability.groups.includes(group.type as never))
      return [
        issue('analysis.group.unsupported', [...path, 'type'], {
          field: group.field,
          type: group.type,
        }),
      ];

    const issues: Issue[] = [];
    if (group.type === 'HISTOGRAM') {
      if (!Number.isFinite(group.interval) || group.interval <= 0)
        issues.push(
          issue('analysis.group.interval-not-positive', [...path, 'interval']),
        );
    }
    if (group.type === 'DATE_HISTOGRAM') {
      if (!capability.dateUnits?.includes(group.unit as never))
        issues.push(
          issue('analysis.group.unit-unsupported', [...path, 'unit'], {
            unit: group.unit,
          }),
        );
      if (group.timeZone !== undefined && group.timeZone.trim() === '')
        issues.push(
          issue('analysis.group.blank-time-zone', [...path, 'timeZone']),
        );
    }
    if (group.type === 'TERMS') {
      if (group.missingKey !== undefined && group.missingKey.trim() === '')
        issues.push(
          issue('analysis.group.blank-missing-key', [...path, 'missingKey']),
        );
    }
    return issues;
  });
}

function expressionIssues(
  expression: AnalysisExpression,
  scope: AnalysisScope,
  path: IssuePath,
  expressionsAllowed: boolean,
): Issue[] {
  // A config arrives from a store, so a metric may carry no expression at all
  // or one of a shape this version does not know. Both are findings, not
  // crashes: the kernel's contract is a definition and a config in, a result
  // or an Issue out.
  if (!isExpression(expression))
    return [issue('analysis.expression.malformed', path)];

  if (expression.type === 'FIELD')
    return scope.aggregations.has(expression.field)
      ? []
      : [
          issue('analysis.field.unknown', [...path, 'field'], {
            field: expression.field,
          }),
        ];

  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];

  const issues: Issue[] = [];
  if (!expressionsAllowed)
    issues.push(issue('analysis.expressions.undeclared', path));
  if (
    expression.operator === 'DIVIDE' &&
    expression.right.type === 'CONSTANT' &&
    expression.right.value === 0
  )
    issues.push(
      issue('analysis.expression.divide-by-zero', [...path, 'right']),
    );

  issues.push(
    ...expressionIssues(
      expression.left,
      scope,
      [...path, 'left'],
      expressionsAllowed,
    ),
    ...expressionIssues(
      expression.right,
      scope,
      [...path, 'right'],
      expressionsAllowed,
    ),
  );
  return issues;
}

/** Whether a value can be read as one of the three expression shapes. */
function isExpression(value: AnalysisExpression | undefined): boolean {
  const type = (value as { type?: unknown } | undefined)?.type;
  return type === 'FIELD' || type === 'CONSTANT' || type === 'BINARY';
}

function derivedIssues(
  expression: AnalysisDerivedExpression,
  available: ReadonlySet<string>,
  path: IssuePath,
): Issue[] {
  if (expression.type === 'METRIC_REF')
    return available.has(expression.metric)
      ? []
      : [
          issue('analysis.derived.unknown-metric', [...path, 'metric'], {
            metric: expression.metric,
          }),
        ];
  if (expression.type === 'CONSTANT')
    return Number.isFinite(expression.value)
      ? []
      : [issue('analysis.constant.not-finite', [...path, 'value'])];
  return [
    ...derivedIssues(expression.left, available, [...path, 'left']),
    ...derivedIssues(expression.right, available, [...path, 'right']),
  ];
}

function validateMetrics(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  scope: AnalysisScope,
): Issue[] {
  const issues: Issue[] = [];
  // DERIVED may only reach metrics declared before it, which rules out both
  // forward references and cycles by construction.
  const earlier = new Set<string>();
  const expressionsAllowed = capability.expressions === true;

  config.metrics.forEach((metric, index) => {
    const path: IssuePath = ['metrics', index];
    const declared = (field: string) => scope.aggregations.get(field);

    switch (metric.type) {
      case 'COUNT':
        if (!capability.count)
          issues.push(issue('analysis.count.undeclared', path));
        break;
      case 'NUMERIC': {
        issues.push(
          ...expressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.functions.includes(metric.function as never))
            issues.push(
              issue('analysis.function.unsupported', [...path, 'function'], {
                field: metric.expression.field,
                fn: metric.function,
              }),
            );
        }
        break;
      }
      case 'ANY': {
        const entry = declared(metric.field);
        if (!entry)
          issues.push(
            issue('analysis.field.unknown', [...path, 'field'], {
              field: metric.field,
            }),
          );
        else if (!entry.any)
          issues.push(
            issue('analysis.any.undeclared', path, { field: metric.field }),
          );
        break;
      }
      case 'DISTINCT_COUNT': {
        issues.push(
          ...expressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.distinctCount)
            issues.push(
              issue('analysis.distinctCount.undeclared', path, {
                field: metric.expression.field,
              }),
            );
        }
        break;
      }
      case 'PERCENTILE': {
        issues.push(
          ...expressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
          ),
        );
        if (metric.expression?.type === 'FIELD') {
          const entry = declared(metric.expression.field);
          if (entry && !entry.percentile)
            issues.push(
              issue('analysis.percentile.undeclared', path, {
                field: metric.expression.field,
              }),
            );
        }
        // Wow accepts the open interval only.
        if (
          !Number.isFinite(metric.percentile) ||
          metric.percentile <= 0 ||
          metric.percentile >= 100
        )
          issues.push(
            issue('analysis.percentile.out-of-range', [...path, 'percentile']),
          );
        break;
      }
      case 'DERIVED': {
        if (!expressionsAllowed)
          issues.push(issue('analysis.expressions.undeclared', path));
        issues.push(
          ...derivedIssues(metric.expression, earlier, [...path, 'expression']),
        );
        break;
      }
    }

    if (metric.type !== 'ANY') earlier.add(metric.alias);
  });

  return issues;
}

function validateHaving(config: AnalysisViewConfig): Issue[] {
  if (!config.having) return [];
  const { nonAnyMetrics } = aliasesOf(config);

  const walk = (
    expression: AnalysisHavingExpression,
    path: IssuePath,
  ): Issue[] => {
    if ('operands' in expression)
      return expression.operands.flatMap((operand, index) =>
        walk(operand, [...path, 'operands', index]),
      );
    return nonAnyMetrics.has(expression.metric)
      ? []
      : [
          issue('analysis.having.unknown-metric', [...path, 'metric'], {
            metric: expression.metric,
          }),
        ];
  };

  return walk(config.having, ['having']);
}

function validateSortAndColumns(config: AnalysisViewConfig): Issue[] {
  const { groups, metrics } = aliasesOf(config);
  const known = new Set([...groups, ...metrics]);
  const issues: Issue[] = [];

  config.sort.forEach((sort, index) => {
    if (!known.has(sort.alias))
      issues.push(
        issue('analysis.sort.unknown-alias', ['sort', index, 'alias'], {
          alias: sort.alias,
        }),
      );
  });

  const seen = new Set<string>();
  config.table.columns.forEach((column, index) => {
    const path: IssuePath = ['table', 'columns', index, 'alias'];
    if (!known.has(column.alias))
      issues.push(
        issue('analysis.column.unknown-alias', path, { alias: column.alias }),
      );
    else if (seen.has(column.alias))
      issues.push(
        issue('analysis.column.duplicate', path, { alias: column.alias }),
      );
    seen.add(column.alias);
  });

  return issues;
}

function validateLimits(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  const declared = capability.limits ?? {};

  if (!Number.isInteger(config.limit) || config.limit < 1)
    issues.push(issue('analysis.limit.not-positive', ['limit']));
  else {
    // The runtime ceiling always applies; a capability may only lower it.
    const max = Math.min(
      declared.maxLimit ?? Number.POSITIVE_INFINITY,
      limits.maxAnalysisRows,
    );
    if (config.limit > max)
      issues.push(issue('analysis.limit.too-large', ['limit'], { max }));
  }

  const count = (
    value: number,
    max: number | undefined,
    code: string,
    path: IssuePath,
  ) => {
    if (max !== undefined && value > max)
      issues.push(issue(code, path, { max }));
  };
  count(config.groups.length, declared.maxGroups, 'analysis.groups.too-many', [
    'groups',
  ]);
  count(
    config.metrics.length,
    declared.maxMetrics,
    'analysis.metrics.too-many',
    ['metrics'],
  );
  count(
    (config.elements ?? []).length,
    declared.maxElements,
    'analysis.elements.too-many',
    ['elements'],
  );

  return issues;
}
