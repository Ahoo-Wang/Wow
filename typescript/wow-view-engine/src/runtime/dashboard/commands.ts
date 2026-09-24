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
  DashboardViewConfig,
  FieldOption,
  FilterValue,
  Issue,
  PanelClick,
  PanelLayout,
  PanelPresentation,
  RecordData,
  RuntimeLimits,
  ViewInstance,
} from '../../model/index.js';
import { refreshIntervalOf } from '../refreshTimer.js';
import type {
  NewContentPanel,
  NewFilter,
  NewPanel,
  NewPanelPlacement,
  OrderStep,
} from '../../dashboard/index.js';
import type { EditStep } from './history.js';
import type { DashboardRuntimeState, HeldFilters } from './contract.js';
import type {
  BoardEdits,
  DashboardEditing,
  DashboardFilterEditing,
} from './editing.js';
import type { FilterValues } from './filterValues.js';
import type {
  CrossFilterOutcome,
  DestinationBoard,
  PanelPresses,
  PressDestination,
} from './press.js';

const NO_REFUSAL: Issue[] = [];

/**
 * The board's commands that are one call on one of its parts — its edits
 * (`boardEditing`) and what its filters hold (`FilterValues`) — or one
 * patch of its snapshot, as methods of the runtime that holds it, so the
 * forwarding lives beside the parts rather than in the runtime.
 */
export abstract class BoardCommands
  implements DashboardEditing, DashboardFilterEditing
{
  protected abstract readonly edits: BoardEdits;
  protected abstract readonly values: FilterValues;
  protected abstract readonly presses: PanelPresses;
  abstract readonly limits: RuntimeLimits;
  abstract get disposed(): boolean;
  abstract getSnapshot(): DashboardRuntimeState;
  abstract edit(patch: Partial<DashboardViewConfig>): void;
  abstract apply(): void;
  abstract showTab(tabId: string | null): void;
  /** Patches the snapshot and re-times the board (`RuntimeStore.setState`). */
  protected abstract patch(state: Partial<DashboardRuntimeState>): void;
  /** Reads the panels again — their clicks among them — once they were read. */
  protected abstract reread(): void;
  /** Arms or holds the board's one timer again (`RuntimeStore.retime`). */
  protected abstract retime(): void;
  /** Whether the board refreshes itself on its interval (`setAutoRefresh`). */
  private autoRefresh = true;
  /** What the board refused of the filters it opened on (`opensOn`). */
  private openingRefusal: Issue[] = NO_REFUSAL;

  setAutoRefresh(on: boolean): void {
    if (this.disposed || this.autoRefresh === on) return;
    this.autoRefresh = on;
    this.retime();
  }

  /**
   * The preference is the user's and is kept like any other; what it may run
   * is the model's to say, and it declares no dashboard member that runs on
   * its own (`autoRunMembers`), so the switch arms nothing here.
   */
  setAutoApply(on: boolean): void {
    if (this.disposed || this.getSnapshot().autoApply === on) return;
    this.patch({ autoApply: on });
  }

  setEditing(active: boolean): void {
    if (this.disposed || this.getSnapshot().editing === active) return;
    this.patch({ editing: active });
  }

  /**
   * Building holds the timer (D26 Q39: the author's panels are not re-run
   * under them) and lets go of the reader's own interval — what is chosen
   * while building is the board's (Q35). Ending it arms the timer again.
   */
  setBuilding(active: boolean): void {
    if (this.disposed || this.getSnapshot().building === active) return;
    this.patch(
      active ? { building: true, readerRefresh: null } : { building: false },
    );
  }

  /**
   * The board's own interval while it is built — edited and applied, as
   * any view's is, so the timer reads it and a save writes it — and the
   * reader's for this opening while it is read, beside the draft (D26 Q35).
   */
  setRefreshInterval(interval: number | null): void {
    if (this.disposed) return;
    if (this.getSnapshot().building) {
      this.edit({ refresh: { interval } });
      this.apply();
      return;
    }
    const { minRefreshInterval: min, maxRefreshInterval: max } = this.limits;
    if (
      interval !== null &&
      !(Number.isInteger(interval) && interval >= min && interval <= max)
    )
      return;
    this.patch({ readerRefresh: { interval } });
  }

  /**
   * Whether the board's timer waits for a reason of the board's own: the
   * host turned it off, or the board is being built. A panel's request in
   * flight is the runtime's to add.
   */
  protected holdsTimer(): boolean {
    return !this.autoRefresh || this.getSnapshot().building;
  }

  /** The interval the timer keeps: the reader's own over the board's. */
  protected intervalInForce(): number | null {
    const { readerRefresh, applied } = this.getSnapshot();
    return refreshIntervalOf(
      readerRefresh ? { refresh: readerRefresh } : applied,
    );
  }

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
  reorderPanel(panelId: string, step: OrderStep): void {
    this.edits.reorderPanel(panelId, step);
  }
  undo(): EditStep | null {
    return this.followed(this.edits.undo());
  }
  redo(): EditStep | null {
    return this.followed(this.edits.redo());
  }
  /**
   * A default taken back or made again is what its filter holds from then
   * on, as setting it was (D22 G).
   */
  private followed(step: EditStep | null): EditStep | null {
    if (step?.command === 'setFilterDefault' && step.subject !== null)
      this.setFilterValue(step.subject, this.edits.defaultOf(step.subject));
    return step;
  }

  // What the filters hold (D22 F): see `FilterValues`.
  setFilterValue(name: string, value: FilterValue | null): Issue[] {
    return this.disposed ? [] : this.values.set(name, value);
  }
  setGroupingUnit(unit: AnalysisDateUnit): void {
    if (!this.disposed) this.values.unit(unit);
  }
  clearFilters(): void {
    if (!this.disposed) this.values.clear();
  }
  setFilters(filters: DashboardFilters): Issue[] {
    return this.disposed ? [] : this.values.take(filters);
  }
  holdFilters(held: HeldFilters | null): Issue[] {
    if (this.disposed) return [];
    const { refused, moved } = this.values.hold(held);
    // A click that sets a filter the host now holds is set aside, and one
    // let go is in force again: the panels' clicks are read again.
    if (moved) this.reread();
    return refused;
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
  destinationBoard(instanceId: string): Promise<DestinationBoard | null> {
    return this.presses.board(instanceId);
  }

  /**
   * Where a board opens, and what its filters hold as it does
   * (`ViewEngine.open`: a host's address says both).
   */
  opensOn(
    tab: string | null,
    filters?: DashboardFilters | null,
    held?: HeldFilters | null,
  ): void {
    if (tab !== null) this.showTab(tab);
    const refused = [
      ...(filters ? this.setFilters(filters) : []),
      ...(held ? this.holdFilters(held) : []),
    ];
    if (refused.length > 0) this.openingRefusal = refused;
  }
  /** See `DashboardRuntime.refusedFilters`. */
  get refusedFilters(): Issue[] {
    return this.openingRefusal;
  }
}
