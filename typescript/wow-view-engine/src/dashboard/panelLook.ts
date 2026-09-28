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
  DashboardViewPanel,
  Issue,
  IssuePath,
  ViewDefinition,
} from '../model/index.js';
import { isPlainObject, issue } from '../filter/index.js';
import { isPresentationMember } from './panels.js';
import { declaredView, type DefinitionLookup } from './declared.js';

// How a data panel looks at its view and which view it opens, as admission
// judges them: the shape, and a view declared in code against the registered
// definitions (validate.ts keeps the rest of the panel).

/**
 * An override of how the panel looks (D22 D). Only its shape is this
 * kernel's to judge: whether a chart fits the view's result is the analysis
 * kernel's, and the runtime asks it — an override that does not fit is
 * dropped there with the same note, never refused (`presentation.ts`).
 */
export function validatePresentation(
  panel: DashboardViewPanel,
  path: IssuePath,
): Issue[] {
  const presentation: unknown = panel.presentation;
  if (presentation === undefined) return [];
  const fits =
    isPlainObject(presentation) &&
    Object.keys(presentation).every(isPresentationMember);
  return fits
    ? []
    : [
        issue(
          'dashboard.panel.presentation-dropped',
          [...path, 'presentation'],
          {},
          'warning',
        ),
      ];
}

/**
 * The view 「在工作台中打开」 opens in the panel's stead: an id, which the
 * workbench resolves when it opens. Anything else is let go with a warning,
 * and the panel's own view opens.
 */
export function validateOpens(
  panel: DashboardViewPanel,
  path: IssuePath,
  view: { definition: ViewDefinition } | null = null,
  lookup?: DefinitionLookup,
): Issue[] {
  const opens: unknown = panel.opens;
  const at: IssuePath = [...path, 'opens'];
  if (opens === undefined) return [];
  if (typeof opens !== 'string' || opens === '')
    return [issue('dashboard.panel.opens-invalid', at, {}, 'warning')];
  // A view declared in code is checked where it is registered (todo C): one
  // not there, or of another definition than the panel's own, is not what
  // 「在工作台中打开」 can open, and the panel opens its own instead.
  const declared = declaredView(opens, lookup);
  if (!declared) return [];
  if ('missing' in declared)
    return [
      declared.missing.definition === null
        ? issue('dashboard.panel.opens-unknown', at, {}, 'warning')
        : issue(
            'dashboard.panel.opens-undeclared',
            at,
            { definition: declared.missing.definition },
            'warning',
          ),
    ];
  const other = declared.reference.definition;
  return view && other.id !== view.definition.id
    ? [
        issue(
          'dashboard.panel.opens-elsewhere',
          at,
          { view: declared.reference.instance.title, definition: other.title },
          'warning',
        ),
      ]
    : [];
}
