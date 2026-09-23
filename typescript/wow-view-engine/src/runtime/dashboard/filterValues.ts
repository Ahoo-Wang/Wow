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
  type FilterTree,
  type FilterValue,
  type Issue,
} from '../../model/index.js';
import type { FieldKindRegistry } from '../../filter/index.js';
import {
  admitFilters,
  bindingsOf,
  filtersOf,
  isViewPanel,
  mapGlobalFilter,
} from '../../dashboard/index.js';
import { AUTO_APPLY_DELAY_MS } from '../autoApply.js';
import type { RuntimeEnvironment } from '../environment.js';
import { RefreshTimer } from '../refreshTimer.js';
import type { ValueCandidateSource } from '../valueCandidates.js';
import type { PanelView } from './children.js';
import {
  FilterCandidates,
  type CandidateSourceFactory,
} from './filterCandidates.js';
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

  constructor(private readonly host: FilterValuesHost) {
    this.timer = new RefreshTimer(host.environment, () => host.run());
    this.candidates = new FilterCandidates(host.candidateSources);
  }

  /** One filter set; `null` clears it — a required one to its default. */
  set(name: string, value: FilterValue | null): Issue[] {
    const current = this.host.current();
    const values = { ...current.values };
    if (value === null) delete values[name];
    else values[name] = value;
    return this.put({ ...current, values });
  }

  /** The time grouping's unit; one the board does not offer is ignored. */
  unit(unit: AnalysisDateUnit): void {
    this.put({ ...this.host.current(), unit });
  }

  /** Every filter as asked. */
  put(wanted: DashboardFilters): Issue[] {
    const { filters, refused } = admitFilters(
      this.host.applied(),
      wanted,
      this.host.kinds,
    );
    if (refused.length > 0) return refused;
    if (dequal(filters, this.host.current())) return [];
    this.host.commit(filters);
    if (this.host.started()) {
      this.timer.stop();
      this.timer.sync(AUTO_APPLY_DELAY_MS);
    }
    return [];
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
    const targets = panelsOf(applied).flatMap(panel => {
      if (!isViewPanel(panel)) return [];
      const binding = bindingsOf(panel).find(
        entry => entry.globalField === name,
      );
      const view = binding && this.host.viewOf(panel);
      if (!binding || !view) return [];
      const scope = () => {
        const outer = this.host.scope();
        return outer && mapGlobalFilter(outer, bindingsOf(panel));
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

  /** The host's condition changed: what was counted under it is forgotten. */
  rescoped(): void {
    this.candidates.reset();
  }

  stop(): void {
    this.timer.stop();
  }
}
