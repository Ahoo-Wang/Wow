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

import { withQueryScope } from '../../filter/filterScope.js';
import type { FieldSort } from '@ahoo-wang/fetcher-wow';
import type {
  FilterConfiguration,
  FilterMode,
} from '../../filter/filterModel.js';
import {
  compileFilterConfiguration,
  isSimpleFilter,
} from '../../filter/filterCore.js';
import { validateFilterJson } from '../../filter/filterConfigurationValidation.js';
import { sameFilterQuery } from '../../filter/filterTree.js';
import type { DeepReadonly } from '../../lib/types.js';
import type {
  RecordColumn,
  RecordKey,
  RecordPresentation,
  RecordCardConfig,
} from '../../contracts/viewModel.js';
import { validateRecordPresentation } from '../validation/presentationValidation.js';
import { resolveRecordPresentation } from '../resolveRecordPresentation.js';
import { getRecordKey } from '../recordValidation.js';
import type { SessionStore } from '../../engine/SessionStore.js';
import type { RecordQueries } from './RecordQueries.js';
import type { RecordSummaries } from './RecordSummaries.js';
import { copy, sameJsonState } from '../../lib/snapshot.js';

/** Explicit session edits, validation and the queries each edit requires. */
export class RecordEdits {
  constructor(
    private readonly store: SessionStore,
    private readonly queries: Pick<RecordQueries, 'change' | 'cancel'>,
    private readonly summaries: Pick<RecordSummaries, 'key' | 'sync'>,
  ) {}

  async applyFilter(id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    if (!session.filterValid)
      throw new Error('筛选输入无效，请先修正或撤销修改');
    const definition = this.store.definition(session.positionId);
    const compiled = compileFilterConfiguration(
      session.filterDraft,
      definition.fields,
      definition.allowedOperators,
      this.store.filterCompilers,
      definition.timeZone,
    );
    if (compiled.errors.length || !compiled.expression)
      throw new Error(
        compiled.errors.map(error => error.message).join('；') ||
          '筛选组件无法编译',
      );
    const filters = copy(session.filterDraft);
    await this.queries.change(
      session.positionId,
      () => {
        this.store.updateInstance(
          session,
          {
            ...session.instance,
            config: { ...session.instance.config, filters },
          },
          {
            filterDraft: filters,
            filterBaseline: filters,
            filterValid: true,
            page: 1,
            cursor: null,
            appliedFilter: withQueryScope(
              compiled.expression,
              session.scopeFilter,
            ),
          },
        );
      },
      true,
    );
  }

  setFilterDraft(
    draft: DeepReadonly<FilterConfiguration>,
    id?: string,
    valid?: boolean,
  ): void {
    const session = this.store.recordSession(id);
    const nextValid = valid === undefined ? session.filterValid : valid;
    if (typeof nextValid !== 'boolean')
      throw new Error('筛选有效性必须是布尔值');
    validateFilterJson(draft);
    if (
      sameJsonState(session.filterDraft, draft) &&
      session.filterValid === nextValid
    )
      return;
    const patch = { filterDraft: copy(draft), filterValid: nextValid };
    const definition = this.store.definition(session.positionId);
    const compiled = compileFilterConfiguration(
      draft,
      definition.fields,
      definition.allowedOperators,
      this.store.filterCompilers,
      definition.timeZone,
    );
    if (
      nextValid &&
      !compiled.errors.length &&
      sameFilterQuery(
        withQueryScope(compiled.expression, session.scopeFilter),
        session.appliedFilter,
      )
    ) {
      this.store.updateInstance(
        session,
        {
          ...session.instance,
          config: { ...session.instance.config, filters: patch.filterDraft },
        },
        { ...patch, filterBaseline: patch.filterDraft },
      );
    } else
      this.store.patch(session.positionId, {
        kind: 'record',
        ...patch,
        instance: {
          ...session.instance,
          config: { ...session.instance.config, filters: patch.filterDraft },
        },
      });
  }

  setFilterValidity(valid: boolean, id?: string): void {
    if (typeof valid !== 'boolean') throw new Error('筛选有效性必须是布尔值');
    const session = this.store.recordSession(id);
    this.setFilterDraft(session.filterDraft, session.positionId, valid);
  }

  setFilterMode(mode: FilterMode, id?: string): void {
    const session = this.store.recordSession(id);
    if (mode !== 'simple' && mode !== 'advanced')
      throw new Error('筛选模式无效');
    if (mode === 'simple' && !isSimpleFilter(session.filterDraft.root))
      throw new Error('当前条件需要高级筛选模式');
    if (session.filterDraft.mode === mode) return;
    this.setFilterDraft({ ...session.filterDraft, mode }, session.positionId);
  }

  async setSort(sort: DeepReadonly<FieldSort[]>, id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    await this.queries.change(session.positionId, () => {
      this.store.updateInstance(
        session,
        { ...session.instance, config: { ...session.instance.config, sort } },
        { page: 1, cursor: null },
      );
    });
  }

  setLayout(layout: RecordPresentation['layout'], id?: string): void {
    const session = this.store.recordSession(id);
    const presentation = resolveRecordPresentation(
      this.store.definition(session.positionId),
      layout,
      session.instance.config.presentation,
    );
    this.setPresentation(presentation, session.positionId);
  }

  setCardConfig(card: DeepReadonly<RecordCardConfig>, id?: string): void {
    const session = this.store.recordSession(id);
    this.setPresentation(
      { ...session.instance.config.presentation, card },
      session.positionId,
    );
  }

  setColumns(columns: DeepReadonly<RecordColumn[]>, id?: string): void {
    const session = this.store.recordSession(id);
    this.setPresentation(
      { ...session.instance.config.presentation, table: { columns } },
      session.positionId,
    );
  }

  private setPresentation(
    presentation: DeepReadonly<RecordPresentation>,
    id: string,
  ): void {
    const session = this.store.recordSession(id);
    validateRecordPresentation(
      presentation,
      this.store.definition(session.positionId),
    );
    if (sameJsonState(session.instance.config.presentation, presentation))
      return;
    const key = this.summaries.key(session);
    this.store.updateInstance(session, {
      ...session.instance,
      config: { ...session.instance.config, presentation },
    });
    const current = this.store.find(id);
    if (current?.kind === 'record' && key !== this.summaries.key(current))
      this.summaries.sync(id);
  }

  async setPage(index: number, id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    if (
      (
        session.result?.config ??
        session.queryAttempt?.config ??
        session.instance.config
      ).pagination.mode !== 'paged'
    )
      throw new Error('游标分页仅支持向后加载下一页');
    if (!Number.isSafeInteger(index) || index < 1)
      throw new Error('页码必须是正整数');
    await this.queries.change(
      session.positionId,
      () => {
        this.store.patch(session.positionId, { kind: 'record', page: index });
      },
      false,
      'scope',
    );
  }

  async setPageSize(size: number, id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    await this.queries.change(session.positionId, () => {
      this.store.updateInstance(
        session,
        {
          ...session.instance,
          config: {
            ...session.instance.config,
            pagination: { ...session.instance.config.pagination, size },
          },
        },
        { page: 1, cursor: null },
      );
    });
  }

  async nextPage(id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    if (
      (
        session.result?.config ??
        session.queryAttempt?.config ??
        session.instance.config
      ).pagination.mode === 'paged'
    )
      return this.setPage(session.page + 1, session.positionId);
    if (session.queryStatus !== 'success' || session.nextCursor === null)
      return;
    await this.queries.change(
      session.positionId,
      () => {
        this.store.patch(session.positionId, {
          kind: 'record',
          page: session.page + 1,
          cursor: session.nextCursor,
        });
      },
      false,
      'scope',
    );
  }

  setSelection(keys: RecordKey[], id?: string): void {
    const session = this.store.recordSession(id);
    const available = new Set(
      session.rows.map(row =>
        getRecordKey(
          row,
          this.store.definition(session.positionId).record!.rowKey,
        ),
      ),
    );
    if (!Array.isArray(keys) || keys.some(key => !available.has(key)))
      throw new Error('选中记录必须属于当前查询结果');
    if (session.refreshing && keys.length)
      this.queries.cancel(session.positionId);
    this.store.patch(session.positionId, {
      kind: 'record',
      selectedRowKeys: [...new Set(keys)],
    });
  }

  async restore(id?: string): Promise<void> {
    const session = this.store.recordSession(id);
    const filters = session.baseline.config.filters;
    const filterDraft = filters;
    const definition = this.store.definition(session.positionId);
    const compiled = compileFilterConfiguration(
      filters,
      definition.fields,
      definition.allowedOperators,
      this.store.filterCompilers,
      definition.timeZone,
    );
    await this.queries.change(
      session.positionId,
      () => {
        this.store.patch(session.positionId, {
          kind: 'record',
          instance: session.baseline,
          filterDraft,
          filterBaseline: filterDraft,
          filterValid: true,
          appliedFilter: withQueryScope(
            compiled.expression,
            session.scopeFilter,
          ),
          page: 1,
          cursor: null,
          writeError: session.requiresReload ? session.writeError : null,
        });
      },
      true,
    );
  }
}
