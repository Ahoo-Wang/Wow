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

import { sameJsonState } from '../../lib/snapshot.js';
import { compileFilterConfiguration } from '../../filter/filterConfiguration.js';
import type { FilterCompilerRegistry } from '../../filter/filterModel.js';
import { sameFilterQuery } from '../../filter/filterTree.js';
import type { DeepReadonly } from '../../lib/types.js';
import type {
  RecordSession,
  ViewDefinition,
  ViewInstance,
} from '../recordModel.js';
import { EMPTY_RECORD_SUMMARY } from '../recordSummary.js';
import { getRecordKey } from '../validation/recordData.js';

export function createSession(
  instance: ViewInstance,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
): RecordSession {
  const filterDraft = instance.config.filters;
  const compiled = compileFilterConfiguration(
    instance.config.filters,
    definition.fields,
    definition.allowedOperators,
    compilers,
    definition.timeZone,
  );
  return {
    baseline: instance,
    instance,
    dirty: false,
    filterDraft,
    filterBaseline: filterDraft,
    filterValid: true,
    filterPending: compiled.errors.length > 0,
    appliedFilter: compiled.expression ?? null,
    page: 1,
    cursor: null,
    nextCursor: null,
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

export function instanceContent({
  title,
  scope,
  config,
}: DeepReadonly<ViewInstance>) {
  return { title, scope, config };
}

export function deriveSession(
  session: RecordSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  previous?: RecordSession,
): RecordSession {
  let filterPending = previous?.filterPending;
  if (
    !previous ||
    previous.filterDraft !== session.filterDraft ||
    previous.filterValid !== session.filterValid ||
    previous.appliedFilter !== session.appliedFilter
  ) {
    const compiled = compileFilterConfiguration(
      session.filterDraft,
      definition.fields,
      definition.allowedOperators,
      compilers,
      definition.timeZone,
    );
    filterPending =
      !session.filterValid ||
      compiled.errors.length > 0 ||
      !sameFilterQuery(compiled.expression, session.appliedFilter);
  }
  let selectedRowKeys = session.selectedRowKeys;
  if (
    selectedRowKeys.length &&
    (!previous ||
      previous.rows !== session.rows ||
      previous.selectedRowKeys !== selectedRowKeys)
  ) {
    const available = new Set(
      session.rows.map(row => getRecordKey(row, definition.rowKey)),
    );
    if (selectedRowKeys.some(key => !available.has(key)))
      selectedRowKeys = selectedRowKeys.filter(key => available.has(key));
  }
  return {
    ...session,
    selectedRowKeys,
    filterPending: filterPending!,
    dirty:
      previous &&
      previous.instance === session.instance &&
      previous.baseline === session.baseline
        ? previous.dirty
        : !sameJsonState(
            instanceContent(session.instance),
            instanceContent(session.baseline),
          ),
  };
}

export function isSystemSession(session: RecordSession): boolean {
  return [session.baseline, session.instance].some(
    value => value.scope.type === 'public' && value.scope.source === 'system',
  );
}

/** Server metadata is authoritative; only locally editable content survives a reload. */
export function rebaseSession(
  baseline: ViewInstance,
  latest: RecordSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
): RecordSession {
  if (!latest.dirty && !latest.filterPending && !latest.requiresReload)
    return createSession(baseline, definition, compilers);
  return deriveSession(
    {
      ...latest,
      baseline,
      instance: {
        ...baseline,
        title: latest.instance.title,
        config: latest.instance.config,
      },
      writeError: null,
      requiresReload: false,
    },
    definition,
    compilers,
    latest,
  );
}

/** A new copy carries the originating editor state without confusing its saved baseline. */
export function inheritEditingSession(
  baseline: ViewInstance,
  source: RecordSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  instance: DeepReadonly<ViewInstance> = {
    ...baseline,
    config: source.instance.config,
  },
): RecordSession {
  return deriveSession(
    {
      ...createSession(baseline, definition, compilers),
      instance,
      filterDraft: source.filterDraft,
      filterBaseline: source.filterBaseline,
      filterValid: source.filterValid,
      appliedFilter: source.appliedFilter,
    },
    definition,
    compilers,
  );
}
