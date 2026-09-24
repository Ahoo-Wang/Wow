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
  AnalysisDateUnit,
  DashboardFilters,
  DashboardFilterType,
  DashboardTimeGrouping,
  FieldOption,
  FilterValue,
  Issue,
  PanelClick,
  PanelLayout,
  PanelPresentation,
  RecordData,
  ViewInstance,
} from '../../model/index.js';
import type {
  NewContentPanel,
  NewFilter,
  NewPanel,
  NewPanelPlacement,
} from '../../dashboard/index.js';
import type { DashboardEditing, DashboardFilterEditing } from './editing.js';
import type { FilterValues } from './filterValues.js';
import type {
  CrossFilterOutcome,
  PanelPresses,
  PressDestination,
} from './press.js';

/**
 * The board's commands that are one call on one of its parts — its edits
 * (`boardEditing`) and what its filters hold (`FilterValues`) — as methods
 * of the runtime that holds it, so the forwarding lives beside the parts
 * rather than in the runtime.
 */
export abstract class BoardCommands
  implements DashboardEditing, DashboardFilterEditing
{
  protected abstract readonly edits: DashboardEditing & DashboardFilterEditing;
  protected abstract readonly values: FilterValues;
  protected abstract readonly presses: PanelPresses;
  abstract get disposed(): boolean;
  abstract showTab(tabId: string | null): void;

  addPanel(panel: NewPanel, placement?: NewPanelPlacement): string | null {
    return this.edits.addPanel(panel, placement);
  }
  removePanel(panelId: string): void {
    this.edits.removePanel(panelId);
  }
  duplicatePanel(panelId: string): string | null {
    return this.edits.duplicatePanel(panelId);
  }
  renamePanel(panelId: string, title: string): void {
    this.edits.renamePanel(panelId, title);
  }
  replacePanelView(panelId: string, instanceId: string): void {
    this.edits.replacePanelView(panelId, instanceId);
  }
  editPanelContent(panelId: string, patch: Partial<NewContentPanel>): void {
    this.edits.editPanelContent(panelId, patch);
  }
  movePanelToTab(panelId: string, tabId: string): void {
    this.edits.movePanelToTab(panelId, tabId);
  }
  setPresentation(panelId: string, look: PanelPresentation | null): void {
    this.edits.setPresentation(panelId, look);
  }
  referToSaved(panelId: string, instance: ViewInstance): void {
    this.edits.referToSaved(panelId, instance);
  }
  setPanelClick(panelId: string, click: PanelClick | null): void {
    this.edits.setPanelClick(panelId, click);
  }
  addTab(title: string, firstTitle: string): string | null {
    return this.edits.addTab(title, firstTitle);
  }
  renameTab(tabId: string, title: string): void {
    this.edits.renameTab(tabId, title);
  }
  moveTab(tabId: string, index: number): void {
    this.edits.moveTab(tabId, index);
  }
  removeTab(tabId: string): void {
    this.edits.removeTab(tabId);
  }
  addFilter(filter: NewFilter): string | null {
    return this.edits.addFilter(filter);
  }
  renameFilter(name: string, label: string): void {
    this.edits.renameFilter(name, label);
  }
  retypeFilter(name: string, type: DashboardFilterType): void {
    this.edits.retypeFilter(name, type);
  }
  removeFilter(name: string): void {
    this.edits.removeFilter(name);
  }
  /**
   * What a filter starts at is also what it holds from then on: the author
   * setting a default sees the board under it (D22 G).
   */
  setFilterDefault(name: string, value: FilterValue | null): void {
    this.edits.setFilterDefault(name, value);
    this.setFilterValue(name, value);
  }
  setFilterRequired(name: string, required: boolean): void {
    this.edits.setFilterRequired(name, required);
  }
  setFilterMultiple(name: string, multiple: boolean): void {
    this.edits.setFilterMultiple(name, multiple);
  }
  setFilterOptions(name: string, options: FieldOption[] | null): void {
    this.edits.setFilterOptions(name, options);
  }
  moveFilter(name: string, index: number): void {
    this.edits.moveFilter(name, index);
  }
  bindPanel(name: string, panelId: string, panelField: string): string[] {
    return this.edits.bindPanel(name, panelId, panelField);
  }
  unbindPanels(name: string, panelIds: readonly string[]): void {
    this.edits.unbindPanels(name, panelIds);
  }
  setTimeGrouping(grouping: DashboardTimeGrouping | null): void {
    this.edits.setTimeGrouping(grouping);
  }
  place(panelId: string, layout: PanelLayout): void {
    this.edits.place(panelId, layout);
  }

  // What the filters hold (D22 F): see `FilterValues`.
  setFilterValue(name: string, value: FilterValue | null): Issue[] {
    return this.disposed ? [] : this.values.set(name, value);
  }
  setGroupingUnit(unit: AnalysisDateUnit): void {
    if (!this.disposed) this.values.unit(unit);
  }
  clearFilters(): void {
    if (!this.disposed) this.values.put({ values: {} });
  }
  setFilters(filters: DashboardFilters): Issue[] {
    return this.disposed ? [] : this.values.put(filters);
  }
  wiredOptions(name: string): FieldOption[] | null {
    return this.values.optionsOf(name);
  }

  // A press on a panel's group (D22 I): see `PanelPresses`.
  crossFilter(panelId: string, row: RecordData): CrossFilterOutcome {
    return this.disposed
      ? { kind: 'none' }
      : this.presses.crossFilter(panelId, row);
  }
  pressed(panelId: string, row: RecordData): boolean {
    return this.presses.pressed(panelId, row);
  }
  destination(
    panelId: string,
    row: RecordData,
  ): Promise<PressDestination | null> {
    return this.presses.destination(panelId, row);
  }

  /**
   * Where a board opens, and what its filters hold as it does
   * (`ViewEngine.open`: a host's address says both).
   */
  opensOn(tab: string | null, filters?: DashboardFilters | null): void {
    if (tab !== null) this.showTab(tab);
    if (filters) this.setFilters(filters);
  }
}
