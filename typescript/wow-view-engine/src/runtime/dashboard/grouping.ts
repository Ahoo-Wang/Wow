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
  AnalysisDateUnit,
  AnalysisGroup,
  DataViewDefinition,
  Issue,
  IssuePath,
} from '../../model/index.js';
import { issue } from '../../filter/index.js';
import { analysisScope } from '../../analysis/index.js';
import type { DataViewConfig } from '../execute.js';

/**
 * What the board's time grouping does to one panel: its time dimension
 * took the unit (`taken`), or the panel has one that cannot — its
 * definition does not group that field by that unit — and keeps its own
 * (`kept`); `null` for a panel with no time dimension, or a board without a
 * time grouping.
 */
export type PanelGrouping = 'taken' | 'kept' | null;

/**
 * The config a panel's child runs under the board's time grouping (D22 F,
 * 整板 按日／周／月): every date-histogram dimension of an analysis set to
 * `unit` where its definition groups that field by it — the one kernel that
 * says what a dimension may be asked by is the analysis kernel's scope, so
 * this reads it rather than a list of its own — and the rest left as they
 * are. A panel that keeps its own says so, a note on the panel: the reader
 * switched the whole board to weeks and this one still reads by month.
 */
export function regrouped(
  config: DataViewConfig,
  definition: DataViewDefinition,
  unit: AnalysisDateUnit | undefined,
  path: IssuePath,
): { config: DataViewConfig; grouping: PanelGrouping; issues: Issue[] } {
  const none = { config, grouping: null, issues: [] };
  if (unit === undefined || config.kind !== 'analysis') return none;
  if (!config.groups.some(group => group.type === 'DATE_HISTOGRAM'))
    return none;
  const capability = definition.analysis;
  const scope = capability && analysisScope(definition, capability, config);
  let taken = false;
  const kept: AnalysisDateUnit[] = [];
  const groups = config.groups.map((group): AnalysisGroup => {
    if (group.type !== 'DATE_HISTOGRAM') return group;
    const units = scope?.aggregations.get(group.field)?.dateUnits ?? [];
    if (!(units as readonly string[]).includes(unit)) {
      kept.push(group.unit);
      return group;
    }
    taken = true;
    return group.unit === unit ? group : { ...group, unit };
  });
  if (!taken)
    return {
      config,
      grouping: 'kept',
      issues: [
        issue('dashboard.grouping.kept', path, { unit: kept[0] }, 'note'),
      ],
    };
  return { config: { ...config, groups }, grouping: 'taken', issues: [] };
}
