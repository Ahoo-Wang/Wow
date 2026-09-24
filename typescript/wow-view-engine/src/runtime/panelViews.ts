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
  RuntimeLimits,
  ViewAudience,
  ViewConfig,
  ViewInstance,
} from '../model/index.js';
import { isOwnedPanel, isViewPanel } from '../dashboard/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { DashboardViewRuntime } from './dashboardRuntime.js';
import type { DefinitionRegistry } from './definitions.js';
import { validateDataConfig } from './execute.js';
import type { PermissionGuard } from './permissions.js';
import { ViewCommandError } from './write.js';
import type { WriteLedger } from './writeLedger.js';

/** What saving a panel's view stands on: the engine's own parts. */
export interface PanelViewsHost {
  registry: DefinitionRegistry;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  guard: PermissionGuard;
  ledger: WriteLedger;
}

/**
 * Which view of a panel is saved as a view of its own: the analysis the
 * board owns (「另存为视图」, D22 C), or the saved view it shows, copied for
 * another audience (「复制为共享视图并替换」, D22 B).
 */
export type PanelViewSource = 'owned' | 'saved';

/** The refusal for each source, when the panel has none. */
const REFUSED: Record<PanelViewSource, string> = {
  owned: 'dashboard.panel.not-owned',
  saved: 'dashboard.panel.not-referenced',
};

/**
 * A dashboard panel's view saved as a view of its own, the panel pointed at
 * it (`ViewEngine.saveOwnedView`, `ViewEngine.copyPanelView`): the new
 * instance is listed with the definition's other views from then on, and
 * the panel keeps its title, wiring, click and override — it shows the very
 * same view. The board itself is not written: the panel's new reference is
 * an edit of its draft, saved with the board like any other.
 *
 * A saved view is copied as it was saved — not the panel's look over it,
 * nor the board's filters, which stay the panel's — and is left as it is.
 * Judged as any save-as is: the config against its definition, a title,
 * the right to create at that audience. A panel without that source is
 * refused (`dashboard.panel.not-owned`, `dashboard.panel.not-referenced`).
 */
export class PanelViews {
  constructor(private readonly host: PanelViewsHost) {}

  async save(
    target: unknown,
    panelId: string,
    from: PanelViewSource,
    input: { title: string; scope: ViewAudience },
  ): Promise<ViewInstance> {
    const board = target instanceof DashboardViewRuntime ? target : null;
    const view = board && sourceOf(board, panelId, from);
    if (!board || !view)
      throw new ViewCommandError(issue(REFUSED[from], [], { panel: panelId }));
    const { definitionId, config } = view;
    const definition = this.host.registry.require(definitionId);
    if (input.title.trim().length === 0)
      throw new ViewCommandError(issue('view.title.empty', ['title']));
    if (definition.kind !== 'data' || config.kind === 'dashboard')
      throw new ViewCommandError(
        issue('runtime.kind.not-declared', [], {
          definition: definitionId,
          kind: config.kind,
        }),
      );
    const { kinds, limits } = this.host;
    const found = validateDataConfig({ definition, kinds, limits }, config);
    if (found.some(entry => entry.severity === 'error'))
      throw new ViewCommandError(issue('view.config.invalid', []));
    this.host.guard.requireCreate(definitionId, input.scope);

    const { title, scope } = input;
    const instance = (await this.host.ledger.dispatch(
      {
        action: 'create',
        input: { definitionId, title, scope, config },
        intent: 'save-as',
      },
      undefined,
    )) as ViewInstance;
    board.referToSaved(panelId, instance);
    return instance;
  }
}

/**
 * The view a panel would save: what it owns, or the saved view its child
 * has open — which is the one this reader can read, and none while the
 * reference is unread or refused.
 */
function sourceOf(
  board: DashboardViewRuntime,
  panelId: string,
  from: PanelViewSource,
): { definitionId: string; config: ViewConfig } | null {
  const panel = board
    .getSnapshot()
    .draft.panels.find(entry => entry.id === panelId);
  if (from === 'owned') return isOwnedPanel(panel) ? panel.owned : null;
  const saved = board.panelRuntime(panelId)?.getSnapshot().saved;
  return isViewPanel(panel) &&
    panel.owned === undefined &&
    saved?.id === panel.instanceId
    ? saved
    : null;
}
