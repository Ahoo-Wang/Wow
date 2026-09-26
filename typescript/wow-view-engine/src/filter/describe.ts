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
  AnalysisDateDiffUnit,
  AnalysisDateUnit,
  FieldKindId,
  FieldDefinition,
  FilterGroupOperator,
  FilterLeaf,
  FilterOperatorName,
  FilterTree,
  IssuePath,
  NumberFormat,
} from '../model/index.js';
import {
  isBlankLeafValue,
  type FieldKind,
  type FieldKindRegistry,
} from './fieldKind.js';
import { isFilterGroup, isFilterNode } from './tree.js';
import type {
  DateTimePreset,
  DurationComparison,
  RelativeDateDirection,
  RelativeDateUnit,
} from './values.js';

/**
 * What a condition compares against, as its kind read it.
 *
 * A closed union, and the reason the summary can be translated at all: the
 * bar used to be handed one English sentence per condition, so the most
 * visible line of the result area was the one line no catalogue could reach.
 * Each variant carries the raw value, plus the labels a kind has already
 * resolved — `/ui` turns it into words with the wording in force.
 */
export type FilterSummaryValue =
  /** The operator is the whole condition: `IS_NULL`, `IS_EMPTY`. */
  | { kind: 'none' }
  /**
   * The kind cannot read this value, so only the field's name is true of it.
   * A stored config outlives the definition that admitted it.
   */
  | { kind: 'blank' }
  /** One value, as the field holds it; `label` when the kind resolved one. */
  | { kind: 'text'; value: string | number | boolean; label?: string }
  /**
   * Several values, and the label the definition gave each one — positional,
   * and `undefined` where it named none.
   *
   * A label is what the definition said this value is called, never a
   * stand-in for the value itself: a label the kind invented by stringifying
   * the value would win over the field's own formatting, and a currency
   * entry would show as a bare number beside a column showing ¥.
   */
  | {
      kind: 'list';
      values: readonly (string | number)[];
      labels?: readonly (string | undefined)[];
    }
  /**
   * Two bounds in the field's own units. Both are required: one bound is not
   * a range, it is the condition that actually compiles — an absolute
   * `BETWEEN` with no upper edge compiles to `GTE`, and it says `GTE`.
   */
  | { kind: 'range'; from: string | number; to: string | number }
  /**
   * A distance from the evaluation moment, and what the condition makes of
   * it: `window` reaches from now to there, `instant` stands on it.
   *
   * The two are different conditions over the same stored value — `BETWEEN`
   * asks for the span, `GTE` and `LTE` compare against its far edge — and
   * saying "in the last 7 days" where "7 days ago" was meant is the summary
   * describing a query that did not run. It is not the operator restated:
   * `direction` already says which side of now, `operator` already says
   * which way the comparison runs, and a kind offering `LT` would leave a
   * `before`/`after` spelling stale on the first use.
   */
  | {
      kind: 'relative';
      amount: number;
      unit: RelativeDateUnit;
      direction: RelativeDateDirection;
      bound: 'window' | 'instant';
    }
  /**
   * A time since another moment (N3): the earlier moment by its label, how
   * the time between compares, the amount and its unit.
   */
  | {
      kind: 'duration';
      from: string;
      comparison: DurationComparison;
      amount: number;
      unit: AnalysisDateDiffUnit;
    }
  /** A named calendar period, resolved at compile time. */
  | { kind: 'preset'; preset: DateTimePreset }
  /**
   * A range that is exactly one period on the calendar of its zone — one
   * day, one month, the seven days from a midnight (`periodOf`) — which is
   * what a group pressed on a date axis opens its records under. It reads as
   * the period, 「2026年9月22日」, the way the bucket was printed where it was
   * pressed, rather than as the two instants bounding it: the last of those
   * is a millisecond before the next midnight, a detail nobody chose.
   */
  | {
      kind: 'period';
      unit: AnalysisDateUnit;
      /** The period's first instant, as the range stores it. */
      from: string;
      /** The zone the period is one on the calendar of. */
      timeZone: string;
    }
  /**
   * A range that is whole periods of one unit, more than one of them — the
   * first one's start to the last one's end, on the calendar of its zone —
   * which is what a brushed stretch of a date axis opens its records under
   * (D33 Q52). It reads as the first and the last period, 「9月1日 ～ 9月3日」,
   * as the buckets were printed where they were brushed.
   */
  | {
      kind: 'periods';
      unit: AnalysisDateUnit;
      /** The first period's first instant, as the range stores it. */
      from: string;
      /** The last period's first instant. */
      last: string;
      timeZone: string;
    }
  /**
   * One segment of a number line, `[from, to)`: the pair `GTE from` and
   * `LT to` on one field, side by side under "all of" — which is what a band
   * of a number histogram opens its records under. It reads as the band,
   * 「单价 在 ¥0～500」, the way the band was printed where it was pressed,
   * rather than as two comparisons: two chips for one question, where the
   * menu and the name of the view opened from it said one sentence. Not a
   * kind's reading — no one leaf is it — so `describeFilter` joins the two
   * items (`FilterSummaryItem.paths`).
   */
  | { kind: 'segment'; from: number; to: number };

/**
 * What a `FieldKind` says about one applied condition.
 *
 * `text` is the English line the kind has always produced, kept because a
 * host may read `FilterSummaryItem.text` directly; the parts beside it are
 * what `/ui` formats the badge from.
 */
export interface FieldKindDescription {
  /** The English reading, for a host that consumes `text` as it stands. */
  text: string;
  /** How the condition reads; the leaf's own operator unless given. */
  operator?: FilterOperatorName;
  /**
   * What the operator means for this kind, where the generic word would say
   * something else. `IN` over an array asks whether the array contains any
   * of the candidates, not whether a value is one of them, and "is any of"
   * reads as the second — so `array` names the relation and the bar words
   * that instead.
   */
  relation?: FilterSummaryRelation;
  value: FilterSummaryValue;
  /** For a predicate-valued kind: the conditions inside it. */
  items?: readonly FilterSummaryItem[];
  /** The operator joining `items`. */
  group?: FilterGroupOperator;
}

/**
 * A relation a kind reads its operator as, where the operator's own word
 * would mislead. `/ui` words these through the catalogue, as it does
 * operators.
 */
export type FilterSummaryRelation = 'has-any' | 'has-none' | 'has-all';

/** One applied condition, for the summary bar above a result. */
export interface FilterSummaryItem {
  /** Location of the node, so the bar can remove or focus it. */
  path: IssuePath;
  /**
   * Every condition the item reads, when it is more than the one at `path`:
   * a segment's two comparisons (`FilterSummaryValue` `segment`), in tree
   * order. Taking the item out of force takes out each of them.
   */
  paths?: readonly IssuePath[];
  /**
   * The condition in English, as the kind reads it out. It is a fallback
   * rather than the summary: `/ui` builds the badge from the parts below, so
   * the bar reads in the language the catalogue is in.
   */
  text: string;
  /** The field or its kind is no longer available, in it or under it. */
  unresolved: boolean;
  /** A condition's field; a group has none. */
  field?: string;
  /** Field label, or the raw name when the field is gone. */
  label?: string;
  /** The field's kind, so the bar shows the value the way the field does. */
  kind?: FieldKindId;
  /** The field's renderer key, which overrides its kind: `cell ?? kind`. */
  cell?: string;
  /** The field's number format, for the same reason. */
  numberFormat?: NumberFormat;
  /**
   * How the condition reads; absent on a group, and on a segment, which is
   * two operators said as one reading.
   */
  operator?: FilterOperatorName;
  /** What that operator means for this kind, when its own word would not. */
  relation?: FilterSummaryRelation;
  /** What the condition compares against; absent on a group. */
  value?: FilterSummaryValue;
  /**
   * The operator joining `items`: a group's own, or the one inside a
   * predicate. An item with no `field` is a group.
   */
  group?: FilterGroupOperator;
  /** The conditions read out inside: a group's children, or a predicate's. */
  items?: readonly FilterSummaryItem[];
}

/** How a group's own operator reads between its conditions. */
export function groupJoinWord(op: FilterGroupOperator): string {
  return op === 'or' ? ' or ' : op === 'nor' ? ' nor ' : ' and ';
}

/**
 * Whether this item is a group rather than one condition.
 *
 * A group names no field. A predicate-valued condition carries `items` and a
 * `group` too — it reads its predicate out — but it is one condition, and the
 * bar draws it as one.
 */
export function isGroupItem(item: FilterSummaryItem): boolean {
  return item.field === undefined && item.items !== undefined;
}

/**
 * The conditions in force, one item per child of the root, so the summary
 * keeps the tree's logic: a group under the root is one item that reads out
 * its own conditions joined by its own operator, with a group inside it in
 * parentheses. Items side by side read as "all of"; a root that is `or` or
 * `nor` therefore folds into one item that says so. Blank conditions never
 * reached the query and are left out; a condition whose field or kind is
 * gone is named rather than hidden.
 */
export function describeFilter(
  fields: readonly FieldDefinition[],
  tree: FilterTree,
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  const byName = new Map(fields.map(field => [field.name, field]));
  const items = describeGroup(tree, [], byName, kinds);
  // Items side by side read as "all of", and one item alone reads the same
  // under `or`; `nor` negates even a lone condition, so it always says so.
  if (tree.op === 'and' || (tree.op === 'or' && items.length < 2)) return items;
  if (items.length === 0) return items;
  return [groupItem(tree.op, [], items)];
}

function describeGroup(
  group: FilterTree,
  path: IssuePath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): FilterSummaryItem[] {
  const items: FilterSummaryItem[] = [];
  group.children.forEach((node, index) => {
    const at: IssuePath = [...path, 'children', index];
    if (!isFilterNode(node)) return;
    if (isFilterGroup(node)) {
      const inner = describeGroup(node, at, byName, kinds);
      if (inner.length > 0) items.push(groupItem(node.op, at, inner));
      return;
    }
    const item = describeCondition(node, at, byName, kinds);
    if (item) items.push(item);
  });
  // Two comparisons are one segment only when both hold: under "any of" or
  // "none of" the pair asks something else.
  return group.op === 'and' ? joinSegments(items) : items;
}

/** The bound one item puts on a number field: `GTE` or `LT` of a number. */
function segmentBound(
  item: FilterSummaryItem,
): { side: 'from' | 'to'; value: number } | undefined {
  if (
    item.kind !== 'number' ||
    item.unresolved ||
    item.items !== undefined ||
    item.relation !== undefined ||
    item.value?.kind !== 'text' ||
    typeof item.value.value !== 'number'
  )
    return undefined;
  if (item.operator === 'GTE') return { side: 'from', value: item.value.value };
  if (item.operator === 'LT') return { side: 'to', value: item.value.value };
  return undefined;
}

/**
 * The items of one "all of" group with each field's `GTE` and `LT` read as
 * one segment, where the field has exactly one of each and they bound
 * something: at the place of the first, the second left out. A field with
 * two lower bounds is not one segment, and neither is an empty one — both
 * are said as the comparisons they are.
 */
function joinSegments(items: FilterSummaryItem[]): FilterSummaryItem[] {
  type Bound = { item: FilterSummaryItem; side: 'from' | 'to'; value: number };
  const byField = new Map<string, Bound[]>();
  for (const item of items) {
    const bound = segmentBound(item);
    if (item.field === undefined || !bound) continue;
    byField.set(item.field, [
      ...(byField.get(item.field) ?? []),
      { item, ...bound },
    ]);
  }
  const joined = new Map<FilterSummaryItem, FilterSummaryItem | null>();
  for (const pair of byField.values()) {
    if (pair.length !== 2 || pair[0].side === pair[1].side) continue;
    const [first, second] = pair;
    const from = first.side === 'from' ? first.value : second.value;
    const to = first.side === 'to' ? first.value : second.value;
    if (!(from < to)) continue;
    joined.set(
      first.item,
      segmentItem(first.item, [first.item.path, second.item.path], from, to),
    );
    joined.set(second.item, null);
  }
  if (joined.size === 0) return items;
  return items.flatMap(item => {
    const said = joined.get(item);
    if (said === undefined) return [item];
    return said === null ? [] : [said];
  });
}

function segmentItem(
  bound: FilterSummaryItem,
  paths: readonly IssuePath[],
  from: number,
  to: number,
): FilterSummaryItem {
  const { path, field, label, kind, cell, numberFormat } = bound;
  return {
    path,
    paths,
    text: `${label ?? field ?? ''} in [${from}, ${to})`,
    unresolved: false,
    ...(field !== undefined ? { field } : {}),
    ...(label !== undefined ? { label } : {}),
    ...(kind !== undefined ? { kind } : {}),
    ...(cell !== undefined ? { cell } : {}),
    ...(numberFormat !== undefined ? { numberFormat } : {}),
    value: { kind: 'segment', from, to },
  };
}

function groupItem(
  op: FilterGroupOperator,
  path: IssuePath,
  inner: readonly FilterSummaryItem[],
): FilterSummaryItem {
  // A group inside a group is parenthesised; a predicate leaf carries a
  // `group` of its own and is a condition, not a nesting of this one.
  const parts = inner.map(item =>
    isGroupItem(item) ? `(${item.text})` : item.text,
  );
  // "A nor B" needs both sides; a lone condition under `nor` is its negation.
  const text =
    op === 'nor' && parts.length === 1
      ? `not ${parts[0]}`
      : parts.join(groupJoinWord(op));
  return {
    path,
    text,
    unresolved: inner.some(item => item.unresolved),
    group: op,
    items: inner,
  };
}

function describeCondition(
  node: FilterLeaf,
  path: IssuePath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry,
): FilterSummaryItem | null {
  const field = byName.get(node.field);
  const kind = field ? kinds.get(field.kind) : undefined;
  // A condition that was never finished did not reach the query, so it is
  // not one of the conditions in force.
  if (
    field &&
    kind &&
    isBlankLeafValue(node.value, node.operator, field, kind, kinds)
  )
    return null;
  const described =
    field && kind
      ? describeLeaf(kind, node, field, kinds, [...byName.values()])
      : undefined;
  if (!field || described === undefined)
    return {
      path,
      field: node.field,
      label: field?.label ?? node.field,
      ...(field ? fieldParts(field) : {}),
      // Nothing is known about the value, but the question still is: the
      // bar names the field and the operator, and says no more.
      operator: node.operator,
      value: { kind: 'blank' },
      text: `${field?.label ?? node.field} ${node.operator}`,
      unresolved: true,
    };
  return {
    path,
    field: field.name,
    label: field.label,
    ...fieldParts(field),
    operator: described.operator ?? node.operator,
    ...(described.relation ? { relation: described.relation } : {}),
    value: described.value,
    ...(described.items ? { items: described.items } : {}),
    ...(described.group ? { group: described.group } : {}),
    text: described.text,
    // A predicate holds conditions of its own, and a field the element
    // definition has since dropped is unreadable there just as it is here.
    // The bar draws one badge for the outer condition, so that is where the
    // mark has to land — the same reckoning `groupItem` does.
    unresolved: described.items?.some(item => item.unresolved) ?? false,
  };
}

/**
 * What the bar needs about the field to show its value the way it does.
 *
 * `cell` travels with `kind` because the display rule is `cell ?? kind`, not
 * `kind`: a field that stores a millisecond instant as a number and declares
 * `cell: 'date'` is a date everywhere it is shown, and a bar that read only
 * the kind put a thirteen-digit number under a column of dates.
 */
function fieldParts(
  field: FieldDefinition,
): Pick<FilterSummaryItem, 'kind' | 'cell' | 'numberFormat'> {
  return {
    kind: field.kind,
    ...(field.cell ? { cell: field.cell } : {}),
    ...(field.numberFormat ? { numberFormat: field.numberFormat } : {}),
  };
}

function describeLeaf(
  kind: FieldKind,
  leaf: FilterLeaf,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  fields: readonly FieldDefinition[],
): FieldKindDescription | undefined {
  try {
    return kind.describe({ leaf, field, kinds, fields });
  } catch {
    return undefined;
  }
}
