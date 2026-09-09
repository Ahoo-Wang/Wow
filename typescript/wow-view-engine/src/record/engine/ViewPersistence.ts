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

import type { ViewInstance, SaveAsScope } from '../recordModel.js';
import type { ViewHost } from '../ViewHost.js';
import { validateViewInstance } from '../recordValidation.js';

import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import { hasUnknownWriteOutcome, type InstanceWork } from './InstanceWork.js';
import type { RecordQueries } from './RecordQueries.js';
import { copy, message, sameJsonState } from '../../lib/snapshot.js';
import {
  createSession,
  inheritEditingSession,
  instanceContent,
} from './sessionState.js';
import { ViewServiceError } from '../viewServiceContract.js';
import { permissionsFor } from './instancePermissions.js';

/** Saved configuration writes and their immutable response reconciliation. */
export class ViewPersistence {
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly queries: RecordQueries,
  ) {}

  async save(id?: string): Promise<void> {
    await this.write(undefined, id);
  }

  async saveAs(
    options: { title: string; scope: SaveAsScope },
    id?: string,
  ): Promise<void> {
    await this.write(options, id);
  }

  private async write(
    options: { title: string; scope: SaveAsScope } | undefined,
    id?: string,
  ): Promise<void> {
    const session = this.store.session(id);
    id = session.instance.id;
    const definition = this.store.definition();
    const lifecycle = this.scope.version;
    const token = Symbol();
    const selection = this.scope.selection;
    let received = false;
    let dispatched = false;
    let selectedCopy: string | undefined;
    let followUp: (() => Promise<void>) | undefined;
    const current = () =>
      this.scope.current(lifecycle) && this.work.writeToken(id) === token;
    try {
      if (session.filterPending)
        throw new Error('请先查询或撤销筛选修改，再保存视图');
      this.work.assertWritable(
        session,
        Boolean(options && this.work.createRequest(id)),
      );
      const permissions = permissionsFor(this.host, session);
      if (
        options &&
        !(
          options.scope?.type === 'personal' ||
          (options.scope?.type === 'public' &&
            options.scope.source === 'shared')
        )
      )
        throw new Error('另存仅支持个人或公共共享实例');
      if (
        options
          ? !(options.scope.type === 'personal'
              ? permissions.saveAsPersonal
              : permissions.saveAsShared)
          : !permissions.save
      )
        throw new Error('宿主未允许此保存操作');
      const submitted = copy(
        options
          ? { ...session.instance, title: options.title, scope: options.scope }
          : session.instance,
      );
      validateViewInstance(submitted, definition, id);
      const knownIds =
        this.work.createRequest(id)?.knownIds ??
        new Set(this.store.getSnapshot().instanceIds);
      this.work.beginWrite(id, token);
      this.store.patch(id, {
        writeStatus: options ? 'creating' : 'saving',
        writeError: null,
      });
      if (!current()) return;
      let result: ViewInstance;
      if (options) {
        const { definitionId, kind, title, scope, config } = submitted;
        const previous = this.work.createRequest(id);
        if (
          previous &&
          !sameJsonState(
            instanceContent(previous.submitted),
            instanceContent(submitted),
          )
        )
          throw new ViewServiceError(
            'UNKNOWN_OUTCOME',
            '上次另存结果尚未确认，请先使用原配置重试',
          );
        const request = previous ?? {
          requestId: crypto.randomUUID(),
          submitted,
          knownIds,
          source: session,
        };
        this.work.beginCreate(id, request);
        try {
          dispatched = true;
          result = await this.host.instance!.create!(
            structuredClone({ definitionId, kind, title, scope, config }),
            { requestId: request.requestId },
          );
        } catch (error) {
          if (hasUnknownWriteOutcome(error)) {
            if (!this.work.unverifiedCreate(id))
              this.work.markCreateUnverified(id);
          } else if (!previous && this.work.createRequest(id) === request) {
            // A rejected retry says nothing about an earlier uncertain attempt.
            // Full load may have preserved this original request while it was pending.
            this.work.finishCreate(id);
            this.work.finishWrite(id, token, () => {
              this.store.clearPendingCreate(id);
              if (this.store.find(id)?.requiresReload)
                this.store.patch(id, {
                  requiresReload: false,
                  writeError: null,
                });
            });
          }
          throw error;
        }
      } else {
        dispatched = true;
        result = await this.host.instance!.save!(structuredClone(submitted));
      }
      if (!current()) return;
      received = true;
      if (options)
        this.work.markCreateUnverified(
          id,
          result &&
            typeof result.id === 'string' &&
            result.id.trim() &&
            !knownIds.has(result.id)
            ? result.id
            : null,
        );
      validateViewInstance(result, definition, options ? undefined : id);
      if (options && knownIds.has(result.id))
        throw new Error('另存返回的实例 ID 已存在');
      if (!sameJsonState(instanceContent(result), instanceContent(submitted)))
        throw new Error('保存结果不符合原样保存契约，请重新加载核对');
      const saved = copy(result);
      if (options) this.work.finishCreate(id);
      const latest = this.store.session(id);
      if (options) {
        let created = createSession(
          saved,
          definition,
          this.store.filterCompilers,
        );
        if (
          this.store.getSnapshot().selectedInstanceId === id &&
          this.scope.selection === selection
        ) {
          selectedCopy = saved.id;
          const navigation = this.scope.advanceSelection();
          if (!current()) return;
          this.queries.cancel(id);
          if (!current()) return;
          // Cancellation notifies subscribers; a newer navigation owns the selection.
          if (
            this.scope.selection !== navigation ||
            this.store.getSnapshot().selectedInstanceId !== id
          )
            selectedCopy = undefined;
          else if (!this.store.find(saved.id))
            created = inheritEditingSession(
              saved,
              this.store.session(id),
              definition,
              this.store.filterCompilers,
            );
        }
        if (this.work.isDeleted(saved.id)) {
          this.work.finishWrite(id, token, () =>
            this.store.patch(id, {
              writeStatus: 'idle',
              writeError: null,
              requiresReload: false,
            }),
          );
          return;
        }
        if (selectedCopy) followUp = this.queries.followUp(selectedCopy);
        this.work.finishWrite(id, token, () =>
          this.store.publish({
            instanceIds: [
              ...new Set([...this.store.getSnapshot().instanceIds, saved.id]),
            ],
            sessions: {
              ...this.store.getSnapshot().sessions,
              [id]: {
                ...this.store.session(id),
                writeStatus: 'idle',
                writeError: null,
                requiresReload: false,
              },
              // Cancellation notifies observers; an opened copy may now own newer edits.
              [saved.id]: this.store.find(saved.id) ?? created,
            },
            ...(selectedCopy
              ? { selectedInstanceId: selectedCopy, error: null }
              : {}),
          }),
        );
      } else
        this.work.finishWrite(id, token, () =>
          this.store.patch(id, {
            baseline: saved,
            instance: {
              ...saved,
              title: latest.instance.title,
              config: latest.instance.config,
            },
            writeStatus: 'idle',
            writeError: null,
          }),
        );
    } catch (error) {
      if (
        !this.scope.current(lifecycle) ||
        (this.work.writeToken(id) && this.work.writeToken(id) !== token)
      ) {
        if (this.scope.current(lifecycle)) throw error;
        return;
      }
      this.work.finishWrite(id, token, () =>
        this.store.patch(id, {
          writeError: message(error),
          writeStatus: 'idle',
          ...(received ||
          this.work.unverifiedCreate(id) ||
          (dispatched && hasUnknownWriteOutcome(error))
            ? { requiresReload: true }
            : {}),
        }),
      );
      throw error;
    } finally {
      this.work.finishWrite(id, token);
    }
    void followUp?.().catch(() => {});
  }
}
