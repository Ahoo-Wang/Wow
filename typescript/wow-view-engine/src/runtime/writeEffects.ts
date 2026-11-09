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

import { isOwnedPanel, isViewPanel, panelsOf } from '../dashboard/index.js';
import type { ViewInstance, ViewPreferences } from '../model/index.js';
import { DashboardViewRuntime } from './dashboardRuntime.js';
import type { ManagedViewRuntime } from './viewRuntimeTypes.js';
import type { WritePayload } from './write.js';
import type { WriteLedgerHost, WriteResult } from './writeLedger.js';

/**
 * What a confirmed write changes. It hangs off the payload rather than the
 * command, so replaying an intent as a retry or an overwrite advances the
 * same baseline, and completes the same operation, the first attempt would
 * have: an effect left after the first attempt's `await` was lost with its
 * answer, and a retry that recovered the write left the operation half
 * done (#5478).
 *
 * `target` is the runtime the operation acts on besides the one the write
 * went out through — the board a panel's view was saved from.
 */
export function applyWriteEffect(
  host: WriteLedgerHost,
  payload: WritePayload,
  result: WriteResult,
  runtime: ManagedViewRuntime | undefined,
  target: ManagedViewRuntime | undefined,
): void {
  switch (payload.action) {
    case 'create': {
      const instance = result as ViewInstance;
      host.noteInstance(instance);
      if (payload.intent === 'first-save') runtime?.markSaved(instance);
      if (payload.panel) promotePanel(target, payload.panel, instance);
      announce(host, 'create', instance);
      return;
    }
    case 'save':
    case 'rename':
    case 'changeAudience': {
      const instance = result as ViewInstance;
      host.noteInstance(instance);
      // Every open view of this instance moves to the new baseline, not
      // only the one the command came through: the same view open twice
      // would otherwise keep a revision nobody can write against. Only a
      // save is the view's own write, and only it is marked saved — which
      // settles the view's outcome and, on a board, starts its history
      // again. A rename or a change of audience carries no config: it
      // moves the baseline and leaves the draft, the board's undo and any
      // write of the view's own still waiting exactly as they were.
      if (payload.action === 'save') runtime?.markSaved(instance);
      const added = payload.action === 'save' ? payload.addedPanel : undefined;
      for (const holder of host.holders(instance.id))
        if (added !== undefined) addedToBoard(holder, instance, added);
        else if (payload.action !== 'save' || holder !== runtime)
          holder.moveBaseline(instance);
      announce(host, payload.action, instance);
      return;
    }
    case 'delete':
      // Preferences keep the id; a later reorder or default cleans it up.
      host.dropInstance(payload.id, runtime);
      host.noteChange({
        definitionId: payload.definitionId,
        kind: 'delete',
        id: payload.id,
      });
      return;
    case 'preferences':
      host.notePreferences(payload.definitionId, result as ViewPreferences);
  }
}

/** The actions that hand back an instance say so the same way. */
function announce(
  host: WriteLedgerHost,
  kind: 'create' | 'save' | 'rename' | 'changeAudience',
  instance: ViewInstance,
): void {
  host.noteChange({
    definitionId: instance.definitionId,
    kind,
    id: instance.id,
  });
}

/**
 * 放进仪表盘 confirmed (#5447). The board as saved is the board it was made
 * from plus one panel. An open board with nothing of its own takes it as it
 * now is. One its reader edited while the write was out keeps those edits:
 * its baseline moves to the saved revision, and the added panel is carried
 * onto its draft — placed in the draft as it is now, under the id it was
 * saved with — so the draft is that revision plus the reader's edits, and
 * the next save neither drops the panel nor loses an edit.
 *
 * That is right only because the write was this engine's own and its whole
 * change is known: one panel. A board that learns of a newer revision any
 * other way is never treated so. The command refuses a board already
 * edited, so a draft still clean here is the config the write was made
 * from; and a retry carries nothing twice — the panel goes by its id.
 */
function addedToBoard(
  holder: ManagedViewRuntime,
  saved: ViewInstance,
  panelId: string,
): void {
  if (!(holder instanceof DashboardViewRuntime)) return;
  if (!holder.getSnapshot().dirty) {
    holder.adoptSaved(saved);
    return;
  }
  holder.moveBaseline(saved);
  const board = saved.config.kind === 'dashboard' ? saved.config : null;
  const panel = panelsOf(board ?? { panels: [] }).find(
    entry => entry.id === panelId,
  );
  if (panel) holder.carryPanel(panel);
}

/**
 * A panel's view saved as a view of its own (「另存为视图」, D22 C),
 * confirmed: the panel shows the new view from now on — after the first
 * attempt and after a retry that recovered the create alike. Only while the
 * board is open and the panel still shows what was saved: one removed, or
 * pointed at another view meanwhile, is the reader's later word. Done once:
 * a panel already showing the new view is left as it is.
 */
function promotePanel(
  board: ManagedViewRuntime | undefined,
  { id, shows }: { id: string; shows: string | null },
  instance: ViewInstance,
): void {
  if (!(board instanceof DashboardViewRuntime) || board.disposed) return;
  const panel = board.getSnapshot().draft.panels.find(entry => entry.id === id);
  const stillShows =
    shows === null
      ? isOwnedPanel(panel)
      : isViewPanel(panel) &&
        panel.owned === undefined &&
        panel.instanceId === shows;
  if (stillShows) board.referToSaved(id, instance);
}
