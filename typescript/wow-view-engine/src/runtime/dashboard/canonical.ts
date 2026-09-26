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
 * A board read under the paths its panels' definitions rename aliases to
 * (#3519, capabilities.md 18), as far as the panels are known: an owned
 * view's at once, a referenced one's once it has loaded. A filter wired by
 * an alias then narrows its panel through the path the panel runs on, and
 * the board is saved under it next time.
 */

import type {
  AnalysisViewConfig,
  DashboardViewConfig,
  ViewInstance,
} from '../../model/index.js';
import type { DataPanelSource } from '../../dashboard/index.js';
import { withCanonicalPanelFields } from '../../dashboard/panelFieldNames.js';
import { withCanonicalNames } from '../../capabilities/index.js';
import type { PanelView } from './children.js';
import type { DashboardRuntimeState } from './contract.js';

/** Reads one board config under the paths; itself when nothing is renamed. */
export type BoardReading = (config: DashboardViewConfig) => DashboardViewConfig;

/** The reading of a board whose panels' views `viewOf` knows so far. */
export function canonicalBoard(
  viewOf: (panel: DataPanelSource) => PanelView | null,
): BoardReading {
  return config =>
    withCanonicalPanelFields(
      config,
      panel => viewOf(panel)?.definition.narrowing?.renamed,
      canonicalOwned,
    );
}

/** A saved board read so; itself when nothing is renamed. */
export function canonicalSaved(
  saved: ViewInstance | null,
  read: BoardReading,
): ViewInstance | null {
  if (saved?.config.kind !== 'dashboard') return saved;
  const config = read(saved.config);
  return config === saved.config ? saved : { ...saved, config };
}

/**
 * The draft, the applied board and the baseline read so together, as a
 * patch — the baseline moves with them, so a board saved under an alias
 * does not open dirty. Empty when none of them changes.
 */
export function canonicalState(
  state: Pick<DashboardRuntimeState, 'draft' | 'applied' | 'saved'>,
  read: BoardReading,
  store: {
    isDirty(draft: DashboardViewConfig, saved: ViewInstance | null): boolean;
  },
): Partial<DashboardRuntimeState> {
  const { draft, applied, saved } = state;
  const next = {
    draft: read(draft),
    applied: read(applied),
    saved: canonicalSaved(saved, read),
  };
  if (next.draft === draft && next.applied === applied && next.saved === saved)
    return {};
  return { ...next, dirty: store.isDirty(next.draft, next.saved) };
}

/**
 * A view the board owns under the paths its definition renames aliases to.
 * One that is not a config at all is left as it is, for admission to report
 * on its panel alone.
 */
function canonicalOwned(
  config: AnalysisViewConfig,
  renamed: Readonly<Record<string, string>>,
): AnalysisViewConfig {
  try {
    return withCanonicalNames(config, renamed);
  } catch {
    return config;
  }
}
