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

import {
  parseSystemInstanceId,
  type AnalysisViewConfig,
  type DashboardViewConfig,
  type ViewInstance,
} from '../../model/index.js';
import {
  withTimeBindings,
  type DataPanelSource,
  type PanelTime,
} from '../../dashboard/index.js';
import { withCanonicalPanelFields } from '../../dashboard/panelFieldNames.js';
import { withCanonicalNames } from '../../capabilities/index.js';
import type { PanelView } from './children.js';
import type { DashboardRuntimeState } from './contract.js';

/** Reads one board config under the paths; itself when nothing is renamed. */
export type BoardReading = (config: DashboardViewConfig) => DashboardViewConfig;

/**
 * The reading of a board whose panels' views `viewOf` knows so far: under
 * the paths they rename aliases to, then with the board's time filter
 * wired to each panel it reaches on its own (`withTimeBindings`, todo D,
 * D1) — a panel whose view is not known yet is wired once it is.
 */
export function canonicalBoard(
  viewOf: (panel: DataPanelSource) => PanelView | null,
): BoardReading {
  const timeOf = panelTime(viewOf);
  return config =>
    withTimeBindings(
      withCanonicalPanelFields(
        config,
        panel => viewOf(panel)?.definition.narrowing?.renamed,
        canonicalOwned,
      ),
      timeOf,
    );
}

/**
 * What a panel's view says of time (`PanelTime`): the system view's own
 * field where it names one (`null` for a view read whole), else the
 * definition's; `undefined` for a view not known yet or a definition that
 * names none. A saved copy of a system view is timed as its definition is.
 */
export function panelTime(
  viewOf: (panel: DataPanelSource) => PanelView | null,
): PanelTime {
  return panel => {
    const view = viewOf(panel);
    if (!view) return undefined;
    const { definition, instance } = view;
    const declared = instance ? parseSystemInstanceId(instance.id) : null;
    const own =
      declared?.definitionId === definition.id
        ? definition.views?.find(entry => entry.id === declared.viewId)
            ?.timeField
        : undefined;
    const name = own === undefined ? definition.timeField : own;
    if (name === undefined) return undefined;
    if (name === null) return null;
    return definition.fields.find(field => field.name === name) ?? null;
  };
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
