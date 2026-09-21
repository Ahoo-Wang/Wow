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

import type { FilterTree, Issue } from '../../model/index.js';
import type { PanelReference } from '../../dashboard/index.js';
import { hasError, type DataViewRuntime } from '../viewRuntime.js';
import { atPanel, panelIssues } from './panels.js';

/** Builds the child runtime of one data panel, with its scope already in force. */
export type PanelRuntimeFactory = (
  reference: PanelReference,
  scopeFilter: FilterTree | null,
) => DataViewRuntime;

/**
 * A child runtime, the subscription that watches it, and what the last sync
 * knew about its panel — its index in the config and the dashboard's own
 * findings about it — so the panel's issues can be rebuilt when the child
 * alone changes.
 */
export interface PanelChild {
  runtime: DataViewRuntime;
  unsubscribe: () => void;
  /** The panel's index in the config, where its issues are addressed. */
  index: number;
  /** The dashboard's own findings about the panel, as of the last sync. */
  own: Issue[];
}

/**
 * The child runtimes of a dashboard's data panels, by panel id, and their
 * lifecycle: brought in line with a panel and its scope on every sync, let
 * go when the panel goes or points elsewhere, disposed with the dashboard.
 *
 * A child admits the scope it is handed like any condition, and a refusal is
 * the panel's problem: the child stops rather than running its previous
 * scope, and the reasons land in the panel's issues where the dashboard's
 * own would. The dashboard is told when a child notifies — its timer waits
 * on its panels, and a panel's issues follow its child — and subscribes to
 * nothing else here; the UI watches each child itself.
 */
export class PanelChildren {
  private readonly children = new Map<string, PanelChild>();

  constructor(
    private readonly create: PanelRuntimeFactory,
    /** Called whenever a child notifies, with the panel it belongs to. */
    private readonly notified: (panelId: string) => void,
  ) {}

  get(panelId: string): PanelChild | undefined {
    return this.children.get(panelId);
  }

  /** The child runtime of one panel, for a host that drives a panel itself. */
  runtimeOf(panelId: string): DataViewRuntime | null {
    return this.children.get(panelId)?.runtime ?? null;
  }

  /**
   * One panel's child for a reference that can be run, and what the panel
   * reports: the existing child re-scoped when it still stands for the
   * reference, a new one otherwise.
   */
  sync(
    panelId: string,
    index: number,
    own: Issue[],
    reference: PanelReference,
    scope: FilterTree | null,
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const existing = this.children.get(panelId);
    if (existing && holds(existing.runtime, reference)) {
      // Before the scope goes in: the child may notify on the spot, and the
      // refresh that answers it reads these.
      existing.index = index;
      existing.own = own;
      const refused = existing.runtime.setScopeFilter(scope);
      if (!hasError(refused))
        return {
          runtime: existing.runtime,
          issues: panelIssues(index, own, existing.runtime),
        };
      this.drop(panelId);
      return { runtime: null, issues: [...own, ...atPanel(index, refused)] };
    }
    // The panel points somewhere else now, or the instance was reloaded.
    if (existing) this.drop(panelId);

    const runtime = this.create(reference, scope);
    // A scope a panel's definition refuses is no longer among that child's
    // issues — it drops what it cannot carry and runs un-narrowed, which is
    // right for a view a host embedded and wrong for a panel of a board: the
    // dashboard's condition is the question the panel was put there to answer,
    // so a panel that cannot carry it shows nothing and says why.
    const refused = [...runtime.refusedScope, ...runtime.getSnapshot().issues];
    if (hasError(refused)) {
      runtime.dispose();
      return { runtime: null, issues: [...own, ...atPanel(index, refused)] };
    }
    const unsubscribe = runtime.subscribe(() => this.notified(panelId));
    this.children.set(panelId, { runtime, unsubscribe, index, own });
    runtime.apply();
    return { runtime, issues: panelIssues(index, own, runtime) };
  }

  /** Lets go of the children whose panels are no longer among `live`. */
  keepOnly(live: ReadonlySet<string>): void {
    for (const panelId of [...this.children.keys()])
      if (!live.has(panelId)) this.drop(panelId);
  }

  drop(panelId: string): void {
    const child = this.children.get(panelId);
    if (!child) return;
    child.unsubscribe();
    child.runtime.dispose();
    this.children.delete(panelId);
  }

  disposeAll(): void {
    for (const child of this.children.values()) {
      child.unsubscribe();
      child.runtime.dispose();
    }
    this.children.clear();
  }

  /** Re-runs every child on what it has applied. */
  refresh(): void {
    for (const child of this.children.values()) child.runtime.refresh();
  }

  /** Whether any child has a query in flight. */
  loading(): boolean {
    for (const child of this.children.values())
      if (child.runtime.getSnapshot().query.status === 'loading') return true;
    return false;
  }
}

/** Whether a child runtime still stands for exactly this reference. */
function holds(runtime: DataViewRuntime, reference: PanelReference): boolean {
  const saved = runtime.getSnapshot().saved;
  return (
    saved?.id === reference.instance.id &&
    saved.revision === reference.instance.revision
  );
}
