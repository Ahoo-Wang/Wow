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

import { useCallback, useState } from 'react';
import {
  analysisScope,
  momentMetrics,
  type Dropped,
} from '../analysis/index.js';
import { withoutDangling } from '../analysis/dangling.js';
import type {
  AnalysisMetric,
  AnalysisViewConfig,
  ChartSpec,
  ViewConfig,
} from '../model/index.js';
import type { ViewRuntime } from '../runtime/index.js';
import { fieldOptions, type AnalysisFieldOption } from './analysisFields.js';
import type { DropCause, ReshapeProposal } from './analysisEditing.js';

/**
 * What the last edit took out of the question besides what it was asked
 * to — the dimensions and metrics a step of the chain left behind, the
 * derived metrics that read one removed — and the step it followed (D71).
 */
export interface DropNotice extends Dropped {
  /** The step of the chain it followed; absent after any other edit. */
  cause?: DropCause;
  /**
   * What the dropped ones are named against: the fields and metrics of the
   * question before the edit, whose scope may no longer hold their fields.
   */
  naming: { fields: AnalysisFieldOption[]; metrics: AnalysisMetric[] };
}

/**
 * Whether a metric's ✕ may take it out: `last` when it is the only one,
 * `cascade` when every other metric reads it and would leave with it — the
 * edit #3777 makes as asked, leaving admission to refuse it.
 */
export type MetricRemoval = 'ok' | 'last' | 'cascade';

export interface ReshapeInput {
  runtime: ViewRuntime | null;
  /** The draft on screen: the notice lasts while it is the one an edit made. */
  draft: ViewConfig | undefined;
  /** That draft, where it is an analysis. */
  config: AnalysisViewConfig | undefined;
  /** Edits the live draft (`useAnalysisEditor`'s `change`). */
  change(
    update: (current: AnalysisViewConfig) => Partial<AnalysisViewConfig>,
  ): void;
  /** The chart fitted to a shape (`useAnalysisEditor`'s `fitTo`). */
  fitTo(
    chart: ChartSpec,
    shape: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'elements'>,
  ): ChartSpec;
}

/**
 * The one way the question's shape changes — `reshape` — with what an edit
 * of it took out beyond what it was asked to kept for the notice that says
 * so and puts it back (D71), and whether a metric may be removed at all.
 */
export function useReshape({
  runtime,
  draft,
  config,
  change,
  fitTo,
}: ReshapeInput) {
  const owner = runtime?.definition;
  const definition = owner?.kind === 'data' ? owner : undefined;
  const capability = definition?.analysis;
  /**
   * A change to what is grouped or measured, with everything that names an
   * alias brought along.
   *
   * Groups and metrics are what the rest of the config points at: the chart
   * addresses its slots by alias, the sort orders by one, the table lists
   * them. Editing the lists alone left those three naming aliases that were
   * gone — the chart reported `chart.group.unconsumed` and vanished, and the
   * sort reported `analysis.sort.unknown-alias` — so the one edit carries all
   * four. A slot, an ordering or a column the user chose is kept wherever it
   * still names something; nothing else survives the alias it referred to.
   * The metrics themselves follow the same way (`withoutDangling`): a
   * derived metric that read one gone goes with it, and so does a having
   * rule on it — else the next run stopped at `analysis.derived.unknown-metric`
   * or `analysis.having.unknown-metric`. Only what this edit broke goes: a
   * reference that read nothing before it stays for admission to point at.
   * Where following would leave nothing to measure, the edit is made as
   * asked, and admission says so.
   */
  // What the last edit took out, the question it started from and the
  // draft it made: the notice lasts while that draft is the draft. After
  // an undo, the draft it put back, so 「已撤销」 lasts as long.
  const [drop, setDrop] = useState<
    | { notice: DropNotice; before: AnalysisViewConfig; after: unknown }
    | { notice: null; after: unknown }
    | null
  >(null);
  const factsOf = useCallback(
    (of: AnalysisViewConfig): Parameters<typeof withoutDangling>[1] =>
      definition && capability
        ? {
            moments: momentMetrics(
              of.metrics,
              analysisScope(definition, capability, of).fields,
            ),
            havingMetrics: capability.havingMetrics,
          }
        : {},
    [definition, capability],
  );
  const reshape = useCallback(
    (
      update: (current: AnalysisViewConfig) => ReshapeProposal | undefined,
      cause?: DropCause,
    ) => {
      // Filled in by the update, which `change` runs synchronously.
      const said: { notice?: DropNotice; before?: AnalysisViewConfig } = {};
      change(current => {
        const proposal = update(current);
        if (!proposal) return {};
        const { dropped: stepped, columns: listed, ...proposed } = proposal;
        const shape = { ...current, ...proposed };
        const followed = withoutDangling(shape, factsOf(shape), {
          shape: current,
          facts: factsOf(current),
        });
        // Where following the edit would leave nothing to measure, the edit
        // is made as asked and admission says what it lacks.
        const next =
          followed.metrics.length === 0
            ? proposed
            : { ...proposed, metrics: followed.metrics };
        const took = {
          groups: stepped?.groups ?? [],
          metrics: [
            ...(stepped?.metrics ?? []),
            ...(followed.metrics.length === 0
              ? []
              : shape.metrics.filter(metric =>
                  followed.removed.includes(metric.alias),
                )),
          ],
        };
        if (took.groups.length + took.metrics.length > 0) {
          const naming = {
            fields:
              definition && capability
                ? fieldOptions(
                    analysisScope(definition, capability, current),
                    runtime?.kinds,
                  )
                : [],
            metrics: current.metrics,
          };
          said.notice = { ...took, naming, ...(cause ? { cause } : {}) };
          said.before = current;
        }
        // The having follows either way: only the derived metric's finding
        // is left for admission, not rules on a metric that left.
        const { having } = followed;
        const aliases = new Set([
          ...next.groups.map(group => group.alias),
          ...next.metrics.map(metric => metric.alias),
        ]);
        return {
          ...next,
          ...(having === current.having ? {} : { having }),
          chart: fitTo(current.chart, { ...current, ...next }),
          // Wow refuses a sort over an ungrouped aggregation, and it has one
          // row anyway, so losing the last group empties the ordering too.
          sort:
            next.groups.length === 0
              ? []
              : current.sort.filter(entry => aliases.has(entry.alias)),
          table: {
            ...current.table,
            columns: (listed ?? current.table.columns).filter(column =>
              aliases.has(column.alias),
            ),
          },
        };
      });
      const { notice, before } = said;
      if (notice && before && runtime)
        setDrop({ notice, before, after: runtime.getSnapshot().draft });
    },
    [change, fitTo, factsOf, runtime, definition, capability],
  );

  const live = drop && draft === drop.after ? drop : null;

  return {
    reshape,
    dropped: live?.notice ?? null,
    /** Whether the draft is the one the last undo put back. */
    undone: live !== null && live.notice === null,
    undoDrop: useCallback(() => {
      if (!runtime || !live?.notice) return;
      const { before } = live;
      runtime.edit({
        elements: before.elements,
        groups: before.groups,
        metrics: before.metrics,
        having: before.having,
        chart: before.chart,
        sort: before.sort,
        table: before.table,
      });
      setDrop({ notice: null, after: runtime.getSnapshot().draft });
    }, [runtime, live]),
    dismissDrop: useCallback(() => setDrop(null), []),
    metricRemoval: useCallback(
      (index: number): MetricRemoval => {
        if (!config || config.metrics.length <= 1) return 'last';
        const shape = {
          ...config,
          metrics: config.metrics.filter(
            (_metric, at) => at !== index,
          ) as AnalysisViewConfig['metrics'],
        };
        return withoutDangling(shape, factsOf(shape), {
          shape: config,
          facts: factsOf(config),
        }).metrics.length === 0
          ? 'cascade'
          : 'ok';
      },
      [config, factsOf],
    ),
  };
}
