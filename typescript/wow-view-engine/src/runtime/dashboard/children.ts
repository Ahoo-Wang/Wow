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
  presentationMembers,
  type DataViewDefinition,
  type FilterTree,
  type Issue,
  type ViewInstance,
  type ViewScope,
} from '../../model/index.js';
import { isPlainObject } from '../../filter/index.js';
import type {
  DataPanelSource,
  PanelDefinition,
  PanelReference,
} from '../../dashboard/index.js';
import type { DataViewConfig } from '../execute.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import { hasError } from '../runtimeStore.js';
import { atPanel, panelIssues } from './panels.js';

/**
 * What one data panel runs: the view it shows — a saved one, or one the
 * board owns — and the config its child is handed, which is the view's own
 * with the panel's override of how it looks laid over it.
 */
export interface PanelView {
  /** The saved view, the child's baseline; `null` for a view the board owns. */
  instance: ViewInstance | null;
  definition: DataViewDefinition;
  config: DataViewConfig;
  /** The view's title, or the panel's for an owned view. */
  title: string;
  /** Who reads the view: the saved view's audience, or the board's own. */
  scope: ViewScope;
}

/**
 * The view a data panel shows, once there is one to run: the saved view its
 * reference loaded, or the one it owns, judged by the definition this
 * release declares for it. `null` while a reference is loading, and for one
 * admission has already said cannot run. An owned view has no title of its
 * own — the panel's is its name — and is read by the board's audience.
 */
export function panelView(
  panel: DataPanelSource,
  reference: (instanceId: string) => PanelReference | null | undefined,
  definitions: (definitionId: string) => PanelDefinition | null,
  board: ViewScope,
): PanelView | null {
  const owned: unknown = panel.owned;
  if (owned !== undefined) {
    if (!isPlainObject(owned) || typeof owned.definitionId !== 'string')
      return null;
    const found = definitions(owned.definitionId);
    if (found?.definition.kind !== 'data') return null;
    return {
      instance: null,
      definition: found.definition,
      config: owned.config as DataViewConfig,
      title: panel.title ?? '',
      scope: board,
    };
  }
  const loaded =
    typeof panel.instanceId === 'string' ? reference(panel.instanceId) : null;
  if (loaded?.definition.kind !== 'data') return null;
  const { instance, definition } = loaded;
  return {
    instance,
    definition,
    config: instance.config as DataViewConfig,
    title: instance.title,
    scope: instance.scope,
  };
}

/** Builds the child runtime of one data panel, with its scope already in force. */
export type PanelRuntimeFactory = (
  view: PanelView,
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
  /** The view it was built for, and the config it was last handed. */
  view: PanelView;
  unsubscribe: () => void;
  /** The panel's index in the config, where its issues are addressed. */
  index: number;
  /** The dashboard's own findings about the panel, as of the last sync. */
  own: Issue[];
  /**
   * A refresh of the board went by while the panel was on a tab not shown
   * (`PanelChildren.refresh`): its rows are older than the board's, so it
   * is refreshed the next time its tab is shown.
   */
  missed: boolean;
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
   * One panel's child for a view that can be run, and what the panel
   * reports: the existing child re-scoped when it still stands for the same
   * view, a new one otherwise.
   *
   * The same view with another config — the panel's override changed, or
   * the question a board owns was edited — is handed to the child as an
   * edit and run, rather than a new child: the rows on screen stay until the
   * new ones land, instead of the panel blanking to a skeleton for a change
   * of chart. A config the child now refuses stops it, as a refused scope
   * does.
   *
   * A change of how the rows are drawn alone — the layout, the chart
   * (`presentationMembers`) — is an edit and nothing more: presentation
   * never asks the source (D20), and the panel draws the child's draft over
   * the rows it has (`useAnalysisResult`). A child that missed a refresh
   * while its tab was away is refreshed here, unless what changed runs it
   * anyway.
   */
  sync(
    panelId: string,
    index: number,
    own: Issue[],
    view: PanelView,
    scope: FilterTree | null,
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const existing = this.children.get(panelId);
    if (existing && sameView(existing.view, view)) {
      // Before the scope goes in: the child may notify on the spot, and the
      // refresh that answers it reads these.
      existing.index = index;
      existing.own = own;
      const changed = !dequal(existing.view.config, view.config);
      const asks = changed && !drawsOnly(existing.view.config, view.config);
      if (changed)
        existing.runtime.edit(replacing(existing.view.config, view.config));
      existing.view = view;
      const rescoped = !dequal(existing.runtime.scopeFilter, scope ?? null);
      const refused = changed ? [...existing.runtime.getSnapshot().issues] : [];
      if (!hasError(refused))
        refused.push(...existing.runtime.setScopeFilter(scope));
      if (!hasError(refused)) {
        if (asks) existing.runtime.apply();
        else if (existing.missed && !rescoped) existing.runtime.refresh();
        existing.missed = false;
        return {
          runtime: existing.runtime,
          issues: panelIssues(index, own, existing.runtime),
        };
      }
      this.drop(panelId);
      return { runtime: null, issues: [...own, ...atPanel(index, refused)] };
    }
    // The panel points somewhere else now, or the instance was reloaded.
    if (existing) this.drop(panelId);

    const runtime = this.create(view, scope);
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
    this.children.set(panelId, {
      runtime,
      view,
      unsubscribe,
      index,
      own,
      missed: false,
    });
    runtime.apply();
    return { runtime, issues: panelIssues(index, own, runtime) };
  }

  /**
   * A panel on a tab not shown: its child, if it has one, is kept as it is —
   * rows and all, neither re-scoped nor re-run (D22 E: only the tab on
   * screen runs) — and only where its findings are addressed moves with the
   * config. `null` for a panel that has never been shown.
   */
  hold(
    panelId: string,
    index: number,
    own: Issue[],
  ): { runtime: DataViewRuntime; issues: Issue[] } | null {
    const held = this.children.get(panelId);
    if (!held) return null;
    held.index = index;
    held.own = own;
    return {
      runtime: held.runtime,
      issues: panelIssues(index, own, held.runtime),
    };
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

  /**
   * Re-runs every child on the tab shown on what it has applied; the others
   * are marked to run when their tab is shown again (`sync`).
   */
  refresh(shown: (panelId: string) => boolean = () => true): void {
    for (const [panelId, child] of this.children)
      if (shown(panelId)) child.runtime.refresh();
      else child.missed = true;
  }

  /**
   * The soonest moment a child on the tab shown stops being true of its own
   * accord (`DataViewRuntime.rolloverAt`) — a metric card whose period has
   * ended — or `null`. The board asks again then, as it refreshes: every
   * panel on screen on one clock.
   */
  rolloverAt(shown: (panelId: string) => boolean): number | null {
    let soonest: number | null = null;
    for (const [panelId, child] of this.children) {
      if (!shown(panelId)) continue;
      const at = child.runtime.rolloverAt();
      if (at !== null && (soonest === null || at < soonest)) soonest = at;
    }
    return soonest;
  }

  /** Whether any child has a query in flight. */
  loading(): boolean {
    for (const child of this.children.values())
      if (child.runtime.getSnapshot().query.status === 'loading') return true;
    return false;
  }
}

/**
 * An edit that turns one config into another: every member of the next,
 * and every member only the previous one has taken off — an edit is a
 * patch, and a member it leaves out would otherwise stay.
 */
function replacing(
  previous: DataViewConfig,
  next: DataViewConfig,
): Partial<DataViewConfig> {
  const patch: Record<string, unknown> = {};
  for (const key of Object.keys(previous)) patch[key] = undefined;
  return { ...patch, ...next };
}

/**
 * Whether two configs of one view differ only in how the rows are drawn —
 * the members that never ask the source (`presentationMembers`).
 */
function drawsOnly(previous: DataViewConfig, next: DataViewConfig): boolean {
  const drawn = presentationMembers(next.kind);
  const before: Record<string, unknown> = { ...previous };
  const after: Record<string, unknown> = { ...next };
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...keys].every(
    key => drawn.includes(key) || dequal(before[key], after[key]),
  );
}

/**
 * Whether a child built for one view still stands for another: the same
 * saved view at the same revision, or a view the board owns of the same
 * definition and kind. Its config may still differ; `sync` hands that over.
 */
function sameView(held: PanelView, next: PanelView): boolean {
  if (held.definition.id !== next.definition.id) return false;
  if (held.config.kind !== next.config.kind) return false;
  if (held.instance === null || next.instance === null)
    return held.instance === next.instance;
  return (
    held.instance.id === next.instance.id &&
    held.instance.revision === next.instance.revision
  );
}
