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
  createAnalysisSession,
  deriveAnalysisSession,
} from '../analysis/analysisSession.js';
import type { AnalysisCompilerRegistry } from '../analysis/analysisModel.js';
import { sameJsonState } from '../lib/snapshot.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  ViewSession,
  ViewDefinition,
  ViewInstance,
  ViewInstanceConflict,
  RecordViewInstance,
  AnalysisViewInstance,
} from '../contracts/viewModel.js';
import {
  createRecordSession,
  deriveRecordSession,
} from '../record/engine/recordSession.js';

export function createSession(
  instance: DeepReadonly<ViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  compilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry = {},
  maxConfigBytes?: number,
): ViewSession {
  if (instance.kind === 'analysis')
    return createAnalysisSession(
      instance,
      definition,
      compilers,
      analysisCompilers,
      maxConfigBytes,
    );
  return createRecordSession(instance, definition, compilers, maxConfigBytes);
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
  const dirty =
    session.kind === 'record' &&
    previous?.kind === 'record' &&
    previous.instance === session.instance &&
    previous.baseline === session.baseline
      ? previous.dirty
      : !sameJsonState(
          instanceContent(session.instance),
          instanceContent(session.baseline),
        );
  if (session.kind === 'analysis') {
    return deriveAnalysisSession(
      session,
      definition,
      compilers,
      analysisCompilers,
      previous?.kind === 'analysis' ? previous : undefined,
      editVersion,
      dirty,
      maxConfigBytes,
    );
  }
  const prior = previous?.kind === 'record' ? previous : undefined;
  return deriveRecordSession(
    session,
    definition,
    compilers,
    prior,
    editVersion,
    dirty,
    maxConfigBytes,
  );
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

/** A persisted result replaces the baseline; the discriminated union keeps store.patch narrowing. */
export function baselinePatch(
  baseline: DeepReadonly<RecordViewInstance> | RecordViewInstance,
  local: DeepReadonly<ViewInstance>,
): {
  kind: 'record';
  baseline: DeepReadonly<RecordViewInstance>;
  instance: DeepReadonly<RecordViewInstance>;
};
export function baselinePatch(
  baseline: DeepReadonly<AnalysisViewInstance> | AnalysisViewInstance,
  local: DeepReadonly<ViewInstance>,
): {
  kind: 'analysis';
  baseline: DeepReadonly<AnalysisViewInstance>;
  instance: DeepReadonly<AnalysisViewInstance>;
};
export function baselinePatch(
  baseline: DeepReadonly<ViewInstance>,
  local: DeepReadonly<ViewInstance>,
):
  | {
      kind: 'record';
      baseline: DeepReadonly<RecordViewInstance>;
      instance: DeepReadonly<RecordViewInstance>;
    }
  | {
      kind: 'analysis';
      baseline: DeepReadonly<AnalysisViewInstance>;
      instance: DeepReadonly<AnalysisViewInstance>;
    };
export function baselinePatch(
  baseline: DeepReadonly<ViewInstance>,
  local: DeepReadonly<ViewInstance>,
):
  | {
      kind: 'record';
      baseline: DeepReadonly<RecordViewInstance>;
      instance: DeepReadonly<RecordViewInstance>;
    }
  | {
      kind: 'analysis';
      baseline: DeepReadonly<AnalysisViewInstance>;
      instance: DeepReadonly<AnalysisViewInstance>;
    } {
  if (baseline.kind === 'record')
    return { kind: 'record', baseline, instance: withContent(baseline, local) };
  return {
    kind: 'analysis',
    baseline,
    instance: withContent(baseline, local),
  };
}
