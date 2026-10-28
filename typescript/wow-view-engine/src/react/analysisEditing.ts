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

import { fiveNumberMetrics } from '../analysis/boxplot.js';
import { ohlcMetrics } from '../analysis/candlestick.js';
import {
  DEFAULT_MISSING_KEY,
  derivedMetric,
  durationMetric,
  formulaMetric,
  freeAlias,
  isDuration,
  metricWithCondition,
  momentMetrics,
  columnsAfterMove,
  moveMetric,
  type AnalysisScope,
  type Dropped,
  type MetricMove,
} from '../analysis/index.js';
import {
  AggregationGroupTypes,
  AggregationMetricTypes,
  isDateCell,
  isValueMetric,
  without,
  type AnalysisColumn,
  type DateDiffUnitName,
  type AnalysisGroup,
  type AnalysisHavingExpression,
  type AnalysisTop,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type FilterTree,
} from '../model/index.js';

/**
 * What a change to the question proposes: the groups and metrics, the chain
 * where it moves, and what the change itself took out beyond what it was
 * asked to (`withElements`' `dropped`), for the editor to say.
 */
export type ReshapeProposal = Pick<AnalysisViewConfig, 'groups' | 'metrics'> &
  Partial<Pick<AnalysisViewConfig, 'elements'>> & {
    dropped?: Dropped;
    /** The table's declared columns, where the change moves one. */
    columns?: AnalysisColumn[];
  };

/** The edit a drop is said after: a step of the chain, or any other. */
export type DropCause =
  { kind: 'expand'; name: string } | { kind: 'collapse'; name: string };

/** A change to what is grouped or measured; see `useAnalysisEditor`'s reshape. */
export type Reshape = (
  update: (current: AnalysisViewConfig) => ReshapeProposal | undefined,
  cause?: DropCause,
) => void;

/**
 * The edits to the question: the dimensions, the metrics, and what keeps a
 * group. Each changes the draft and brings the chart, the sort and the
 * table columns along with whatever alias moved.
 */
export interface QuestionEditing {
  /** Adds a dimension after the ones there are. */
  addGroup(group: AnalysisGroup): void;
  /** Merges `patch` into the dimension at `index`. */
  updateGroup(index: number, patch: Partial<AnalysisGroup>): void;
  /** Takes the dimension at `index` out. */
  removeGroup(index: number): void;
  /** Names the dimension at `index`; `undefined` takes the name back. */
  renameGroup(index: number, label: string | undefined): void;
  /**
   * Whether a terms dimension keeps the records with no value as a bucket
   * of their own. Any other dimension is left as it is.
   */
  setMissingBucket(index: number, on: boolean): void;
  /**
   * Whether a date dimension fills in the buckets no record falls in,
   * between the first and the last. Any other dimension is left as it is.
   */
  setDense(index: number, on: boolean): void;
  /** Adds a metric after the ones there are. */
  addMetric(metric: AnalysisMetric): void;
  /**
   * Merges `patch` into the metric at `index`; a change of summary is
   * `replaceMetric`.
   */
  updateMetric(index: number, patch: Partial<AnalysisMetric>): void;
  /**
   * Puts a whole metric in a row's place. A change of summary is a change
   * of type — a sum becomes a distinct count — and a patch over the old
   * shape would leave its `function` or `expression` behind for admission
   * to trip over; the card builds the new metric and swaps it in.
   */
  replaceMetric(index: number, metric: AnalysisMetric): void;
  /** Names the metric at `index`; `undefined` takes the name back. */
  renameMetric(index: number, label: string | undefined): void;
  /**
   * The conditions a metric counts under, or none. An empty tree is kept
   * while the card is being filled in — validation says it is unfinished
   * and the query waits — and `undefined` takes the condition away. A
   * derived metric counts under none and is left as it is.
   */
  setMetricFilter(index: number, filter: FilterTree | undefined): void;
  /**
   * A second card of the same metric, right after it, with an empty
   * condition to fill in: 复制「金额的总和」并加条件. The copy keeps no
   * display name — two cards called the same thing is the ambiguity the
   * name exists to resolve, and the condition it is about to carry is what
   * resolves it. Answers the copy's alias — the card's identity — so the
   * slot can open the copy's conditions on the spot: the menu item
   * promised a condition, and a second identical card with nothing open is
   * not one. `undefined` where there was nothing to copy.
   */
  duplicateMetric(index: number): string | undefined;
  /**
   * The five numbers a boxplot draws of this metric's field (「补齐箱线图
   * 的五个数」): the ones among its lowest, 25th, 50th and 75th
   * percentiles and highest it is not, added right after it under its own
   * condition (`fiveNumberMetrics`). Nothing for a metric of no field.
   */
  addFiveNumbers(index: number): void;
  /**
   * The four numbers a candlestick draws of this metric's field (「补齐 K
   * 线的四个数」): the ones among its opening value, highest, lowest and
   * closing value it is not, added right after it under its own condition
   * and ordered by its own time (`ohlcMetrics`). Nothing for a metric that
   * is none of the four of a field.
   */
  addOhlc(index: number): void;
  /**
   * The metric at `from` put at `to`, as far as the order allows: a
   * derived metric stays after what it reads, and a metric before what
   * reads it (`moveMetric`). A declared column order moves the column too.
   * Answers where it landed and, when it landed short, which metric
   * stopped it.
   */
  moveMetric(from: number, to: number): MetricMove | undefined;
  /**
   * Takes the metric at `index` out; never the last one, since an
   * aggregation query without a metric has nothing to return.
   */
  removeMetric(index: number): void;
  /**
   * A formula over the first two fields the unit can measure, or over
   * one field twice when it has only one: the card is there to be
   * changed, and a card with a slot it cannot fill is not offered.
   */
  addFormula(): void;
  /**
   * A time between two moments (「两个时刻之差」): the average `unit`s
   * from the first time the counting unit holds to the second — two
   * fields it buckets by date and takes into arithmetic. Nothing when it
   * holds fewer than two: a moment to itself is always nothing.
   */
  addDuration(unit: DateDiffUnitName): void;
  /**
   * The duration a metric measures, cut into bands one unit wide as a
   * dimension of its own (「付款到发货 0–1 小时、1–2 小时…」): a band of
   * a computed number, placed after the dimensions there are.
   */
  groupByDuration(index: number): void;
  /**
   * A derived metric over the first two metrics before it that a derived
   * one may read — any but a sample value or a moment
   * (`analysis.derived.moment-operand`) — or over one metric twice.
   */
  addDerived(): void;
  /**
   * The rules a group must meet to be kept (「只保留」); `undefined` takes
   * them away.
   */
  setHaving(having: AnalysisHavingExpression | undefined): void;
  /** The top N kept within each group; `undefined` takes it away. */
  setTop(top: AnalysisTop | undefined): void;
}

/** What the edits are built over, once per scope. */
interface QuestionEditingInput {
  reshape: Reshape;
  /** A plain edit of the draft, for members nothing else names. */
  edit(patch: Partial<AnalysisViewConfig>): void;
  scope: AnalysisScope | null;
}

/**
 * The edits to the question as plain functions over the draft, built once
 * per scope; each changes the draft through `reshape`. Kept out of the hook
 * so the hook stays the wiring and this stays the rules.
 */
export function questionEditing({
  reshape,
  edit,
  scope,
}: QuestionEditingInput): QuestionEditing {
  const patchMetric = (
    index: number,
    update: (metric: AnalysisMetric) => AnalysisMetric,
  ) =>
    reshape(current => ({
      groups: current.groups,
      metrics: current.metrics.map((metric, at) =>
        at === index ? update(metric) : metric,
      ) as AnalysisViewConfig['metrics'],
    }));
  const taken = (current: AnalysisViewConfig) => [
    ...current.groups.map(group => group.alias),
    ...current.metrics.map(metric => metric.alias),
  ];

  return {
    ...groupEditing(reshape),
    addMetric: (metric: AnalysisMetric) =>
      reshape(current => ({
        groups: current.groups,
        metrics: [...current.metrics, metric] as AnalysisViewConfig['metrics'],
      })),
    updateMetric: (index: number, patch: Partial<AnalysisMetric>) =>
      patchMetric(index, metric => ({ ...metric, ...patch }) as AnalysisMetric),
    replaceMetric: (index: number, metric: AnalysisMetric) =>
      patchMetric(index, () => metric),
    renameMetric: (index: number, label: string | undefined) =>
      patchMetric(index, metric =>
        label === undefined
          ? (without(metric, 'label') as AnalysisMetric)
          : { ...metric, label },
      ),
    setMetricFilter: (index: number, filter: FilterTree | undefined) =>
      patchMetric(index, metric =>
        metric.type === AggregationMetricTypes.DERIVED
          ? metric
          : filter === undefined
            ? (without(metric, 'filter') as AnalysisMetric)
            : { ...metric, filter },
      ),
    duplicateMetric: (index: number): string | undefined => {
      // `reshape` runs synchronously against the live draft, so the alias
      // is in hand when it returns.
      let alias: string | undefined;
      reshape(current => {
        const copy = metricWithCondition(
          current.metrics[index],
          taken(current),
        );
        if (!copy) return undefined;
        alias = copy.alias;
        const metrics = [...current.metrics];
        metrics.splice(index + 1, 0, copy);
        return {
          groups: current.groups,
          metrics: metrics as AnalysisViewConfig['metrics'],
        };
      });
      return alias;
    },
    addFiveNumbers: (index: number) =>
      reshape(current => {
        const metric = current.metrics[index];
        const missing = metric && fiveNumberMetrics(metric, taken(current));
        if (!missing || missing.length === 0) return undefined;
        const metrics = [...current.metrics];
        metrics.splice(index + 1, 0, ...missing);
        return {
          groups: current.groups,
          metrics: metrics as AnalysisViewConfig['metrics'],
        };
      }),
    addOhlc: (index: number) =>
      reshape(current => {
        const metric = current.metrics[index];
        const missing = metric && ohlcMetrics(metric, taken(current));
        if (!missing || missing.length === 0) return undefined;
        const metrics = [...current.metrics];
        metrics.splice(index + 1, 0, ...missing);
        return {
          groups: current.groups,
          metrics: metrics as AnalysisViewConfig['metrics'],
        };
      }),
    moveMetric: (from: number, to: number): MetricMove | undefined => {
      let moved: MetricMove | undefined;
      reshape(current => {
        moved = moveMetric(current.metrics, from, to);
        if (moved.to === from) return undefined;
        // 结果的列跟着走: a declared column order moves the column too.
        return {
          groups: current.groups,
          metrics: moved.metrics as AnalysisViewConfig['metrics'],
          columns: columnsAfterMove(
            current.table.columns,
            moved.metrics,
            current.metrics[from].alias,
          ),
        };
      });
      return moved;
    },
    removeMetric: (index: number) =>
      reshape(current =>
        // An aggregation query without a metric has nothing to return.
        current.metrics.length <= 1
          ? undefined
          : {
              groups: current.groups,
              metrics: current.metrics.filter(
                (_metric, at) => at !== index,
              ) as AnalysisViewConfig['metrics'],
            },
      ),
    addFormula: () => {
      // A date is no operand (`analysis.expression.date-operand`): its
      // earliest and latest are functions of it, arithmetic is not.
      const dated = (name: string) => {
        const field = scope?.fields.get(name);
        return field !== undefined && isDateCell(field.cell ?? field.kind);
      };
      const measurable = [...(scope?.aggregations.values() ?? [])].filter(
        entry => entry.functions.length > 0 && !dated(entry.field),
      );
      const [first, second = first] = measurable;
      if (!first) return;
      reshape(current => ({
        groups: current.groups,
        metrics: [
          ...current.metrics,
          formulaMetric(
            first.field,
            second.field,
            first.functions[0],
            taken(current),
          ),
        ] as AnalysisViewConfig['metrics'],
      }));
    },
    addDuration: (unit: DateDiffUnitName) => {
      const [from, to] = durationEnds(scope);
      if (!from || !to) return;
      reshape(current => ({
        groups: current.groups,
        metrics: [
          ...current.metrics,
          durationMetric(from, to, unit, taken(current)),
        ] as AnalysisViewConfig['metrics'],
      }));
    },
    groupByDuration: (index: number) =>
      reshape(current => {
        const metric = current.metrics[index];
        if (!metric || !isDuration(metric)) return undefined;
        return {
          groups: [
            ...current.groups,
            {
              type: AggregationGroupTypes.HISTOGRAM,
              alias: freeAlias('band', taken(current)),
              expression: metric.expression,
              interval: 1,
            },
          ],
          metrics: current.metrics,
        };
      }),
    addDerived: () => {
      reshape(current => {
        const moments = scope
          ? momentMetrics(current.metrics, scope.fields)
          : new Set<string>();
        const readable = current.metrics.filter(
          metric => !isValueMetric(metric) && !moments.has(metric.alias),
        );
        const [first, second = first] = readable;
        if (!first) return undefined;
        return {
          groups: current.groups,
          metrics: [
            ...current.metrics,
            derivedMetric(first.alias, second.alias, taken(current)),
          ] as AnalysisViewConfig['metrics'],
        };
      });
    },
    setHaving: (having: AnalysisHavingExpression | undefined) =>
      edit(having === undefined ? { having: undefined } : { having }),
    setTop: (top: AnalysisTop | undefined) =>
      edit(top === undefined ? { top: undefined } : { top }),
  };
}

/** The edits to the dimensions, each through `reshape`. */
function groupEditing(
  reshape: Reshape,
): Pick<
  QuestionEditing,
  | 'addGroup'
  | 'updateGroup'
  | 'removeGroup'
  | 'renameGroup'
  | 'setMissingBucket'
  | 'setDense'
> {
  const patchGroup = (
    index: number,
    update: (group: AnalysisGroup) => AnalysisGroup,
  ) =>
    reshape(current => ({
      groups: current.groups.map((group, at) =>
        at === index ? update(group) : group,
      ),
      metrics: current.metrics,
    }));

  return {
    addGroup: (group: AnalysisGroup) =>
      reshape(current => ({
        groups: [...current.groups, group],
        metrics: current.metrics,
      })),
    updateGroup: (index: number, patch: Partial<AnalysisGroup>) =>
      patchGroup(index, group => ({ ...group, ...patch }) as AnalysisGroup),
    removeGroup: (index: number) =>
      reshape(current => ({
        groups: current.groups.filter((_group, at) => at !== index),
        metrics: current.metrics,
      })),
    // The three settings that come and go rather than change: a name taken
    // back, a sentinel bucket dropped, a fill switched off leave no key
    // behind, so the config stays what a fresh one would be.
    renameGroup: (index: number, label: string | undefined) =>
      patchGroup(index, group =>
        label === undefined
          ? (without(group, 'label') as AnalysisGroup)
          : { ...group, label },
      ),
    setMissingBucket: (index: number, on: boolean) =>
      patchGroup(index, group =>
        group.type !== AggregationGroupTypes.TERMS
          ? group
          : on
            ? { ...group, missingKey: DEFAULT_MISSING_KEY }
            : without(group, 'missingKey'),
      ),
    setDense: (index: number, on: boolean) =>
      patchGroup(index, group =>
        group.type !== AggregationGroupTypes.DATE_HISTOGRAM &&
        group.type !== AggregationGroupTypes.DATE_PART
          ? group
          : on
            ? { ...group, dense: true }
            : (without(group, 'dense') as AnalysisGroup),
      ),
  };
}

/**
 * The two times a new duration runs between: the first two fields of the
 * counting unit that it buckets by date and takes into arithmetic — which
 * is what Wow asks of each end of a `DATE_DIFF` (#3539).
 */
function durationEnds(scope: AnalysisScope | null): string[] {
  return [...(scope?.aggregations.values() ?? [])]
    .filter(
      entry =>
        entry.groups.includes(AggregationGroupTypes.DATE_HISTOGRAM) &&
        entry.expressionInput !== false,
    )
    .map(entry => entry.field)
    .slice(0, 2);
}
