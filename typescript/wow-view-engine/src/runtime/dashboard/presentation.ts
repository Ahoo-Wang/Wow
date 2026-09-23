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
  PANEL_PRESENTATION_MEMBERS,
  type Issue,
  type IssuePath,
  type PanelPresentation,
} from '../../model/index.js';
import { issue, isPlainObject } from '../../filter/index.js';
import {
  validateDataConfig,
  type DataViewConfig,
  type KernelContext,
} from '../execute.js';
import { hasError } from '../runtimeStore.js';

/**
 * The members of a view's config a panel may look at it through, by kind:
 * a record view's rows as a table or as cards, an analysis's layout, chart
 * and totals row (D22 D). What each kind stores anyway; the rest of the
 * config is the question, which a panel never overrides.
 */
const MEMBERS: Record<DataViewConfig['kind'], readonly string[]> = {
  record: ['layout'],
  analysis: ['layout', 'chart', 'table'],
};

/**
 * The config a panel's child runs: the view's own with the panel's override
 * of how it looks laid over it, and what the panel says about an override
 * it could not use.
 *
 * An override is dropped — whole, with one note, never an error — when it no
 * longer fits the view it is laid over: a member that kind does not have (a
 * chart over a record view), or a chart the view's result cannot be drawn as
 * any more because the view was changed since (the analysis kernel says so,
 * the one that says so for the view itself). The panel then shows the view
 * as the view says, which is what it would show without an override. A view
 * already refused on its own config is left to say so: its override is not
 * what is wrong with it.
 *
 * An override that is no object, or names a member no view has, is the
 * dashboard kernel's to note (`validatePresentation`); here it is read as
 * setting nothing more than the members it may set.
 */
export function presentedConfig(
  base: DataViewConfig,
  presentation: PanelPresentation | undefined,
  judge: Pick<KernelContext, 'definition' | 'kinds' | 'limits'>,
  path: IssuePath,
): { config: DataViewConfig; issues: Issue[] } {
  if (!isPlainObject(presentation)) return { config: base, issues: [] };
  const set = Object.keys(presentation).filter(key =>
    (PANEL_PRESENTATION_MEMBERS as readonly string[]).includes(key),
  );
  if (set.length === 0) return { config: base, issues: [] };
  const dropped = {
    config: base,
    issues: [
      issue('dashboard.panel.presentation-dropped', path, {}, 'warning'),
    ],
  };
  const allowed = MEMBERS[base.kind];
  if (set.some(key => !allowed.includes(key))) return dropped;

  const over: Record<string, unknown> = {};
  for (const key of set)
    over[key] = presentation[key as keyof PanelPresentation];
  const config = { ...base, ...over };
  if (hasError(validateDataConfig(judge, config)))
    return hasError(validateDataConfig(judge, base))
      ? { config: base, issues: [] }
      : dropped;
  return { config, issues: [] };
}
