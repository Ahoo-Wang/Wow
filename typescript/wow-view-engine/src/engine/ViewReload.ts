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
  ViewInstance,
  ViewInstanceConflict,
} from '../contracts/viewModel.js';
import type { ViewCreateInput } from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import { validateViewInstance } from '../record/recordValidation.js';
import { readInstanceList } from '../record/validation/instanceValidation.js';

import { permissionsFor } from './instancePermissions.js';
import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import type { InstanceWork } from './InstanceWork.js';
import type { ViewQueries } from './ViewQueries.js';
import { copy, message, sameJsonState } from '../lib/snapshot.js';
import {
  rebaseSession,
  resetEditingSession,
  withContent,
  assertConflictReview,
  inheritEditingSession,
  instanceContent,
} from './sessionState.js';

/** Reload and uncertain-save-as reconciliation; never silently discards local edits. */
export class ViewReload {
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly queries: ViewQueries,
  ) {}

  canReloadInstance(id = this.store.getSnapshot().selectedInstanceId): boolean {
    if (
      this.scope.disposed ||
      !id ||
      !(this.store.find(id) ?? this.store.findPendingCreate(id))
    )
      return false;
    const pending = this.work.unverifiedCreate(id);
    return pending
      ? Boolean(
          (pending.id &&
            (this.host.instance?.load || this.host.instance?.list)) ||
          (this.work.createRequest(id) && this.host.instance?.create),
        )
      : Boolean(this.host.instance?.load || this.host.instance?.list);
  }

  async useRemoteInstance(
    review: ViewInstanceConflict,
    id?: string,
  ): Promise<void> {
    const session = this.store.session(id);
    this.work.assertWritable(session, false, true);
    assertConflictReview(session, review);
    const restored = resetEditingSession(
      session,
      copy(review.remote),
      this.store.definition(),
      this.store.filterCompilers,
      this.store.analysisCompilers,
      this.store.limits.maxConfigBytes,
    );
    const update = () => {
      assertConflictReview(this.store.session(session.instance.id), review);
      this.store.patch(session.instance.id, {
        ...restored,
        conflict: undefined,
      });
    };
    if (session.kind === 'analysis' || restored.validation.length > 0) {
      this.queries.cancel(session.instance.id);
      update();
      return;
    }
    await this.queries.change(session.instance.id, update, true);
  }

  async reloadInstance(id?: string): Promise<void> {
    const session = this.store.sessionForReload(id);
    id = session.instance.id;
    const lifecycle = this.scope.version;
    const definition = this.store.definition();
    const controller = new AbortController();
    let started = false;
    let queryId = id;
    let followUp: (() => Promise<void>) | undefined;
    const selection = this.scope.selection;
    try {
      const unverified = this.work.unverifiedCreate(id);
      const existingBaseline = unverified?.id
        ? this.store.find(unverified.id)?.baseline
        : undefined;
      if (!this.canReloadInstance(id))
        throw new Error(
          '宿主未提供 instance.load 或 instance.list，无法重新加载',
        );
      if (this.work.writeToken(id))
        throw new Error('实例正在写入，请等待操作完成');
      const previous = this.work.beginReload(id, controller);
      started = true;
      previous?.abort();
      if (this.work.reloadToken(id) !== controller) return;
      this.queries.cancel(id);
      if (
        !this.scope.current(lifecycle) ||
        this.work.reloadToken(id) !== controller
      )
        return;
      let result: ViewInstance;
      if (
        (!unverified || unverified.id) &&
        !this.host.instance?.load &&
        this.host.instance?.list
      ) {
        const list = await withDeadline(
          () => this.host.instance!.list!(definition.id, controller.signal),
          this.store.limits.loadTimeoutMs,
          controller,
        );
        if (
          !this.scope.current(lifecycle) ||
          this.work.reloadToken(id) !== controller
        )
          return;
        const matched = readInstanceList(list, definition).find(
          item => item.id === (unverified?.id ?? id),
        );
        if (!matched)
          throw new Error('实例列表未包含待核对的实例 ID，仍需核对');
        result = matched;
      } else if (unverified && (!unverified.id || !this.host.instance?.load)) {
        const request = this.work.createRequest(id);
        if (!request || !this.host.instance?.create)
          throw new Error('缺少原创建请求，无法确认另存结果');
        const permissions = permissionsFor(this.host, session);
        if (
          !(request.submitted.scope.type === 'personal'
            ? permissions.saveAsPersonal
            : permissions.saveAsShared)
        )
          throw new Error('宿主未允许重试此创建操作');
        // Replaying the original request is authoritative; list content is not identity.
        const { definitionId, kind, title, scope, config } = request.submitted;
        result = await withDeadline(
          () =>
            this.host.instance!.create!(
              structuredClone({
                definitionId,
                kind,
                title,
                scope,
                config,
              }) as ViewCreateInput,
              { requestId: request.requestId, signal: controller.signal },
            ),
          this.store.limits.writeTimeoutMs,
          controller,
        );
        if (
          !this.scope.current(lifecycle) ||
          this.work.reloadToken(id) !== controller
        )
          return;
        validateViewInstance(result, definition, unverified.id ?? undefined);
        if (
          !sameJsonState(
            instanceContent(result),
            instanceContent(request.submitted),
          )
        )
          throw new Error('创建回执不符合原样保存契约，仍需核对');
      } else
        result = await withDeadline(
          () =>
            this.host.instance!.load!(unverified?.id ?? id, controller.signal),
          this.store.limits.loadTimeoutMs,
          controller,
        );
      if (
        !this.scope.current(lifecycle) ||
        this.work.reloadToken(id) !== controller
      )
        return;
      validateViewInstance(
        result,
        definition,
        unverified ? (unverified.id ?? undefined) : id,
        false,
      );
      if (result.kind !== session.instance.kind)
        throw new Error('重新加载不能改变实例类型');
      const baseline = copy(result);
      this.work.clearDelete(id);
      const latest = this.store.sessionForReload(id);
      if (unverified) {
        if (unverified.knownIds.has(baseline.id))
          throw new Error('另存结果没有新的实例 ID，仍需核对');
        let selectCopy =
          this.store.getSnapshot().openingInstanceId === null &&
          this.store.getSnapshot().selectedInstanceId === id &&
          this.scope.selection === selection;
        if (selectCopy) {
          const navigation = this.scope.advanceSelection();
          if (
            !this.scope.current(lifecycle) ||
            this.work.reloadToken(id) !== controller
          )
            return;
          selectCopy =
            this.scope.selection === navigation &&
            this.store.getSnapshot().selectedInstanceId === id;
        }
        const source = this.store.sessionForReload(id);
        const existing = this.store.find(baseline.id);
        if (this.work.isDeleted(baseline.id)) {
          this.work.finishCreate(id);
          this.store.clearPendingCreate(id);
          this.work.finishReload(id, controller, () =>
            this.store.patch(id, { writeError: null, requiresReload: false }),
          );
          return;
        }
        // Abort observers may have opened or edited the copy or the source.
        const created =
          (existing
            ? existing.baseline === existingBaseline &&
              existing.writeStatus === 'idle' &&
              !existing.requiresReload
              ? rebaseSession(
                  baseline,
                  existing,
                  definition,
                  this.store.filterCompilers,
                  this.store.analysisCompilers,
                )
              : existing
            : undefined) ??
          inheritEditingSession(
            baseline,
            source,
            definition,
            this.store.filterCompilers,
            withContent(baseline, {
              ...source.instance,
              title: unverified.submitted.title,
            }),
            this.store.analysisCompilers,
          );
        if (
          selectCopy ||
          this.store.getSnapshot().selectedInstanceId === baseline.id
        )
          queryId = baseline.id;
        this.work.finishCreate(id);
        const pendingCreates = { ...this.store.getSnapshot().pendingCreates };
        delete pendingCreates[id];
        followUp = this.queries.followUp(queryId, true);
        this.work.finishReload(id, controller, () =>
          this.store.publish({
            pendingCreates,
            instanceIds: [
              ...new Set([
                ...this.store.getSnapshot().instanceIds,
                baseline.id,
              ]),
            ],
            sessions: {
              ...this.store.getSnapshot().sessions,
              ...(this.store.find(id)
                ? {
                    [id]: {
                      ...source,
                      writeError: null,
                      requiresReload: false,
                    },
                  }
                : {}),
              [baseline.id]: created,
            },
            ...(selectCopy
              ? { selectedInstanceId: baseline.id, error: null }
              : {}),
          }),
        );
      } else {
        followUp = this.queries.followUp(queryId, true);
        this.work.finishReload(id, controller, () =>
          this.store.patch(
            id,
            rebaseSession(
              baseline,
              latest,
              definition,
              this.store.filterCompilers,
              this.store.analysisCompilers,
            ),
          ),
        );
      }
    } catch (error) {
      if (
        !this.scope.current(lifecycle) ||
        (started && this.work.reloadToken(id) !== controller)
      )
        return;
      this.work.finishReload(id, controller, () =>
        this.store.patch(id, { writeError: message(error) }),
      );
      throw error;
    } finally {
      this.work.finishReload(id, controller);
    }
    void followUp?.().catch(() => {});
  }
}
