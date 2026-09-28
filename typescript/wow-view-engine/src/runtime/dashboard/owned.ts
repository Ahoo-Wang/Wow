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
 * The analyses a board owns, judged whole by the analysis kernel.
 *
 * The dashboard kernel may not import the analysis one, so on its own it
 * checks only that an owned view says it is an analysis, and the filter it
 * runs under. Whatever else is wrong with one — a chart with no axis, a
 * metric its definition cannot compute — would otherwise surface as a
 * `TypeError` the first time the board read it. So both places that admit
 * a board run this: a definition's declared board at registration
 * (`validateDefinition`) and every board a runtime opens (`admit`).
 *
 * What it finds is the panel's alone (`panelOf`): one analysis a board
 * cannot run puts that panel out, and the rest of the board draws.
 */

import { dequal } from 'dequal';
import type {
  AnalysisViewConfig,
  DashboardViewConfig,
  DataViewDefinition,
  Issue,
  IssuePath,
  RuntimeLimits,
  ViewScope,
} from '../../model/index.js';
import { isPlainObject, type FieldKindRegistry } from '../../filter/index.js';
import { validateAnalysis } from '../../analysis/index.js';
import { validateShape } from '../../analysis/validateShape.js';
import {
  validateDashboard,
  type PanelDefinition,
  type PanelReferences,
} from '../../dashboard/index.js';
import { panelOf } from './panels.js';

/** One owned analysis the kernel refused, and why. */
export interface OwnedRefusal {
  /** The panel's index in the board's `panels`. */
  index: number;
  /** The panel's id, or its index for one without a usable id. */
  panel: string;
  /** Where the analysis sits in the board's config. */
  path: IssuePath;
  /** The analysis kernel's errors, addressed from the board's config. */
  errors: Issue[];
}

/**
 * Every owned analysis the analysis kernel refuses: against its own
 * definition when `definitionOf` knows it, by its shape alone when it does
 * not. `undefined` from `definitionOf` means "judge the shape"; `null`
 * means "say nothing" — the board's own admission already said the
 * definition is unknown, or is not one an analysis can be of.
 */
export function ownedRefusals(
  config: DashboardViewConfig,
  definitionOf: (definitionId: string) => DataViewDefinition | null | undefined,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
): OwnedRefusal[] {
  // The dashboard kernel has already refused a board whose panels are not a
  // list, and an owned view that is not an object naming an analysis.
  if (!Array.isArray(config.panels)) return [];
  return config.panels.flatMap((panel: unknown, index): OwnedRefusal[] => {
    if (!isPlainObject(panel) || !isPlainObject(panel.owned)) return [];
    const owned = panel.owned;
    if (!isPlainObject(owned.config) || owned.config.kind !== 'analysis')
      return [];
    const analysis = owned.config as unknown as AnalysisViewConfig;
    const target =
      typeof owned.definitionId === 'string'
        ? definitionOf(owned.definitionId)
        : undefined;
    if (target === null) return [];
    const found =
      target?.analysis !== undefined
        ? validateAnalysis(target, analysis, kinds, { limits })
        : validateShape(analysis);
    const errors = found.filter(entry => entry.severity === 'error');
    if (errors.length === 0) return [];
    const path: IssuePath = ['panels', index, 'owned', 'config'];
    return [
      {
        index,
        panel: typeof panel.id === 'string' ? panel.id : String(index),
        path,
        errors: errors.map(entry => ({
          ...entry,
          path: [...path, ...entry.path],
        })),
      },
    ];
  });
}

/**
 * A board's admission as its runtime makes it: the dashboard kernel's
 * (`validateDashboard`), then what the analysis kernel finds in each
 * analysis the board owns, against the definition its panel is judged by.
 *
 * An owned analysis whose definition is unknown, or cannot be analysed, the
 * dashboard kernel has already said so of, so nothing more is said of it
 * here. And that kernel has judged the filter the analysis runs under,
 * merged with the board's: a finding it already made of the panel is not
 * said twice.
 */
export function admitBoard(
  config: DashboardViewConfig,
  scope: ViewScope,
  refs: PanelReferences,
  kinds: FieldKindRegistry,
  options: {
    limits: RuntimeLimits;
    definitions: (definitionId: string) => PanelDefinition | null;
  },
): Issue[] {
  const said = validateDashboard(config, scope, refs, kinds, options);
  const definitionOf = (id: string) => {
    const found = options.definitions(id)?.definition;
    return found?.kind === 'data' && found.analysis ? found : null;
  };
  const refusals = ownedRefusals(config, definitionOf, kinds, options.limits);
  return [
    ...said,
    ...refusals.flatMap(({ index, errors }) => {
      const already = said.filter(found => panelOf(found) === index);
      return errors.filter(
        found =>
          !already.some(
            one => one.code === found.code && dequal(one.params, found.params),
          ),
      );
    }),
  ];
}
