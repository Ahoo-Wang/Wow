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
import { elementScopeFields, type AnalysisScope } from './capability.js';
import { queryFilterIssues } from './queryFilter.js';

export function validateElements(
  config: AnalysisViewConfig,
  scope: AnalysisScope,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): Issue[] {
  return (config.elements ?? []).flatMap((element, index) => {
    const path: IssuePath = ['elements', index];
    if (!scope.declaredPaths.has(element.path))
      return [
        issue('analysis.element.undeclared', [...path, 'path'], {
          path: element.path,
        }),
      ];
    if (!element.filter) return [];
    return queryFilterIssues(
      element.filter,
      elementScopeFields(scope, element.path),
      kinds,
      limits,
      'element',
      [...path, 'filter'],
    );
  });
}
