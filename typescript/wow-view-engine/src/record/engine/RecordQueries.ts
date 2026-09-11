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

import {
  beginDiagnostic,
  RuntimeLimitError,
  type RuntimeDiagnostic,
  assertConfigSize,
  withDeadline,
  validateRuntimeLimits,
  QueryBudget,
  type RuntimeLimits,
} from '../../engine/runtimeLimits.js';
import type { RecordQuerySource } from '../../contracts/viewModel.js';
import type { ViewHost } from '../../contracts/ViewHost.js';
import { validateRecordRows } from '../recordValidation.js';
import { getRecordRefreshBlockReason } from '../recordRefreshPolicy.js';
import { cloneSnapshot } from '../../lib/types.js';
import type { EngineScope } from '../../engine/EngineScope.js';
import type { SessionStore } from '../../engine/SessionStore.js';
import type { RecordSummaries } from './RecordSummaries.js';
import { copy, message } from '../../lib/snapshot.js';

/** Owns record reads; pagination and UI edits only submit explicit query commands. */
export class RecordQueries {
  private readonly releases = new Map<string, () => void>();
  private readonly queries = new Map<string, AbortController>();
  private readonly intents = new Map<string, symbol>();
  private readonly consumedCursors = new Map<string, Set<string>>();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly summaries: RecordSummaries,
    private readonly limits: Readonly<RuntimeLimits> = validateRuntimeLimits(),
    private readonly budget = new QueryBudget(limits.maxConcurrentQueries),
    private readonly onDiagnostic?: (event: RuntimeDiagnostic) => void,
  ) {}
  reset(): void {
    this.releases.forEach(release => release());
    this.releases.clear();
    this.queries.forEach(controller => controller.abort());
    this.queries.clear();
    this.consumedCursors.clear();
    this.intents.clear();
    this.summaries.reset();
  }

  cancel(id: string): void {
    this.replaceController(id);
  }

  private replaceController(
    id: string,
    next?: AbortController,
    release?: () => void,
  ): void {
    this.releases.get(id)?.();
    if (release) this.releases.set(id, release);
    else this.releases.delete(id);
    this.intents.set(id, Symbol());
    const previous = this.queries.get(id);
    if (next) this.queries.set(id, next);
    else this.queries.delete(id);
    previous?.abort();
    // Abort listeners may already have started a newer read.
    if (this.queries.get(id) !== next) return;
    const session = this.store.find(id);
    if (
      session?.kind === 'record' &&
      (session.queryStatus === 'loading' || session.refreshing)
    )
      this.store.patch(id, {
        kind: 'record',
        refreshing: false,
        ...(session.queryStatus === 'loading' ? { queryStatus: 'idle' } : {}),
      });
  }

  /** One boundary for a synchronous edit and its read; observers may submit newer work. */
  async change(
    id: string,
    update: () => void,
    invalidateSummary = false,
    mode: 'query' | 'refresh' | 'scope' = 'query',
  ): Promise<void> {
    const current = this.captureIntent(id);
    update();
    if (!current()) return;
    if (invalidateSummary) this.summaries.invalidate(id);
    if (current()) await this.run(id, mode);
  }

  private captureIntent(id: string): () => boolean {
    const intent = this.intents.get(id),
      lifecycle = this.scope.version;
    return () =>
      this.scope.current(lifecycle) && this.intents.get(id) === intent;
  }

  /** Automatic follow-ups additionally require the instance to remain selected. */
  followUp(id: string, refresh = false): () => Promise<void> {
    const current = this.captureIntent(id);
    return async () => {
      if (!current() || this.store.getSnapshot().selectedInstanceId !== id)
        return;
      const session = this.store.find(id);
      if (!session) return;
      if (session.kind !== 'record') return;
      if (
        session.appliedFilter === null ||
        session.validation.some(
          issue => issue.id === 'config' || issue.id === 'config-size',
        )
      )
        return;
      await (refresh ? this.refresh(id) : this.run(id, 'scope'));
    };
  }

  async run(
    id: string,
    mode: 'query' | 'refresh' | 'background' | 'scope' | 'retry' = 'query',
  ): Promise<void> {
    const background = mode === 'background';
    const session = this.store.recordSession(id);
    const prior =
      mode === 'query'
        ? null
        : mode === 'retry'
          ? session.queryAttempt
          : (session.result ?? session.queryAttempt);
    const config = prior?.config ?? {
      ...session.instance.config,
      filters: session.filterBaseline,
    };
    const filter = prior?.filter ?? session.appliedFilter;
    const queryAttempt = filter
      ? { config, filter, page: session.page, cursor: session.cursor }
      : null;
    const diagnostic = beginDiagnostic(this.onDiagnostic, 'record', 'query');
    try {
      assertConfigSize(config, this.limits.maxConfigBytes);
    } catch (error) {
      diagnostic('failed', 'RESOURCE_LIMIT');
      throw error;
    }
    const definition = this.store.definition();
    const lifecycle = this.scope.version;
    const controller = new AbortController();
    const current = () =>
      this.scope.current(lifecycle) && this.queries.get(id) === controller;
    let release: () => void;
    try {
      release = this.budget.acquire(`record:${id}`, controller);
    } catch (error) {
      diagnostic('failed', 'BUSY');
      throw error;
    }
    const deadline = Date.now() + this.limits.queryTimeoutMs;
    this.replaceController(id, controller, release);
    if (!current()) {
      diagnostic('superseded');
      return;
    }
    this.store.patch(
      id,
      background
        ? { kind: 'record', refreshing: true, queryError: null, queryAttempt }
        : {
            kind: 'record',
            queryAttempt,
            rows:
              (mode === 'refresh' || mode === 'retry') &&
              config.pagination.mode === 'paged'
                ? session.rows
                : [],
            selectedRowKeys: [],
            total:
              (mode === 'refresh' || mode === 'retry') &&
              config.pagination.mode === 'paged'
                ? session.total
                : null,
            nextCursor: null,
            queryError: null,
            queryStatus: 'loading',
            refreshing: false,
          },
    );
    if (!background) this.summaries.sync(id, undefined, false);
    try {
      diagnostic('started');
      if (!current()) return;
      if (filter === null)
        throw new Error('筛选组件配置无法编译，请先修正筛选');
      const source = await withDeadline(
        () => this.host.resolveSource(definition.sourceId),
        Math.max(1, deadline - Date.now()),
        controller,
      );
      if (!current()) return;
      const { sort, pagination } = config;
      if (!source || typeof source[pagination.mode] !== 'function')
        throw new Error(`数据源不支持 ${pagination.mode} 分页查询`);
      if (!background) this.summaries.sync(id, source);
      if (!current()) return;
      const result = await withDeadline<
        | Awaited<ReturnType<NonNullable<RecordQuerySource['paged']>>>
        | Awaited<ReturnType<NonNullable<RecordQuerySource['cursor']>>>
      >(
        () =>
          pagination.mode === 'paged'
            ? source.paged!(
                cloneSnapshot<
                  Parameters<NonNullable<RecordQuerySource['paged']>>[0]
                >({
                  filter,
                  sort,
                  pagination: { index: session.page, size: pagination.size },
                }),
                undefined,
                controller,
              )
            : source.cursor!(
                cloneSnapshot<
                  Parameters<NonNullable<RecordQuerySource['cursor']>>[0]
                >({
                  filter,
                  sort,
                  size: pagination.size,
                  cursor: session.cursor,
                }),
                undefined,
                controller,
              ),
        Math.max(1, deadline - Date.now()),
        controller,
      );
      if (!current()) return;
      if (!result || typeof result !== 'object' || Array.isArray(result))
        throw new Error('查询结果必须是分页对象');
      validateRecordRows(result.list, definition.record!.rowKey);
      let total: number | null = null;
      let nextCursor: string | null = null;
      let consumedCursors: Set<string> | undefined;
      if (pagination.mode === 'paged') {
        if (
          !('total' in result) ||
          !Number.isSafeInteger(result.total) ||
          result.total < 0
        )
          throw new Error('查询结果 total 必须是非负整数');
        total = result.total;
        if (session.page > Math.max(1, Math.ceil(total / pagination.size))) {
          if (background) this.summaries.invalidate(id);
          if (!current()) return;
          this.store.patch(id, {
            kind: 'record',
            page: 1,
            rows: [],
            selectedRowKeys: [],
            total: null,
            nextCursor: null,
            queryStatus: 'loading',
            refreshing: false,
          });
          if (!current()) return;
          // Page one remains valid even at total=0, so correction cannot loop.
          // Return the new owner directly so its failures reach the caller.
          diagnostic('superseded');
          return this.run(id, mode);
        }
      } else {
        if (
          !('nextCursor' in result) ||
          (result.nextCursor !== null &&
            (typeof result.nextCursor !== 'string' || !result.nextCursor))
        )
          throw new Error('查询结果 nextCursor 必须是非空字符串或 null');
        nextCursor = result.nextCursor;
        consumedCursors =
          session.cursor === null
            ? new Set<string>()
            : (this.consumedCursors.get(id) ?? new Set<string>());
        if (
          nextCursor !== null &&
          (nextCursor === session.cursor || consumedCursors.has(nextCursor))
        )
          throw new Error('查询结果返回了重复分页游标');
      }
      if (background) this.summaries.invalidate(id);
      if (!current()) return;
      const rows = copy(result.list);
      if (consumedCursors) {
        if (session.cursor !== null) consumedCursors.add(session.cursor);
        this.consumedCursors.set(id, consumedCursors);
      }
      release();
      this.store.patch(id, {
        kind: 'record',
        result: {
          config: copy(config),
          filter: copy(filter),
          page: session.page,
          cursor: session.cursor,
          rows,
          total,
          nextCursor,
          receivedAt: Date.now(),
        },
        rows,
        refreshing: false,
        total,
        nextCursor,
        queryStatus: 'success',
      });
      this.summaries.sync(id, source);
      diagnostic('succeeded');
    } catch (error) {
      if (!current()) return;
      release();
      this.store.patch(id, {
        kind: 'record',
        queryStatus: 'error',
        queryError: message(error),
        refreshing: false,
      });
      if (!background) this.summaries.updatePage(id);
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError ? error.code : 'QUERY_FAILED',
      );
      throw error;
    } finally {
      diagnostic(
        this.scope.disposed || !this.queries.has(id)
          ? 'cancelled'
          : 'superseded',
      );
      release();
      if (this.queries.get(id) === controller) this.queries.delete(id);
    }
  }

  async retry(id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    await this.run(session.instance.id, 'retry');
  }

  async refresh(
    id?: string,
    options?: { background?: boolean },
  ): Promise<void> {
    const session = this.store.recordSession(id);
    if (options?.background) {
      if (getRecordRefreshBlockReason(session)) return;
      await this.run(session.instance.id, 'background');
      return;
    }
    await this.change(
      session.instance.id,
      () => {
        if (
          (
            session.result?.config ??
            session.queryAttempt?.config ??
            session.instance.config
          ).pagination.mode === 'cursor'
        )
          this.store.patch(session.instance.id, {
            kind: 'record',
            page: 1,
            cursor: null,
          });
      },
      true,
      'refresh',
    );
  }
}
