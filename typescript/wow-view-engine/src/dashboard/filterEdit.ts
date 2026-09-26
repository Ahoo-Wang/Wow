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
 * What setting up a board's filters does to its config (D22 G): add one,
 * name it, give it another type, take it off, say what it starts at, whether
 * it is required, takes several values or picks from a list of its own, put
 * it elsewhere in the bar; the board's time grouping; and its fixed scope
 * taken out whole. Each a pure
 * function from the config to the next, as the other edits of a board are
 * (`edit.ts`), so the runtime applies it to its draft and to what is on
 * screen alike.
 *
 * An edit naming a filter the board lacks hands the same config back.
 */

import {
  DASHBOARD_FILTER_KINDS,
  MAX_DASHBOARD_FILTERS,
  filterTypeOf,
  type DashboardField,
  type DashboardFilterType,
  type DashboardTimeGrouping,
  type DashboardViewConfig,
  type FieldOption,
  type FilterValue,
  without,
} from '../model/index.js';
import { emptyFilter, isPlainObject } from '../filter/index.js';
import { filtersOf as fieldsOf, isOneDayValue } from './filters.js';
import { freshId, isViewPanel } from './panels.js';
import { unbindPanels } from './wiring.js';

/** A filter as it is added: its type and its name on the bar. */
export interface NewFilter {
  type: DashboardFilterType;
  label: string;
}

/**
 * The board with one more filter, last on the bar, and its name — the first
 * `filter-n` not taken — or `null` when the board holds as many as it may.
 * A new filter holds nothing, takes one value, and is wired to no panel yet.
 */
export function addFilter(
  config: DashboardViewConfig,
  filter: NewFilter,
): { config: DashboardViewConfig; name: string } | null {
  const fields = fieldsOf(config);
  if (fields.length >= MAX_DASHBOARD_FILTERS) return null;
  const name = freshId(
    fields.map(field => field.name),
    'filter',
  );
  const field: DashboardField = {
    name,
    label: filter.label.trim(),
    kind: DASHBOARD_FILTER_KINDS[filter.type][0],
  };
  return { config: { ...config, fields: [...fields, field] }, name };
}

/** A filter's name on the bar; a blank one is not taken. */
export function renameFilter(
  config: DashboardViewConfig,
  name: string,
  label: string,
): DashboardViewConfig {
  const next = label.trim();
  if (next.length === 0) return config;
  return mapFilter(config, name, field =>
    field.label === next ? field : { ...field, label: next },
  );
}

/**
 * A filter of another type. What it held and what it started at were of
 * the old type, so both go, and so do its list and its source; its wires go
 * too — each was to a field of the old type. The same type keeps it as it
 * is.
 */
export function retypeFilter(
  config: DashboardViewConfig,
  name: string,
  type: DashboardFilterType,
): DashboardViewConfig {
  const field = fieldsOf(config).find(entry => entry.name === name);
  if (!field || filterTypeOf(field.kind) === type) return config;
  const retyped: DashboardField = {
    name: field.name,
    label: field.label,
    kind: DASHBOARD_FILTER_KINDS[type][0],
    ...(field.multiple &&
    (type === 'text' || type === 'id' || type === 'number')
      ? { multiple: true as const }
      : {}),
  };
  return unwire(
    mapFilter(config, name, () => retyped),
    name,
  );
}

/** The board without a filter, and without every wire to it. */
export function removeFilter(
  config: DashboardViewConfig,
  name: string,
): DashboardViewConfig {
  const fields = fieldsOf(config);
  if (!fields.some(field => field.name === name)) return config;
  return unwire(
    { ...config, fields: fields.filter(field => field.name !== name) },
    name,
  );
}

/** What a filter starts at; `null` takes the default off. */
export function setFilterDefault(
  config: DashboardViewConfig,
  name: string,
  value: FilterValue | null,
): DashboardViewConfig {
  return mapFilter(config, name, field => {
    if (value !== null) return { ...field, default: value };
    return field.default === undefined ? field : without(field, 'default');
  });
}

/** Whether a filter always has a value (and so must have a default). */
export function setFilterRequired(
  config: DashboardViewConfig,
  name: string,
  required: boolean,
): DashboardViewConfig {
  return mapFilter(config, name, field => {
    if (required === (field.required === true)) return field;
    return required ? { ...field, required: true } : without(field, 'required');
  });
}

/**
 * Whether a date filter holds one day and nothing else
 * (`DashboardField.oneDay`). Turned on, a default that is no one day goes,
 * so the filter is not left refusing its own start; a required one then
 * asks for a default, as it does when it has none.
 */
export function setFilterOneDay(
  config: DashboardViewConfig,
  name: string,
  oneDay: boolean,
): DashboardViewConfig {
  return mapFilter(config, name, field => {
    if (oneDay === (field.oneDay === true)) return field;
    if (!oneDay) return without(field, 'oneDay');
    const start = field.default;
    const kept =
      start === undefined || isOneDayValue(start)
        ? field
        : without(field, 'default');
    return { ...kept, oneDay: true };
  });
}

/**
 * Whether a filter takes several values. Turned off, a default holding
 * several keeps its first, so the filter is not left refusing its own start.
 */
export function setFilterMultiple(
  config: DashboardViewConfig,
  name: string,
  multiple: boolean,
): DashboardViewConfig {
  return mapFilter(config, name, field => {
    if (multiple === (field.multiple === true)) return field;
    if (multiple) return { ...field, multiple: true };
    const single = without(field, 'multiple');
    const start: unknown = field.default;
    if (Array.isArray(start) && start.length > 1)
      return { ...single, default: start.slice(0, 1) };
    if (isPlainObject(start) && Array.isArray(start.items))
      return start.items.length > 1
        ? { ...single, default: { items: start.items.slice(0, 1) } }
        : single;
    return single;
  });
}

/**
 * Where a filter's choices come from (D22 G, 「值从哪来」): a list of its
 * own, or — `null` — the values of the fields it is wired to.
 */
export function setFilterOptions(
  config: DashboardViewConfig,
  name: string,
  options: FieldOption[] | null,
): DashboardViewConfig {
  return mapFilter(config, name, field => {
    if (options !== null) return { ...field, options };
    return field.options === undefined ? field : without(field, 'options');
  });
}

/** A filter moved to `index` on the bar. */
export function moveFilter(
  config: DashboardViewConfig,
  name: string,
  index: number,
): DashboardViewConfig {
  const fields = fieldsOf(config);
  const from = fields.findIndex(field => field.name === name);
  const to = Math.max(0, Math.min(fields.length - 1, Math.trunc(index)));
  if (from < 0 || from === to) return config;
  const next = [...fields];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return { ...config, fields: next };
}

/** The board's time grouping set, or taken off with `null`. */
export function setTimeGrouping(
  config: DashboardViewConfig,
  grouping: DashboardTimeGrouping | null,
): DashboardViewConfig {
  if (grouping === null)
    return config.timeGrouping === undefined
      ? config
      : without(config, 'timeGrouping');
  return { ...config, timeGrouping: grouping };
}

/**
 * The board without its fixed scope (D23 Q16, D26 Q31): the condition a
 * board saved before its filters could not hold, taken out whole by its
 * author — never a reader's to take — and the empty tree left in its place,
 * so the member stays and the board is never read as pre-C again. A board
 * whose fixed scope is empty already comes back as it was.
 */
export function removeFixedScope(
  config: DashboardViewConfig,
): DashboardViewConfig {
  const { fixed } = config;
  const empty =
    isPlainObject(fixed) &&
    fixed.op === 'and' &&
    Array.isArray(fixed.children) &&
    fixed.children.length === 0;
  return empty ? config : { ...config, fixed: emptyFilter() };
}

function mapFilter(
  config: DashboardViewConfig,
  name: string,
  change: (field: DashboardField) => DashboardField,
): DashboardViewConfig {
  const field = fieldsOf(config).find(entry => entry.name === name);
  if (!field) return config;
  const next = change(field);
  return next === field
    ? config
    : {
        ...config,
        fields: config.fields.map(entry => (entry === field ? next : entry)),
      };
}

/** Every wire to one filter taken off the panels. */
function unwire(
  config: DashboardViewConfig,
  name: string,
): DashboardViewConfig {
  const ids = config.panels.flatMap(panel =>
    isViewPanel(panel) ? [panel.id] : [],
  );
  return unbindPanels(config, name, ids);
}
