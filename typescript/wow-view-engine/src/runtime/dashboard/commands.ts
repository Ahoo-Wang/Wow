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
  DashboardWidth,
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

/** What the rules across the board's parts need of the runtime holding them. */
export interface BoardRulesHost {
  readonly limits: RuntimeLimits;
  readonly edits: BoardEdits;
  readonly values: FilterValues;
  disposed(): boolean;
  snapshot(): DashboardRuntimeState;
  /** Patches the snapshot and re-times the board (`RuntimeStore.setState`). */
  patch(state: Partial<DashboardRuntimeState>): void;
  /** Arms or holds the board's one timer again (`RuntimeStore.retime`). */
  retime(): void;
  /** Reads the panels again — their clicks among them — once they were read. */
  reread(): void;
  edit(patch: Partial<DashboardViewConfig>): void;
  apply(): void;
  showTab(tabId: string | null): void;
}

/**
 * The rules that span the board's parts (A-04), each under its own name and
 * in one place, rather than among the one-line forwards of `BoardCommands`:
 * what building lets through and what it holds, whose refresh interval is
 * in force, a filter's default that is also what it holds, what a host
 * holding a filter does to the panels' clicks, and what a board opens on.
 */
export class BoardRules {
  /** Whether the board refreshes itself on its interval (`setAutoRefresh`). */
  private autoRefresh = true;
  /** What the board refused of the filters it opened on (`opensOn`). */
  private openingRefusal: Issue[] = NO_REFUSAL;

  constructor(private readonly host: BoardRulesHost) {}

  /**
   * Whether an edit is taken: only while the board is built, so a gesture
   * that outlived the building — a view picked, then 「取消」 while it still
   * loaded — does nothing to the board read after it (Q-02).
   */
  building(): boolean {
    return !this.host.disposed() && this.host.snapshot().building;
  }

  /**
   * Building holds the timer (D26 Q39: the author's panels are not re-run
   * under them) and lets go of the reader's own interval — what is chosen
   * while building is the board's (Q35). Ending it arms the timer again,
   * refuses every edit from then on and forgets the history: there is no
   * building left to take a step of back.
   */
  setBuilding(active: boolean): void {
    if (this.host.disposed() || this.host.snapshot().building === active)
      return;
    this.host.patch(
      active
        ? { building: true, readerRefresh: null }
        : { building: false, history: this.host.edits.forget() },
    );
  }

  setAutoRefresh(on: boolean): void {
    if (this.host.disposed() || this.autoRefresh === on) return;
    this.autoRefresh = on;
    this.host.retime();
  }

  /**
   * Whether the board's timer waits for a reason of the board's own: the
   * host turned it off, or the board is being built. A panel's request in
   * flight is the runtime's to add.
   */
  holdsTimer(): boolean {
    return !this.autoRefresh || this.host.snapshot().building;
  }

  /** The interval the timer keeps: the reader's own over the board's. */
  intervalInForce(): number | null {
    const { readerRefresh, applied } = this.host.snapshot();
    return refreshIntervalOf(
      readerRefresh ? { refresh: readerRefresh } : applied,
    );
  }

  /**
   * The board's own interval while it is built — edited and applied, as
   * any view's is, so the timer reads it and a save writes it — and the
   * reader's for this opening while it is read, beside the draft (D26 Q35).
   */
  setRefreshInterval(interval: number | null): void {
    if (this.host.disposed()) return;
    if (this.host.snapshot().building) {
      this.host.edit({ refresh: { interval } });
      this.host.apply();
      return;
    }
    const { minRefreshInterval: min, maxRefreshInterval: max } =
      this.host.limits;
    if (
      interval !== null &&
      !(Number.isInteger(interval) && interval >= min && interval <= max)
    )
      return;
    this.host.patch({ readerRefresh: { interval } });
  }

  /**
   * What a filter starts at is also what it holds from then on: the author
   * setting a default sees the board under it (D22 G). A default refused
   * with the building sets nothing either.
   */
  setFilterDefault(name: string, value: FilterValue | null): void {
    if (!this.building()) return;
    this.host.edits.setFilterDefault(name, value);
    this.host.values.set(name, value);
  }

  /**
   * A default taken back or made again is what its filter holds from then
   * on, as setting it was (D22 G).
   */
  followed(step: EditStep | null): EditStep | null {
    if (step?.command === 'setFilterDefault' && step.subject !== null)
      this.host.values.set(
        step.subject,
        this.host.edits.defaultOf(step.subject),
      );
    return step;
  }

  /**
   * A click that sets a filter the host now holds is set aside, and one let
   * go is in force again: the panels' clicks are read again.
   */
  holdFilters(held: HeldFilters | null): Issue[] {
    if (this.host.disposed()) return [];
    const { refused, moved } = this.host.values.hold(held);
    if (moved) this.host.reread();
    return refused;
  }

  /**
   * Where a board opens, and what its filters hold as it does
   * (`ViewEngine.open`: a host's address says both); what it refused of
   * them is kept (`refusedFilters`).
   */
  opensOn(
    tab: string | null,
    filters?: DashboardFilters | null,
    held?: HeldFilters | null,
  ): void {
    if (tab !== null) this.host.showTab(tab);
    const taken = filters && !this.host.disposed();
    const refused = [
      ...(taken ? this.host.values.take(filters) : []),
      ...(held ? this.holdFilters(held) : []),
    ];
    if (refused.length > 0) this.openingRefusal = refused;
  }

  /** See `DashboardRuntime.refusedFilters`. */
  get refusedFilters(): Issue[] {
    return this.openingRefusal;
  }
}

/**
 * The board's commands as methods of the runtime that holds it, each one
 * call on one of its parts — its edits (`boardEditing`), what its filters
 * hold (`FilterValues`), a press (`PanelPresses`) or a rule that spans them
 * (`BoardRules`) — or one patch of its snapshot, so the forwarding lives
 * beside the parts rather than in the runtime, and decides nothing.
 */
export abstract class BoardCommands
  implements DashboardEditing, DashboardFilterEditing
{
  protected abstract readonly edits: BoardEdits;
  protected abstract readonly values: FilterValues;
  protected abstract readonly presses: PanelPresses;
  protected abstract readonly rules: BoardRules;
  abstract get disposed(): boolean;
  abstract getSnapshot(): DashboardRuntimeState;
  /** Patches the snapshot and re-times the board (`RuntimeStore.setState`). */
  protected abstract patch(state: Partial<DashboardRuntimeState>): void;

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

  // The rules that span the parts: see `BoardRules`.
  setBuilding(active: boolean): void {
    this.rules.setBuilding(active);
  }
  setAutoRefresh(on: boolean): void {
    this.rules.setAutoRefresh(on);
  }
  setRefreshInterval(interval: number | null): void {
    this.rules.setRefreshInterval(interval);
  }
  setFilterDefault(name: string, value: FilterValue | null): void {
    this.rules.setFilterDefault(name, value);
  }
  undo(): EditStep | null {
    return this.rules.followed(this.edits.undo());
  }
  redo(): EditStep | null {
    return this.rules.followed(this.edits.redo());
  }
  holdFilters(held: HeldFilters | null): Issue[] {
    return this.rules.holdFilters(held);
  }
  opensOn(
    tab: string | null,
    filters?: DashboardFilters | null,
    held?: HeldFilters | null,
  ): void {
    this.rules.opensOn(tab, filters, held);
  }
  /** See `DashboardRuntime.refusedFilters`. */
  get refusedFilters(): Issue[] {
    return this.rules.refusedFilters;
  }

  // Building the board (D22 A–E): see `boardEditing`.
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
  removeFixedScope(): void {
    this.edits.removeFixedScope();
  }
  place(panelId: string, layout: PanelLayout): void {
    this.edits.place(panelId, layout);
  }
  reorderPanel(panelId: string, step: OrderStep): void {
    this.edits.reorderPanel(panelId, step);
  }
  setWidth(width: DashboardWidth): void {
    this.edits.setWidth(width);
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
}
