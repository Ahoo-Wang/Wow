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
  AnalysisSession,
  AnalysisViewInstance,
  ViewDefinition,
} from '../contracts/viewModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { configSizeIssues } from '../engine/sessionValidationCache.js';
import { compileAnalysis } from './analysisCompiler.js';
import type { AnalysisCompilerRegistry } from './analysisModel.js';
import { validateAnalysisPresentation } from './analysisProjection.js';
import { sameAnalysisQueryDraft } from './analysisQueryPolicy.js';

export function createAnalysisSession(
  instance: DeepReadonly<AnalysisViewInstance>,
  definition: DeepReadonly<ViewDefinition>,
  filterCompilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry = {},
  maxConfigBytes?: number,
): AnalysisSession {
  return deriveAnalysisSession(
    {
      kind: 'analysis',
      positionId: instance.id,
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
    filterCompilers,
    analysisCompilers,
    undefined,
    0,
    false,
    maxConfigBytes,
  );
}

export function deriveAnalysisSession(
  session: AnalysisSession,
  definition: DeepReadonly<ViewDefinition>,
  filterCompilers: FilterCompilerRegistry,
  analysisCompilers: AnalysisCompilerRegistry,
  previous: AnalysisSession | undefined,
  editVersion: number,
  dirty: boolean,
  maxConfigBytes?: number,
): AnalysisSession {
  const compiled =
    previous &&
    sameAnalysisQueryDraft(previous.instance.config, session.instance.config)
      ? previous.compilation
      : compileAnalysis(session.instance.config, {
          fields: definition.fields,
          capability: definition.analysis!,
          timeZone: definition.timeZone,
          allowedOperators: definition.allowedOperators,
          filterCompilers,
          compilers: analysisCompilers,
        });
  const sizeIssues = configSizeIssues(session.instance.config, maxConfigBytes);
  return {
    ...session,
    editVersion,
    compilation: compiled,
    queryValid: session.filterValid && !!compiled.plan && !sizeIssues.length,
    validation: [
      ...(!session.filterValid
        ? [{ id: 'filters', message: '筛选输入无效' }]
        : []),
      ...compiled.errors,
      ...sizeIssues,
      ...validateAnalysisPresentation(
        session.instance.config.presentation,
        compiled.plan?.schema,
      ).map(message => ({ id: 'presentation', message })),
    ],
    dirty,
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

export function clearAnalysisResult(session: AnalysisSession): AnalysisSession {
  return { ...session, result: null };
}
