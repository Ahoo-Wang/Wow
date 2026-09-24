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
 * What a press on one group of a panel does (D22 H, I): a panel's `click`
 * read as the untrusted thing a stored config is, what admission says about
 * it, the edit that sets it, the filters a press can set, and the URL a
 * page destination is filled into.
 *
 * What a press *computes* — the group's value in a filter's shape, the
 * conditions carried to another view — needs the analysis kernel, which
 * this one may not import; that is the runtime's (`runtime/dashboard/
 * press.ts`). Everything here reads configs alone.
 */

import {
  filterTypeOf,
  without,
  type AnalysisGroupType,
  type DashboardField,
  type DashboardFilterType,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type Issue,
  type IssuePath,
  type PanelClick,
  type ViewConfig,
  sameFilterType,
} from '../model/index.js';
import { isPlainObject, issue } from '../filter/index.js';
import { filtersOf } from './filters.js';
import {
  bindingsOf,
  clickOf,
  isSafeContentUrl,
  isViewPanel,
} from './panels.js';
import type { PanelReferences } from './validate.js';

/** `{{ name }}`, spaces inside the braces allowed; the name has none. */
const PLACEHOLDER = /\{\{\s*([^{}\s]+)\s*\}\}/g;

/** The names a URL template's `{{…}}` hold, in order, each once. */
export function urlPlaceholders(template: string): string[] {
  return [
    ...new Set([...template.matchAll(PLACEHOLDER)].map(match => match[1])),
  ];
}

/**
 * A URL template with each `{{name}}` replaced by its value, encoded as one
 * component — a value never adds a path, a query or a scheme — and a name
 * with no value replaced by nothing. `null` when what comes out is not a
 * URL a board may open (`isSafeContentUrl`): only http, https, mailto and a
 * path inside the application.
 */
export function fillUrl(
  template: string,
  values: Readonly<Record<string, string>>,
): string | null {
  const url = template.replace(PLACEHOLDER, (_match, name: string) =>
    encodeURIComponent(values[name] ?? ''),
  );
  return isSafeContentUrl(url) ? url : null;
}

/** One dimension of a panel's view, as a press reads it: a field bucketed so. */
export interface PressableGroup {
  field: string;
  type: AnalysisGroupType;
}

/**
 * Whether a press on a group of this dimension has a value for a filter of
 * this type (D22 I): a date filter takes a date bucket — the window it
 * spans — and a text, id, number or yes-or-no filter takes one value of a
 * `TERMS` dimension. A band of numbers is two bounds, not a value, so a
 * number filter takes no `HISTOGRAM` group; a filter of a kind outside the
 * five takes none.
 */
export function takesGroup(
  type: DashboardFilterType | null,
  group: AnalysisGroupType,
): boolean {
  if (type === null) return false;
  return type === 'date' ? group === 'DATE_HISTOGRAM' : group === 'TERMS';
}

/**
 * The board filters a press on this panel can set (「更新仪表盘筛选」):
 * each wired to it through a field one of `groups` buckets in a way the
 * filter takes (`takesGroup`), with the field it is wired through. What a
 * panel groups by is its view's, so `groups` are the view's dimensions.
 */
export function crossFilterChoices(
  filters: readonly DashboardField[],
  panel: DashboardViewPanel,
  groups: readonly PressableGroup[],
): { filter: DashboardField; field: string }[] {
  const bound = new Map(
    bindingsOf(panel).map(binding => [binding.globalField, binding]),
  );
  return filters.flatMap(filter => {
    const binding = bound.get(filter.name);
    if (!binding) return [];
    const type = filterTypeOf(filter.kind);
    const grouped = groups.some(
      group =>
        group.field === binding.panelField && takesGroup(type, group.type),
    );
    return grouped ? [{ filter, field: binding.panelField }] : [];
  });
}

/**
 * The dimensions of a view a press can name: an analysis's groups, read as
 * untrusted; none for a record view, and none for an analysis over expanded
 * elements — its groups are an element's, which no root condition selects.
 */
export function pressableGroups(config: ViewConfig | undefined): {
  groups: PressableGroup[];
  pressable: boolean;
} {
  if (config?.kind !== 'analysis') return { groups: [], pressable: false };
  const elements: unknown = config.elements;
  if (Array.isArray(elements) && elements.length > 0)
    return { groups: [], pressable: false };
  const groups: unknown = config.groups;
  return {
    groups: Array.isArray(groups)
      ? groups.filter(
          (group): group is PressableGroup =>
            isPlainObject(group) &&
            typeof group.field === 'string' &&
            typeof group.type === 'string',
        )
      : [],
    pressable: true,
  };
}

/**
 * What admission says about a panel's click (D22 I). Every finding is a
 * warning: a click that cannot do what it says leaves the panel as it is,
 * and a press on it opens the follow-up menu instead — the board still runs
 * and still saves. `view` is the panel's view once known; before, only the
 * shape and what the board itself says are judged.
 */
export function validatePanelClick(
  panel: DashboardViewPanel,
  path: IssuePath,
  config: DashboardViewConfig,
  view: { config: ViewConfig; fields?: readonly FieldDefinition[] } | null,
  refs: PanelReferences = new Map(),
): Issue[] {
  const stored: unknown = panel.click;
  if (stored === undefined) return [];
  const at: IssuePath = [...path, 'click'];
  const warn = (code: string, params: Record<string, string> = {}) =>
    issue(code, at, params, 'warning');
  const click = clickOf(panel);
  if (click === null) return [warn('dashboard.click.invalid')];
  const read = view ? pressableGroups(view.config) : null;
  if (read && !read.pressable) return [warn('dashboard.click.unpressable')];

  switch (click.kind) {
    case 'filter': {
      const filter = filtersOf(config).find(
        field => field.name === click.filter,
      );
      if (!filter)
        return [
          warn('dashboard.click.filter-unknown', { filter: click.filter }),
        ];
      const binding = bindingsOf(panel).find(
        entry => entry.globalField === filter.name,
      );
      if (!binding)
        return [
          warn('dashboard.click.filter-unwired', { filter: filter.label }),
        ];
      // A press has a value for the filter only where the panel groups by
      // the field it is wired through, in a way the filter takes.
      const grouped =
        !read ||
        crossFilterChoices(filtersOf(config), panel, read.groups).some(
          choice => choice.filter.name === filter.name,
        );
      if (!grouped)
        return [
          warn('dashboard.click.filter-ungrouped', {
            filter: filter.label,
            field: binding.panelField,
          }),
        ];
      return [];
    }
    case 'url': {
      // Judged filled with a harmless word: a template whose scheme only
      // shows once a value is in (`{{scheme}}:…`) is no page of the host's.
      const names = urlPlaceholders(click.url);
      const probe = Object.fromEntries(names.map(name => [name, 'x']));
      if (fillUrl(click.url, probe) === null)
        return [warn('dashboard.click.url-unsafe')];
      if (!read) return [];
      const fields = new Set(read.groups.map(group => group.field));
      const unknown = names.find(name => !fields.has(name));
      return unknown === undefined
        ? []
        : [warn('dashboard.click.url-unknown-field', { field: unknown })];
    }
    case 'view':
      return [];
    case 'dashboard':
      return validateBoardClick(click, at, read?.groups ?? null, {
        fields: view?.fields ?? null,
        own: filtersOf(config),
        target: refs.get(click.instanceId),
      });
  }
}

/** A click that opens another board (D23 Q17). */
export type BoardClick = Extract<PanelClick, { kind: 'dashboard' }>;

/**
 * What a click that opens another board says (D23 Q17), each finding a
 * warning. Known without the target: a mapped dimension this panel no
 * longer groups by, a mapped filter of this board's it no longer has. Once
 * the target has been read: a target gone, not a board, or a mapped filter
 * it no longer has or that cannot take what is mapped to it.
 *
 * `target` is the board as the references hold it: `undefined` while
 * unread, since it is read only when 「点击时…」 opens on it or a reader
 * presses — never when this board opens — and `null` once found gone.
 * `groups` and `fields` are the panel's once its view is known; `own` is
 * this board's filters.
 */
export function validateBoardClick(
  click: BoardClick,
  at: IssuePath,
  groups: readonly PressableGroup[] | null,
  {
    fields,
    own,
    target,
  }: {
    fields: readonly FieldDefinition[] | null;
    own: readonly DashboardField[];
    target: { instance: { config: ViewConfig } } | null | undefined;
  },
): Issue[] {
  const warn = (code: string, params: Record<string, string> = {}) =>
    issue(code, at, params, 'warning');
  const issues: Issue[] = [];
  const mapped = Object.entries(click.values);
  const grouped = groups && new Set(groups.map(group => group.field));
  const ours = new Map(own.map(filter => [filter.name, filter]));
  for (const [, source] of mapped)
    if ('dimension' in source) {
      if (grouped && !grouped.has(source.dimension))
        issues.push(
          warn('dashboard.click.board-dimension-unknown', {
            field: source.dimension,
          }),
        );
    } else if (!ours.has(source.filter))
      issues.push(
        warn('dashboard.click.board-source-unknown', { filter: source.filter }),
      );
  if (target === undefined) return issues;
  if (target === null) return [...issues, warn('dashboard.click.board-gone')];
  const board = target.instance.config;
  if (board.kind !== 'dashboard')
    return [...issues, warn('dashboard.click.board-not-a-board')];
  const byName = new Map(filtersOf(board).map(field => [field.name, field]));
  for (const [name, source] of mapped) {
    const filter = byName.get(name);
    if (!filter) {
      issues.push(
        warn('dashboard.click.board-filter-unknown', { filter: name }),
      );
      continue;
    }
    // Judged only where what is mapped is still there: a source gone is
    // said above, once.
    const mismatch =
      'dimension' in source
        ? (grouped?.has(source.dimension) ?? false) &&
          boardValueChoices(filter, groups ?? [], fields).every(
            group => group.field !== source.dimension,
          )
        : ours.has(source.filter) &&
          boardFilterChoices(filter, own).every(
            choice => choice.name !== source.filter,
          );
    if (mismatch)
      issues.push(
        warn('dashboard.click.board-filter-mismatch', {
          filter: filter.label,
          field:
            'dimension' in source
              ? source.dimension
              : (ours.get(source.filter)?.label ?? source.filter),
        }),
      );
  }
  return issues;
}

/**
 * This board's filters another board's filter can take its value from
 * (「这块板的〈筛选〉」, D23 Q17): each of the same filter type
 * (`sameFilterType`), so the value it holds is one the target reads. Never
 * matched by name: the author picks one.
 */
export function boardFilterChoices(
  filter: DashboardField,
  own: readonly DashboardField[],
): DashboardField[] {
  return own.filter(candidate => sameFilterType(filter.kind, candidate.kind));
}

/**
 * The dimensions of a panel another board's filter can take its value from
 * (「这一组的〈维度〉」, D23 Q17): each one bucketed in a way the filter takes
 * (`takesGroup`) over a field of the filter's type (`sameFilterType`), once
 * per field. Without the panel's fields — its view not known yet — the
 * bucketing alone is asked. Never matched by name: this is the list the
 * author picks from, not a guess.
 */
export function boardValueChoices(
  filter: DashboardField,
  groups: readonly PressableGroup[],
  fields: readonly FieldDefinition[] | null,
): PressableGroup[] {
  const type = filterTypeOf(filter.kind);
  const seen = new Set<string>();
  return groups.filter(group => {
    if (seen.has(group.field) || !takesGroup(type, group.type)) return false;
    const field = fields?.find(entry => entry.name === group.field);
    if (fields && !(field && sameFilterType(filter.kind, field.kind)))
      return false;
    seen.add(group.field);
    return true;
  });
}

/**
 * A data panel's click (「点击时…」), or its click taken off (`null`) —
 * back to the follow-up menu. Kept only on a data panel.
 */
export function setPanelClick(
  config: DashboardViewConfig,
  id: string,
  click: PanelClick | null,
): DashboardViewConfig {
  let changed = false;
  const panels = config.panels.map(panel => {
    if (!isViewPanel(panel) || panel.id !== id) return panel;
    if (click === null) {
      if (panel.click === undefined) return panel;
      changed = true;
      return without(panel, 'click') as DashboardViewPanel;
    }
    changed = true;
    return { ...panel, click };
  });
  return changed ? { ...config, panels } : config;
}
