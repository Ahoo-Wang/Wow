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

import { useEffect, useMemo, useRef } from 'react';
import type {
  AnalysisViewConfig,
  FilterNode,
  RecordViewConfig,
  ViewConfig,
} from '../model/index.js';
import { drillFilter } from '../analysis/index.js';
import { defaultRecordConfig } from '../record/index.js';
import {
  handedConditions,
  withFilterMode,
  withHandedFilter,
  type BoardOrigin,
  type GroupNaming,
  type HandOver,
  type ViewNavigation,
  type DataViewRuntime,
  type ViewRuntimeState,
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

/** A view that takes nothing off the page it leaves. */
const NOTHING_HANDED: HandOver = { scopeFilter: null, filter: null };

/**
 * The follow-ups on a dashboard panel's groups (D22 H): the analysis view's
 * own menu — the records behind a group, the question split by another
 * dimension, the question of the group alone — each opened through the
 * host's route (`navigate`) as a view nobody saved, rather than beside the
 * panel: a board has no "beside". Without a route there is nothing to open,
 * and the menu is not offered at all.
 *
 * What opens takes what the panel's view takes off the page (`handOver`,
 * read at the press, D26 Q30): what the page holds as its scope, which
 * nobody in the workbench takes off; the reader's values as conditions of
 * its own beside the view's and the group's, which they can — so the
 * workbench shows what the panel showed. And the board it came from, for
 * the way back (Q33).
 */
export function usePanelFollowUps(
  runtime: DataViewRuntime | null,
  navigate: ((to: ViewNavigation) => void) | undefined,
  handOver: () => HandOver | null,
): FollowUpHost {
  const state = useViewRuntime(runtime) as ViewRuntimeState<ViewConfig> | null;
  // Read at the press rather than on render: the board's filters move
  // without this panel's view changing.
  const latest = useRef(handOver);
  useEffect(() => {
    latest.current = handOver;
  }, [handOver]);
  return useMemo(() => {
    const definition = runtime?.definition;
    const canDrill =
      navigate !== undefined &&
      runtime?.kind === 'analysis' &&
      definition?.kind === 'data' &&
      definition.record !== undefined;
    const handed = () => latest.current() ?? NOTHING_HANDED;
    return {
      state,
      canDrill,
      drill(
        conditions: readonly FilterNode[],
        title: string,
        subject?: string,
      ) {
        if (!canDrill || !runtime || !state || definition?.kind !== 'data')
          return;
        const away = handed();
        const config: RecordViewConfig = {
          ...defaultRecordConfig(definition, runtime.limits),
          filter: drillFilter(state.applied.filter, [
            ...handedConditions(away.filter),
            ...conditions,
          ]),
        };
        navigate?.({
          kind: 'unsaved',
          definitionId: definition.id,
          title,
          config: withFilterMode(config),
          scopeFilter: away.scopeFilter,
          ...naming(subject, conditions),
          ...origin(away.from),
        });
      },
      follow(
        config: ViewConfig,
        title: string,
        conditions: readonly FilterNode[],
        subject?: string,
      ) {
        if (!navigate || !runtime || config.kind !== 'analysis') return;
        navigate(
          unsaved(runtime, config, title, handed(), subject, conditions),
        );
      },
    };
  }, [runtime, state, navigate]);
}

/**
 * An analysis the board owns, opened in the workbench (「在工作台中打开」): it
 * has no saved view to open, so it goes as a view nobody saved, taking what
 * its panel takes off the board (`handOver`).
 */
export function ownedNavigation(
  runtime: DataViewRuntime,
  title: string,
  handOver: HandOver | null,
): ViewNavigation | null {
  const config = runtime.getSnapshot().applied;
  if (config.kind !== 'analysis') return null;
  return unsaved(runtime, config, title, handOver ?? NOTHING_HANDED);
}

/** An analysis nobody saved, under what it takes off the page. */
function unsaved(
  runtime: DataViewRuntime,
  config: AnalysisViewConfig,
  title: string,
  away: HandOver,
  subject?: string,
  conditions: readonly FilterNode[] = [],
): ViewNavigation {
  return {
    kind: 'unsaved',
    definitionId: runtime.definition.id,
    title,
    config: withHandedFilter(config, away.filter),
    scopeFilter: away.scopeFilter,
    ...naming(subject, conditions),
    ...origin(away.from),
  };
}

/**
 * What the title claims of the group, which the workbench reads to stop
 * claiming it once the reader takes the group off: the group's conditions
 * alone, not the board's, which the title never named.
 */
function naming(
  subject: string | undefined,
  conditions: readonly FilterNode[],
): { named?: GroupNaming } {
  return subject === undefined ? {} : { named: { subject, conditions } };
}

/** The board a view leaves, when it has one to go back to. */
function origin(from: BoardOrigin | undefined): { from?: BoardOrigin } {
  return from ? { from } : {};
}
