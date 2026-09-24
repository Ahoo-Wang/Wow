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

import { dequal } from 'dequal';
import {
  filterTypeOf,
  type AnalysisDateUnit,
  type DashboardFilters,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldOption,
  type FilterTree,
  type FilterValue,
  type Issue,
} from '../../model/index.js';
import {
  admitFilters,
  bindingsOf,
  defaultFilters,
  filtersOf,
  isViewPanel,
  wiredOptions,
} from '../../dashboard/index.js';
import {
  isEmptyFilter,
  issue,
  type FieldKindRegistry,
} from '../../filter/index.js';
import { AUTO_APPLY_DELAY_MS } from '../autoApply.js';
import type { RuntimeEnvironment } from '../environment.js';
import { RefreshTimer } from '../refreshTimer.js';
import type { ValueCandidateSource } from '../valueCandidates.js';
import type { PanelView } from './children.js';
import type { HeldFilters } from './contract.js';
import {
  FilterCandidates,
  type CandidateSourceFactory,
} from './filterCandidates.js';
import { panelScope } from './panelRun.js';
import { panelsOf } from './panels.js';

/** What the filter values need of the runtime that holds the board. */
export interface FilterValuesHost {
  kinds: FieldKindRegistry;
  environment: RuntimeEnvironment;
  candidateSources?: CandidateSourceFactory;
  /** The board on screen, which the values are admitted against. */
  applied(): DashboardViewConfig;
  /** What the filters hold now (`DashboardRuntimeState.filters`). */
  current(): DashboardFilters;
  /** Shows new values at once. */
  commit(filters: DashboardFilters): void;
  /** Whether the panels have run yet: until then a value is only noted. */
  started(): boolean;
  /** Runs the panels on the values in force. */
  run(): void;
  /** The view a data panel shows, once known. */
  viewOf(panel: DashboardViewPanel): PanelView | null;
  /** The host's condition, in the board's names. */
  scope(): FilterTree | null;
}

/**
 * What a board's filters hold, and how a change of it reaches the panels
 * (D22 F): admitted against the board on screen — what it refuses is
 * answered and nothing changes — shown at once, and run a moment later, so a
 * burst of keystrokes is one query per panel (「改了就跑」, the analysis's
 * `AUTO_APPLY_DELAY_MS`). Also what a text filter offers to pick from.
 */
export class FilterValues {
  private readonly timer: RefreshTimer;
  private readonly candidates: FilterCandidates;
  /** The filters the host holds, by name (`hold`). */
  private held: ReadonlySet<string> = new Set();
  /** Whether the host holds the time grouping too. */
  private heldUnit = false;

  constructor(private readonly host: FilterValuesHost) {
    this.timer = new RefreshTimer(host.environment, () => host.run());
    this.candidates = new FilterCandidates(host.candidateSources);
  }

  /**
   * One filter set; `null` clears it — a required one to its default. A
   * value set this way is nobody's press, so where the last one came from
   * goes with it (`DashboardFilters.from`).
   */
  set(name: string, value: FilterValue | null): Issue[] {
    if (this.held.has(name)) return [heldIssue(name)];
    return this.put(this.with(name, value, null));
  }

  /**
   * One filter set by a press on a group of `panelId` (D22 I): that panel is
   * then left unnarrowed by it. `null` clears it, and where it came from.
   */
  press(name: string, value: FilterValue | null, panelId: string): Issue[] {
    if (this.held.has(name)) return [heldIssue(name)];
    return this.put(this.with(name, value, value === null ? null : panelId));
  }

  /**
   * The filters a host holds (an embed's locked and hidden ones, D22) and
   * what they hold — `null` for a filter's default — and, with `unit`, the
   * time grouping: the reader's commands — a value set, a press, 「清空」, a
   * unit picked — leave them as they are. The values go in at once, as
   * admitted: what the board refuses is left out and answered, every time
   * it is asked, so a host that says the same again hears the same. Answers
   * too whether which filters are held changed, which is what the panels'
   * clicks read. A filter let go keeps its value and is the reader's again.
   */
  hold(held: HeldFilters | null): { refused: Issue[]; moved: boolean } {
    const values = held?.values ?? {};
    const next = new Set(Object.keys(values));
    const grouping = held !== null && held.unit !== undefined;
    const moved =
      next.size !== this.held.size ||
      [...next].some(name => !this.held.has(name)) ||
      grouping !== this.heldUnit;
    this.held = next;
    this.heldUnit = grouping;
    // What was counted under the held values is counted again under the
    // new ones.
    if (moved) this.candidates.reset();

    const applied = this.host.applied();
    const current = this.host.current();
    const defaults = defaultFilters(applied);
    const wanted: DashboardFilters = {
      ...current,
      values: { ...current.values },
    };
    for (const [name, value] of Object.entries(values)) {
      const start = value ?? defaults.values[name];
      if (start === undefined) delete wanted.values[name];
      else wanted.values[name] = start;
    }
    if (grouping) {
      const unit = held?.unit ?? defaults.unit;
      if (unit !== undefined) wanted.unit = unit;
    }
    const { filters, refused } = admitFilters(applied, wanted, this.host.kinds);
    if (!dequal(filters, current)) this.commit(filters);
    return { refused, moved };
  }

  /** Whether the host holds this filter (`hold`). */
  holds(name: string): boolean {
    return this.held.has(name);
  }

  /**
   * 「清空」: every filter the reader holds cleared — a required one back to
   * its default — and the time grouping to its default; what the host holds
   * stays.
   */
  clear(): void {
    const current = this.host.current();
    const values: Record<string, FilterValue> = {};
    for (const name of this.held) {
      const value = current.values[name];
      if (value !== undefined) values[name] = value;
    }
    this.put(
      this.heldUnit && current.unit !== undefined
        ? { values, unit: current.unit }
        : { values },
    );
  }

  private with(
    name: string,
    value: FilterValue | null,
    panelId: string | null,
  ): DashboardFilters {
    const current = this.host.current();
    const values = { ...current.values };
    const from = { ...current.from };
    if (value === null) delete values[name];
    else values[name] = value;
    if (panelId === null) delete from[name];
    else from[name] = panelId;
    return { ...current, values, from };
  }

  /** The time grouping's unit; one the board does not offer is ignored. */
  unit(unit: AnalysisDateUnit): void {
    if (this.heldUnit) return;
    this.put({ ...this.host.current(), unit });
  }

  /**
   * Every filter as asked, all or nothing: a reader's one change (`set`,
   * `press`, a unit) is refused whole, and nothing moves.
   */
  private put(wanted: DashboardFilters): Issue[] {
    const { filters, refused } = admitFilters(
      this.host.applied(),
      wanted,
      this.host.kinds,
    );
    if (refused.length > 0) return refused;
    if (!dequal(filters, this.host.current())) this.commit(filters);
    return [];
  }

  /**
   * Every filter at once, as a host's address has them (`setFilters`, and
   * what a board opens on): what the board takes goes in, and what it
   * refuses — a filter renamed or taken off since the address was written,
   * a value its kind cannot read — is left out and answered, so one stale
   * name never costs the reader the rest of the address.
   */
  take(wanted: DashboardFilters): Issue[] {
    const { filters, refused } = admitFilters(
      this.host.applied(),
      wanted,
      this.host.kinds,
    );
    if (!dequal(filters, this.host.current())) this.commit(filters);
    return refused;
  }

  /** Shows what the filters hold at once, and runs it a moment later. */
  private commit(filters: DashboardFilters): void {
    this.host.commit(filters);
    if (this.host.started()) {
      this.timer.stop();
      this.timer.sync(AUTO_APPLY_DELAY_MS);
    }
  }

  /**
   * The values in force, admitted against `config`: a filter taken off
   * holds nothing, a required one never less than its default; the same
   * object while they say the same, so a re-sync does not tell the host
   * they changed.
   */
  on(config: DashboardViewConfig): DashboardFilters {
    const current = this.host.current();
    const { filters } = admitFilters(config, current, this.host.kinds);
    return dequal(filters, current) ? current : filters;
  }

  /**
   * What a text filter offers to pick from: the values of the fields it is
   * wired to, counted, one list across the board (`FilterCandidates`);
   * `null` for a filter of another type, one with a list of its own, or one
   * wired to no field that offers its values.
   */
  candidatesOf(name: string): ValueCandidateSource | null {
    const applied = this.host.applied();
    const filter = filtersOf(applied).find(field => field.name === name);
    if (!filter || filterTypeOf(filter.kind) !== 'text' || filter.options)
      return null;
    // A list the wired fields declare is picked from, never counted.
    if (this.optionsOf(name)) return null;
    const targets = panelsOf(applied).flatMap(panel => {
      if (!isViewPanel(panel)) return [];
      const binding = bindingsOf(panel).find(
        entry => entry.globalField === name,
      );
      const view = binding && this.host.viewOf(panel);
      if (!binding || !view) return [];
      // Counted under the condition the panel runs under (`panelScope`) —
      // the board's fixed scope, the host's condition and the filters the
      // host holds, never the reader's own values — so a board fixed to one
      // region, or a page locked to one customer, offers that one's values
      // and no one else's. Read as it is asked: the source outlives this
      // call, and the board may have changed by then.
      const scope = () => {
        const board = this.host.applied();
        const now =
          panelsOf(board)
            .filter(isViewPanel)
            .find(entry => entry.id === panel.id) ?? panel;
        const tree = panelScope(now, {
          applied: board,
          filters: { values: this.heldValues() },
          injected: this.host.scope(),
          kinds: this.host.kinds,
        });
        return isEmptyFilter(tree) ? null : tree;
      };
      return [
        {
          panelId: panel.id,
          definition: view.definition,
          field: binding.panelField,
          scope,
        },
      ];
    });
    return this.candidates.of(name, targets);
  }

  /**
   * The list a filter without one of its own picks from: what the fields
   * it is wired to declare, merged (`wiredOptions`); `null` when none does.
   */
  optionsOf(name: string): FieldOption[] | null {
    const applied = this.host.applied();
    const filter = filtersOf(applied).find(field => field.name === name);
    if (!filter || filter.options) return null;
    return wiredOptions(
      applied,
      name,
      panel => this.host.viewOf(panel)?.definition.fields ?? null,
    );
  }

  /** What the filters the host holds hold now. */
  private heldValues(): Record<string, FilterValue> {
    const { values } = this.host.current();
    return Object.fromEntries(
      Object.entries(values).filter(([name]) => this.held.has(name)),
    );
  }

  /** The host's condition changed: what was counted under it is forgotten. */
  rescoped(): void {
    this.candidates.reset();
  }

  stop(): void {
    this.timer.stop();
  }
}

/** What a reader's command on a filter the host holds is answered with. */
function heldIssue(name: string): Issue {
  return issue('dashboard.filter.held', ['filters', name], { field: name });
}
