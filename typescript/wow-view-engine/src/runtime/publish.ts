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
 * What a system view is made from (D81): 「发布为系统视图」, a saved view
 * copied as a stored system view, which `ViewEngine.publishAsSystem`
 * sends; and the one rule a system board keeps — every saved view its
 * panels reference is a system view, since system views are read in every
 * tenant and a shared or personal one is not.
 */

import {
  isSystemInstanceId,
  isSystemScope,
  listParam,
  type ViewConfig,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import { isViewPanel } from '../dashboard/index.js';
import { issue } from '../filter/index.js';
import { requireStorable } from './commandChecks.js';
import type { ViewStore } from '../store/ViewStore.js';
import type { PermissionGuard } from './permissions.js';
import type { SummaryCache } from './summaries.js';
import { ViewCommandError } from './write.js';
import type { WriteLedger } from './writeLedger.js';

/** What a saved view a panel references is, as far as it can be read. */
export type ReferenceOf = (
  id: string,
) => Promise<{ scope: ViewScope; title: string } | undefined>;

/**
 * Creates the copy of the saved view `id` (`read`: an open view's saved
 * instance, else the store's) — its title and its saved config, never a
 * draft — as a stored system view. Asked first: that the source is not a
 * system view already (`view.system.read-only`, as the manager offers no
 * such button), `editSystem` (`view.create.forbidden`) and the config's
 * size; a board's panels the ledger asks of every create into the system
 * scope (`requireSystemPanels`). The source is not
 * written; the copy lands like a 另存为 (`save-as`), bound to no open view.
 */
export async function publishCopy(
  guard: PermissionGuard,
  ledger: WriteLedger,
  id: string,
  read: () => Promise<ViewInstance>,
): Promise<ViewInstance> {
  // A view declared in code is refused before anything is read: no store
  // holds it.
  const source = isSystemInstanceId(id) ? null : await read();
  if (!source || isSystemScope(source.scope))
    throw new ViewCommandError(
      issue('view.system.read-only', [], { action: 'publish' }),
    );
  const { definitionId, title, config } = source;
  guard.requireCreate(definitionId, 'system');
  return (await ledger.dispatch(
    {
      action: 'create',
      input: {
        definitionId,
        title,
        scope: 'system',
        config: requireStorable(config),
      },
      intent: 'save-as',
    },
    undefined,
  )) as ViewInstance;
}

/**
 * Refuses a board headed for the system views when any panel references a
 * saved view that is not a system view — the one it shows, the one
 * 「在工作台中打开」 opens, or the one a press opens — naming those panels
 * (`dashboard.system.non-system-panels`): such a panel would be blank for
 * every reader in every other tenant. A view the board owns, and a view
 * that cannot be read, count as they are: owned is fine, unreadable is not.
 * Asked of a `scope` other than `system`, it lets the board pass.
 */
export async function requireSystemPanels(
  config: ViewConfig,
  referenceOf: ReferenceOf,
  scope: ViewScope = 'system',
): Promise<void> {
  if (!isSystemScope(scope) || config.kind !== 'dashboard') return;
  const named: string[] = [];
  for (const panel of config.panels) {
    if (!isViewPanel(panel)) continue;
    const ids = [
      panel.owned === undefined ? panel.instanceId : undefined,
      panel.opens,
      panel.click && 'instanceId' in panel.click
        ? panel.click.instanceId
        : undefined,
    ].filter((id): id is string => typeof id === 'string' && id !== '');
    const read = await Promise.all(
      ids.map(async id =>
        isSystemInstanceId(id)
          ? { scope: 'system' as const, title: '' }
          : referenceOf(id),
      ),
    );
    if (read.every(found => found !== undefined && isSystemScope(found.scope)))
      continue;
    const shown = panel.owned === undefined ? read[0]?.title : undefined;
    named.push(panel.title ?? shown ?? panel.id);
  }
  if (named.length > 0)
    throw new ViewCommandError(
      issue('dashboard.system.non-system-panels', [], {
        panels: listParam(named),
        count: named.length,
      }),
    );
}

/**
 * How the engine reads a panel's saved view for `requireSystemPanels`: the
 * summary it last saw, else the store's copy; one that cannot be read is
 * none.
 */
export function readReferences(
  summaries: SummaryCache,
  store: ViewStore,
): ReferenceOf {
  return async id => summaries.get(id) ?? store.get(id).catch(() => undefined);
}
