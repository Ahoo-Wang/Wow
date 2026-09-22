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
  AggregationFieldCapability,
  AnalysisCapability,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
  FilterNode,
  FilterTree,
  FilterValue,
} from '../model/index.js';
import { isFilterGroup } from '../filter/index.js';

/**
 * What an analysis may name, and where.
 *
 * Wow's `elements` is one ordered parent-to-child chain, not a list of
 * sibling arrays, and the chain decides the counting unit: without it a
 * record is a root document, with it a record is one entry of the innermost
 * expanded element. Three different domains follow from that, and a merged
 * map of "everything reachable" could answer none of them:
 *
 * - the **root filter** names the definition's own fields, absolutely;
 * - **element `i`'s filter** names the fields that element holds;
 * - a **dimension, a metric, a numeric expression or a metric filter** names
 *   the fields of the innermost expanded element — or the root's, when the
 *   config expands nothing. A root field there is refused by Wow ("requires
 *   its declared element scope"), so it is refused here.
 *
 * Names are the config's spelling throughout: fully qualified from the
 * query-model root (`state.orders.lines.sku`), which is unambiguous to
 * validate and to show. Compilation strips the prefix of the scope a name is
 * sent in, because that is the spelling Wow reads (`relativeFields` and
 * `relativeTree` below).
 */
export interface AnalysisScope {
  /**
   * Fields a dimension, a metric, an expression or a metric filter may name:
   * the innermost expanded element's, or the definition's when the config
   * expands nothing.
   */
  fields: Map<string, FieldDefinition>;
  /** How those same fields may be aggregated. */
  aggregations: Map<string, AggregationFieldCapability>;
  /** Fields the root filter may name — always the definition's own. */
  rootFields: FieldDefinition[];
  /** The expanded chain, outermost first; empty when nothing is expanded. */
  elements: AnalysisScopeElement[];
  /** The chain the capability declares, outermost first, as it writes it. */
  declaredChain: string[];
  /**
   * Every field the view can name somewhere, by its config spelling.
   *
   * Not a domain of its own: it is how a field that exists but sits in the
   * wrong one is told apart from a field that does not exist at all, which
   * are two different findings for the reader.
   */
  reachable: Map<string, FieldDefinition>;
}

/** One expanded level of the chain. */
export interface AnalysisScopeElement {
  /** As the config and Wow write it: relative to the previous element. */
  path: string;
  /** The absolute logical path from the query-model root. */
  absolute: string;
  /** The fields this element holds, under their config spelling. */
  fields: Map<string, FieldDefinition>;
  aggregations: Map<string, AggregationFieldCapability>;
}

export function analysisScope(
  definition: DataViewDefinition,
  capability: AnalysisCapability,
  config?: Pick<AnalysisViewConfig, 'elements'>,
): AnalysisScope {
  const rootFields = definition.fields;
  const root = new Map(rootFields.map(field => [field.name, field]));
  const reachable = new Map(root);
  const declared = capability.elements ?? [];
  const declaredChain = declared.map(element => element.path);

  const elements: AnalysisScopeElement[] = [];
  // The config walks down the declared chain and may stop early, so level `i`
  // is expanded only if every level above it was. A config that names another
  // path at some level does not continue the chain, and the scope ends there;
  // `validateElements` reports which entry broke it.
  let held: readonly FieldDefinition[] = rootFields;
  let absolute = '';
  (config?.elements ?? []).forEach((entry, index) => {
    const step = declared[index];
    if (elements.length !== index || !step || step.path !== entry.path) return;
    const holder = held.find(field => field.name === step.path);
    if (!holder) return;
    absolute = absolute === '' ? step.path : qualify(absolute, step.path);
    held = holder.elements ?? [];

    const fields = new Map(
      held.map(field => {
        const named = { ...field, name: qualify(absolute, field.name) };
        return [named.name, named] as const;
      }),
    );
    const aggregations = new Map(
      step.aggregations.map(aggregation => {
        const named = {
          ...aggregation,
          field: qualify(absolute, aggregation.field),
        };
        return [named.field, named] as const;
      }),
    );
    for (const [name, field] of fields) reachable.set(name, field);
    elements.push({ path: step.path, absolute, fields, aggregations });
  });

  const innermost = innermostElement(elements);
  return {
    fields: innermost ? innermost.fields : root,
    aggregations: innermost
      ? innermost.aggregations
      : new Map(capability.fields.map(entry => [entry.field, entry])),
    rootFields: [...rootFields],
    elements,
    declaredChain,
    reachable,
  };
}

/**
 * The level the counting unit is: the last one expanded, or none at all.
 *
 * `undefined` is the root, and it is the ordinary case — an analysis that
 * expands nothing counts root documents.
 */
export function innermostElement(
  elements: readonly AnalysisScopeElement[],
): AnalysisScopeElement | undefined {
  return elements.length === 0 ? undefined : elements[elements.length - 1];
}

/**
 * An element field's full path, from the scope it sits in and its own name.
 *
 * It always prefixes. It used to leave a name alone when that name already
 * began with the path, which accepted both spellings of the same reference
 * and so papered over a convention that had never been decided — and made
 * `items.sku` mean one thing at the root of an element and another inside a
 * nested object that happened to share the array's name. A declaration names
 * what it holds relative to itself; composing the path is this function's
 * job alone.
 */
export function qualify(path: string, field: string): string {
  return `${path}.${field}`;
}

/**
 * The fields element `i`'s own gate filter may name: the ones that element
 * holds, under the config's spelling.
 *
 * Admission and compilation both need this list and must agree on it — a
 * filter admitted against one set of fields and compiled against another is
 * how a `compileFilter` throw gets past a validator — so the rule is written
 * once, here, rather than spelled out at each end. Compilation then renames
 * them relative to that element, which is how Wow reads them.
 */
export function elementFilterFields(
  scope: AnalysisScope,
  index: number,
): FieldDefinition[] {
  return [...(scope.elements[index]?.fields.values() ?? [])];
}

/**
 * Names that exist somewhere in this view but not in the domain given.
 *
 * A dimension over a root field while the config expands `state.orders` is
 * not a typo — the field is right there in the range panel — so it is
 * reported as standing outside the counting unit rather than as unknown.
 */
export function outOfScopeNames(
  scope: AnalysisScope,
  domain: ReadonlyMap<string, FieldDefinition>,
): Set<string> {
  const outside = new Set<string>();
  for (const name of scope.reachable.keys())
    if (!domain.has(name)) outside.add(name);
  return outside;
}

/**
 * Which finding a name no aggregation capability covers deserves.
 *
 * A name the view can reach somewhere exists — it is in the range panel, or
 * in an outer level of the expansion — and naming it as a dimension or a
 * metric means reaching outside the counting unit, which Wow refuses. A name
 * nothing declares is simply unknown, and the two send a reader to different
 * places.
 */
export function unknownOrOutside(scope: AnalysisScope, field: string): string {
  return scope.reachable.has(field)
    ? 'analysis.field.outside-scope'
    : 'analysis.field.unknown';
}

/** The fields of `domain`, plus the ones outside it, for a filter's admission. */
export function withOutOfScope(
  scope: AnalysisScope,
  domain: ReadonlyMap<string, FieldDefinition>,
): FieldDefinition[] {
  const fields = [...domain.values()];
  for (const [name, field] of scope.reachable)
    if (!domain.has(name)) fields.push(field);
  return fields;
}

/**
 * The prefix a name in `absolute` position loses on its way to Wow.
 *
 * Empty for the root, where the config's spelling is already Wow's.
 */
export function scopePrefix(absolute: string): string {
  return absolute === '' ? '' : `${absolute}.`;
}

/** One name, relative to the scope it is sent in. */
export function relativeName(field: string, prefix: string): string {
  return prefix !== '' && field.startsWith(prefix)
    ? field.slice(prefix.length)
    : field;
}

/** The same fields, named as the scope they are compiled in names them. */
export function relativeFields(
  fields: readonly FieldDefinition[],
  prefix: string,
): FieldDefinition[] {
  if (prefix === '') return [...fields];
  return fields.map(field => ({
    ...field,
    name: relativeName(field.name, prefix),
  }));
}

/**
 * The same tree, with every field name relative to the scope it is compiled
 * in — the leaves' own, and the ones inside a predicate a leaf holds.
 *
 * A predicate's fields are written with the same absolute spelling as the
 * leaf that holds it (`elementFields` composes them from the leaf's field
 * name), so the same prefix comes off both. Nothing else about the tree
 * changes, and a tree at the root passes through untouched.
 */
export function relativeTree(tree: FilterTree, prefix: string): FilterTree {
  if (prefix === '') return tree;
  return {
    ...tree,
    children: tree.children.map(child => relativeNode(child, prefix)),
  };
}

function relativeNode(node: FilterNode, prefix: string): FilterNode {
  if (isFilterGroup(node)) return relativeTree(node, prefix);
  const leaf = { ...node, field: relativeName(node.field, prefix) };
  // A predicate is a tree stored as a leaf's value, which the value type
  // knows only as JSON; the cast is the same one every kind holding a tree
  // makes when it reads its own value back.
  if (isFilterGroup(node.value))
    leaf.value = relativeTree(node.value, prefix) as unknown as FilterValue;
  return leaf;
}
