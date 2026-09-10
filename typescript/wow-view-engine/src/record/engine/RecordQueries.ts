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

import type { RecordQuerySource } from '../recordModel.js';
import type { ViewHost } from '../ViewHost.js';
import { validateRecordRows } from '../recordValidation.js';
import { getRecordRefreshBlockReason } from '../recordRefreshPolicy.js';
import { cloneSnapshot } from '../../lib/types.js';
import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import type { RecordSummaries } from './RecordSummaries.js';
import { copy, message } from '../../lib/snapshot.js';

/** Owns record reads; pagination and UI edits only submit explicit query commands. */
export class RecordQueries {
  private readonly queries = new Map<string, AbortController>();
  private readonly intents = new Map<string, symbol>();
  private readonly consumedCursors = new Map<string, Set<string>>();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly summaries: RecordSummaries,
  ) {}
  reset(): void {
    this.queries.forEach(controller => controller.abort());
    this.queries.clear();
    this.consumedCursors.clear();
    this.intents.clear();
    this.summaries.reset();
  }

  cancel(id: string): void {
    this.replaceController(id);
  }

  private replaceController(id: string, next?: AbortController): void {
    this.intents.set(id, Symbol());
    const previous = this.queries.get(id);
    if (next) this.queries.set(id, next);
    else this.queries.delete(id);
    previous?.abort();
    // Abort listeners may already have started a newer read.
    if (this.queries.get(id) !== next) return;
    const session = this.store.find(id);
    if (session?.queryStatus === 'loading' || session?.refreshing)
      this.store.patch(id, {
        refreshing: false,
        ...(session.queryStatus === 'loading' ? { queryStatus: 'idle' } : {}),
      });
  }

  /** One boundary for a synchronous edit and its read; observers may submit newer work. */
  async change(
    id: string,
    update: () => void,
    invalidateSummary = false,
    mode: 'query' | 'refresh' = 'query',
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
      await (refresh ? this.refresh(id) : this.run(id));
    };
  }

  async run(
    id: string,
    mode: 'query' | 'refresh' | 'background' = 'query',
  ): Promise<void> {
    const background = mode === 'background';
    const session = this.store.session(id);
    const definition = this.store.definition();
    const lifecycle = this.scope.version;
    const controller = new AbortController();
    const current = () =>
      this.scope.current(lifecycle) && this.queries.get(id) === controller;
    this.replaceController(id, controller);
    if (!current()) return;
    this.store.patch(
      id,
      background
        ? { refreshing: true, queryError: null }
        : {
            rows:
              mode === 'refresh' &&
              session.instance.config.pagination.mode === 'paged'
                ? session.rows
                : [],
            selectedRowKeys: [],
            total:
              mode === 'refresh' &&
              session.instance.config.pagination.mode === 'paged'
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
      if (!current()) return;
      const filter = session.appliedFilter;
      if (filter === null)
        throw new Error('筛选组件配置无法编译，请先修正筛选');
      const source = await this.host.resolveSource(definition.sourceId);
      if (!current()) return;
      const { sort, pagination } = session.instance.config;
      if (!source || typeof source[pagination.mode] !== 'function')
        throw new Error(`数据源不支持 ${pagination.mode} 分页查询`);
      if (!background) this.summaries.sync(id, source);
      if (!current()) return;
      const result =
        pagination.mode === 'paged'
          ? await source.paged!(
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
          : await source.cursor!(
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
            );
      if (!current()) return;
      if (!result || typeof result !== 'object' || Array.isArray(result))
        throw new Error('查询结果必须是分页对象');
      validateRecordRows(result.list, definition.rowKey);
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
          return this.run(id);
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
      this.store.patch(id, {
        rows,
        refreshing: false,
        total,
        nextCursor,
        queryStatus: 'success',
      });
      this.summaries.sync(id, source);
    } catch (error) {
      if (!current()) return;
      this.store.patch(id, {
        queryStatus: 'error',
        queryError: message(error),
        refreshing: false,
      });
      if (!background) this.summaries.updatePage(id);
      throw error;
    } finally {
      if (this.queries.get(id) === controller) this.queries.delete(id);
    }
  }

  async retry(id?: string): Promise<void> {
    const session = this.store.session(id);
    const retainsRows =
      session.instance.config.pagination.mode === 'paged' &&
      session.rows.length > 0;
    await this.run(session.instance.id, retainsRows ? 'refresh' : 'query');
  }

  async refresh(
    id?: string,
    options?: { background?: boolean },
  ): Promise<void> {
    const session = this.store.session(id);
    if (options?.background) {
      if (getRecordRefreshBlockReason(session)) return;
      await this.run(session.instance.id, 'background');
      return;
    }
    await this.change(
      session.instance.id,
      () => {
        if (session.instance.config.pagination.mode === 'cursor')
          this.store.patch(session.instance.id, { page: 1, cursor: null });
      },
      true,
      'refresh',
    );
  }
}
