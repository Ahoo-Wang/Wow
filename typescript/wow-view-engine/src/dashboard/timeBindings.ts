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
 * A board's time filter reaching its panels on its own (todo D, D67):
 * every panel over data that says when its records happen
 * (`DataViewDefinition.timeField`, `SystemView.timeField`) is narrowed by
 * the board's time range without a wire written for it. A wire written by
 * hand always wins, and a panel that reads whole says so
 * (`DashboardViewPanel.ignoresTime`).
 *
 * The wires are read into the board, marked as made rather than chosen
 * (`auto: true`), where a board is read under its panels' definitions —
 * the same reading that renames a panel's aliases (`canonicalBoard`) — so
 * every part of the engine sees one board: what narrows a panel, what the
 * filter reaches, what a press sets, what the author sees wired.
 */

import {
  filterTypeOf,
  sameFilterType,
  type DashboardField,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
} from '../model/index.js';
import { filtersOf } from './filters.js';
import { bindingsOf, isViewPanel, panelsOf } from './panels.js';

/**
 * The board's time filter: its one date filter. A board with two — when an
 * order was placed, when it shipped — has no one range to read every panel
 * in, so none is wired on its own there.
 */
export function timeFilterOf(
  config: DashboardViewConfig,
): DashboardField | null {
  const dated = filtersOf(config).filter(
    filter => filterTypeOf(filter.kind) === 'date',
  );
  return dated.length === 1 ? dated[0] : null;
}

/**
 * The board with its time filter wired to every data panel it does not
 * reach yet, through the field `timeFieldOf` says the panel's view is timed
 * by (`null` for none, or none known yet); itself when nothing is added.
 */
export function withTimeBindings(
  config: DashboardViewConfig,
  timeFieldOf: (panel: DashboardViewPanel) => FieldDefinition | null,
): DashboardViewConfig {
  const filter = timeFilterOf(config);
  if (!filter) return config;
  let changed = false;
  const panels = panelsOf(config).map(panel => {
    if (!isViewPanel(panel) || panel.ignoresTime === true) return panel;
    const bindings = bindingsOf(panel);
    if (bindings.some(binding => binding.globalField === filter.name))
      return panel;
    const field = timeFieldOf(panel);
    if (!field || !sameFilterType(filter.kind, field.kind)) return panel;
    changed = true;
    // Whatever else the stored list holds stays as it is, for admission to
    // report on the panel.
    const stored: unknown = panel.bindings;
    return {
      ...panel,
      bindings: [
        ...(Array.isArray(stored) ? stored : []),
        { globalField: filter.name, panelField: field.name, auto: true },
      ],
    };
  });
  return changed ? { ...config, panels } : config;
}
