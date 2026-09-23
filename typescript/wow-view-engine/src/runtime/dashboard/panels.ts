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
} from '../../model/index.js';
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
}

/**
 * The panels a config holds, read as the untrusted thing a stored config is.
 * Admission reports a `panels` that is not an array; until it is fixed there
 * is nothing to load or run, and nothing to throw about.
 */
export function panelsOf(
  config: DashboardViewConfig,
): readonly DashboardPanel[] {
  return Array.isArray(config.panels) ? config.panels : [];
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
  const caveats = snapshot.issues.filter(
    found =>
      found.severity === 'warning' &&
      !own.some(
        said =>
          said.severity === 'warning' &&
          said.code === found.code &&
          dequal(said.params, found.params),
      ),
  );
  return [
    ...own,
    ...atPanel(index, caveats),
    ...atPanel(index, resultIssues(snapshot.result?.data)),
  ];
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
        dequal(panel.issues, other.issues)
      );
    })
  );
}
