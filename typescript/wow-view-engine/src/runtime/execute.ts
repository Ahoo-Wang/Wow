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

import type { CursorQuery, FilterPagedQuery } from '@ahoo-wang/fetcher-wow';
import {
  FIRST_PAGE,
  compileRecord,
  compileSummaries,
  projectRecord,
  projectSummaries,
  validateRecord,
  type SummaryRow,
} from '../record/index.js';
import {
  compileAnalysis,
  compileAnalysisTotals,
  projectAnalysis,
  validateAnalysis,
} from '../analysis/index.js';
import type {
  FieldKindRegistry,
  FilterCompileContext,
} from '../filter/index.js';
import type {
  AnalysisViewConfig,
  DataViewDefinition,
  Issue,
  RecordData,
  RecordPageTarget,
  RecordViewConfig,
  RuntimeLimits,
} from '../model/index.js';
import type { RuntimeEnvironment } from './environment.js';
import type { ProjectedView, ViewSource } from './source.js';

/** The two kinds a `ViewRuntime` executes; a dashboard owns child runtimes. */
export type DataViewConfig = RecordViewConfig | AnalysisViewConfig;

/** Everything an execution needs besides the config itself. */
export interface KernelContext {
  definition: DataViewDefinition;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  source: ViewSource;
}

/** Admission, dispatched by kind. Both kernels take the same three inputs. */
export function validateDataConfig(
  context: KernelContext,
  config: DataViewConfig,
): Issue[] {
  const { definition, kinds, limits } = context;
  return config.kind === 'record'
    ? validateRecord(definition, config, kinds, { limits })
    : validateAnalysis(definition, config, kinds, { limits });
}

/** The first page of the definition's paging mode. */
export function firstPageOf(
  definition: DataViewDefinition,
): RecordPageTarget | undefined {
  const paging = definition.record?.paging;
  return paging ? FIRST_PAGE[paging] : undefined;
}

/**
 * Compiles, queries and projects, in one scheduler slot.
 *
 * Summaries and totals travel with their main query rather than taking a slot
 * of their own: they belong to the same view of the same moment, and a second
 * slot would let them supersede or outlive it.
 */
export function executeDataConfig(
  context: KernelContext,
  config: DataViewConfig,
  page: RecordPageTarget | undefined,
  controller: AbortController,
): Promise<ProjectedView> {
  const filterContext: FilterCompileContext = {
    now: context.environment.now(),
    timeZone: context.environment.timeZone,
  };
  return config.kind === 'record'
    ? executeRecord(context, config, page, filterContext, controller)
    : executeAnalysis(context, config, filterContext, controller);
}

async function executeRecord(
  context: KernelContext,
  config: RecordViewConfig,
  page: RecordPageTarget | undefined,
  filterContext: FilterCompileContext,
  controller: AbortController,
): Promise<ProjectedView> {
  const { definition, kinds, source } = context;
  // `validateRecord` rejects a record config whose definition declares no
  // record capability, so the paging mode is known by the time we execute.
  const target = page ?? firstPageOf(definition) ?? FIRST_PAGE.paged;
  const query = compileRecord(definition, config, kinds, filterContext, target);
  const totals = compileSummaries(definition, config, kinds, filterContext);

  const [result, rows] = await Promise.all([
    'cursor' in target
      ? source.cursor(query as CursorQuery, undefined, controller)
      : source.paged(query as FilterPagedQuery, undefined, controller),
    // A failed summary query costs the summary row, never the page itself.
    totals
      ? source.aggregate(totals, undefined, controller).catch(() => null)
      : Promise.resolve(null),
  ]);

  const index = 'index' in target ? target.index : 1;
  const view = projectRecord(definition, config, result, index);
  return {
    kind: 'record',
    view,
    summaries: summaryRow(context, config, result.list, rows),
  };
}

function summaryRow(
  context: KernelContext,
  config: RecordViewConfig,
  pageRows: readonly RecordData[],
  totals: RecordData[] | null,
): SummaryRow | null {
  if ((config.summaries ?? []).length === 0) return null;
  // Falling back to the visible rows keeps a number on screen; `scope` says so.
  return totals
    ? projectSummaries(context.definition, config, {
        scope: 'total',
        result: totals,
      })
    : projectSummaries(context.definition, config, {
        scope: 'page',
        rows: pageRows,
      });
}

async function executeAnalysis(
  context: KernelContext,
  config: AnalysisViewConfig,
  filterContext: FilterCompileContext,
  controller: AbortController,
): Promise<ProjectedView> {
  const { definition, kinds, source } = context;
  const query = compileAnalysis(definition, config, kinds, filterContext);
  const totalsQuery = compileAnalysisTotals(
    definition,
    config,
    kinds,
    filterContext,
  );

  const [rows, totals] = await Promise.all([
    source.aggregate(query, undefined, controller),
    totalsQuery
      ? source
          .aggregate(totalsQuery, undefined, controller)
          .catch(() => undefined)
      : Promise.resolve(undefined),
  ]);

  return {
    kind: 'analysis',
    view: projectAnalysis(definition, config, rows, totals),
  };
}
