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

import { useMemo } from 'react';
import type {
  AnalysisViewConfig,
  FilterNode,
  FilterTree,
  RecordViewConfig,
  ViewConfig,
} from '../model/index.js';
import { drillFilter } from '../analysis/index.js';
import { isSimpleTree } from '../filter/index.js';
import { defaultRecordConfig } from '../record/index.js';
import type {
  DashboardNavigation,
  DataViewRuntime,
  ViewRuntimeState,
} from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';
import type { WorkbenchController } from './useWorkbench.js';

/**
 * The part of a workbench the follow-up menu drives (`useAnalysisResult`):
 * what a dashboard panel hands it in place of one.
 */
export type FollowUpHost = Pick<
  WorkbenchController,
  'state' | 'canDrill' | 'drill' | 'follow'
>;

/**
 * The follow-ups on a dashboard panel's groups (D22 H): the analysis view's
 * own menu — the records behind a group, the question split by another
 * dimension, the question of the group alone — each opened through the
 * host's route (`navigate`) as a view nobody saved, rather than beside the
 * panel: a board has no "beside". Without a route there is nothing to open,
 * and the menu is not offered at all.
 *
 * What opens carries the board: the filters as they reach the panel — the
 * child's injected scope, in its own field names — join the view's own
 * conditions and the group's, so the workbench shows what the panel showed
 * and the reader can take any of them off there.
 */
export function usePanelFollowUps(
  runtime: DataViewRuntime | null,
  navigate: ((to: DashboardNavigation) => void) | undefined,
): FollowUpHost {
  const state = useViewRuntime(runtime) as ViewRuntimeState<ViewConfig> | null;
  return useMemo(() => {
    const definition = runtime?.definition;
    const canDrill =
      navigate !== undefined &&
      runtime?.kind === 'analysis' &&
      definition?.kind === 'data' &&
      definition.record !== undefined;
    const board = boardConditions(runtime?.scopeFilter ?? null);
    return {
      state,
      canDrill,
      drill(conditions: readonly FilterNode[], title: string) {
        if (!canDrill || !runtime || !state || definition?.kind !== 'data')
          return;
        const config: RecordViewConfig = {
          ...defaultRecordConfig(definition, runtime.limits),
          filter: drillFilter(state.applied.filter, [...board, ...conditions]),
        };
        navigate?.({
          kind: 'unsaved',
          definitionId: definition.id,
          title,
          config: withMode(config),
        });
      },
      follow(config: ViewConfig, title: string) {
        if (!navigate || !runtime || config.kind !== 'analysis') return;
        navigate({
          kind: 'unsaved',
          definitionId: runtime.definition.id,
          title,
          config: underBoard(config, board),
        });
      },
    };
  }, [runtime, state, navigate]);
}

/**
 * An analysis the board owns, opened in the workbench (「在工作台中打开」): it
 * has no saved view to open, so it goes as a view nobody saved, under the
 * board's filters as they reach its panel.
 */
export function ownedNavigation(
  runtime: DataViewRuntime,
  title: string,
): DashboardNavigation | null {
  const config = runtime.getSnapshot().applied;
  if (config.kind !== 'analysis') return null;
  return {
    kind: 'unsaved',
    definitionId: runtime.definition.id,
    title,
    config: underBoard(config, boardConditions(runtime.scopeFilter)),
  };
}

/** The board's conditions as nodes to AND onto a view's own. */
function boardConditions(scope: FilterTree | null): FilterNode[] {
  if (!scope) return [];
  return isSimpleTree(scope) ? scope.children : [scope];
}

function underBoard(
  config: AnalysisViewConfig,
  board: readonly FilterNode[],
): AnalysisViewConfig {
  if (board.length === 0) return config;
  return withMode({ ...config, filter: drillFilter(config.filter, board) });
}

/**
 * A condition tree that no longer flattens into one "all of" group is an
 * advanced one, which is how the editor has to open it.
 */
function withMode<C extends RecordViewConfig | AnalysisViewConfig>(
  config: C,
): C {
  return isSimpleTree(config.filter)
    ? config
    : { ...config, filterMode: 'advanced' };
}
