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
  sameJson,
  type ViewInstance,
  type AnalysisExpression,
  type AnalysisGroup,
  type AnalysisMetric,
  type DataViewConfig,
  type FilterNode,
  type FilterTree,
  type RecordViewConfig,
} from '../model/index.js';
import { durationFrom } from '../filter/index.js';

/**
 * A config with every field it names by an alias renamed to the field's
 * canonical path (#3519): the source answers, projects and reports under
 * the canonical path, so a view has to ask by it to read what comes back.
 * Returns the config itself when it names no alias.
 *
 * What is renamed is what names a root field: conditions (their own and a
 * metric's), the sort, the columns and cards, the summaries, an analysis's
 * dimensions, metrics and expanded paths. A condition inside an element's
 * predicate names the element's own fields and is left as it is.
 */
export function withCanonicalNames<C extends DataViewConfig>(
  config: C,
  renamed: Readonly<Record<string, string>>,
): C {
  if (Object.keys(renamed).length === 0) return config;
  const name = (field: string) => renamed[field] ?? field;
  if (config.kind === 'record') {
    const next = renamedRecord(config, name) as C;
    return sameJson(next, config) ? config : next;
  }
  const base = { ...config, filter: renamedTree(config.filter, name) };
  const next = {
    ...base,
    kind: 'analysis' as const,
    groups: config.groups.map((group): AnalysisGroup =>
      group.field === undefined
        ? {
            ...group,
            expression: renamedExpression(group.expression, name),
          }
        : { ...group, field: name(group.field) },
    ),
    metrics: config.metrics.map(metric => renamedMetric(metric, name)) as [
      AnalysisMetric,
      ...AnalysisMetric[],
    ],
    ...(config.elements
      ? {
          elements: config.elements.map(element => ({
            ...element,
            path: name(element.path),
          })),
        }
      : {}),
  };
  return sameJson(next, config) ? config : next;
}

/**
 * A definition's starting record config (`record.defaults`, a partial one)
 * with every field it names by an alias renamed to the path, as a saved
 * config is read (`withCanonicalNames`): a view created from it asks by the
 * path from the start. Returns the defaults themselves when they name no
 * alias.
 */
export function withCanonicalDefaults(
  defaults: Partial<RecordViewConfig>,
  renamed: Readonly<Record<string, string>>,
): Partial<RecordViewConfig> {
  if (Object.keys(renamed).length === 0) return defaults;
  const next = renamedRecord(defaults, name => renamed[name] ?? name);
  return sameJson(next, defaults) ? defaults : next;
}

/** The record members that name a root field, each renamed where present. */
function renamedRecord<R extends Partial<RecordViewConfig>>(
  config: R,
  name: (field: string) => string,
): R {
  const next: R = { ...config };
  if (config.filter) next.filter = renamedTree(config.filter, name);
  if (config.sort)
    next.sort = config.sort.map(entry => ({
      ...entry,
      field: name(entry.field),
    }));
  if (config.table)
    next.table = {
      ...config.table,
      columns: config.table.columns.map(column => ({
        ...column,
        field: name(column.field),
      })),
    };
  if (config.card)
    next.card = {
      ...config.card,
      title: name(config.card.title),
      fields: config.card.fields.map(name),
      ...(config.card.image === undefined
        ? {}
        : { image: name(config.card.image) }),
    };
  if (config.summaries)
    next.summaries = config.summaries.map(summary => ({
      ...summary,
      field: name(summary.field),
    }));
  return next;
}

function renamedMetric(
  metric: AnalysisMetric,
  name: (field: string) => string,
): AnalysisMetric {
  let next: AnalysisMetric = metric;
  if (metric.type === 'ANY') next = { ...metric, field: name(metric.field) };
  else if (metric.type === 'FIRST' || metric.type === 'LAST')
    next = {
      ...metric,
      field: name(metric.field),
      ...(metric.orderBy === undefined
        ? {}
        : { orderBy: name(metric.orderBy) }),
    };
  else if ('expression' in metric && metric.type !== 'DERIVED')
    next = {
      ...metric,
      expression: renamedExpression(metric.expression, name),
    };
  if ('filter' in next && next.filter)
    next = {
      ...next,
      filter: renamedTree(next.filter, name),
    };
  return next;
}

function renamedExpression(
  expression: AnalysisExpression,
  name: (field: string) => string,
): AnalysisExpression {
  if (expression.type === 'FIELD')
    return { ...expression, field: name(expression.field) };
  if (expression.type === 'BINARY')
    return {
      ...expression,
      left: renamedExpression(expression.left, name),
      right: renamedExpression(expression.right, name),
    };
  if (expression.type === 'DATE_DIFF')
    return {
      ...expression,
      from: name(expression.from),
      to: name(expression.to),
    };
  return expression;
}

function renamedTree(
  tree: FilterTree,
  name: (field: string) => string,
): FilterTree {
  return renamedNode(tree, name) as FilterTree;
}

function renamedNode(
  node: FilterNode,
  name: (field: string) => string,
): FilterNode {
  if ('children' in node && Array.isArray(node.children))
    return {
      ...node,
      children: node.children.map(child => renamedNode(child, name)),
    };
  if ('field' in node && typeof node.field === 'string') {
    const from = durationFrom(node);
    return {
      ...node,
      field: name(node.field),
      ...(from === undefined
        ? {}
        : { value: { ...(node.value as object), from: name(from) } }),
    };
  }
  return node;
}

/**
 * A view's draft, applied config and saved baseline under the paths a
 * narrowing renames aliases to, as a patch; `null` when nothing names an
 * alias. The baseline moves with them, so the view is not left dirty.
 */
export function withCanonicalState<C extends DataViewConfig>(
  state: { draft: C; applied: C; saved: ViewInstance | null },
  renamed: Readonly<Record<string, string>>,
): { draft: C; applied: C; saved?: ViewInstance } | null {
  const { draft, applied, saved } = state;
  const nextDraft = withCanonicalNames(draft, renamed);
  const nextApplied = withCanonicalNames(applied, renamed);
  const savedConfig =
    saved && saved.config.kind !== 'dashboard'
      ? withCanonicalNames(saved.config, renamed)
      : saved?.config;
  if (
    nextDraft === draft &&
    nextApplied === applied &&
    savedConfig === saved?.config
  )
    return null;
  return {
    draft: nextDraft,
    applied: nextApplied,
    ...(saved && savedConfig && savedConfig !== saved.config
      ? { saved: { ...saved, config: savedConfig } }
      : {}),
  };
}
