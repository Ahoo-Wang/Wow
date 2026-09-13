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

import { withDeadline } from '../lib/runtimeLimits.js';
import type {
  ViewInstance,
  ViewCreateInput,
  ViewSession,
  SaveAsScope,
  ViewInstanceConflict,
} from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import { validateViewInstance } from '../contracts/validation/instanceValidation.js';

import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import { hasUnknownWriteOutcome, type InstanceWork } from './InstanceWork.js';
import type { ViewQueries } from './ViewQueries.js';
import { copy, sameJsonState } from '../lib/snapshot.js';
import {
  createSession,
  assertConflictReview,
  inheritEditingSession,
  instanceContent,
  baselinePatch,
  withContent,
} from './sessionState.js';
import { reconcileWriteFailure, writeFailurePatch } from './writeRecovery.js';
import { ViewServiceError } from '../contracts/viewServiceContract.js';
import { permissionsFor } from './instancePermissions.js';

/** Marks a create reconciliation that returned without publishing a result. */
const WRITE_ABORT = Symbol();

/** Inputs resolved before dispatch, shared with dispatch and reconciliation. */
interface PreparedWrite {
  readonly submitted: ViewInstance;
  readonly knownIds: ReadonlySet<string>;
}

/** Orchestration state of an in-flight write handed to its private phases. */
interface WriteContext {
  readonly id: () => string;
  readonly lifecycle: number;
  readonly token: symbol;
  readonly current: () => boolean;
  readonly selection: () => number;
  readonly dispatched: () => boolean;
  readonly setDispatched: () => void;
}

/** Saved configuration writes and their immutable response reconciliation. */
export class ViewPersistence {
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly queries: ViewQueries,
  ) {}

  async save(id?: string): Promise<void> {
    const session = this.store.session(id);
    await this.write(
      session.kind === 'dashboard' && !session.persisted
        ? {
            title: session.instance.title,
            scope: session.instance.scope as SaveAsScope,
          }
        : undefined,
      id,
    );
  }

  async overwriteInstance(
    review: ViewInstanceConflict,
    id?: string,
  ): Promise<void> {
    await this.write(undefined, id, review);
  }

  async saveAs(
    options: { title: string; scope: SaveAsScope },
    id?: string,
  ): Promise<string | undefined> {
    return this.write(options, id);
  }

  private async write(
    options: { title: string; scope: SaveAsScope } | undefined,
    id?: string,
    review?: ViewInstanceConflict,
  ): Promise<string | undefined> {
    const session = this.store.session(id);
    id = session.instance.id;
    const lifecycle = this.scope.version;
    const token = Symbol();
    const selection = this.scope.selection;
    let received = false;
    let dispatched = false;
    const current = () =>
      this.scope.current(lifecycle) && this.work.writeToken(id) === token;
    const ctx = {
      id: () => id,
      lifecycle,
      token,
      current,
      selection: () => selection,
      dispatched: () => dispatched,
      setDispatched: () => {
        dispatched = true;
      },
    } as const;
    try {
      const prepared = this.prepareWrite(session, options, review);
      const result = await this.dispatchWrite(prepared, options, review, ctx);
      if (!current()) return;
      received = true;
      if (options) {
        const outcome = this.reconcileCreate(prepared, result, ctx);
        if (outcome === WRITE_ABORT) return;
        return outcome;
      }
      this.reconcileSave(prepared, result, ctx);
    } catch (error) {
      if (
        !this.scope.current(lifecycle) ||
        (this.work.writeToken(id) && this.work.writeToken(id) !== token)
      ) {
        if (this.scope.current(lifecycle)) throw error;
        return;
      }
      reconcileWriteFailure(error, {
        finish: onSettled => this.work.finishWrite(id, token, onSettled),
        patch: writeFailurePatch(
          this.store,
          id,
          error,
          received ||
            Boolean(this.work.unverifiedCreate(id)) ||
            (dispatched && hasUnknownWriteOutcome(error)),
        ),
      });
    } finally {
      this.work.finishWrite(id, token);
    }
  }

  private prepareWrite(
    session: ViewSession,
    options: { title: string; scope: SaveAsScope } | undefined,
    review?: ViewInstanceConflict,
  ): PreparedWrite {
    const id = session.instance.id;
    const definition = this.store.definition();
    if (session.validation.length > 0)
      throw new Error('配置无效，请先修正后保存');
    this.work.assertWritable(
      session,
      Boolean(options && this.work.createRequest(id)),
      Boolean(options || review),
    );
    if (review) assertConflictReview(session, review);
    const permissions = permissionsFor(this.host, session);
    if (
      options &&
      !(
        options.scope?.type === 'personal' ||
        (options.scope?.type === 'public' && options.scope.source === 'shared')
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
        : review
          ? {
              ...review.remote,
              title: session.instance.title,
              config: session.instance.config,
            }
          : session.instance,
    );
    validateViewInstance(submitted, definition, id);
    const knownIds =
      this.work.createRequest(id)?.knownIds ??
      new Set(this.store.getSnapshot().instanceIds);
    return { submitted, knownIds };
  }

  private async dispatchWrite(
    prepared: PreparedWrite,
    options: { title: string; scope: SaveAsScope } | undefined,
    review: ViewInstanceConflict | undefined,
    ctx: WriteContext,
  ): Promise<ViewInstance | undefined> {
    const id = ctx.id();
    const session = this.store.session(id);
    const { submitted, knownIds } = prepared;
    const { token, current } = ctx;
    this.work.beginWrite(id, token);
    this.store.patch(id, {
      writeStatus: options ? 'creating' : 'saving',
      writeError: null,
    });
    if (!current()) return;
    if (review) assertConflictReview(this.store.session(id), review);
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
      const controller = new AbortController();
      try {
        ctx.setDispatched();
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
      ctx.setDispatched();
      result = await withDeadline(
        () => this.host.instance!.save!(structuredClone(submitted)),
        this.store.limits.writeTimeoutMs,
        new AbortController(),
      );
    }
    return result;
  }

  private reconcileCreate(
    prepared: PreparedWrite,
    result: ViewInstance | undefined,
    ctx: WriteContext,
  ): string | typeof WRITE_ABORT {
    const id = ctx.id();
    const definition = this.store.definition();
    const { submitted, knownIds } = prepared;
    const { token, current } = ctx;
    const selection = ctx.selection();
    let selectedCopy: string | undefined;
    this.work.markCreateUnverified(
      id,
      result &&
        typeof result.id === 'string' &&
        result.id.trim() &&
        !knownIds.has(result.id)
        ? result.id
        : null,
    );
    validateViewInstance(result, definition, undefined);
    if (knownIds.has(result.id)) throw new Error('另存返回的实例 ID 已存在');
    if (!sameJsonState(instanceContent(result), instanceContent(submitted)))
      throw new Error('保存结果不符合原样保存契约，请重新加载核对');
    const saved = copy(result);
    const createdId = saved.id;
    this.work.finishCreate(id);
    let created = createSession(
      saved,
      definition,
      this.store.filterCompilers,
      this.store.analysisCompilers,
    );
    const creatingSource = this.store.session(id);
    if (creatingSource.kind === 'dashboard' && !creatingSource.persisted)
      created = inheritEditingSession(
        saved,
        creatingSource,
        definition,
        this.store.filterCompilers,
        withContent(saved, creatingSource.instance),
        this.store.analysisCompilers,
      );
    if (
      this.store.getSnapshot().selectedInstanceId === id &&
      this.scope.selection === selection
    ) {
      selectedCopy = saved.id;
      const navigation = this.scope.advanceSelection();
      if (!current()) return WRITE_ABORT;
      this.queries.cancel(id);
      if (!current()) return WRITE_ABORT;
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
          creatingSource.kind === 'dashboard' && !creatingSource.persisted
            ? withContent(saved, this.store.session(id).instance)
            : undefined,
          this.store.analysisCompilers,
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
      return WRITE_ABORT;
    }

    const source = this.store.session(id);
    const draft = source.kind === 'dashboard' && !source.persisted;
    const remaining = { ...this.store.getSnapshot().sessions };
    if (draft) delete remaining[id];
    this.work.finishWrite(id, token, () =>
      this.store.publish({
        instanceIds: [
          ...new Set([...this.store.getSnapshot().instanceIds, saved.id]),
        ],
        sessions: {
          ...remaining,
          ...(!draft
            ? {
                [id]: {
                  ...source,
                  writeStatus: 'idle' as const,
                  writeError: null,
                  requiresReload: false,
                },
              }
            : {}),
          // Cancellation notifies observers; an opened copy may now own newer edits.
          [saved.id]: this.store.find(saved.id) ?? created,
        },
        ...(selectedCopy
          ? { selectedInstanceId: selectedCopy, error: null }
          : {}),
      }),
    );
    return createdId;
  }

  private reconcileSave(
    prepared: PreparedWrite,
    result: ViewInstance | undefined,
    ctx: WriteContext,
  ): void {
    const id = ctx.id();
    const definition = this.store.definition();
    const { submitted } = prepared;
    const { token } = ctx;
    validateViewInstance(result, definition, id);
    if (!sameJsonState(instanceContent(result), instanceContent(submitted)))
      throw new Error('保存结果不符合原样保存契约，请重新加载核对');
    const saved = copy(result);
    const latest = this.store.session(id);
    this.work.finishWrite(id, token, () =>
      this.store.patch(id, {
        ...baselinePatch(saved, latest.instance),
        conflict: undefined,
        writeStatus: 'idle',
        writeError: null,
      }),
    );
  }
}
