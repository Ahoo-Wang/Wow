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

import { withDeadline } from './runtimeLimits.js';
import type {
  ViewSession,
  ViewInstancePermissions,
} from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import { validateViewInstance } from '../record/recordValidation.js';

import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import { hasUnknownWriteOutcome, type InstanceWork } from './InstanceWork.js';
import type { ViewQueries } from './ViewQueries.js';
import type { RecordSummaries } from '../record/engine/RecordSummaries.js';
import { copy, message, sameJsonState } from '../lib/snapshot.js';
import { createSession, instanceContent, withContent } from './sessionState.js';
import { permissionsFor } from './instancePermissions.js';

/** Explicit persisted name, deletion and user-preference operations. */
export class ViewManagement {
  private defaultWrite?: { version: number };
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly queries: ViewQueries,
    private readonly summaries: RecordSummaries,
    private readonly definitionId: string,
  ) {}
  getPermissions(id?: string): ViewInstancePermissions {
    const key = id ?? this.store.getSnapshot().selectedInstanceId;
    return permissionsFor(
      this.host,
      this.scope.disposed || key === null ? undefined : this.store.find(key),
    );
  }

  async renameInstance(title: string, id?: string): Promise<void> {
    const session = this.store.session(id);
    id = session.instance.id;
    if (!this.getPermissions(id).rename)
      throw new Error('系统视图或宿主未授权的视图不能编辑名称');
    if (typeof title !== 'string' || !title.trim())
      throw new Error('视图名称不能为空');
    this.work.assertWritable(session);
    title = title.trim();
    if (title === session.baseline.title && title === session.instance.title)
      return;
    const lifecycle = this.scope.version;
    const token = Symbol();
    const current = () =>
      this.scope.current(lifecycle) && this.work.writeToken(id) === token;
    let received = false;
    let dispatched = false;
    try {
      this.work.beginWrite(id, token);
      this.store.patch(id, { writeStatus: 'renaming', writeError: null });
      if (!current()) return;
      dispatched = true;
      const result = await withDeadline(
        () => this.host.instance!.rename!(id, title, session.baseline.revision),
        this.store.limits.writeTimeoutMs,
        new AbortController(),
      );
      if (!current()) return;
      received = true;
      validateViewInstance(result, this.store.definition(), id);
      if (
        !sameJsonState(instanceContent(result), {
          ...instanceContent(session.baseline),
          title,
        })
      )
        throw new Error('改名结果修改了其他视图配置，请重新加载核对');
      const baseline = copy(result);
      const latest = this.store.session(id);
      const local = {
        ...latest.instance,
        title:
          latest.instance.title === session.instance.title
            ? title
            : latest.instance.title,
      };
      this.work.finishWrite(id, token, () =>
        this.store.patch(id, {
          ...(baseline.kind === 'record'
            ? {
                kind: 'record',
                baseline,
                instance: withContent(baseline, local),
              }
            : {
                kind: 'analysis',
                baseline,
                instance: withContent(baseline, local),
              }),
          writeStatus: 'idle',
          writeError: null,
        }),
      );
    } catch (error) {
      if (!current()) return;
      this.work.finishWrite(id, token, () =>
        this.store.patch(id, {
          writeStatus: 'idle',
          writeError: message(error),
          ...(received || (dispatched && hasUnknownWriteOutcome(error))
            ? { requiresReload: true }
            : {}),
        }),
      );
      throw error;
    } finally {
      this.work.finishWrite(id, token);
    }
  }

  canSetDefaultInstance(): boolean {
    return (
      !this.scope.disposed &&
      typeof this.host.preference?.saveDefault === 'function'
    );
  }

  async setDefaultInstance(instanceId: string | null): Promise<void> {
    if (
      instanceId !== null &&
      (typeof instanceId !== 'string' || !instanceId.trim())
    )
      throw new Error('默认视图必须是有效实例 ID 或 null');
    this.store.definition();
    if (!this.canSetDefaultInstance())
      throw new Error('宿主未提供默认视图保存接口');
    if (instanceId !== null)
      this.work.assertWritable(this.store.session(instanceId));
    this.assertDefaultWritable();
    const request = { version: this.scope.version };
    this.defaultWrite = request;
    try {
      await this.host.preference!.saveDefault!(this.definitionId, instanceId);
      if (!this.scope.current(request.version) || this.defaultWrite !== request)
        return;
      this.defaultWrite = undefined;
      this.store.publish({ defaultInstanceId: instanceId });
    } catch (error) {
      if (!this.scope.current(request.version) || this.defaultWrite !== request)
        return;
      throw Object.assign(
        new Error(`${message(error)}；请重试或重新加载核对默认视图`),
        { cause: error },
      );
    } finally {
      if (this.defaultWrite === request) this.defaultWrite = undefined;
    }
  }

  private assertDefaultWritable(): void {
    if (this.defaultWrite) throw new Error('默认视图正在保存');
    if (
      Object.values(this.store.getSnapshot().sessions).some(
        session => session.writeStatus === 'deleting',
      )
    )
      throw new Error('视图正在删除，请等待操作完成');
  }

  canReorderInstances(): boolean {
    if (
      this.scope.disposed ||
      typeof this.host.preference?.saveOrder !== 'function'
    )
      return false;
    try {
      return this.host.permission?.getDefinition
        ? this.host.permission?.getDefinition().reorder === true
        : true;
    } catch {
      return false;
    }
  }

  async reorderInstances(instanceIds: readonly string[]): Promise<void> {
    this.store.definition();
    if (!this.canReorderInstances()) throw new Error('宿主未提供视图排序接口');
    if (this.work.ordering) throw new Error('视图顺序正在保存');
    const known = new Set(this.store.getSnapshot().instanceIds);
    if (
      !Array.isArray(instanceIds) ||
      instanceIds.length !== known.size ||
      new Set(instanceIds).size !== known.size ||
      instanceIds.some(id => !known.has(id))
    )
      throw new Error('排序必须完整包含当前视图，不能重复或添加未知视图');
    const order = [...instanceIds];
    if (
      order.every(
        (id, index) => id === this.store.getSnapshot().instanceIds[index],
      )
    )
      return;
    const lifecycle = this.scope.version;
    const token = Symbol();
    this.work.beginOrder(token);
    try {
      await this.host.preference!.saveOrder!(this.definitionId, [...order]);
      if (!this.scope.current(lifecycle) || this.work.ordering !== token)
        return;
      const latest = new Set(this.store.getSnapshot().instanceIds);
      const remaining = order.filter(id => latest.has(id));
      const included = new Set(remaining);
      let index = 0;
      this.work.finishOrder(token);
      this.store.publish({
        instanceIds: this.store
          .getSnapshot()
          .instanceIds.map(id => (included.has(id) ? remaining[index++] : id)),
      });
    } finally {
      this.work.finishOrder(token);
    }
  }

  canRetryDeleteInstance(id?: string): boolean {
    const key = id ?? this.store.getSnapshot().selectedInstanceId;
    const session = key === null ? undefined : this.store.find(key);
    return Boolean(
      session?.requiresReload &&
      this.getPermissions(session.instance.id).delete &&
      this.work.unverifiedDelete(session.instance.id) &&
      this.work.unverifiedDelete(session.instance.id)?.revision ===
        session.baseline.revision,
    );
  }

  async deleteInstance(id?: string): Promise<void> {
    const session = this.store.session(id);
    id = session.instance.id;
    if (!this.getPermissions(id).delete)
      throw new Error('系统视图或宿主未授权的视图不能删除');
    // Repeating the same versioned delete is idempotent; other writes still need reconciliation.
    this.work.assertWritable(session, this.canRetryDeleteInstance(id));
    this.assertDefaultWritable();
    const lifecycle = this.scope.version;
    const token = Symbol();
    const current = () =>
      this.scope.current(lifecycle) && this.work.writeToken(id) === token;
    let dispatched = false;
    try {
      this.work.beginWrite(id, token);
      this.store.patch(id, { writeStatus: 'deleting', writeError: null });
      if (!current()) return;
      dispatched = true;
      const result = await withDeadline(
        () => this.host.instance!.delete!(id, session.baseline.revision),
        this.store.limits.writeTimeoutMs,
        new AbortController(),
      );
      if (!current()) return;
      if (
        !result ||
        typeof result !== 'object' ||
        Array.isArray(result) ||
        !('defaultInstance' in result)
      )
        throw new Error('删除回执缺少权威默认视图，请重试核对');
      const defaultInstance =
        result.defaultInstance === null ? null : copy(result.defaultInstance);
      let defaultSession: ViewSession | undefined;
      if (defaultInstance !== null) {
        validateViewInstance(defaultInstance, this.store.definition());
        if (defaultInstance.id === id)
          throw new Error('删除回执不能将已删除视图设为默认');
        if (!this.store.find(defaultInstance.id))
          defaultSession = createSession(
            defaultInstance,
            this.store.definition(),
            this.store.filterCompilers,
            this.store.analysisCompilers,
          );
      }
      this.work.markDeleted(id);
      this.queries.cancel(id);
      this.summaries.invalidate(id);
      if (!current()) return;
      this.work.finishCreate(id);
      const sessions = { ...this.store.getSnapshot().sessions };
      delete sessions[id];
      const instanceIds = this.store
        .getSnapshot()
        .instanceIds.filter(key => key !== id);
      if (defaultSession) {
        sessions[defaultSession.instance.id] = defaultSession;
        instanceIds.push(defaultSession.instance.id);
      }
      const wasSelected = this.store.getSnapshot().selectedInstanceId === id;
      const nextId = wasSelected
        ? (instanceIds[0] ?? null)
        : this.store.getSnapshot().selectedInstanceId;
      const followUp =
        wasSelected && nextId !== null
          ? this.queries.followUp(nextId)
          : undefined;
      this.work.finishWrite(id, token, () =>
        this.store.publish({
          sessions,
          instanceIds,
          selectedInstanceId: nextId,
          defaultInstanceId: defaultInstance?.id ?? null,
        }),
      );
      void followUp?.().catch(() => {});
    } catch (error) {
      if (!current()) return;
      if (dispatched && hasUnknownWriteOutcome(error))
        this.work.markDeleteUnverified(id, session.baseline.revision);
      this.work.finishWrite(id, token, () =>
        this.store.patch(id, {
          writeStatus: 'idle',
          writeError: message(error),
          ...(dispatched && hasUnknownWriteOutcome(error)
            ? { requiresReload: true }
            : {}),
        }),
      );
      throw error;
    } finally {
      this.work.finishWrite(id, token);
    }
  }
}
