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

import { AGGREGATION_LIMITS } from '@ahoo-wang/fetcher-wow';
import {
  DEFAULT_RUNTIME_LIMITS,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisHavingExpression,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterTree,
  type Issue,
  type IssuePath,
  type RuntimeLimits,
} from '../model/index.js';
import {
  countLeaves,
  issue,
  isBlankLeafValue,
  isFilterLeaf,
  operatorsOf,
  validateFilter,
  validateViewConfigBase,
  walkFilter,
  type FieldKindRegistry,
} from '../filter/index.js';
import {
  analysisScope,
  elementScopeFields,
  type AnalysisScope,
} from './capability.js';
import { validateChart } from './validateChart.js';

/** Wow reserves this prefix and accepts single-segment aliases only. */
const RESERVED_ALIAS_PREFIX = '__wow';

/**
 * One segment of Wow's query-field syntax, which is all an alias may be.
 *
 * `aggregationAlias` runs the alias through the same admission a field path
 * gets and then refuses a dot, so a name like `orders total` or `2024` never
 * reaches the server as an alias — it reaches `aggregation.sum` as a
 * `TypeError`, which is a crash rather than something the editor can point at.
 */
const ALIAS_PATTERN = /^@?[A-Za-z_][A-Za-z0-9_-]*$/;

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

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Whether the config has the skeleton every other rule reads through.
 *
 * A config arrives from a store, so any container may be missing or of the
 * wrong kind — `groups` a string, `chart` absent, an alias a number. The rules
 * index into them without looking (`groups.map`, `alias.includes`,
 * `CHART_FAMILY[chart.type]`), so each wrong piece is named here with its
 * path, and the caller stops before anything walks it. This is the analysis
 * counterpart of the filter budget: the kernel's contract is an Issue out,
 * never a TypeError.
 */
function validateShape(config: AnalysisViewConfig): Issue[] {
  const issues: Issue[] = [];
  const malformed = (path: IssuePath) =>
    issues.push(issue('analysis.config.malformed', path));

  // Every entry of these lists is addressed by alias before it is validated,
  // so the alias must at least be a string.
  const list = (value: unknown, path: IssuePath, aliased: boolean) => {
    if (!Array.isArray(value)) return malformed(path);
    value.forEach((entry, index) => {
      if (!isObject(entry)) malformed([...path, index]);
      else if (aliased && typeof entry.alias !== 'string')
        malformed([...path, index, 'alias']);
    });
  };

  list(config.groups, ['groups'], true);
  list(config.metrics, ['metrics'], true);
  list(config.sort, ['sort'], true);
  if (config.elements !== undefined) list(config.elements, ['elements'], false);
  if (!isObject(config.table)) malformed(['table']);
  else list(config.table.columns, ['table', 'columns'], true);
  if (!isObject(config.chart)) malformed(['chart']);
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
    else if (!ALIAS_PATTERN.test(alias))
      issues.push(issue('analysis.alias.invalid', path, { alias }));
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
  limits: RuntimeLimits,
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
    return queryFilterIssues(
      element.filter,
      elementScopeFields(scope, element.path),
      kinds,
      limits,
      'element',
      [...path, 'filter'],
    );
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
      // A DATE_HISTOGRAM fills in every bucket its range implies rather than
      // only the ones that have rows, and a second dimension would multiply
      // that filling out across each of its own keys. Wow refuses it.
      if (group.dense === true && config.groups.length !== 1)
        issues.push(
          issue('analysis.group.dense-not-alone', [...path, 'dense']),
        );
      // Only the blank check, which is Wow's own. This zone is passed through
      // to the server and never resolved here, so the browser's zone table has
      // no standing over it: a name the backend knows, or a fixed offset, must
      // not be refused because this client's ICU is trimmed or out of date. A
      // filter value is the opposite case — it is resolved here against dayjs,
      // so an unknown zone there is an error.
      if (group.timeZone !== undefined) {
        if (typeof group.timeZone !== 'string')
          issues.push(
            issue('analysis.config.malformed', [...path, 'timeZone']),
          );
        else if (group.timeZone.trim() === '')
          issues.push(
            issue('analysis.group.blank-time-zone', [...path, 'timeZone']),
          );
      }
    }
    if (group.type === 'TERMS') {
      if (group.missingKey !== undefined) {
        if (typeof group.missingKey !== 'string')
          issues.push(
            issue('analysis.config.malformed', [...path, 'missingKey']),
          );
        else if (group.missingKey.trim() === '')
          issues.push(
            issue('analysis.group.blank-missing-key', [...path, 'missingKey']),
          );
      }
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
    expression.right?.type === 'CONSTANT' &&
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

/**
 * The budget of an expression, derived or having tree, checked iteratively
 * before anything recurses into it.
 *
 * These trees arrive from a store like a filter does, and the walks below are
 * plain recursion, so without this a nested `BINARY` chain would exhaust the
 * stack before any rule ran. The filter budget is reused as the ceiling: Wow
 * caps an expression at the same depth 8 and 256 nodes it uses for nothing
 * else, and one pair of limits keeps the caller's override in one place.
 * Like Wow, depth is per tree and the node count is one budget over every
 * tree of one kind, so a config cannot slip past by spreading a large tree
 * across many metrics.
 */
type BudgetOverrun = 'too-deep' | 'too-many-nodes';

const BUDGET_ISSUE_CODES = {
  expression: {
    'too-deep': 'analysis.expression.too-deep',
    'too-many-nodes': 'analysis.expression.too-many-nodes',
  },
  having: {
    'too-deep': 'analysis.having.too-deep',
    'too-many-nodes': 'analysis.having.too-many-nodes',
  },
} as const;

/** One node counter per tree kind, shared across the metrics of a config. */
interface BudgetCounter {
  nodes: number;
}

function checkTreeBudget(
  root: unknown,
  children: (node: unknown) => readonly unknown[],
  limits: RuntimeLimits,
  counted: BudgetCounter,
): BudgetOverrun | undefined {
  const pending: { node: unknown; depth: number }[] = [
    { node: root, depth: 1 },
  ];
  while (pending.length > 0) {
    const { node, depth } = pending.pop() as { node: unknown; depth: number };
    if (depth > limits.maxFilterDepth) return 'too-deep';
    counted.nodes += 1;
    if (counted.nodes > limits.maxFilterNodes) return 'too-many-nodes';
    for (const child of children(node))
      pending.push({ node: child, depth: depth + 1 });
  }
  return undefined;
}

function budgetIssues(
  kind: keyof typeof BUDGET_ISSUE_CODES,
  overrun: BudgetOverrun | undefined,
  path: IssuePath,
  limits: RuntimeLimits,
): Issue[] {
  if (overrun === undefined) return [];
  const max =
    overrun === 'too-deep' ? limits.maxFilterDepth : limits.maxFilterNodes;
  return [issue(BUDGET_ISSUE_CODES[kind][overrun], path, { max })];
}

/** The two operands of a BINARY node; anything else is a leaf, sound or not. */
function binaryChildren(node: unknown): readonly unknown[] {
  const shaped = node as
    { type?: unknown; left?: unknown; right?: unknown } | null | undefined;
  return shaped?.type === 'BINARY' ? [shaped.left, shaped.right] : [];
}

/** The operands of a having group; a non-array is the walk's to report. */
function havingChildren(node: unknown): readonly unknown[] {
  const operands = (node as { operands?: unknown } | null | undefined)
    ?.operands;
  return Array.isArray(operands) ? operands : [];
}

/** `expressionIssues` behind the budget, which decides whether it runs. */
function budgetedExpressionIssues(
  expression: AnalysisExpression,
  scope: AnalysisScope,
  path: IssuePath,
  expressionsAllowed: boolean,
  limits: RuntimeLimits,
  counted: BudgetCounter,
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : expressionIssues(expression, scope, path, expressionsAllowed);
}

/** `derivedIssues` behind the budget, which decides whether it runs. */
function budgetedDerivedIssues(
  expression: AnalysisDerivedExpression | undefined,
  available: ReadonlySet<string>,
  path: IssuePath,
  limits: RuntimeLimits,
  counted: BudgetCounter,
): Issue[] {
  const overrun = checkTreeBudget(expression, binaryChildren, limits, counted);
  return overrun
    ? budgetIssues('expression', overrun, path, limits)
    : derivedIssues(expression, available, path);
}

/** Whether a value can be read as one of the three expression shapes. */
function isExpression(value: AnalysisExpression | undefined): boolean {
  const type = (value as { type?: unknown } | undefined)?.type;
  return type === 'FIELD' || type === 'CONSTANT' || type === 'BINARY';
}

/** The derived counterpart: one side going missing is a finding, not a crash. */
function isDerivedExpression(
  value: AnalysisDerivedExpression | undefined,
): value is AnalysisDerivedExpression {
  const type = (value as { type?: unknown } | undefined)?.type;
  return type === 'METRIC_REF' || type === 'CONSTANT' || type === 'BINARY';
}

function derivedIssues(
  expression: AnalysisDerivedExpression | undefined,
  available: ReadonlySet<string>,
  path: IssuePath,
): Issue[] {
  // A DERIVED expression arrives from a store like any other part of the
  // config, so it is admitted before it is walked.
  if (!isDerivedExpression(expression))
    return [issue('analysis.expression.malformed', path)];

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

/**
 * Admits a metric's own filter, which reaches compilation either way.
 *
 * Two checks, because the filter is wrong in two different ways. It is an
 * ordinary filter over the analysis scope, so it must name fields that exist
 * and hold values its operators can take — nothing was checking that at all.
 * It is also in metric position, where Wow allows less than it does at the
 * root.
 */
/** Where a filter sits, which decides what it may say and how it is reported. */
type QueryFilterPosition = 'metric' | 'element';

const QUERY_FILTER_CODES = {
  metric: {
    empty: 'analysis.metricFilter.empty',
    incomplete: 'analysis.metricFilter.incomplete',
  },
  element: {
    empty: 'analysis.elementFilter.empty',
    incomplete: 'analysis.elementFilter.incomplete',
  },
} as const;

function queryFilterIssues(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
  position: QueryFilterPosition,
  path: IssuePath,
): Issue[] {
  const admitted = validateFilter(fields, tree, kinds, { limits });
  // The budget is there so a tree from a store cannot cost unbounded work.
  // `validateFilter` answers an oversized tree with the budget issue alone, so
  // a second full walk here would spend exactly what the budget refused.
  const issues = admitted.some(found => BUDGET_CODES.includes(found.code))
    ? admitted
    : [...admitted, ...saysNothingIssues(tree, fields, kinds, position)];
  return issues.map(found => ({ ...found, path: [...path, ...found.path] }));
}

/** What `validateFilter` answers with, alone, when a tree is over budget. */
const BUDGET_CODES = ['filter.tree.too-deep', 'filter.tree.too-many-nodes'];

/**
 * Refuses a filter that says nothing.
 *
 * A filter panel is a surface: someone puts a condition there because it is
 * one they reach for often, and leaving it empty is how they say "not right
 * now". So everywhere else an empty condition is unfinished rather than wrong,
 * and `compileFilter` drops it.
 *
 * A filter that is part of the query is not a surface. Neither a metric's own
 * filter nor an element's expansion gate has an editor at all; the choice is
 * between having one and not having one, and both are written as the `filter`
 * property being present or absent. Having written one, a filter that says
 * nothing compiles to `MATCH_ALL` and widens silently — a count meant to be of
 * paid orders returns all of them, an expansion meant to be of shipped lines
 * takes every line. Wrong numbers, no warning. It can say nothing in two ways,
 * so both are refused: a condition with no value, and no conditions at all.
 *
 * A metric filter carries one more restriction, which an element filter does
 * not. It decides, per record, whether that record counts toward this one
 * metric — MongoDB re-expresses it as a `$cond` guard and Elasticsearch as a
 * filter aggregation — so it has one record's value to work with, and a kind
 * that declares itself non-scalar has no such value: it compiles to a
 * condition over the entries of a collection, or to a match across the
 * record's text. The kind answers this rather than a list of ids here, because
 * `withFieldKinds` lets an app replace a built-in kind or register one of its
 * own, and what settles it is the shape a kind compiles to.
 *
 * The metadata kinds stay usable in a metric filter, unlike inside an element
 * predicate where they are refused: an element is not a record and has no id
 * or owner, but a metric filter is looking at a whole record.
 */
function saysNothingIssues(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  position: QueryFilterPosition,
): Issue[] {
  const codes = QUERY_FILTER_CODES[position];
  if (countLeaves(tree) === 0) return [issue(codes.empty, [])];

  const byName = new Map(fields.map(field => [field.name, field]));
  const issues: Issue[] = [];
  for (const { node, path } of walkFilter(tree)) {
    if (!isFilterLeaf(node)) continue;
    // An unknown field, a kind no registry holds and an operator the field
    // does not offer are all `validateFilter`'s to report, not this one's.
    const field = byName.get(node.field);
    if (!field) continue;
    const kind = kinds.get(field.kind);
    if (!kind || !operatorsOf(field, kind).includes(node.operator)) continue;

    if (position === 'metric' && kind.scalar === false)
      issues.push(
        issue('analysis.metricFilter.not-scalar', path, { field: field.name }),
      );
    else if (isBlankLeafValue(node.value, node.operator, field, kind, kinds))
      issues.push(issue(codes.incomplete, path, { field: field.name }));
  }
  return issues;
}

function validateMetrics(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  // Wow refuses `metrics must not be empty.`; an aggregation with nothing to
  // compute is caught here rather than by the server.
  if (config.metrics.length === 0)
    issues.push(issue('analysis.metrics.empty', ['metrics']));

  // DERIVED may only reach metrics declared before it, which rules out both
  // forward references and cycles by construction.
  const earlier = new Set<string>();
  const expressionsAllowed = capability.expressions === true;
  // One node budget over every aggregate expression and another over every
  // derived one, as Wow counts them.
  const expressionNodes: BudgetCounter = { nodes: 0 };
  const derivedNodes: BudgetCounter = { nodes: 0 };

  config.metrics.forEach((metric, index) => {
    const path: IssuePath = ['metrics', index];
    const declared = (field: string) => scope.aggregations.get(field);

    // A metric's own filter was compiled and never admitted, so it could name
    // a field that does not exist and reach `compileFilter`, which answers
    // that by throwing.
    //
    // `DERIVED` is the exception. It carries no filter in the protocol and
    // `compileMetric` never emits one, but a stored config can still hold a
    // stale `filter` from before the metric was switched to `DERIVED`.
    // Refusing it would block a config over a property that changes nothing.
    if (metric.type !== 'DERIVED' && 'filter' in metric && metric.filter)
      issues.push(
        ...queryFilterIssues(
          metric.filter,
          [...scope.fields.values()],
          kinds,
          limits,
          'metric',
          [...path, 'filter'],
        ),
      );

    switch (metric.type) {
      case 'COUNT':
        if (!capability.count)
          issues.push(issue('analysis.count.undeclared', path));
        break;
      case 'NUMERIC': {
        issues.push(
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
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
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
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
          ...budgetedExpressionIssues(
            metric.expression,
            scope,
            [...path, 'expression'],
            expressionsAllowed,
            limits,
            expressionNodes,
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
          ...budgetedDerivedIssues(
            metric.expression,
            earlier,
            [...path, 'expression'],
            limits,
            derivedNodes,
          ),
        );
        break;
      }
      default:
        // A type this version does not know is a finding, not a fall-through:
        // `compileMetric` has no mapping for it and must never be reached.
        issues.push(
          issue('analysis.metric.type-unknown', [...path, 'type'], {
            type: String((metric as { type: unknown }).type),
          }),
        );
    }

    if (metric.type !== 'ANY') earlier.add(metric.alias);
  });

  return issues;
}

function validateHaving(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  limits: RuntimeLimits,
): Issue[] {
  if (!config.having) return [];
  // Having is a declared capability like expressions; an undeclared one is
  // refused before its shape is even walked.
  if (capability.having !== true)
    return [issue('analysis.having.undeclared', ['having'])];
  // Having filters the grouped rows, so it needs rows to filter: an ungrouped
  // aggregation is one row, and Wow refuses a having over it.
  if (config.groups.length === 0)
    return [issue('analysis.having.requires-group', ['having'])];

  // The budget first, iteratively, so the recursive walk below never sees a
  // tree that could exhaust the stack.
  const overrun = checkTreeBudget(config.having, havingChildren, limits, {
    nodes: 0,
  });
  if (overrun) return budgetIssues('having', overrun, ['having'], limits);

  const { nonAnyMetrics } = aliasesOf(config);

  const walk = (
    expression: AnalysisHavingExpression,
    path: IssuePath,
  ): Issue[] => {
    // A having arrives from a store: a number, or a null inside a group's
    // operands, is a finding at its own depth rather than a crash.
    if (typeof expression !== 'object' || expression === null)
      return [issue('analysis.having.malformed', path)];
    if ('operands' in expression) {
      // A group whose operands are not an array cannot be walked; report it
      // rather than crash on a shape this version does not know.
      if (!Array.isArray(expression.operands))
        return [issue('analysis.having.malformed', path)];
      return expression.operands.flatMap((operand, index) =>
        walk(operand, [...path, 'operands', index]),
      );
    }
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

  // One row cannot be ordered: Wow refuses a sort without a groupBy, so a
  // config that carries one is caught here rather than by the server.
  if (config.sort.length > 0 && groups.size === 0)
    issues.push(issue('analysis.sort.requires-group', ['sort']));
  const sorted = new Set<string>();
  config.sort.forEach((sort, index) => {
    const path: IssuePath = ['sort', index, 'alias'];
    if (!known.has(sort.alias))
      issues.push(
        issue('analysis.sort.unknown-alias', path, { alias: sort.alias }),
      );
    // One column cannot be ordered twice; Wow refuses `sort fields must be
    // unique.` and the second entry never had any effect anyway.
    if (sorted.has(sort.alias))
      issues.push(
        issue('analysis.sort.duplicate', path, { alias: sort.alias }),
      );
    sorted.add(sort.alias);
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
      AGGREGATION_LIMITS.MAX_LIMIT,
    );
    if (config.limit > max)
      issues.push(issue('analysis.limit.too-large', ['limit'], { max }));
  }

  // Wow's own sizes always apply: a capability that declares no limits does
  // not lift them, it only means it lowers none of them. Without this the
  // ceiling was the capability's alone, so an undeclared one let a config
  // through to be refused by `aggregation.query()` instead.
  const count = (
    value: number,
    declaredMax: number | undefined,
    ceiling: number,
    code: string,
    path: IssuePath,
  ) => {
    const max = Math.min(declaredMax ?? Number.POSITIVE_INFINITY, ceiling);
    if (value > max) issues.push(issue(code, path, { max }));
  };
  count(
    config.groups.length,
    declared.maxGroups,
    AGGREGATION_LIMITS.MAX_GROUPS,
    'analysis.groups.too-many',
    ['groups'],
  );
  count(
    config.metrics.length,
    declared.maxMetrics,
    AGGREGATION_LIMITS.MAX_METRICS,
    'analysis.metrics.too-many',
    ['metrics'],
  );
  count(
    (config.elements ?? []).length,
    declared.maxElements,
    AGGREGATION_LIMITS.MAX_ELEMENTS,
    'analysis.elements.too-many',
    ['elements'],
  );
  count(
    config.sort.length,
    undefined,
    AGGREGATION_LIMITS.MAX_SORT_FIELDS,
    'analysis.sort.too-many',
    ['sort'],
  );

  return issues;
}
