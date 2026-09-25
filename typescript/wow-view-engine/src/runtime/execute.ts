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

import type { CursorQuery, FilterPagedQuery } from '@ahoo-wang/wow-client';
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
  type AnalysisView,
} from '../analysis/index.js';
import { foldsSplit, splitWholeConfig } from '../analysis/splitOther.js';
import {
  issue,
  type FieldKindRegistry,
  type FilterCompileContext,
} from '../filter/index.js';
import type {
  AnalysisViewConfig,
  DataViewConfig,
  DataViewDefinition,
  Issue,
  RecordData,
  RecordPageTarget,
  RecordViewConfig,
  RuntimeLimits,
} from '../model/index.js';
import type { RuntimeEnvironment } from './environment.js';
import { isCalledOff, type FailureReporter } from './failures.js';
import type { ProjectedView, ViewSource } from './source.js';
import { sourceReason } from './sourceReason.js';

/** Everything an execution needs besides the config itself. */
export interface KernelContext {
  definition: DataViewDefinition;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  source: ViewSource;
  /**
   * Tells the host of a query that failed where nothing rejects to a caller
   * who would: a summary, a total or a split answered without (D40). The
   * view's own query is told by the runtime, which knows whether it still
   * counts.
   */
  queryFailed: FailureReporter;
}

/** Admission, dispatched by kind. Both kernels take the same three inputs. */
export function validateDataConfig(
  context: Pick<KernelContext, 'definition' | 'kinds' | 'limits'>,
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
    // A failed summary query costs the summary row's scope, never the page
    // itself — and the downgrade is reported rather than absorbed, below.
    totals
      ? attempt(
          context,
          'summaries',
          controller,
          source.aggregate(totals, undefined, controller),
        )
      : null,
  ]);

  const index = 'index' in target ? target.index : 1;
  const view = projectRecord(definition, config, result, index, context.limits);
  const summaries = summaryRow(context, config, result.list, rows);
  return {
    kind: 'record',
    view,
    summaries,
    issues: summaries?.scope === 'page' ? [summaryDowngraded()] : [],
  };
}

/**
 * A query whose failure is one of the answers, not the end of the request.
 * The screen says what it cost; the host is told what failed — unless the
 * request it rode with was called off, which is no failure at all.
 */
function attempt<T>(
  context: KernelContext,
  operation: string,
  controller: AbortController,
  query: Promise<T>,
): Promise<T | null> {
  return query.catch((error: unknown) => {
    if (!isCalledOff(error, controller.signal))
      context.queryFailed(operation, error);
    return null;
  });
}

/**
 * The summary row, at the widest scope that actually answered.
 *
 * `total` is its own aggregation over everything the conditions match;
 * `page` is the rows on screen added up, which is all that is left when that
 * aggregation fails. Dropping the row instead would be no kinder — a page
 * total is a useful number — so it stays, saying which of the two it is, and
 * the caller reports the downgrade. An AVG over the twenty rows in front of
 * you, presented as the AVG over forty thousand, is the one mistake this row
 * could make; silence about it is how that mistake is made.
 */
function summaryRow(
  context: KernelContext,
  config: RecordViewConfig,
  pageRows: readonly RecordData[],
  totals: RecordData[] | null,
): SummaryRow | null {
  if ((config.summaries ?? []).length === 0) return null;
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

/**
 * The scope the summary row lost.
 *
 * Nothing is parameterised: the path addresses the `summaries` the config
 * asked for, and which rows the row does cover is what its own label says.
 * It is built per result all the same, so no two results share one object.
 */
function summaryDowngraded(): Issue {
  return issue(
    'runtime.summary.page-only',
    ['summaries'],
    undefined,
    'warning',
  );
}

async function executeAnalysis(
  context: KernelContext,
  config: AnalysisViewConfig,
  filterContext: FilterCompileContext,
  controller: AbortController,
): Promise<ProjectedView> {
  const { definition, kinds, limits, source } = context;
  const query = compileAnalysis(
    definition,
    config,
    kinds,
    filterContext,
    limits,
  );
  const totalsQuery = compileAnalysisTotals(
    definition,
    config,
    kinds,
    filterContext,
  );

  const [rows, totals] = await Promise.all([
    source.aggregate(query, undefined, controller),
    totalsQuery
      ? attempt(
          context,
          'totals',
          controller,
          source.aggregate(totalsQuery, undefined, controller),
        )
      : null,
  ]);

  const projected = projectAnalysis(
    definition,
    config,
    rows,
    totals ?? undefined,
    kinds,
    filterContext,
    limits,
  );
  // A split past the palette that adds up asks once more, grouped by its
  // axis alone, for the rest it folds into 「其他」 (D33 Q56) — only then, so
  // every other chart costs the queries it did. Failing, the chart draws
  // every series, as over a metric that does not add up — and says so,
  // with the source's reason (D42): a chart that quietly draws another
  // chart than the one asked for is the one thing it must not do.
  const whole = foldsSplit(config, projected.rows)
    ? await source
        .aggregate(
          compileAnalysis(
            definition,
            splitWholeConfig(definition, config, limits),
            kinds,
            filterContext,
            limits,
          ),
          undefined,
          controller,
        )
        .then(
          answer => ({ rows: answer }),
          async (error: unknown) => {
            // The host is told what failed, as `attempt` tells it, unless
            // the request it rode with was called off.
            if (!isCalledOff(error, controller.signal))
              context.queryFailed('split', error);
            return { reason: await sourceReason(error) };
          },
        )
    : null;
  const view =
    whole && 'rows' in whole
      ? projectAnalysis(
          definition,
          config,
          rows,
          totals ?? undefined,
          kinds,
          { ...filterContext, splitWhole: whole.rows },
          limits,
        )
      : projected;
  return {
    kind: 'analysis',
    view,
    issues: [
      ...(whole && 'reason' in whole
        ? [
            issue(
              'analysis.split.whole-failed',
              ['chart', 'cartesian', 'splitBy'],
              { reason: whole.reason },
              'warning',
            ),
          ]
        : []),
      ...cutShortIssues(config, view),
    ],
  };
}

/**
 * The findings a result makes about the groups below its last row
 * (`cutShortIssues`). A workbench draws them beside the result they are
 * about rather than in the status line over the whole view: a sentence
 * about the rows belongs where the rows are (2026-09-23 audit).
 */
export const CUT_SHORT_CODES: readonly string[] = [
  'analysis.result.more-groups',
  'analysis.result.at-limit',
];

/**
 * What the screen is told about the groups below the last row.
 *
 * A grouping that goes on past the last row shown makes every share,
 * percentage and slice on the screen a fraction of a prefix — the one thing a
 * reader cannot check for themselves. The query asked for one row more than
 * the limit, so this is normally a fact rather than a guess
 * (`analysis.result.more-groups`); only where no probe was possible is it
 * still said as a maybe (`analysis.result.at-limit`), and the two are never
 * both true. See `analysisProbeLimit` and `AnalysisView.truncated`.
 */
function cutShortIssues(
  config: AnalysisViewConfig,
  view: AnalysisView,
): Issue[] {
  // Only shares of a whole mislead when the whole is cut short: a pie's
  // slices are fractions of the groups shown. Elsewhere every row is its
  // own true number, and a view that asks for its top 30 has left the rest
  // out on purpose — worth saying, but as a note, not as a warning that
  // something is wrong.
  const severity = sharesOfWhole(config) ? 'warning' : 'note';
  // A ranking — the groups ordered by a metric and cut at N — left the rest
  // out on purpose: 「销售额前 10 的城市」 is the question, and a note under
  // it saying "only the first 10 are shown" tells its author what they
  // asked for. The probe cannot know intent; the sort is the one place the
  // config says it. Unsorted, or sorted by a dimension, the first N are
  // whichever the order happens to put first, and the note stays. A pie
  // keeps its warning either way: its slices are shares of what is shown.
  if (severity === 'note' && isRanking(config)) return [];
  if (view.truncated)
    return [
      issue(
        'analysis.result.more-groups',
        ['limit'],
        { limit: config.limit },
        severity,
      ),
    ];
  if (view.atLimit === undefined) return [];
  return [
    issue(
      'analysis.result.at-limit',
      ['limit'],
      { limit: view.atLimit },
      severity,
    ),
  ];
}

/** Whether the groups are ordered by a metric first: a top N, on purpose. */
function isRanking(config: AnalysisViewConfig): boolean {
  const first = config.sort[0];
  return (
    first !== undefined &&
    config.metrics.some(metric => metric.alias === first.alias)
  );
}

/** Whether the result is drawn as shares of the groups it holds. */
function sharesOfWhole(config: AnalysisViewConfig): boolean {
  return config.layout === 'chart' && config.chart?.type === 'pie';
}
