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

/*
 * Not re-exported by `dashboard/index.ts`: only the dashboard runtime reads
 * a board under the paths its panels' definitions rename aliases to, as it
 * resolves them (capabilities.md 18).
 */

import {
  sameJson,
  type AnalysisViewConfig,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import { isViewPanel, panelsOf } from './panels.js';
import { URL_PLACEHOLDER } from './placeholders.js';

/** Each field a panel's definition names by an alias, by that alias. */
export type PanelRenames = (
  panel: DashboardViewPanel,
) => Readonly<Record<string, string>> | null | undefined;

/** A view the board owns, read under the paths `renamed` maps to. */
export type OwnedViewRename = (
  config: AnalysisViewConfig,
  renamed: Readonly<Record<string, string>>,
) => AnalysisViewConfig;

/**
 * The board with every name it gives a data panel's field renamed to the
 * path the panel's definition renames it to (#3519): a binding's
 * `panelField`, a dimension another board's filter takes its value from,
 * a `{{field}}` of a page click, and the view the panel owns. The board
 * then agrees with the fields its panels run on — a filter wired by an
 * alias still narrows its panel — and is saved under the paths next time.
 *
 * `renamedOf` says what a panel's definition renamed, `null` while it is
 * not known. A stored config is untrusted: what is not the shape it names
 * is left as it is, for admission to report. Returns the config itself
 * when nothing is renamed.
 */
export function withCanonicalPanelFields(
  config: DashboardViewConfig,
  renamedOf: PanelRenames,
  ownedView: OwnedViewRename,
): DashboardViewConfig {
  let changed = false;
  const panels = panelsOf(config).map(panel => {
    if (!isViewPanel(panel)) return panel;
    const renamed = renamedOf(panel);
    if (!renamed || Object.keys(renamed).length === 0) return panel;
    const next = renamedPanel(panel, renamed, ownedView);
    if (next !== panel) changed = true;
    return next;
  });
  return changed ? { ...config, panels } : config;
}

function renamedPanel(
  panel: DashboardViewPanel,
  renamed: Readonly<Record<string, string>>,
  ownedView: OwnedViewRename,
): DashboardPanel {
  const name = (field: string) => renamed[field] ?? field;
  const next: Record<string, unknown> = { ...panel };
  const bindings: unknown = panel.bindings;
  if (Array.isArray(bindings))
    next.bindings = bindings.map((entry: unknown) =>
      isPlainObject(entry) && typeof entry.panelField === 'string'
        ? { ...entry, panelField: name(entry.panelField) }
        : entry,
    );
  const click: unknown = panel.click;
  if (isPlainObject(click)) next.click = renamedClick(click, name);
  const owned: unknown = panel.owned;
  if (
    isPlainObject(owned) &&
    isPlainObject(owned.config) &&
    owned.config.kind === 'analysis'
  )
    next.owned = {
      ...owned,
      config: ownedView(owned.config as unknown as AnalysisViewConfig, renamed),
    };
  return sameJson(next, panel) ? panel : (next as unknown as DashboardPanel);
}

function renamedClick(
  click: Record<string, unknown>,
  name: (field: string) => string,
): Record<string, unknown> {
  if (click.kind === 'url' && typeof click.url === 'string')
    return {
      ...click,
      // The name is the first thing inside the braces, so its first
      // occurrence in the placeholder is the name, spaces kept.
      url: click.url.replace(URL_PLACEHOLDER, (whole, field: string) =>
        whole.replace(field, name(field)),
      ),
    };
  const values: unknown = click.values;
  if (click.kind === 'dashboard' && isPlainObject(values))
    return {
      ...click,
      values: Object.fromEntries(
        Object.entries(values).map(([filter, source]) => [
          filter,
          isPlainObject(source) && typeof source.dimension === 'string'
            ? { ...source, dimension: name(source.dimension) }
            : source,
        ]),
      ),
    };
  return click;
}
