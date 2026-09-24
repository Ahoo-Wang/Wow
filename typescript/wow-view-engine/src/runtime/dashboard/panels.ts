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
 * What a dashboard runtime knows about its panels without holding any: how
 * a stored config's panels are read, which panel an issue belongs to, how a
 * child's findings are addressed from the dashboard, and when two panel
 * arrays say the same thing. Nothing here has state, so nothing here needs
 * the runtime.
 */

import { dequal } from 'dequal';
import type {
  DashboardPanel,
  DashboardViewConfig,
  Issue,
  PanelClick,
  ViewKind,
} from '../../model/index.js';
import { clickOf, panelTab, type FilterReach } from '../../dashboard/index.js';
import { issue } from '../../filter/index.js';
import type { PanelGrouping } from './grouping.js';
import type { PanelChild } from './children.js';
import { resultIssues } from '../source.js';
import type { DataViewRuntime } from '../viewRuntime.js';

/** One panel as the grid renders it. */
export interface DashboardPanelState {
  id: string;
  panel: DashboardPanel;
  /**
   * The child runtime of a data panel, once its reference has been loaded and
   * admitted. `null` for a content panel and for one that cannot run.
   */
  runtime: DataViewRuntime | null;
  /** Issues about this panel alone; the dashboard around it still works. */
  issues: Issue[];
  /** The tab it is on (`panelTab`); `null` on a board without tabs. */
  tab: string | null;
  /**
   * A data panel on a tab that has not been shown yet: nothing was asked
   * for it and nothing is wrong with it — its child is made the first time
   * its tab is (D22 E, only the tab on screen runs).
   */
  waiting: boolean;
  /**
   * What each of the board's filters does to it, by filter name
   * (`filterReach`): wired, and through which field, or not and why. Empty
   * for a content panel; a data panel whose view is not known yet answers
   * for its wires alone.
   */
  reach: Readonly<Record<string, FilterReach>>;
  /** What the board's time grouping does to it; see `PanelGrouping`. */
  grouping: PanelGrouping;
  /**
   * What a press on one of its groups does (D22 H, I): its click as set, or
   * `null` for the follow-up menu — none set, or one admission found
   * something wrong with, which the panel's warning says (`clickInForce`).
   */
  click: PanelClick | null;
}

/**
 * A panel's click, unless admission said something about it: a click that
 * cannot do what it says is set aside and a press opens the follow-up menu,
 * as the warning on the panel says. So is one that sets a filter the host
 * holds (`DashboardRuntime.holdFilters`): a press cannot change what the
 * page fixed, so it does what a press does on a panel with no click.
 */
export function clickInForce(
  panel: DashboardPanel,
  issues: readonly Issue[],
  held: (filter: string) => boolean = () => false,
): PanelClick | null {
  const said = issues.some(
    found => found.path[0] === 'panels' && found.path[2] === 'click',
  );
  const click = said ? null : clickOf(panel);
  return click?.kind === 'filter' && held(click.filter) ? null : click;
}

/** The panel an issue belongs to, or `null` for one about the dashboard. */
export function panelOf(found: Issue): number | null {
  const [head, index] = found.path;
  return head === 'panels' && typeof index === 'number' ? index : null;
}

/**
 * Whether the issues hold an error about the dashboard itself — one no
 * panel owns, "too many panels" at `['panels']` included.
 *
 * Only such an error stops the board: its apply, its timer and every panel
 * with it. A panel's own error — a reference it may not use, a binding that
 * does not hold, a link it may not show — stops that panel and nothing else,
 * so a drag still lands and the global filter still reaches the panels that
 * can carry it. One broken panel must not decide what the others show, and
 * a board a user cannot rearrange until some other panel is fixed is one
 * they cannot use.
 */
export function blocksBoard(issues: readonly Issue[]): boolean {
  return issues.some(
    found => found.severity === 'error' && panelOf(found) === null,
  );
}

/**
 * What a board says about itself, above its panels (Q-01, A-07): every
 * finding on the draft that no panel carries. That is the board's own —
 * "too many panels" at `['panels']` among them, which belongs to no one
 * panel — and a panel's that the draft raised and no panel wears yet: a
 * board condition mapped onto a panel field that warns is, until it is
 * applied, a finding under `['panels', …]` that only the draft holds, and
 * a save would write it unseen. A panel's own findings stay the panel's to
 * say, each in its own frame. One reading, so the workbench, an embed that
 * builds and a host drawing its own board say the same.
 */
export function boardFindings(state: {
  issues: readonly Issue[];
  panels: readonly Pick<DashboardPanelState, 'issues'>[];
}): Issue[] {
  const carried = state.panels.flatMap(panel => panel.issues);
  return state.issues.filter(
    found =>
      panelOf(found) === null || !carried.some(shown => dequal(shown, found)),
  );
}

/**
 * Whether these issues stop a view of this kind from being saved.
 *
 * Any error does for a record or an analysis view: its config is one
 * question, and a question that cannot be asked is not worth keeping. A
 * dashboard is stopped only by what stops the board (`blocksBoard`): a
 * panel's own trouble — a view some readers cannot open, a binding that no
 * longer holds, a view whose saved settings no longer work — is said on the
 * panel and saved with the board (D22 B), since the author may well be
 * saving precisely to fix a different panel, and a board nobody can save
 * until every panel is well is one nobody can maintain.
 */
export function stopsSave(kind: ViewKind, issues: readonly Issue[]): boolean {
  return kind === 'dashboard'
    ? blocksBoard(issues)
    : issues.some(found => found.severity === 'error');
}

/** What a thrown value says for itself; not everything thrown is an `Error`. */
export function reasonOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * What a panel reports for a child that runs: the dashboard's own findings
 * about it, then what the child still has to say about its saved config,
 * re-addressed to the panel.
 *
 * The child's part is read off its snapshot rather than off `setScopeFilter`,
 * which answers `[]` for a scope it already holds — a layout edit re-syncs
 * every panel with the scope unchanged, and a warning must not vanish on
 * that. And it is read second: the dashboard re-validates the merged filter
 * against the child's fields, and the child admits the same merged tree, so
 * a kind that warns is heard twice. The panel shows the wording, and the
 * same sentence twice tells nobody anything more, so a warning the
 * dashboard already reported is not repeated.
 *
 * Then what the child's **last result** has to say (`resultIssues`): the
 * summaries that fell back to this page because their query failed, an
 * analysis that filled its limit. Both workbenches and `EmbeddedView` say
 * these; a panel showing the very same truncated pie must not fall silent
 * because it hangs in a dashboard. They are read off the snapshot on every
 * rebuild, and a child notifying on a result is what rebuilds, so the
 * marker on the panel follows the result it sits over.
 */
export function panelIssues(
  index: number,
  own: readonly Issue[],
  runtime: DataViewRuntime,
): Issue[] {
  const snapshot = runtime.getSnapshot();
  // What its own config says beside running: a warning, and a note — the
  // chart it cannot draw for this shape, drawn as its table (`chart.as-table`)
  // — which a panel would otherwise show without a word, as the workbench
  // never does.
  const caveats = snapshot.issues.filter(
    found =>
      found.severity === 'note' ||
      (found.severity === 'warning' &&
        !own.some(
          said =>
            said.severity === 'warning' &&
            said.code === found.code &&
            dequal(said.params, found.params),
        )),
  );
  return [
    ...own,
    ...atPanel(index, caveats),
    ...atPanel(index, resultIssues(snapshot.result?.data)),
  ];
}

/**
 * The panels with one child's panel re-issued from what the child says now
 * (`panelIssues`), or `null` when nothing changed — the child is not the
 * one on screen for its panel, or it says what the panel already does.
 */
export function reissued(
  panels: readonly DashboardPanelState[],
  child: Pick<PanelChild, 'index' | 'own' | 'runtime'> | undefined,
): DashboardPanelState[] | null {
  const at = panels.findIndex(panel => panel.runtime === child?.runtime);
  if (!child || at < 0) return null;
  const current = panels[at];
  const issues = panelIssues(child.index, child.own, child.runtime);
  if (dequal(issues, current.issues)) return null;
  const next = [...panels];
  next[at] = { ...current, issues };
  return next;
}

/** A child's issues, addressed from the dashboard's config. */
export function atPanel(index: number, issues: readonly Issue[]): Issue[] {
  return issues.map(found => ({
    ...found,
    path: ['panels', index, ...found.path],
  }));
}

/**
 * Whether two panel arrays say the same thing, so a re-sync that changes
 * nothing can keep the previous one and a grid bound with
 * `useSyncExternalStore` does not re-render on every apply.
 */
export function samePanels(
  previous: readonly DashboardPanelState[],
  next: readonly DashboardPanelState[],
): boolean {
  return (
    previous.length === next.length &&
    previous.every((panel, index) => {
      const other = next[index];
      return (
        panel.id === other.id &&
        panel.panel === other.panel &&
        panel.runtime === other.runtime &&
        panel.tab === other.tab &&
        panel.waiting === other.waiting &&
        panel.grouping === other.grouping &&
        dequal(panel.reach, other.reach) &&
        dequal(panel.issues, other.issues) &&
        // A click set aside for a filter the host holds changes nothing
        // else about the panel.
        dequal(panel.click, other.click)
      );
    })
  );
}

/**
 * The tab a board shows: the one asked for while it has it, else its first;
 * `null` for a board without tabs. A stored config is read as untrusted.
 */
export function shownTab(
  config: DashboardViewConfig,
  requested: string | null,
): string | null {
  return panelTab(config, requested === null ? {} : { tab: requested });
}

/** A panel whose reference could not be put to work, as its finding. */
export function panelFailure(
  index: number,
  instanceId: string,
  reason: string,
): Issue {
  return issue('dashboard.panel.failed', ['panels', index, 'instanceId'], {
    instance: instanceId,
    reason,
  });
}
