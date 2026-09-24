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
 * Which field of which panel a board filter narrows (D22 G): the fields a
 * filter can be wired to on a panel, wiring one by hand and auto-connecting
 * the rest — every other panel, on any tab and over any data, with a field
 * of the same name and the same type (user ruling: 同名同类型即接，跨定义也接)
 * — and what reaches each panel, so a panel a filter does not reach can say
 * so (「不受此筛选影响」).
 */

import {
  sameFilterType,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type FieldOption,
  type OwnedView,
  type PanelBinding,
  without,
} from '../model/index.js';
import { filtersOf } from './filters.js';
import { bindingsOf, clicksFilter, isViewPanel } from './panels.js';

/**
 * What of a data panel says which view it shows: the saved one it points
 * at, or the one it owns — all a panel being added has yet, before it has
 * an id or a place, and all that finding its fields reads.
 */
export interface DataPanelSource {
  instanceId?: string;
  owned?: OwnedView;
  title?: string;
}

/**
 * The fields of the view a data panel shows, once it is known; `null` for a
 * panel whose view has not loaded, or cannot be — nothing is said about it.
 */
export type PanelFields = (
  panel: DataPanelSource,
) => readonly FieldDefinition[] | null;

/**
 * What one filter does to one data panel: wired, through which field, and
 * whether auto-connect made the wire; or not wired, and why — its view has
 * no field of the filter's type (「没有可接的字段」), or it has one nobody
 * chose (`unwired`).
 */
export type FilterReach =
  | { wired: true; field: string; auto: boolean }
  | { wired: false; why: 'no-field' | 'unwired' };

/**
 * The fields of a panel a filter can be wired to: its view's fields of the
 * filter's type, in the definition's order. Empty for a filter of no type.
 */
export function wireableFields(
  field: Pick<DashboardField, 'kind'>,
  fields: readonly FieldDefinition[],
): FieldDefinition[] {
  return fields.filter(candidate => sameFilterType(field.kind, candidate.kind));
}

/**
 * What every filter of the board does to one panel, by filter name; an
 * empty record for a content panel. A panel whose fields are not known yet
 * (`fields` is `null`) is answered for its wires alone: an unwired filter
 * cannot be told apart from one with nothing to wire, so it is left out.
 */
export function filterReach(
  config: DashboardViewConfig,
  panel: DashboardPanel,
  fields: readonly FieldDefinition[] | null,
): Record<string, FilterReach> {
  const reach: Record<string, FilterReach> = {};
  if (!isViewPanel(panel)) return reach;
  const bindings = bindingsOf(panel);
  for (const field of filtersOf(config)) {
    const binding = bindings.find(entry => entry.globalField === field.name);
    if (binding)
      reach[field.name] = {
        wired: true,
        field: binding.panelField,
        auto: binding.auto === true,
      };
    else if (fields !== null)
      reach[field.name] = {
        wired: false,
        why: wireableFields(field, fields).length > 0 ? 'unwired' : 'no-field',
      };
  }
  return reach;
}

/**
 * The filters that reach at least one panel on a tab (`null`: a board
 * without tabs). A filter outside it narrows nothing the reader is looking
 * at, which the filter bar shows by dimming it.
 */
export function filtersOnTab(
  panels: readonly {
    tab: string | null;
    reach: Readonly<Record<string, FilterReach>>;
  }[],
  tab: string | null,
): Set<string> {
  const reaching = new Set<string>();
  for (const panel of panels)
    if (panel.tab === tab)
      for (const [name, reach] of Object.entries(panel.reach))
        if (reach.wired) reaching.add(name);
  return reaching;
}

/**
 * The board with one panel wired by hand to `panelField` for a filter, and
 * every other data panel not yet wired to it connected on its own where its
 * view has a field of that very name and of the filter's type — on any tab,
 * over any data (D22 G). `connected` names the panels auto-connect wired,
 * for the reader to be told how many and to undo them (`unbindPanels`).
 *
 * An id filter with no candidate source of its own takes the one the field
 * wired by hand names. The same board when the filter or the panel is not
 * the board's, or the panel's view has no such field of that type.
 */
export function bindPanel(
  config: DashboardViewConfig,
  name: string,
  panelId: string,
  panelField: string,
  fieldsOf: PanelFields,
): { config: DashboardViewConfig; connected: string[] } {
  const same = { config, connected: [] };
  const filter = filtersOf(config).find(field => field.name === name);
  const panel = viewPanel(config, panelId);
  const fields = panel ? fieldsOf(panel) : null;
  const target = fields?.find(field => field.name === panelField);
  if (!filter || !panel || !target) return same;
  if (!sameFilterType(filter.kind, target.kind)) return same;

  const connected: string[] = [];
  const panels = config.panels.map(entry => {
    if (entry === panel) return wire(entry, name, panelField, false);
    if (!isViewPanel(entry) || wiredTo(entry, name)) return entry;
    const has = fieldsOf(entry)?.some(
      field =>
        field.name === panelField && sameFilterType(filter.kind, field.kind),
    );
    if (!has) return entry;
    connected.push(entry.id);
    return wire(entry, name, panelField, true);
  });
  const remote =
    filter.remote === undefined && target.remote !== undefined
      ? target.remote
      : undefined;
  return {
    config: {
      ...config,
      fields:
        remote === undefined
          ? config.fields
          : config.fields.map(field =>
              field === filter ? { ...field, remote } : field,
            ),
      panels,
    },
    connected,
  };
}

/** The board with these panels no longer wired to a filter. */
export function unbindPanels(
  config: DashboardViewConfig,
  name: string,
  panelIds: readonly string[],
): DashboardViewConfig {
  let changed = false;
  const panels = config.panels.map(panel => {
    if (!isViewPanel(panel) || !panelIds.includes(panel.id)) return panel;
    if (!wiredTo(panel, name)) return panel;
    changed = true;
    // A press that set this filter has no value for it any more (D22 I):
    // the panel goes back to the follow-up menu.
    const rest = clicksFilter(panel, name)
      ? (without(panel, 'click') as DashboardViewPanel)
      : panel;
    return {
      ...rest,
      bindings: bindingsOf(panel).filter(entry => entry.globalField !== name),
    };
  });
  return changed ? { ...config, panels } : config;
}

/**
 * The fixed list the fields a filter is wired to offer, merged — what a
 * category filter picks from when it has no list of its own (D22 G,
 * 「值从哪来：接上的字段」, as Metabase's category filter does): every
 * option of every wired field that declares some (an `enum`'s), the same
 * code once, under the label it first comes with. A reader picks 「待出库」
 * and the filter holds `PENDING`, which is what the fields store.
 *
 * `null` when no wired field declares a list — then the values the data
 * holds are counted instead (`DashboardRuntime.valueCandidates`), or typed.
 * A wired field without a list beside ones with one is picked for from
 * the list: the list is the declared vocabulary of those values.
 */
export function wiredOptions(
  config: DashboardViewConfig,
  name: string,
  fieldsOf: PanelFields,
): FieldOption[] | null {
  const merged = new Map<string, FieldOption>();
  for (const panel of config.panels) {
    if (!isViewPanel(panel)) continue;
    const binding = bindingsOf(panel).find(entry => entry.globalField === name);
    if (!binding) continue;
    const field = fieldsOf(panel)?.find(
      entry => entry.name === binding.panelField,
    );
    for (const option of field?.options ?? []) {
      const key = `${typeof option.value}:${String(option.value)}`;
      if (!merged.has(key))
        merged.set(key, { value: option.value, label: option.label });
    }
  }
  return merged.size > 0 ? [...merged.values()] : null;
}

/**
 * The wires auto-connect gives a panel about to be added (D22 G: 以后新加的
 * 面板同样自动接): for each filter it has no wire for, a field of its view
 * with the name the filter is already wired through elsewhere — the most
 * wired name first — or the filter's own name, and of the filter's type.
 */
export function autoBindings(
  config: DashboardViewConfig,
  fields: readonly FieldDefinition[],
  given: readonly PanelBinding[] = [],
): PanelBinding[] {
  const bindings: PanelBinding[] = [];
  for (const filter of filtersOf(config)) {
    if (given.some(entry => entry.globalField === filter.name)) continue;
    const match = wiredNames(config, filter.name)
      .map(name => fields.find(field => field.name === name))
      .find(
        field => field !== undefined && sameFilterType(filter.kind, field.kind),
      );
    if (match)
      bindings.push({
        globalField: filter.name,
        panelField: match.name,
        auto: true,
      });
  }
  return bindings;
}

/**
 * The names a filter is wired through on the board, the most used first,
 * then its own name.
 */
function wiredNames(config: DashboardViewConfig, name: string): string[] {
  const counts = new Map<string, number>();
  for (const panel of config.panels)
    if (isViewPanel(panel))
      for (const binding of bindingsOf(panel))
        if (binding.globalField === name)
          counts.set(
            binding.panelField,
            (counts.get(binding.panelField) ?? 0) + 1,
          );
  const names = [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([field]) => field);
  return names.includes(name) ? names : [...names, name];
}

/** One panel wired to a filter through `panelField`, any old wire replaced. */
function wire(
  panel: DashboardViewPanel,
  name: string,
  panelField: string,
  auto: boolean,
): DashboardViewPanel {
  const binding: PanelBinding = auto
    ? { globalField: name, panelField, auto: true }
    : { globalField: name, panelField };
  return {
    ...panel,
    bindings: [
      ...bindingsOf(panel).filter(entry => entry.globalField !== name),
      binding,
    ],
  };
}

function wiredTo(panel: DashboardViewPanel, name: string): boolean {
  return bindingsOf(panel).some(entry => entry.globalField === name);
}

function viewPanel(
  config: DashboardViewConfig,
  id: string,
): DashboardViewPanel | undefined {
  return config.panels.find(
    (panel): panel is DashboardViewPanel =>
      isViewPanel(panel) && panel.id === id,
  );
}
