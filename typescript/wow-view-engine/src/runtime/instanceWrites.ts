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

import { addPanel } from '../dashboard/index.js';
import {
  audienceOf,
  type DashboardViewConfig,
  type ViewAudience,
  type ViewDefinition,
  type ViewInstance,
  type ViewScope,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type { InstancePermissions, ViewStore } from '../store/ViewStore.js';
import { requireTitle } from './commandChecks.js';
import type { ManagedViewRuntime } from './viewRuntimeTypes.js';
import { ViewCommandError, type WritePayload } from './write.js';
import type { WriteLedger } from './writeLedger.js';

/** What a write addressed by id reaches back into the engine for. */
export interface InstanceWritesHost {
  /** The instance as it is held now, and this reader's right to `action` it. */
  locate(
    id: string,
    action: keyof InstancePermissions,
  ): Promise<{
    revision: string;
    definitionId: string;
    scope: ViewScope;
    runtime: ManagedViewRuntime | undefined;
  }>;
  store: ViewStore;
  definition(id: string): ViewDefinition;
  /** Every open view of the instance. */
  holders(id: string): ManagedViewRuntime[];
  maxDashboardPanels: number;
  ledger: WriteLedger;
}

/**
 * The writes the engine addresses to a saved view by its id — rename,
 * 设为共享／设为个人, delete, and 放进仪表盘 — each asked before it is sent,
 * then one dispatch through the ledger. Held apart from `ViewEngine`, which
 * documents each one and hands it on.
 */
export class InstanceWrites {
  constructor(private readonly host: InstanceWritesHost) {}

  async rename(id: string, title: string): Promise<ViewInstance> {
    title = requireTitle(title);
    const { revision, runtime } = await this.host.locate(id, 'rename');
    const payload: WritePayload = { action: 'rename', id, revision, title };
    return (await this.host.ledger.dispatch(payload, runtime)) as ViewInstance;
  }

  async changeAudience(
    id: string,
    audience: ViewAudience,
  ): Promise<ViewInstance> {
    const { store, ledger } = this.host;
    if (!store.changeAudience)
      throw new ViewCommandError(issue('view.changeAudience.unsupported', []));
    const { revision, scope, runtime } = await this.host.locate(
      id,
      'changeAudience',
    );
    if (audienceOf(scope) === audience)
      return runtime?.getSnapshot().saved ?? store.get(id);
    const payload: WritePayload = {
      action: 'changeAudience',
      id,
      revision,
      audience,
    };
    return (await ledger.dispatch(payload, runtime)) as ViewInstance;
  }

  async delete(id: string): Promise<void> {
    const { definitionId, revision, runtime } = await this.host.locate(
      id,
      'delete',
    );
    const payload: WritePayload = {
      action: 'delete',
      id,
      definitionId,
      revision,
    };
    await this.host.ledger.dispatch(payload, runtime);
  }

  /**
   * The saved view `instanceId` as one more panel on the board `boardId`,
   * written as the board's save in place (see `ViewEngine.addToDashboard`).
   */
  async addToDashboard(
    boardId: string,
    instanceId: string,
  ): Promise<ViewInstance> {
    const { store, ledger, maxDashboardPanels: max } = this.host;
    const { revision, definitionId, runtime } = await this.host.locate(
      boardId,
      'save',
    );
    if (this.host.definition(definitionId).kind !== 'dashboard')
      throw new ViewCommandError(issue('view.addToDashboard.not-board', []));
    // Edits not yet saved on an open board: a save under them would take
    // them, or leave them over a baseline they were not made on.
    const holders = this.host.holders(boardId);
    if (holders.some(holder => holder.getSnapshot().dirty))
      throw new ViewCommandError(issue('view.addToDashboard.unsaved', []));
    const board = runtime?.getSnapshot().saved ?? (await store.get(boardId));
    const view = await store.get(instanceId);
    const added = addPanel(
      board.config as DashboardViewConfig,
      { kind: 'view', instanceId },
      { view: view.config, max },
    );
    if (!added)
      throw new ViewCommandError(
        issue('view.addToDashboard.full', [], { max }),
      );
    const saved = (await ledger.dispatch(
      { action: 'save', id: boardId, revision, config: added.config },
      undefined,
    )) as ViewInstance;
    // An open board had nothing of its own to keep: it takes the board as
    // it now is, the new panel with it.
    for (const holder of holders) holder.adoptSaved(saved);
    return saved;
  }
}
