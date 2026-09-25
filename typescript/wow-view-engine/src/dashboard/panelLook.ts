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

import type { DashboardViewPanel, Issue, IssuePath } from '../model/index.js';
import { isPlainObject, issue } from '../filter/index.js';
import { isPresentationMember } from './panels.js';

// How a data panel looks at its view and which view it opens, as admission
// judges them: the shape only (validate.ts keeps the rest of the panel).

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
): Issue[] {
  const opens: unknown = panel.opens;
  return opens === undefined || (typeof opens === 'string' && opens !== '')
    ? []
    : [
        issue(
          'dashboard.panel.opens-invalid',
          [...path, 'opens'],
          {},
          'warning',
        ),
      ];
}
