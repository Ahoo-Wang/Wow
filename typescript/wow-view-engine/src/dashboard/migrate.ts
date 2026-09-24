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
  DASHBOARD_GRID_COLUMNS,
  filterTypeOf,
  LEGACY_GRID_COLUMNS,
  type DashboardField,
  type DashboardViewConfig,
  type FilterNode,
  type FilterValue,
} from '../model/index.js';
import { emptyFilter, isFilterGroup, isPlainObject } from '../filter/index.js';
import { FILTER_TYPE_OPERATOR } from './filters.js';

const SCALE = DASHBOARD_GRID_COLUMNS / LEGACY_GRID_COLUMNS;

/**
 * A stored config read into the form this engine writes: onto the 24-column
 * grid (D22 E), and a pre-C board condition into its filters' defaults and
 * the board's fixed scope (D23 Q16, D26 Q31; `intoDefaults` below). Each
 * step is marked by the config itself — `columns`, `fixed` — so it happens
 * once: a second read, or a read after any edit, leaves the config alone.
 *
 * A config that does not say which grid it is in (`columns`) was written for
 * the twelve-column grid: every `x` and `w` is doubled and every `y` and `h`
 * kept, which puts each panel on exactly the pixels it had — a column of the
 * new grid and the gap after it are half of one of the old, so a panel
 * twice as many columns wide spans the same width. It gains `columns` and an
 * empty `tabs`, so a save writes the new form and the next read leaves it
 * alone.
 *
 * A config that says anything about `columns` is not guessed at: one naming
 * another grid is left for admission to refuse (`dashboard.grid.unsupported`).
 * A config already in this form comes back as it is (the same object, so
 * nothing downstream sees a change). Nothing here throws on a malformed
 * config either — whatever is not a panel with numbers, or a tree, is
 * carried over untouched and reported where admission reports it.
 *
 * It is called at one place, the engine's read boundary (`readStored`,
 * `runtime/storedViews.ts`), so everything else reads the new form only.
 */
export function migrateDashboardConfig(
  config: DashboardViewConfig,
): DashboardViewConfig {
  return intoDefaults(onTheWideGrid(config));
}

function onTheWideGrid(config: DashboardViewConfig): DashboardViewConfig {
  const stored: unknown = config;
  if (!isPlainObject(stored) || 'columns' in stored) return config;
  const panels: unknown = stored.panels;
  return {
    ...(stored as unknown as DashboardViewConfig),
    columns: DASHBOARD_GRID_COLUMNS,
    tabs: [],
    panels: (Array.isArray(panels)
      ? panels.map(widened)
      : panels) as DashboardViewConfig['panels'],
  };
}

/**
 * A board condition written before batch C, read as the filters' defaults
 * where it says what a filter would, and as the board's fixed scope where
 * it does not (D23 Q16, D26 Q31; AGENTS.md's stored-data exception).
 *
 * Before C a board's global filter was one condition tree over its global
 * fields (`config.filter`); from C a filter is a named field with a default
 * a reader can change. A leaf of that tree's top-level AND that one filter
 * could hold — on a global field of one of the five filter types, asked with
 * that type's operator (an `EQ` of one text or number value is the one-entry
 * list the filter stores), on a filter with no default of its own and only
 * one such leaf — becomes that filter's default. The rest becomes `fixed`,
 * the board's fixed scope, which every panel runs under as it ran under
 * `config.filter`; a tree that is not a plain AND is not taken apart and
 * becomes `fixed` whole. `config.filter` is left empty.
 *
 * The mark is `fixed` itself. A config that has it was read into this form
 * — or written in it — and is never taken apart again: which leaves a
 * filter "could hold" depends on the filters' settings (a default, 「可多选」),
 * and an author changes those after the board was read, so judging again on
 * every read would move a leaf that stayed fixed into a default any reader
 * can clear (A-02). A `filter` that is no tree is left for admission to
 * report (`config.filter.invalid`), with an empty fixed scope beside it.
 */
function intoDefaults(config: DashboardViewConfig): DashboardViewConfig {
  const stored: unknown = config;
  if (!isPlainObject(stored) || 'fixed' in stored) return config;
  const tree: unknown = stored.filter;
  if (!isFilterGroup(tree)) return { ...config, fixed: emptyFilter() };
  const fields: unknown = stored.fields;
  const byName = new Map<string, DashboardField>();
  if (Array.isArray(fields))
    for (const field of fields)
      if (isPlainObject(field) && typeof field.name === 'string')
        byName.set(field.name, field as unknown as DashboardField);

  const defaults = new Map<string, FilterValue>();
  const kept: FilterNode[] = [];
  // Only a plain AND is taken apart: a leaf under an OR says nothing alone.
  for (const child of tree.op === 'and' ? tree.children : []) {
    const field = isPlainObject(child) ? leafField(child, byName) : undefined;
    const value =
      field && !defaults.has(field.name) && field.default === undefined
        ? asDefault(field, child as unknown as Record<string, unknown>)
        : undefined;
    if (field && value !== undefined) defaults.set(field.name, value);
    else kept.push(child);
  }
  return {
    ...config,
    filter: emptyFilter(),
    fixed: tree.op === 'and' ? { ...tree, children: kept } : tree,
    ...(defaults.size === 0
      ? {}
      : {
          fields: (fields as DashboardField[]).map(field =>
            isPlainObject(field) && defaults.has(field.name)
              ? { ...field, default: defaults.get(field.name) }
              : field,
          ),
        }),
  };
}

/** The global field a plain leaf is on, if it is one. */
function leafField(
  node: Record<string, unknown>,
  byName: ReadonlyMap<string, DashboardField>,
): DashboardField | undefined {
  const keys = Object.keys(node).sort().join(',');
  if (keys !== 'field,operator,value' || typeof node.field !== 'string')
    return undefined;
  return byName.get(node.field);
}

/** The leaf's value as the filter's default, or nothing it could hold. */
function asDefault(
  field: DashboardField,
  leaf: Record<string, unknown>,
): FilterValue | undefined {
  const type = filterTypeOf(field.kind);
  if (type === null) return undefined;
  const value = leaf.value as FilterValue;
  // What the filter's own condition asks with (`filterOperatorOf`).
  const operator = FILTER_TYPE_OPERATOR[type];
  if (leaf.operator === operator) {
    // A filter that takes one value holds a list of one.
    if (
      operator === 'IN' &&
      Array.isArray(value) &&
      value.length > 1 &&
      field.multiple !== true
    )
      return undefined;
    return value;
  }
  if (
    leaf.operator === 'EQ' &&
    (type === 'text' || type === 'number') &&
    value !== null &&
    typeof value !== 'object'
  )
    return [value];
  return undefined;
}

function widened(panel: unknown): unknown {
  if (!isPlainObject(panel) || !isPlainObject(panel.layout)) return panel;
  const layout = panel.layout;
  return {
    ...panel,
    layout: { ...layout, x: scaled(layout.x), w: scaled(layout.w) },
  };
}

function scaled(value: unknown): unknown {
  return typeof value === 'number' ? value * SCALE : value;
}
