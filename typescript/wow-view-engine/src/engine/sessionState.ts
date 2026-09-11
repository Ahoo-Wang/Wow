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

import { sameAnalysisQueryDraft } from '../analysis/analysisQueryPolicy.js';
import { configSizeIssues, compileSessionFilter } from './sessionValidation.js';
import { validateViewInstance } from '../record/validation/instanceValidation.js';
import type { AnalysisCompilerRegistry } from '../analysis/analysisModel.js';
import { compileAnalysis } from '../analysis/analysisCompiler.js';
import { validateAnalysisPresentation } from '../analysis/analysisProjection.js';
import { sameJsonState } from '../lib/snapshot.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import { sameFilterQuery } from '../filter/filterTree.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  ViewSession,
  ViewDefinition,
  ViewInstance,
  ViewInstanceConflict,
  RecordViewInstance,
  AnalysisViewInstance,
} from '../contracts/viewModel.js';
import { EMPTY_RECORD_SUMMARY } from '../record/recordSummary.js';
import { getRecordKey } from '../record/validation/recordData.js';

export function createSession(
  instance: DeepReadonly<ViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry = {},
  maxConfigBytes?: number,
): ViewSession {
  if (instance.kind === 'analysis')
    return deriveSession(
      {
        kind: 'analysis',
        editorEpoch: 0,
        compilation: { errors: [] },
        queryValid: false,
        editVersion: 0,
        filterValid: true,
        pendingQuery: null,
        queryAttempt: null,
        baseline: instance,
        instance,
        dirty: false,
        validation: [],
        result: null,
        queryStatus: 'idle',
        queryError: null,
        writeStatus: 'idle',
        writeError: null,
        requiresReload: false,
      },
      definition,
      compilers,
      undefined,
      analysisCompilers,
      maxConfigBytes,
    );
  const filterDraft = instance.config.filters;
  const compiled = compileSessionFilter(
    instance.config.filters,
    definition,
    compilers,
  );
  return {
    kind: 'record',
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

/** Explicitly accepting remote content discards editor-local buffers as one transition. */
export function resetEditingSession(
  previous: ViewSession,
  remote: DeepReadonly<ViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry,
  maxConfigBytes: number,
): ViewSession {
  if (previous.kind !== remote.kind) throw new Error('实例类型不能改变');
  return {
    ...createSession(
      remote,
      definition,
      compilers,
      analysisCompilers,
      maxConfigBytes,
    ),
    editorEpoch: previous.editorEpoch + 1,
  };
}

export function instanceContent({
  kind,
  title,
  scope,
  config,
}: DeepReadonly<ViewInstance>) {
  return { kind, title, scope, config };
}

export function deriveSession(
  session: ViewSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  previous?: ViewSession,
  analysisCompilers: AnalysisCompilerRegistry = {},
  maxConfigBytes?: number,
): ViewSession {
  const editVersion = previous
    ? previous.editVersion +
      Number(
        !sameJsonState(
          instanceContent(previous.instance),
          instanceContent(session.instance),
        ) || previous.filterValid !== session.filterValid,
      )
    : session.editVersion;
  if (session.kind === 'analysis') {
    const cached =
      previous?.kind === 'analysis' &&
      sameAnalysisQueryDraft(previous.instance.config, session.instance.config);
    const compiled = cached
      ? previous.compilation
      : compileAnalysis(session.instance.config, {
          fields: definition.fields,
          capability: definition.analysis!,
          timeZone: definition.timeZone,
          allowedOperators: definition.allowedOperators,
          filterCompilers: compilers,
          compilers: analysisCompilers,
        });
    return {
      ...session,
      editVersion,
      compilation: compiled,
      queryValid:
        session.filterValid &&
        !!compiled.plan &&
        configSizeIssues(session.instance.config, maxConfigBytes).length === 0,
      validation: [
        ...(!session.filterValid
          ? [{ id: 'filters', message: '筛选输入无效' }]
          : []),
        ...(compiled?.errors ?? []),
        ...configSizeIssues(session.instance.config, maxConfigBytes),
        ...validateAnalysisPresentation(
          session.instance.config.presentation,
          compiled?.plan?.schema,
        ).map(message => ({ id: 'presentation', message })),
      ],
      dirty: !sameJsonState(
        instanceContent(session.instance),
        instanceContent(session.baseline),
      ),
      ...(session.conflict
        ? {
            conflict: {
              ...session.conflict,
              local: session.instance,
              filterDraft: session.instance.config.filters,
              filterValid: session.filterValid,
              editVersion,
            },
          }
        : {}),
    };
  }
  const prior = previous?.kind === 'record' ? previous : undefined;
  const compiled = compileSessionFilter(
    session.filterDraft,
    definition,
    compilers,
  );
  const filterPending =
    !session.filterValid ||
    compiled.errors.length > 0 ||
    !sameFilterQuery(compiled.expression, session.appliedFilter);
  let selectedRowKeys = session.selectedRowKeys;
  if (
    selectedRowKeys.length &&
    (!prior ||
      prior.rows !== session.rows ||
      prior.selectedRowKeys !== selectedRowKeys)
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
    filterPending: filterPending!,
    dirty:
      prior &&
      prior.instance === session.instance &&
      prior.baseline === session.baseline
        ? prior.dirty
        : !sameJsonState(
            instanceContent(session.instance),
            instanceContent(session.baseline),
          ),
  };
}

export function isSystemSession(session: ViewSession): boolean {
  return [session.baseline, session.instance].some(
    value => value.scope.type === 'public' && value.scope.source === 'system',
  );
}

/** Server metadata is authoritative; only locally editable content survives a reload. */
export function rebaseSession(
  baseline: ViewInstance,
  latest: ViewSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry = {},
): ViewSession {
  const editable = ({ title, config }: DeepReadonly<ViewInstance>) => ({
    title,
    config,
  });
  const locallyEdited =
    !sameJsonState(editable(latest.instance), editable(latest.baseline)) ||
    !latest.filterValid ||
    (latest.kind === 'record' &&
      !sameJsonState(latest.filterDraft, latest.filterBaseline));
  if (!locallyEdited)
    return {
      ...createSession(baseline, definition, compilers, analysisCompilers),
      editorEpoch: latest.editorEpoch,
    };
  const diverged =
    !sameJsonState(editable(baseline), editable(latest.baseline)) &&
    !sameJsonState(editable(baseline), editable(latest.instance));
  const instance = {
    ...baseline,
    title: latest.instance.title,
    config: latest.instance.config,
    ...(diverged ? { revision: latest.instance.revision } : {}),
  };
  return deriveSession(
    {
      ...latest,
      baseline: diverged ? latest.baseline : baseline,
      instance,
      conflict: diverged
        ? {
            editVersion: latest.editVersion,
            baseline: latest.baseline,
            remote: baseline,
            local: instance,
            filterDraft:
              latest.kind === 'record'
                ? latest.filterDraft
                : latest.instance.config.filters,
            filterValid:
              latest.kind === 'record'
                ? latest.filterValid
                : latest.validation.length === 0,
          }
        : undefined,
      writeError: null,
      requiresReload: false,
    } as ViewSession,
    definition,
    compilers,
    latest,
    analysisCompilers,
  );
}

/** A new copy carries the originating editor state without confusing its saved baseline. */
export function inheritEditingSession(
  baseline: ViewInstance,
  source: ViewSession,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  instance: DeepReadonly<ViewInstance> = {
    ...baseline,
    config: source.instance.config,
  } as DeepReadonly<ViewInstance>,
  analysisCompilers: AnalysisCompilerRegistry = {},
): ViewSession {
  return deriveSession(
    {
      ...createSession(baseline, definition, compilers, analysisCompilers),
      instance,
      ...(source.kind === 'record'
        ? {
            filterDraft: source.filterDraft,
            filterBaseline: source.filterBaseline,
            filterValid: source.filterValid,
            appliedFilter: source.appliedFilter,
          }
        : {}),
    } as ViewSession,
    definition,
    compilers,
    undefined,
    analysisCompilers,
  );
}

export function assertConflictReview(
  session: ViewSession,
  review: ViewInstanceConflict,
): void {
  if (!session.conflict || !sameJsonState(session.conflict, review))
    throw new Error('远端版本或本地编辑已变化，请重新确认冲突');
}

/** Keep server identity/kind and apply only the editable content from the same kind. */
export function withContent(
  baseline: DeepReadonly<RecordViewInstance>,
  local: DeepReadonly<ViewInstance>,
): DeepReadonly<RecordViewInstance>;
export function withContent(
  baseline: DeepReadonly<AnalysisViewInstance>,
  local: DeepReadonly<ViewInstance>,
): DeepReadonly<AnalysisViewInstance>;
export function withContent(
  baseline: DeepReadonly<ViewInstance>,
  local: DeepReadonly<ViewInstance>,
): DeepReadonly<ViewInstance>;
export function withContent(
  baseline: DeepReadonly<ViewInstance>,
  local: DeepReadonly<ViewInstance>,
): DeepReadonly<ViewInstance> {
  if (baseline.kind === 'record' && local.kind === 'record')
    return structuredClone({
      ...baseline,
      title: local.title,
      config: local.config,
    });
  if (baseline.kind === 'analysis' && local.kind === 'analysis')
    return structuredClone({
      ...baseline,
      title: local.title,
      config: local.config,
    });
  throw new Error('实例类型不能改变');
}

function recordIssues(
  instance: DeepReadonly<ViewInstance>,
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
