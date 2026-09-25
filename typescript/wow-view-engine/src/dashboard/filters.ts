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

/**
 * A board filter's value (D22 F): the one condition it stands for, how that
 * condition is edited, and what a board's filters hold when nobody has set
 * them. The value is the reader's and never the config's — the config says
 * what it starts at (`DashboardField.default`) and how it may be set.
 */

import {
  filterTypeOf,
  type DashboardField,
  type DashboardFilterType,
  type DashboardFilters,
  type DashboardViewConfig,
  type FieldDefinition,
  type FieldOption,
  type FilterLeaf,
  type FilterOperatorName,
  type FilterTree,
  type FilterValue,
  type Issue,
  type IssuePath,
  type PanelBinding,
  type RuntimeLimits,
} from '../model/index.js';
import {
  isBlankLeafValue,
  isPlainObject,
  isReferenceFilterValue,
  issue,
  validateFilter,
  type EditorDescriptor,
  type FieldKindRegistry,
} from '../filter/index.js';
import { bindingsOf, clicksFilter, isViewPanel, panelsOf } from './panels.js';

/**
 * The operator a filter of each of the six types is asked with: a date
 * filter a window (`BETWEEN`, which reads a relative window, a range, a day
 * or a named period alike), a yes-or-no `EQ`, text, ids and numbers `IN` —
 * one value or several, so the same condition reaches a `string`, an `enum`
 * and a `reference` field without being translated on the way — and a search
 * `SEARCH`, the text a record view's search box looks for. A pre-C board
 * condition is read into defaults by the same table (`migrateDashboardConfig`).
 */
export const FILTER_TYPE_OPERATOR = {
  date: 'BETWEEN',
  text: 'IN',
  id: 'IN',
  number: 'IN',
  boolean: 'EQ',
  search: 'SEARCH',
} as const satisfies Record<DashboardFilterType, FilterOperatorName>;

/**
 * The operator a filter's condition is asked with: its type's
 * (`FILTER_TYPE_OPERATOR`), or — for a kind outside the six — the way its
 * kind asks by default.
 */
export function filterOperatorOf(
  field: Pick<DashboardField, 'kind'>,
  kinds: FieldKindRegistry,
): FilterOperatorName {
  const type = filterTypeOf(field.kind);
  if (type !== null) return FILTER_TYPE_OPERATOR[type];
  return kinds.get(field.kind)?.defaultOperator ?? 'EQ';
}

/** Whether a filter's value says nothing yet: nothing picked, nothing typed. */
export function isBlankFilterValue(
  field: DashboardField,
  value: unknown,
  kinds: FieldKindRegistry,
): boolean {
  if (value === undefined || value === null) return true;
  const kind = kinds.get(field.kind);
  if (!kind) return false;
  return isBlankLeafValue(
    value,
    filterOperatorOf(field, kinds),
    field,
    kind,
    kinds,
  );
}

/**
 * The condition a filter's value stands for, on the filter's own name, or
 * `null` while it is blank — a blank filter narrows nothing.
 */
export function filterCondition(
  field: DashboardField,
  value: FilterValue | undefined,
  kinds: FieldKindRegistry,
): FilterLeaf | null {
  if (value === undefined || isBlankFilterValue(field, value, kinds))
    return null;
  return {
    field: field.name,
    operator: filterOperatorOf(field, kinds),
    value,
  };
}

/**
 * What a board's filters hold before anyone set them: every default, and the
 * time grouping's default unit.
 */
export function defaultFilters(config: DashboardViewConfig): DashboardFilters {
  const values: Record<string, FilterValue> = {};
  for (const field of filtersOf(config))
    if (field.default !== undefined) values[field.name] = field.default;
  const unit = config.timeGrouping?.default;
  return unit === undefined ? { values } : { values, unit };
}

/** The well-formed filters of a config: a stored `fields` is untrusted. */
export function filtersOf(config: DashboardViewConfig): DashboardField[] {
  const fields: unknown = config.fields;
  return Array.isArray(fields)
    ? fields.filter(
        (field): field is DashboardField =>
          isPlainObject(field) && typeof field.name === 'string',
      )
    : [];
}

/**
 * What one value of one filter is refused for, at `path`: a value its kind
 * cannot read, several values on a filter that takes one. Blank is not
 * refused — it is a filter holding nothing.
 */
export function filterValueIssues(
  field: DashboardField,
  value: FilterValue,
  kinds: FieldKindRegistry,
  path: IssuePath,
  limits?: RuntimeLimits,
): Issue[] {
  const leaf = filterCondition(field, value, kinds);
  if (!leaf) return [];
  if (!field.multiple && countOf(value) > 1)
    return [issue('dashboard.field.not-multiple', path, { field: field.name })];
  const tree: FilterTree = { op: 'and', children: [leaf] };
  return validateFilter([field as FieldDefinition], tree, kinds, {
    limits,
  }).map(found => ({
    ...found,
    // The leaf's own path inside the one-leaf tree says nothing here.
    path: [...path, ...found.path.slice(2)],
  }));
}

/** How many values a list, or a reference's items, holds. */
function countOf(value: FilterValue): number {
  if (Array.isArray(value)) return value.length;
  if (isReferenceFilterValue(value)) return value.items.length;
  return 1;
}

/**
 * The filters a board holds as asked — a host's address, a reader's pick —
 * admitted: a value of a filter the board lacks, or one its filter refuses,
 * is left out and said in `refused`; a required filter left blank holds its
 * default, since it never runs empty; a unit the time grouping does not offer
 * is its default. `wanted` left out is every default.
 */
export function admitFilters(
  config: DashboardViewConfig,
  wanted: DashboardFilters | null | undefined,
  kinds: FieldKindRegistry,
): { filters: DashboardFilters; refused: Issue[] } {
  const defaults = defaultFilters(config);
  if (!wanted) return { filters: defaults, refused: [] };
  const refused: Issue[] = [];
  const values: Record<string, FilterValue> = {};
  const byName = new Map(filtersOf(config).map(field => [field.name, field]));
  const asked = isPlainObject(wanted.values) ? wanted.values : {};
  for (const [name, value] of Object.entries(asked)) {
    const field = byName.get(name);
    const path: IssuePath = ['filters', name];
    if (!field) {
      refused.push(issue('dashboard.filter.unknown', path, { field: name }));
      continue;
    }
    const wrong = filterValueIssues(field, value, kinds, path);
    if (wrong.length > 0) refused.push(...wrong);
    else if (!isBlankFilterValue(field, value, kinds)) values[name] = value;
  }
  // Where a pressed value came from (D22 I), for a value that was asked and
  // taken — a default filling in is nobody's press — and a panel whose press
  // still sets that filter. A stale one is let go, not refused: it only
  // said which panel to leave unfiltered.
  const from = pressedFrom(config, byName, wanted.from, values);
  for (const field of byName.values())
    if (field.required && values[field.name] === undefined) {
      const fallback = defaults.values[field.name];
      if (fallback !== undefined) values[field.name] = fallback;
    }
  const held = from ? { values, from } : { values };
  const grouping = config.timeGrouping;
  if (!grouping) return { filters: held, refused };
  const unit = wanted.unit;
  if (unit !== undefined && !grouping.units.includes(unit))
    refused.push(issue('dashboard.grouping.unit-unknown', ['unit'], { unit }));
  const inForce =
    unit !== undefined && grouping.units.includes(unit)
      ? unit
      : grouping.default;
  return { filters: { ...held, unit: inForce }, refused };
}

/**
 * The panels the values held were pressed on, kept for a value that is held
 * and a data panel that can still set that filter — its click sets it, or
 * it is a date filter wired to the panel, which a brush along the panel's
 * time axis sets (D33 Q52); `undefined` for none, so a board nobody pressed
 * on holds no `from` at all.
 */
function pressedFrom(
  config: DashboardViewConfig,
  byName: ReadonlyMap<string, DashboardField>,
  asked: unknown,
  values: Readonly<Record<string, FilterValue>>,
): Record<string, string> | undefined {
  if (!isPlainObject(asked)) return undefined;
  const panels = panelsOf(config);
  const kept: Record<string, string> = {};
  for (const [name, panelId] of Object.entries(asked)) {
    if (typeof panelId !== 'string' || values[name] === undefined) continue;
    const panel = panels.find(
      entry => isViewPanel(entry) && entry.id === panelId,
    );
    if (!panel || !isViewPanel(panel)) continue;
    const field = byName.get(name);
    const brushed =
      field !== undefined &&
      filterTypeOf(field.kind) === 'date' &&
      bindingsOf(panel).some(binding => binding.globalField === name);
    if (clicksFilter(panel, name) || brushed) kept[name] = panelId;
  }
  return Object.keys(kept).length > 0 ? kept : undefined;
}

/**
 * The board's filters as the condition one panel is narrowed by, in its own
 * field names: each filter wired to it (`bindings`) that holds a value,
 * ANDed. A filter the panel is not wired to is left out — it does not reach
 * that panel, which is what the panel then says (「不受此筛选影响」). Among
 * conditions ANDed, leaving one out asks a wider question, never a wrong
 * one, which an arbitrary tree could not promise.
 * `null` when no wired filter holds a value.
 */
export function panelFilterTree(
  config: DashboardViewConfig,
  filters: DashboardFilters,
  bindings: readonly PanelBinding[],
  kinds: FieldKindRegistry,
): FilterTree | null {
  const byName = new Map(filtersOf(config).map(field => [field.name, field]));
  const children: FilterLeaf[] = [];
  const bound = new Set<string>();
  for (const binding of bindings) {
    if (bound.has(binding.globalField)) continue;
    const field = byName.get(binding.globalField);
    if (!field) continue;
    bound.add(binding.globalField);
    const leaf = filterCondition(
      field,
      filters.values[binding.globalField],
      kinds,
    );
    if (leaf) children.push({ ...leaf, field: binding.panelField });
  }
  return children.length > 0 ? { op: 'and', children } : null;
}

/**
 * The control a filter's value is edited with (D22 F: the condition editor's
 * value controls, never new ones): a date filter the date control its kind
 * asks for, a yes-or-no the yes/no select, an id filter ids from the host's
 * source or its list; a text or number filter with a list of its own the
 * select over it, as over the list its wired fields declare (`wired`, an
 * enum's options); otherwise text picked from the wired fields' values or a
 * number — one value or several as the filter
 * takes. A search filter, and one of a kind outside the six, gets what its
 * kind asks for: a search the text it looks for, one line.
 */
export function filterEditor(
  field: DashboardField,
  value: FilterValue | undefined,
  kinds: FieldKindRegistry,
  wired: readonly FieldOption[] | null = null,
): EditorDescriptor {
  const type = filterTypeOf(field.kind);
  const multiple = field.multiple === true;
  if (type === 'boolean') return { input: 'boolean' };
  if (type === 'date' || type === 'search' || type === null) {
    const kind = kinds.get(field.kind);
    const operator = filterOperatorOf(field, kinds);
    return kind
      ? kind.editor(operator, field, value ?? null)
      : { input: 'none' };
  }
  // An id filter's list is the host's whole list, which the id control
  // takes as it is (`FilterValueEditor`'s `options`), keeping a reference's
  // labels with its ids.
  if (type === 'id') return { input: 'remote', multiple, remote: field.remote };
  if (Array.isArray(field.options) || field.kind === 'enum')
    return { input: 'select', multiple, options: field.options ?? [] };
  // No list of its own: the list its wired fields declare, labels shown and
  // codes stored (`wiredOptions`).
  if (wired && wired.length > 0)
    return { input: 'select', multiple, options: [...wired] };
  if (type === 'number') return { input: 'number', multiple };
  return { input: 'text', multiple };
}

/**
 * A stored value as its control holds it: a filter that takes one value is
 * stored as a one-entry list (`IN`, so it reaches any field of its type) and
 * edited as that one value; a blank one starts at what its control starts
 * at. Only a text, id or number filter is stored as a list.
 */
export function filterControlValue(
  field: DashboardField,
  stored: FilterValue | undefined,
): FilterValue {
  const type = filterTypeOf(field.kind);
  if (type === 'id') return stored ?? { items: [] };
  if (type !== 'text' && type !== 'number') return stored ?? null;
  if (field.multiple) return stored ?? [];
  return Array.isArray(stored) ? (stored[0] ?? null) : null;
}

/**
 * What a control handed back is stored as — the mirror of
 * `filterControlValue` — or `null` for nothing picked. A filter that takes
 * one value keeps the last one picked.
 */
export function filterStoredValue(
  field: DashboardField,
  control: FilterValue,
  kinds: FieldKindRegistry,
): FilterValue | null {
  const type = filterTypeOf(field.kind);
  let stored: FilterValue = control;
  if (type === 'id' && isReferenceFilterValue(control)) {
    if (!field.multiple && control.items.length > 1)
      stored = { items: control.items.slice(-1) } as unknown as FilterValue;
  } else if (type === 'text' || type === 'number') {
    if (field.multiple) stored = Array.isArray(control) ? control : [];
    else if (Array.isArray(control)) stored = control.slice(-1);
    else stored = control === null || control === '' ? [] : [control];
  }
  return isBlankFilterValue(field, stored, kinds) ? null : stored;
}
