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
 * What the menu over a pressed group offers (`AnalysisResultController.followUp`):
 * the records behind the group, the group split by another field, the group
 * alone — each read off the result that ran and the screen over it
 * (`followUpOf`), with the types a host's own menu draws from.
 */

import {
  drillGroups,
  focusOn,
  groupFor,
  groupableFields,
  splitBy,
} from '../analysis/index.js';
import type { drillGap } from '../analysis/drill.js';
import {
  drillRecordConditions,
  drillSpan,
  drilledFields,
} from '../analysis/drill.js';
import { describeFilter, type FilterSummaryItem } from '../filter/index.js';
import type {
  AnalysisViewConfig,
  ChartSpec,
  FilterNode,
  RecordData,
} from '../model/index.js';
import { type ViewRuntime } from '../runtime/index.js';
import type { AnalysisFieldOption } from './analysisFields.js';
import type { FollowUpHost } from './usePanelFollowUps.js';
import type { AnalysisResultInput } from './useAnalysisResult.js';

/** Stable identity for "nothing asked yet", so the memos below stay quiet. */

/** A dimension a group can be split by: a field the result is not grouped on. */
export interface SplitOption {
  field: string;
  label: string;
}

/**
 * One thing the follow-up menu offers on a group. The menu draws
 * each kind its own way — an icon and a word are a host's to choose — and
 * runs it; which kinds are offered, in what order, and what running one
 * does are decided here, so a new follow-up is a new member of this union
 * and one more entry in `followUp`, not another pair of props on the menu.
 */
export type FollowUpAction =
  /**
   * Open the records behind the group, in a record view of their own. The
   * view is named `title`: wording, so the menu says it — of `subject`, the
   * definition's name for its records, and the group.
   *
   * With `gap`, offered and greyed with the reason it cannot run (D38): a
   * group counted over elements of elements, which no condition over the
   * records reaches (`drillGap`) — the menu says so rather than leave the
   * reader to wonder where 「查看这些记录」 went — and `run` does nothing.
   */
  | {
      kind: 'records';
      subject: string;
      run(title: string): void;
      gap?: 'nested-elements';
    }
  /**
   * Ask the same question of the group by another dimension, as a view of
   * its own beside this one (`WorkbenchController.follow`), as `focus`
   * does. Named `title`, of `subject` — this view's name — and the group.
   */
  | {
      kind: 'split';
      subject: string;
      options: readonly SplitOption[];
      run(field: string, title: string): void;
    }
  /**
   * Ask the same question of the group alone, as a view of its own beside
   * this one (`WorkbenchController.follow`), so the way back is this result
   * as it stands. Named `title`, of `subject` — this view's name — and the
   * group.
   */
  | { kind: 'focus'; subject: string; run(title: string): void }
  /**
   * Set a board's filter to the stretch of time the menu is about (D33
   * Q52): offered on a dashboard panel only, over a span, for each date
   * filter wired to the field it spans. The panel it was set from keeps
   * every group, as a press that sets a filter leaves it (D23 Q18). `filter`
   * is that filter's label, for the item to name it by.
   */
  | { kind: 'filter'; filter: string; run(): void };

/** One dimension of the group pressed, for the menu to name it by. */
export interface FollowUpGroup {
  /**
   * The conditions that select it, as the applied bar names them — a date
   * bucket's range as its period, a number band's two comparisons as the
   * one segment they bound — so the menu, the name of a view opened from it
   * and that view's applied bar say one sentence.
   */
  conditions: readonly FilterSummaryItem[];
}

/** What the menu over one pressed group shows. */
export interface FollowUp {
  /** The group, one dimension each, in the order the result is grouped. */
  groups: readonly FollowUpGroup[];
  actions: readonly FollowUpAction[];
}

/** What a follow-up is read off: the result that ran and the screen over it. */
export interface FollowUpSource {
  pickable: boolean;
  ran: AnalysisViewConfig | undefined;
  runtime: ViewRuntime<AnalysisViewConfig> | null;
  workbench: FollowUpHost;
  analysis: Pick<AnalysisResultInput, 'fields' | 'layout'>;
  chart: ChartSpec;
  gap: ReturnType<typeof drillGap> | undefined;
  expanded: boolean;
  moments: ReadonlySet<string>;
}

/**
 * What a press on `row` (or on the span from `row` to `through`) offers:
 * the records behind the group, the group split by another field, the
 * group alone (`AnalysisResultController.followUp`).
 */
export function followUpOf(
  {
    pickable,
    ran,
    runtime,
    workbench,
    analysis,
    chart,
    gap,
    expanded,
    moments,
  }: FollowUpSource,
  row: RecordData,
  through?: RecordData,
): FollowUp | null {
  if (!pickable || !ran || !runtime) return null;
  // A reference group's id goes into the condition by its name.
  const context = {
    timeZone: runtime.environment.timeZone,
    labelOf: (remote: string, id: string | number) =>
      runtime.labels.text(remote, id),
  };
  const drilled = through
    ? drillSpan(ran, runtime.fields, runtime.kinds, row, through, context)
    : drillGroups(ran, runtime.fields, runtime.kinds, row, context);
  if (!drilled) return null;
  const conditions =
    drillRecordConditions(ran, runtime.fields, runtime.kinds, drilled) ?? [];
  const actions: FollowUpAction[] = [];
  // Each view opened is named by its subject and the group, and called
  // by its subject alone once the reader takes the group off it.
  if (workbench.canDrill) {
    const records = runtime.definition.title;
    actions.push(
      gap
        ? { kind: 'records', subject: records, gap, run: () => {} }
        : {
            kind: 'records',
            subject: records,
            run: title => workbench.drill(conditions, title, records),
          },
    );
  }
  // The group named as the menu heads it, by the fields its conditions
  // name: the root's, or the counted element's.
  const named = [...(drilledFields(ran, runtime.fields)?.values() ?? [])];
  const heading = drilled.map(entry => ({
    conditions: describeFilter(
      named,
      { op: 'and', children: entry.conditions },
      runtime.kinds,
    ),
  }));
  // Over elements only the records: 「只显示这一组」 and 「按…拆开」 would
  // narrow the range the elements are counted under, which is the
  // expansion's gate and not the view's conditions — not offered yet.
  if (expanded) return actions.length > 0 ? { groups: heading, actions } : null;
  // Groupable fields the result is not already grouped by — the list the
  // tray adds a dimension from (`groupableFields`) — read off the config
  // that ran, for the reason the conditions are.
  const options: SplitOption[] = groupableFields(
    analysis.fields,
    ran.groups,
  ).map(option => ({ field: option.field, label: option.label }));
  // Both open beside this view: the question that ran, drawn as the screen
  // draws it — the layout and the chart are the draft's, and nothing else
  // the draft holds is applied by a gesture that did not ask for it.
  const drawn: AnalysisViewConfig = {
    ...ran,
    layout: analysis.layout,
    chart,
  };
  const subject = workbench.state?.title ?? '';
  if (options.length > 0)
    actions.push({
      kind: 'split',
      subject,
      options,
      run: (name, title) => {
        const patch = split(
          runtime,
          analysis.fields,
          drawn,
          conditions,
          name,
          moments,
        );
        if (patch)
          workbench.follow({ ...drawn, ...patch }, title, conditions, subject);
      },
    });
  actions.push({
    kind: 'focus',
    subject,
    run: title =>
      workbench.follow(
        { ...drawn, ...focusOn(ran, conditions) },
        title,
        conditions,
        subject,
      ),
  });
  return { groups: heading, actions };
}

/**
 * The patch that asks `config` of the group by the field named, or null
 * where the field is not one to split by. The chart's slots follow the new
 * shape; the metrics do not change, so neither do the moments among them.
 */
function split(
  runtime: ViewRuntime<AnalysisViewConfig>,
  fields: readonly AnalysisFieldOption[],
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
  name: string,
  moments: ReadonlySet<string>,
): ReturnType<typeof splitBy> | null {
  const field = runtime.fields.find(entry => entry.name === name);
  const option = fields.find(entry => entry.field === name);
  if (!field || !option) return null;
  return splitBy(
    config,
    conditions,
    groupFor(field, option, runtime.kinds.get(field.kind)),
    moments,
  );
}
