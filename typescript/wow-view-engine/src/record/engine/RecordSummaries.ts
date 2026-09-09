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
  RecordQuerySource,
  RecordSession,
  RecordSummaryResult,
} from '../recordModel.js';
import type { ViewHost } from '../ViewHost.js';
import {
  calculateRecordSummary,
  createRecordSummaryQuery,
  EMPTY_RECORD_SUMMARY,
  readRecordSummaryResult,
} from '../recordSummary.js';
import { getRecordSummaryMetrics } from '../recordPresentation.js';

import type { EngineScope } from './EngineScope.js';
import type { SessionStore } from './SessionStore.js';
import { copy, message, sameJsonState } from '../../lib/snapshot.js';

/** Independent page/all summary state and cancellable aggregate requests. */
export class RecordSummaries {
  private readonly requests = new Map<string, AbortController>();
  private readonly keys = new Map<string, string>();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
  ) {}
  hasPending(id: string): boolean {
    return this.requests.has(id);
  }
  reset(): void {
    this.requests.forEach(controller => controller.abort());
    this.requests.clear();
    this.keys.clear();
  }

  invalidate(id: string): void {
    const controller = this.requests.get(id);
    this.requests.delete(id);
    this.keys.delete(id);
    if (this.store.find(id)?.allSummary.status !== 'idle')
      this.store.patch(id, { allSummary: EMPTY_RECORD_SUMMARY });
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
    const session = this.store.session(id);
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
      this.store.patch(id, { pageSummary });
  }

  sync(id: string, source?: RecordQuerySource, request = true): void {
    if (this.scope.disposed || !this.store.find(id)) return;
    const lifecycle = this.scope.version;
    this.updatePage(id);
    if (!this.scope.current(lifecycle) || !this.store.find(id)) return;
    const session = this.store.session(id);
    if (this.keys.get(id) !== this.key(session)) this.invalidate(id);
    if (request) void this.query(id, source).catch(() => {});
  }

  private async query(id: string, source?: RecordQuerySource): Promise<void> {
    const session = this.store.session(id);
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
    const latest = this.store.session(id);
    if (this.key(latest) !== key || this.keys.get(id) === key) return;
    const controller = new AbortController();
    this.requests.set(id, controller);
    this.keys.set(id, key);
    const current = () =>
      this.scope.current(lifecycle) && this.requests.get(id) === controller;
    const metrics = getRecordSummaryMetrics(
      session.instance.config.presentation,
    );
    this.store.patch(id, {
      allSummary: { status: 'loading', values: {}, error: null },
    });
    try {
      if (!current()) return;
      source ??= await this.host.resolveSource(
        this.store.definition().sourceId,
      );
      if (!current()) return;
      if (!source.aggregate)
        throw new Error('数据源未提供 aggregate，无法汇总所有记录');
      const result = await source.aggregate(
        createRecordSummaryQuery(session.appliedFilter, metrics),
        undefined,
        controller,
      );
      if (!current()) return;
      this.store.patch(id, {
        allSummary: {
          status: 'success',
          values: copy(readRecordSummaryResult(result, metrics)),
          error: null,
        },
      });
    } catch (error) {
      if (!current()) return;
      this.store.patch(id, {
        allSummary: { status: 'error', values: {}, error: message(error) },
      });
      throw error;
    } finally {
      if (this.requests.get(id) === controller) this.requests.delete(id);
    }
  }

  async refresh(id?: string): Promise<void> {
    const session = this.store.session(id);
    id = session.instance.id;
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
