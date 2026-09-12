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
  validateRuntimeLimits,
  type RuntimeLimits,
} from '../../lib/runtimeLimits.js';
import { RequestRunner } from '../../engine/RequestRunner.js';
import type {
  ViewSource,
  RecordSession,
  RecordSummaryResult,
} from '../../contracts/viewModel.js';
import type { ViewHost } from '../../contracts/ViewHost.js';
import {
  calculateRecordSummary,
  createRecordSummaryQuery,
  EMPTY_RECORD_SUMMARY,
  readRecordSummaryResult,
} from '../recordSummary.js';
import { getRecordSummaryMetrics } from '../recordPresentation.js';

import type { EngineScope } from '../../engine/EngineScope.js';
import type { SessionStore } from '../../engine/SessionStore.js';
import { copy, message, sameJsonState } from '../../lib/snapshot.js';

/** Independent page/all summary state and cancellable aggregate requests. */
export class RecordSummaries {
  private readonly requests = new Map<string, AbortController>();
  private readonly keys = new Map<string, string>();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly limits: Readonly<RuntimeLimits> = validateRuntimeLimits(),
    private readonly runner = new RequestRunner({
      maxConcurrent: limits.maxConcurrentQueries,
      maxQueued: 48,
      maxTimeoutMs: limits.queryTimeoutMs,
    }),
  ) {}
  hasPending(id: string): boolean {
    return this.requests.has(id);
  }
  reset(): void {
    this.requests.forEach((controller, id) =>
      this.runner.cancel(`summary:${id}`, controller),
    );
    this.requests.clear();
    this.keys.clear();
  }

  invalidate(id: string): void {
    const controller = this.requests.get(id);
    this.requests.delete(id);
    this.keys.delete(id);
    const session = this.store.find(id);
    if (session?.kind === 'record' && session.allSummary.status !== 'idle')
      this.store.patch(id, {
        kind: 'record',
        allSummary: EMPTY_RECORD_SUMMARY,
      });
    if (controller) this.runner.cancel(`summary:${id}`, controller);
    controller?.abort();
  }

  key(session: RecordSession): string | undefined {
    const metrics = getRecordSummaryMetrics(
      session.instance.config.presentation,
    );
    if (!metrics.length || session.appliedFilter === null) return undefined;
    return JSON.stringify([
      session.appliedFilter,
      metrics.map(({ id, field, function: fn }) => [id, field, fn]),
    ]);
  }

  updatePage(id: string): void {
    const session = this.store.recordSession(id);
    const metrics = getRecordSummaryMetrics(
      session.instance.config.presentation,
    );
    let pageSummary: RecordSummaryResult = EMPTY_RECORD_SUMMARY;
    if (metrics.length) {
      if (session.queryStatus === 'loading')
        pageSummary = { status: 'loading', values: {}, error: null };
      else if (session.queryStatus === 'success') {
        try {
          pageSummary = {
            status: 'success',
            values: calculateRecordSummary(session.rows, metrics),
            error: null,
          };
        } catch (error) {
          pageSummary = { status: 'error', values: {}, error: message(error) };
        }
      }
    }
    if (!sameJsonState(session.pageSummary, pageSummary))
      this.store.patch(id, { kind: 'record', pageSummary });
  }

  sync(id: string, source?: ViewSource, request = true): void {
    if (this.scope.disposed || !this.store.find(id)) return;
    const lifecycle = this.scope.version;
    this.updatePage(id);
    if (!this.scope.current(lifecycle) || !this.store.find(id)) return;
    const session = this.store.recordSession(id);
    if (this.keys.get(id) !== this.key(session)) this.invalidate(id);
    if (request) void this.query(id, source).catch(() => {});
  }

  private async query(id: string, source?: ViewSource): Promise<void> {
    const session = this.store.recordSession(id);
    const key = this.key(session);
    if (
      !key ||
      session.appliedFilter === null ||
      (this.keys.get(id) === key && session.allSummary.status !== 'idle')
    )
      return;
    const lifecycle = this.scope.version;
    this.invalidate(id);
    if (!this.scope.current(lifecycle) || !this.store.find(id)) return;
    const latest = this.store.recordSession(id);
    if (this.key(latest) !== key || this.keys.get(id) === key) return;
    const controller = new AbortController();
    this.requests.set(id, controller);
    const current = () =>
      this.scope.current(lifecycle) && this.requests.get(id) === controller;
    const metrics = getRecordSummaryMetrics(
      session.instance.config.presentation,
    );
    try {
      const result = await this.runner.submit({
        key: `summary:${id}`,
        policy: 'reject',
        timeoutMs: this.limits.queryTimeoutMs,
        controller,
        run: async () => {
          this.keys.set(id, key);
          this.store.patch(id, {
            kind: 'record',
            allSummary: { status: 'loading', values: {}, error: null },
          });
          if (!current()) throw new Error('汇总请求已失效');
          const sourceId = this.store.definition(session.positionId).sourceId;
          if (!sourceId) throw new Error('查询定义缺少数据源');
          source ??= await this.host.resolveSource(sourceId);
          if (!current()) throw new Error('汇总请求已失效');
          if (!source.aggregate)
            throw new Error('数据源未提供 aggregate，无法汇总所有记录');
          return source.aggregate(
            createRecordSummaryQuery(session.appliedFilter!, metrics),
            undefined,
            controller,
          );
        },
      }).completion;
      if (!current()) return;
      this.store.patch(id, {
        kind: 'record',
        allSummary: {
          status: 'success',
          values: copy(readRecordSummaryResult(result, metrics)),
          error: null,
        },
      });
    } catch (error) {
      if (!current()) return;
      this.store.patch(id, {
        kind: 'record',
        allSummary: { status: 'error', values: {}, error: message(error) },
      });
      throw error;
    } finally {
      if (this.requests.get(id) === controller) this.requests.delete(id);
    }
  }

  async refresh(id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    id = session.positionId;
    const lifecycle = this.scope.version;
    const request = this.requests.get(id),
      key = this.keys.get(id);
    this.updatePage(id);
    if (
      !this.scope.current(lifecycle) ||
      !this.store.find(id) ||
      this.requests.get(id) !== request ||
      this.keys.get(id) !== key
    )
      return;
    this.invalidate(id);
    if (this.scope.current(lifecycle) && this.store.find(id))
      await this.query(id);
  }
}
