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

/**
 * The definition registry: what the application declared in code.
 *
 * Definitions are code, so they are judged once — at construction — and a
 * definition that fails is kept but refused at the point of use. Everything
 * an engine reads out of a definition goes through here: the definition
 * itself, what admission said about it, and the instances it declares.
 */

import {
  CODE_REVISION,
  systemInstanceId,
  type Issue,
  type RuntimeLimits,
  type ViewDefinition,
  type ViewInstance,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import {
  isUsableDefinition,
  validateDefinition,
} from './validateDefinition.js';
import { ViewCommandError } from './write.js';

export class DefinitionRegistry {
  readonly definitions: ReadonlyMap<string, ViewDefinition>;
  /** `validateDefinition` per registered definition, computed once. */
  private readonly findings = new Map<string, Issue[]>();

  constructor(
    definitions: readonly ViewDefinition[],
    kinds: FieldKindRegistry,
    limits: RuntimeLimits,
    report: (found: Issue) => void,
  ) {
    this.definitions = new Map(
      definitions.map(definition => [definition.id, definition]),
    );

    // Definitions are code, so they are judged once, here, rather than on
    // every open. One that fails is kept but refused at the point of use:
    // that beats a blank registry, and beats a crash at application start.
    for (const definition of definitions) {
      const found = validateDefinition(definition, kinds, { limits });
      this.findings.set(definition.id, found);
      for (const entry of found) report(entry);
    }
  }

  /** What `validateDefinition` said about one definition, for a host to show. */
  issues(definitionId: string): Issue[] {
    return this.findings.get(definitionId) ?? [];
  }

  require(id: string): ViewDefinition {
    const definition = this.definitions.get(id);
    if (!definition)
      throw new ViewCommandError(
        issue('view.definition.not-found', [], { id }),
      );
    // A definition that failed admission cannot produce a usable view: its
    // defaults, its system views or its compiled queries would throw instead.
    const found = this.findings.get(id) ?? [];
    if (!isUsableDefinition(found))
      throw new ViewCommandError(
        issue('view.definition.invalid', [], {
          id,
          issues: found.filter(entry => entry.severity === 'error').length,
        }),
      );
    return definition;
  }

  /** One instance a definition declares in code, by its declared id. */
  systemInstance(definitionId: string, viewId: string): ViewInstance {
    const definition = this.require(definitionId);
    const view = definition.views?.find(entry => entry.id === viewId);
    if (!view)
      throw new ViewCommandError(
        issue('view.open.not-found', [], { id: viewId }),
      );
    return {
      id: systemInstanceId(definitionId, viewId),
      definitionId,
      title: view.title,
      scope: 'system',
      revision: CODE_REVISION,
      config: view.config,
    };
  }
}

/** Instances a definition declares in code, in declaration order. */
export function systemInstances(definition: ViewDefinition): ViewInstance[] {
  return (definition.views ?? []).map(view => ({
    id: systemInstanceId(definition.id, view.id),
    definitionId: definition.id,
    title: view.title,
    scope: 'system' as const,
    revision: CODE_REVISION,
    config: view.config,
  }));
}
