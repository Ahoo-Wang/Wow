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
  parseSystemInstanceId,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewScope,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type { InstancePermissions, ViewStore } from '../store/ViewStore.js';
import { requireTitle } from './commandChecks.js';
import type { PermissionGuard } from './permissions.js';
import type { ManagedViewRuntime } from './viewRuntimeTypes.js';
import { ViewCommandError, type WritePayload } from './write.js';
import type { WriteLedger } from './writeLedger.js';

/** What a write addressed by id reaches back into the engine for. */
export interface InstanceWritesHost {
  store: ViewStore;
  guard: PermissionGuard;
  definition(id: string): ViewDefinition;
  /** Drops the open views disposed without being closed. */
  prune(): void;
  /** Every open view of the instance. */
  holders(id: string): ManagedViewRuntime[];
  /** The instance as the last list read it. */
  listed(id: string): ViewInstanceSummary | undefined;
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
    const { revision, runtime } = await this.locate(id, 'rename');
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
    const { revision, scope, runtime } = await this.locate(
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
    const { definitionId, revision, runtime } = await this.locate(id, 'delete');
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
    const { revision, definitionId, runtime } = await this.locate(
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

  /**
   * What a command needs about an instance it was given only the id of: the
   * revision to write against, the definition whose list it belongs to, and
   * the open view it came through, from an open view, the last list, or the
   * store — and this reader's right to `action` it.
   */
  private async locate(
    id: string,
    action: keyof InstancePermissions,
  ): Promise<{
    revision: string;
    definitionId: string;
    scope: ViewScope;
    runtime: ManagedViewRuntime | undefined;
  }> {
    // A code-declared view is not in any store, and no store write can reach it.
    if (parseSystemInstanceId(id))
      throw new ViewCommandError(
        issue('view.system.read-only', [], { action }),
      );
    this.host.prune();
    const [runtime] = this.host.holders(id);
    const known = runtime?.getSnapshot().saved ?? this.host.listed(id);
    const summary = known ?? (await this.host.store.get(id));
    this.host.guard.requireInstance(summary, action);
    return {
      revision: summary.revision,
      definitionId: summary.definitionId,
      scope: summary.scope,
      runtime,
    };
  }
}
