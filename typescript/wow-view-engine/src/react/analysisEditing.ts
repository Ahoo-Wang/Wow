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
  DEFAULT_MISSING_KEY,
  derivedMetric,
  formulaMetric,
  metricWithCondition,
  type AnalysisScope,
} from '../analysis/index.js';
import {
  without,
  type AnalysisGroup,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type FilterTree,
} from '../model/index.js';

/** A change to what is grouped or measured; see `useAnalysisEditor`'s reshape. */
export type Reshape = (
  update: (
    current: AnalysisViewConfig,
  ) =>
    | (Pick<AnalysisViewConfig, 'groups' | 'metrics'> &
        Partial<Pick<AnalysisViewConfig, 'elements'>>)
    | undefined,
) => void;

export interface QuestionEditingInput {
  reshape: Reshape;
  /** A plain edit of the draft, for members nothing else names. */
  edit(patch: Partial<AnalysisViewConfig>): void;
  scope: AnalysisScope | null;
}

/**
 * The edits to the question — the dimensions, the metrics, what keeps a
 * group — as plain functions over the draft. They are built once per scope
 * and change the draft through `reshape`, which brings the chart, the sort
 * and the table columns along with whatever alias moved. Kept out of the
 * hook so the hook stays the wiring and this stays the rules.
 */
export function questionEditing({
  reshape,
  edit,
  scope,
}: QuestionEditingInput) {
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
        group.type !== 'TERMS'
          ? group
          : on
            ? { ...group, missingKey: DEFAULT_MISSING_KEY }
            : without(group, 'missingKey'),
      ),
    setDense: (index: number, on: boolean) =>
      patchGroup(index, group =>
        group.type !== 'DATE_HISTOGRAM'
          ? group
          : on
            ? { ...group, dense: true }
            : without(group, 'dense'),
      ),

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
        metric.type === 'DERIVED'
          ? metric
          : filter === undefined
            ? (without(metric, 'filter') as AnalysisMetric)
            : { ...metric, filter },
      ),
    /**
     * A copy of the metric right after it, answered by the copy's alias —
     * the card's identity — so the slot can open the copy's block; `reshape`
     * runs synchronously against the live draft, so the alias is in hand.
     */
    duplicateMetric: (index: number): string | undefined => {
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
    /**
     * A formula over the first two fields the unit can measure, or over
     * one field twice when it has only one: the card is there to be
     * changed, and a card with a slot it cannot fill is not offered.
     */
    addFormula: () => {
      const measurable = [...(scope?.aggregations.values() ?? [])].filter(
        entry => entry.functions.length > 0,
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
    /**
     * A derived metric over the first two metrics before it that a derived
     * one may read — any but a sample value — or over one metric twice.
     */
    addDerived: () => {
      reshape(current => {
        const readable = current.metrics.filter(
          metric => metric.type !== 'ANY',
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
  };
}

export type QuestionEditing = ReturnType<typeof questionEditing>;
