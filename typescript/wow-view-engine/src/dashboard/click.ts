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
  type Issue,
  type IssuePath,
  type PanelClick,
  type ViewConfig,
} from '../model/index.js';
import { isPlainObject, issue } from '../filter/index.js';
import { filtersOf } from './filters.js';
import {
  bindingsOf,
  clickOf,
  isSafeContentUrl,
  isViewPanel,
} from './panels.js';

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
  view: { config: ViewConfig } | null,
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
  }
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
