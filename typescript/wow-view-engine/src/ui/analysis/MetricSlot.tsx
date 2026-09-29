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

import { useState, type ReactNode } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { PlusIcon } from 'lucide-react';
import { isValueMetric, type FieldOption } from '../../model/index.js';
import { summaryChoices } from '../../analysis/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { useAnnouncer } from '../kit/Announcer.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/dropdown-menu.js';
import { DragHandle, moveTarget } from '../kit/DragHandle.js';
import { sortableList, withoutOptimisticSorting } from '../kit/dragPlugins.js';
import { GroupedMenu } from '../kit/FieldMenu.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { DropdownMenuContent } from '../kit/popups.js';
import { AddButton, EditorSlot } from '../kit/variants.js';
import { chartDragAccessibility, listDrop } from './drag.js';
import {
  defaultMetric,
  freeAlias,
  metricReference,
  usedAliases,
} from './editing.js';
import { durationFields } from './FormulaCard.js';
import { useListFocus } from './listFocus.js';
import { MetricCard } from './MetricCard.js';
import { TermTip } from './TermTip.js';

/**
 * The metrics row (D71): one card per metric, along the row, and the way
 * to add one. What the result keeps of the groups — 「只保留」, the sort,
 * 「前 N 组」 — is the result row's (`ResultSlot.tsx`): none of it is a
 * metric.
 *
 * **The order is the analyst's, within one rule.** Each card is led by the
 * shared handle (`DragHandle`) — dragged by a pointer, moved a place by the
 * arrow keys on it, picked up by Space, or clicked for the menu of the four
 * places it can go — and the columns of the result follow. A metric
 * calculated from others is read in list order (admission refuses one that
 * reads a metric after it), so a move goes only as far as that allows
 * (`moveMetric`): a derived metric stops just after the last metric it
 * reads, a metric just before the first one that reads it, and the row
 * says which one stopped it — under the cards, where the eye is, and in
 * the row's own voice.
 */
export function MetricSlot({
  analysis,
  disabled,
  optionsFor,
  conditioning,
  setConditioning,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * Which card has its conditions open, by the alias that names it. Held
   * above the cards, because the one gesture that opens a card's conditions
   * from *another* card is the copy — 「复制并加条件」 makes the copy and
   * opens it, which is the condition it promised — and above the slot,
   * because Apply, in the tray's footer, has to know whether it is running
   * past a condition the analyst opened and left empty (`Tray`).
   */
  conditioning: string | null;
  setConditioning(alias: string | null): void;
}) {
  const messages = useViewMessages();
  const title = messages.label('label.analysis.slot.metrics');
  const { say, region } = useAnnouncer('metric-order-voice');
  // Why the last move stopped short, and the order that move left: said
  // while the metrics stand in that order, so an edit that adds, removes
  // or moves one — a metric it names taken out, above all — ends it.
  const [stop, setStop] = useState<{ why: string; order: string } | null>(null);
  // A metric taken out leaves the keyboard on this slot (`listFocus.ts`);
  // held here because the card pressed is the one that goes.
  const focus = useListFocus({
    list: '[data-slot="analysis-slot-metrics"]',
    item: '[data-slot="metric-card"]',
    add: '[data-slot="add-metric"]',
  });
  const { metrics } = analysis;
  const keys = metrics.map(metric => metric.alias);
  const stopped = stop?.order === keys.join('\n') ? stop.why : null;
  const nameOf = (alias: string) => {
    const metric = metrics.find(entry => entry.alias === alias);
    return metric ? metricReference(analysis, metric, messages) : alias;
  };
  /**
   * One move, from any input, as far as the order allows, said once: where
   * the card landed, and why it stopped if it stopped short. Nothing is
   * written for a move that changes nothing, and nothing said but the
   * reason.
   */
  const moveTo = (from: number, to: number) => {
    const carried = metrics[from];
    if (!carried || to < 0 || to >= metrics.length || from === to) return;
    const moved = analysis.moveMetric(from, to);
    if (!moved) return;
    const name = nameOf(carried.alias);
    const why = moved.stop
      ? messages.label(`label.analysis.move-stop.${moved.stop.side}`, {
          name,
          other: nameOf(moved.stop.at),
        })
      : null;
    const order = keys.filter(key => key !== carried.alias);
    order.splice(moved.to, 0, carried.alias);
    setStop(why ? { why, order: order.join('\n') } : null);
    const landed =
      moved.to === from
        ? null
        : messages.label('label.chart.moved', {
            name,
            index: moved.to + 1,
            total: metrics.length,
          });
    const sentence = [landed, why].filter(Boolean).join(' ');
    if (sentence) say(sentence);
  };
  // A list of one has no order to change, so it draws no handle.
  const ordered = metrics.length > 1;
  return (
    <EditorSlot
      name="metrics"
      title={title}
      tip={
        <TermTip
          label={messages.label('label.analysis.tip-of', { term: title })}
          tip={messages.label('label.analysis.tip.metrics')}
        />
      }
    >
      <div className="fve:flex fve:flex-wrap fve:items-start fve:gap-2">
        <DragDropProvider
          {...sortableList(chartDragAccessibility(messages, nameOf))}
          onDragEnd={({ operation, canceled }) => {
            const drop = listDrop(keys, operation, canceled);
            if (drop) moveTo(drop.from, drop.to);
          }}
        >
          <ol
            aria-label={title}
            data-slot="metric-list"
            className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-start fve:gap-2"
          >
            {metrics.map((metric, index) => (
              <MetricRow
                key={metric.alias}
                alias={metric.alias}
                index={index}
                sortable={ordered}
              >
                {handle => (
                  <MetricCard
                    analysis={analysis}
                    metric={metric}
                    index={index}
                    handle={
                      ordered ? (
                        <DragHandle
                          ref={handle.ref}
                          label={messages.label('label.chart.reorder', {
                            name: nameOf(metric.alias),
                          })}
                          index={index}
                          total={metrics.length}
                          dragging={handle.dragging}
                          disabled={disabled}
                          axis="horizontal"
                          onMove={move =>
                            moveTo(
                              index,
                              moveTarget(move, index, metrics.length),
                            )
                          }
                        />
                      ) : undefined
                    }
                    focus={focus}
                    disabled={disabled}
                    optionsFor={optionsFor}
                    conditioning={conditioning === metric.alias}
                    onConditioning={open =>
                      setConditioning(open ? metric.alias : null)
                    }
                    onDuplicate={() =>
                      setConditioning(analysis.duplicateMetric(index) ?? null)
                    }
                  />
                )}
              </MetricRow>
            ))}
          </ol>
        </DragDropProvider>
        <AddMetric analysis={analysis} disabled={disabled} />
      </div>
      {stopped && (
        <p
          data-slot="metric-order-note"
          className="fve:text-muted-foreground fve:text-xs"
        >
          {stopped}
        </p>
      )}
      {region}
    </EditorSlot>
  );
}

interface MetricRowProps {
  alias: string;
  index: number;
  /** Whether there is an order to change: a lone card is not carried. */
  sortable: boolean;
  children(handle: {
    ref: (element: HTMLElement | null) => void;
    dragging: boolean;
  }): ReactNode;
}

/**
 * One card of the list. Only a list of two or more is sortable: the library
 * marks what it carries as a button, and a lone card with no handle would
 * be that button itself — an interactive list item round the card's own
 * controls.
 */
function MetricRow(props: MetricRowProps) {
  return props.sortable ? (
    <SortableMetricRow {...props} />
  ) : (
    <li className="fve:max-w-full fve:min-w-0">
      {props.children({ ref: NO_HANDLE, dragging: false })}
    </li>
  );
}

function NO_HANDLE(): void {}

/** One card the library can carry, handing its handle to what it draws. */
function SortableMetricRow({ alias, index, children }: MetricRowProps) {
  const { ref, handleRef, isDragging } = useSortable({
    id: alias,
    index,
    plugins: withoutOptimisticSorting,
  });
  return (
    <li
      ref={ref}
      data-dragging={isDragging ? '' : undefined}
      className="fve:max-w-full fve:min-w-0"
    >
      {children({ ref: handleRef, dragging: isDragging })}
    </li>
  );
}

/**
 * 「+ 添加」: the record count, a field, or a metric written rather than
 * picked, in the menu's three groups.
 */
function AddMetric({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const measurable = analysis.fields.filter(
    field => summaryChoices(field).length > 0,
  );
  // A second plain record count is the first one again: one column twice,
  // under two aliases (the 2026-09-23 audit, P2-6). A count over some of
  // the records is another metric, and 「复制并加条件」 on the card is the
  // way to it — so the menu offers the count only while there is none.
  const counted = analysis.metrics.some(
    metric =>
      metric.type === 'COUNT' &&
      (metric.filter === undefined || metric.filter.children.length === 0),
  );
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <AddButton
            disabled={
              disabled || (measurable.length === 0 && !analysis.countable)
            }
            data-slot="add-metric"
            // The row's term says what is added; the name says it too,
            // for a reader who arrives at the button alone.
            aria-label={messages.label('label.analysis.add-metric')}
          />
        }
      >
        <PlusIcon data-icon="inline-start" />
        {messages.label('label.analysis.add')}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {analysis.countable && (
          <DropdownMenuGroup>
            <DropdownMenuItem
              disabled={counted}
              onClick={() =>
                analysis.addMetric({
                  type: 'COUNT',
                  alias: freeAlias('count', usedAliases(analysis)),
                })
              }
            >
              {messages.label('label.analysis.row-count')}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        )}
        {/* Three kinds of thing to add, set apart as the menu's groups:
              the count, a field, and a metric written rather than picked. */}
        {analysis.countable && measurable.length > 0 && (
          <DropdownMenuSeparator />
        )}
        <GroupedMenu
          items={measurable}
          groups={analysis.fieldGroups}
          itemKey={field => field.field}
          render={field => (
            <DropdownMenuItem
              key={field.field}
              onClick={() =>
                analysis.addMetric(defaultMetric(field, usedAliases(analysis)))
              }
            >
              {messages.say(field.label)}
            </DropdownMenuItem>
          )}
        />
        {/* The two metrics written rather than picked, where the
              capability declares expressions (D20 屏 B). */}
        {analysis.expressionsAllowed && <DropdownMenuSeparator />}
        {analysis.expressionsAllowed && (
          <DropdownMenuGroup>
            <DropdownMenuItem
              disabled={measurable.length === 0}
              onClick={() => analysis.addFormula()}
            >
              {messages.label('label.analysis.add-formula')}
            </DropdownMenuItem>
            <DropdownMenuItem
              disabled={
                !analysis.metrics.some(metric => !isValueMetric(metric))
              }
              onClick={() => analysis.addDerived()}
            >
              {messages.label('label.analysis.add-derived')}
            </DropdownMenuItem>
            {analysis.dateDiffUnits.length > 0 && (
              <DropdownMenuItem
                disabled={durationFields(analysis).length < 2}
                onClick={() => analysis.addDuration(analysis.dateDiffUnits[0])}
              >
                {messages.label('label.analysis.add-duration')}
              </DropdownMenuItem>
            )}
          </DropdownMenuGroup>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
