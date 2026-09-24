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
  AnalysisViewConfig,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import {
  outOfScopeNames,
  withOutOfScope,
  type AnalysisScope,
} from './capability.js';
import { queryFilterIssues } from './queryFilter.js';

/**
 * Admits the expansion chain.
 *
 * Wow's `elements` is one ordered parent-to-child walk, so a config may only
 * follow the chain the capability declares, from its first level down, and
 * stop wherever it likes. Two ways to get it wrong, and they are different
 * mistakes: a path the chain never mentions, and one it does mention but at
 * another depth — an inner array expanded without the array that holds it, or
 * two levels swapped. The second is what a list of sibling arrays used to
 * look like, and it is the one Wow answers with "requires its declared
 * element scope" rather than "unknown field".
 *
 * The first entry that breaks the chain ends the scope, so nothing below it
 * is judged against fields the expansion never reached.
 */
export function validateElements(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  const configured = config.elements ?? [];
  for (const [index, element] of configured.entries()) {
    const path: IssuePath = ['elements', index];
    if (scope.elements[index]?.path !== element.path) {
      issues.push(
        issue(
          scope.declaredChain.includes(element.path)
            ? 'analysis.element.out-of-chain'
            : 'analysis.element.undeclared',
          [...path, 'path'],
          { path: element.path },
        ),
      );
      break;
    }
    if (!element.filter) continue;
    // An element's gate is read inside that element and nowhere else: not the
    // root's fields, not an outer level's, not an inner one's.
    const domain = scope.elements[index].fields;
    issues.push(
      ...queryFilterIssues({
        tree: element.filter,
        fields: withOutOfScope(scope, domain),
        outOfScope: outOfScopeNames(scope, domain),
        kinds,
        limits,
        position: 'element',
        path: [...path, 'filter'],
      }),
    );
  }
  return issues;
}
