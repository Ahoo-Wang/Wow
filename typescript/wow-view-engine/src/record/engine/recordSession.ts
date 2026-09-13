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
import type {
  RecordSession,
  RecordViewInstance,
  ViewDefinition,
} from '../../contracts/viewModel.js';
import type { FilterCompilerRegistry } from '../../filter/filterModel.js';
import { sameFilterQuery } from '../../filter/filterTree.js';
import type { DeepReadonly } from '../../lib/types.js';
import {
  configSizeIssues,
  compileSessionFilter,
} from '../../engine/sessionValidationCache.js';
import { validateViewInstance } from '../../contracts/validation/instanceValidation.js';
import { EMPTY_RECORD_SUMMARY } from '../recordSummary.js';
import { getRecordKey } from '../validation/recordData.js';

export function createRecordSession(
  instance: DeepReadonly<RecordViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  maxConfigBytes?: number,
): RecordSession {
  const filterDraft = instance.config.filters;
  const compiled = compileSessionFilter(filterDraft, definition, compilers);
  return {
    kind: 'record',
    positionId: instance.id,
    editorEpoch: 0,
    editVersion: 0,
    validation: recordIssues(
      instance,
      definition,
      compiled.errors,
      maxConfigBytes,
    ),
    baseline: instance,
    instance,
    conflict: undefined,
    dirty: false,
    filterDraft,
    filterBaseline: filterDraft,
    filterValid: true,
    filterPending: compiled.errors.length > 0,
    appliedFilter: compiled.expression ?? null,
    page: 1,
    cursor: null,
    nextCursor: null,
    result: null,
    queryAttempt: null,
    rows: [],
    total: null,
    pageSummary: EMPTY_RECORD_SUMMARY,
    allSummary: EMPTY_RECORD_SUMMARY,
    selectedRowKeys: [],
    queryStatus: 'idle',
    refreshing: false,
    queryError: null,
    writeStatus: 'idle',
    writeError: null,
    requiresReload: false,
  };
}

export function deriveRecordSession(
  session: RecordSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  previous: RecordSession | undefined,
  editVersion: number,
  dirty: boolean,
  maxConfigBytes?: number,
): RecordSession {
  const compiled = compileSessionFilter(
    session.filterDraft,
    definition,
    compilers,
  );
  const filterPending =
    !session.filterValid ||
    compiled.errors.length > 0 ||
    !sameFilterQuery(
      withQueryScope(compiled.expression, session.scopeFilter),
      session.appliedFilter,
    );
  let selectedRowKeys = session.selectedRowKeys;
  if (
    selectedRowKeys.length &&
    (!previous ||
      previous.rows !== session.rows ||
      previous.selectedRowKeys !== selectedRowKeys)
  ) {
    const available = new Set(
      session.rows.map(row => getRecordKey(row, definition.record!.rowKey)),
    );
    if (selectedRowKeys.some(key => !available.has(key)))
      selectedRowKeys = selectedRowKeys.filter(key => available.has(key));
  }
  return {
    ...session,
    editVersion,
    ...(session.conflict
      ? {
          conflict: {
            ...session.conflict,
            editVersion,
            local: session.instance,
            filterDraft: session.filterDraft,
            filterValid: session.filterValid,
          },
        }
      : {}),
    validation: recordIssues(
      session.instance,
      definition,
      [
        ...(!session.filterValid
          ? [{ id: 'filters', message: '筛选输入无效' }]
          : []),
        ...compiled.errors,
      ],
      maxConfigBytes,
    ),
    selectedRowKeys,
    filterPending,
    dirty,
  };
}

export function clearRecordResult(session: RecordSession): RecordSession {
  return {
    ...session,
    result: null,
    rows: [],
    total: null,
    nextCursor: null,
    selectedRowKeys: [],
    pageSummary: EMPTY_RECORD_SUMMARY,
    allSummary: EMPTY_RECORD_SUMMARY,
  };
}

function recordIssues(
  instance: DeepReadonly<RecordViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  issues: readonly { id: string; message: string }[],
  maxConfigBytes?: number,
) {
  const allIssues = [
    ...issues,
    ...configSizeIssues(instance.config, maxConfigBytes),
  ];
  try {
    validateViewInstance(instance, definition);
    return allIssues;
  } catch (error) {
    return [
      ...allIssues,
      {
        id: 'config',
        message: error instanceof Error ? error.message : '配置无效',
      },
    ];
  }
}
