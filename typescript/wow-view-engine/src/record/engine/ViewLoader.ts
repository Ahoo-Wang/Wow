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
  RecordSession,
  ViewDefinition,
  ViewInstanceList,
  ViewEngineOptions,
  ViewInstance,
} from '../recordModel.js';
import { cloneSnapshot } from '../../lib/types.js';
import type { ViewHost } from '../ViewHost.js';
import {
  validateViewDefinition,
  validateViewInstance,
} from '../recordValidation.js';
import { readInstanceList } from '../validation/instanceValidation.js';
import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import type { InstanceWork } from './InstanceWork.js';
import type { RecordQueries } from './RecordQueries.js';
import type { RecordSummaries } from './RecordSummaries.js';
import { copy, message } from '../../lib/snapshot.js';
import { createSession, inheritEditingSession } from './sessionState.js';

/** Loads definitions/instances and owns navigation intent independently of record queries. */
export class ViewLoader {
  private readonly definitionId: string;
  private readonly localDefinition?: ViewDefinition;
  private readonly localInstances?: ViewInstanceList;
  private readonly inputError?: unknown;
  private loadController?: AbortController;
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly queries: RecordQueries,
    private readonly summaries: RecordSummaries,
    options: ViewEngineOptions,
  ) {
    this.definitionId = options.definitionId;
    try {
      this.localDefinition =
        options.definition === undefined ? undefined : copy(options.definition);
      this.localInstances =
        options.instances === undefined ? undefined : copy(options.instances);
    } catch (error) {
      this.inputError = error;
    }
  }
  dispose(): void {
    this.loadController?.abort();
  }
  async load(): Promise<void> {
    this.scope.assertActive();
    this.work.assertLoadable();
    const lifecycle = this.scope.restart();
    if (!this.scope.current(lifecycle)) return;
    // Reloading cannot prove whether an already dispatched creation committed.
    this.work.preserveCreates({
      ...this.store.getSnapshot().pendingCreates,
      ...this.store.getSnapshot().sessions,
    });
    this.work.clearDeletes();
    this.loadController?.abort();
    if (!this.scope.current(lifecycle)) return;
    this.work.cancelReloads();
    if (!this.scope.current(lifecycle)) return;
    this.queries.reset();
    if (!this.scope.current(lifecycle)) return;
    const controller = new AbortController();
    this.loadController = controller;
    this.store.publish({
      status: 'loading',
      error: null,
      definition: null,
      instanceIds: [],
      selectedInstanceId: null,
      defaultInstanceId: null,
      sessions: Object.create(null),
    });
    let defaultId: string | null = null;
    let followUp: (() => Promise<void>) | undefined;
    try {
      if (this.inputError) throw this.inputError;
      const [definition, list] = await Promise.all([
        Promise.resolve().then(() => {
          if (this.localDefinition !== undefined) return this.localDefinition;
          if (!this.host.definition?.load)
            throw new Error('缺少视图定义或 definition.load');
          return this.host.definition?.load(
            this.definitionId,
            controller.signal,
          );
        }),
        Promise.resolve().then(() => {
          if (this.localInstances !== undefined) return this.localInstances;
          if (!this.host.instance?.list)
            throw new Error('缺少实例列表或 instance.list');
          return this.host.instance?.list(this.definitionId, controller.signal);
        }),
        Promise.resolve().then(async () => {
          const permission = this.host.permission;
          if (permission?.load)
            await permission.load(this.definitionId, controller.signal);
          else await permission?.refresh?.(controller.signal);
        }),
      ]);
      if (!this.scope.current(lifecycle)) return;
      validateViewDefinition(definition);
      if (definition.id !== this.definitionId)
        throw new Error('返回的视图定义 ID 不匹配');
      const instances = readInstanceList(list, definition);
      const sessions: Record<string, RecordSession> = Object.create(null);
      const pendingCreates: Record<string, RecordSession> = Object.create(null);
      for (const instance of instances) {
        this.work.forgetDeleted(instance.id);
        sessions[instance.id] = {
          ...createSession(
            copy(instance),
            definition,
            this.store.filterCompilers,
          ),
          requiresReload: Boolean(this.work.unverifiedCreate(instance.id)),
          writeError: this.work.unverifiedCreate(instance.id)
            ? '另存结果尚未核对，请重新加载核对'
            : null,
        };
      }
      for (const [id, request] of this.work.createEntries()) {
        if (!this.work.unverifiedCreate(id)) continue;
        const loaded = sessions[id];
        const restored = createSession(
          cloneSnapshot<ViewInstance>(request.source.instance),
          definition,
          this.store.filterCompilers,
        );
        const recovery = {
          ...inheritEditingSession(
            cloneSnapshot<ViewInstance>(
              loaded?.baseline ?? request.source.baseline,
            ),
            { ...request.source, appliedFilter: restored.appliedFilter },
            definition,
            this.store.filterCompilers,
            {
              ...(loaded?.instance ?? request.source.instance),
              title: request.source.instance.title,
              config: request.source.instance.config,
            },
          ),
          requiresReload: true,
          writeError: loaded
            ? '另存结果尚未核对，请重新加载核对'
            : '原视图已不在当前列表，另存结果仍需核对',
        };
        if (loaded) sessions[id] = recovery;
        else pendingCreates[id] = recovery;
      }
      if (
        typeof list.defaultInstanceId === 'string' &&
        Object.prototype.hasOwnProperty.call(sessions, list.defaultInstanceId)
      )
        defaultId = list.defaultInstanceId;
      this.scope.loading = false;
      if (defaultId !== null) followUp = this.queries.followUp(defaultId);
      this.store.publish({
        status: 'ready',
        error: null,
        definition: copy(definition),
        instanceIds: instances.map(instance => instance.id),
        selectedInstanceId: defaultId,
        defaultInstanceId: defaultId,
        sessions,
        pendingCreates,
      });
    } catch (error) {
      if (!this.scope.current(lifecycle)) return;
      controller.abort();
      if (!this.scope.current(lifecycle)) return;
      this.scope.loading = false;
      this.store.publish({ status: 'error', error: message(error) });
      throw error;
    }
    await followUp?.();
  }

  async selectInstance(id: string): Promise<void> {
    const definition = this.store.definition();
    if (
      Object.prototype.hasOwnProperty.call(
        this.store.getSnapshot().pendingCreates,
        id,
      )
    )
      throw new Error('另存结果待核对，请先重新加载核对');
    const lifecycle = this.scope.version;
    const { selection, controller } = this.scope.beginSelection();
    const current = () =>
      this.scope.current(lifecycle) && this.scope.selection === selection;
    if (!this.store.find(id)) {
      try {
        if (!this.host.instance?.load) throw new Error(`无法加载实例：${id}`);
        const instance = await this.host.instance?.load(id, controller.signal);
        if (!current()) return;
        validateViewInstance(instance, definition, id);
        this.store.publish({
          sessions: {
            ...this.store.getSnapshot().sessions,
            [id]: createSession(
              copy(instance),
              definition,
              this.store.filterCompilers,
            ),
          },
          instanceIds: [...this.store.getSnapshot().instanceIds, id],
        });
      } catch (error) {
        if (!current()) return;
        this.store.publish({ error: message(error) });
        throw error;
      }
    }
    if (!current()) return;
    const previousId = this.store.getSnapshot().selectedInstanceId;
    if (previousId === id) {
      if (this.store.getSnapshot().error !== null)
        this.store.publish({ error: null });
      return;
    }
    if (previousId !== null) {
      this.queries.cancel(previousId);
      if (!current()) return;
      if (this.summaries.hasPending(previousId))
        this.summaries.invalidate(previousId);
    }
    if (!current()) return;
    this.summaries.invalidate(id);
    if (!current()) return;
    const followUp = this.queries.followUp(id);
    this.store.publish({ selectedInstanceId: id, error: null });
    if (current()) await followUp();
  }
}
