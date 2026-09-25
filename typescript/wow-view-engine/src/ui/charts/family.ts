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
import type { AnalysisColumnView } from '../../analysis/index.js';
import type {
  ChartSpec,
  FunnelSpec,
  FunnelStages,
  RecordData,
} from '../../model/index.js';
import { bandText } from '../band.js';
import {
  columnTitle,
  compactFormat,
  displayValue,
  formatNumber,
  valueText,
} from '../display.js';
import type { MessageKey } from '../messages.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import type { DateTicks } from './dateTicks.js';

/**
 * A value as its column shows it, by the alias the chart read it from.
 *
 * It labels both halves of a chart: a category on an axis or in a legend, and
 * a measured number on a tick, in a tooltip or on a bar. They go through one
 * function on purpose — a table showing ¥1,234.00 beside a tooltip showing
 * 1234 is two readings of one number, and only one of them is the column's.
 */
export type ValueLabel = (
  alias: string | undefined,
  value: unknown,
  /**
   * Written short, where room is scarce: a tick, a label over a bar. Only a
   * number is shortened, and still in its column's format — 「¥1,110万」 in
   * Chinese and `CN¥11.1M` in English (`compactFormat`); a tooltip, the
   * reading table and the table layout keep the whole number.
   */
  compact?: boolean,
) => string;

/** What `AnalysisChart` hands whichever family the data asked for. */
/**
 * Told which group of the result a mark stands for when it is pressed, and
 * where — the mark's element, or the point pressed — so the follow-up menu
 * opens against it. Left out, marks are not pressable.
 */
export type OnPick = (
  row: RecordData,
  anchor: Element | { getBoundingClientRect(): DOMRect },
  /**
   * Where the keyboard goes back to when the menu closes: the table row that
   * was pressed. A mark is not focusable and leaves nothing to return to,
   * and the anchor is no longer that row — it is the cell or the point the
   * menu hangs from — so the two are said apart.
   */
  origin?: HTMLElement,
  /**
   * The press is a span, not one group (D33 Q52): every bucket of a time
   * axis from `row`'s through this one's — a brush along the axis, or a
   * second row of the table picked with Shift. The follow-up menu reads the
   * two as one range (`AnalysisResultController.followUp`).
   */
  through?: RecordData,
) => void;

export interface FamilyProps<D> {
  data: D;
  spec?: ChartSpec;
  className?: string;
  onPick?: OnPick;
  label: ValueLabel;
  /** An alias as its column is titled; `undefined` when no column holds it. */
  column: ColumnTitle;
  /**
   * The short ticks of a time axis (`useDateTicks`): an axis of dates
   * writes 「9月1日」 with the year only where it changes. Left out, every
   * tick reads as its column does.
   */
  dateTicks?: DateTicks;
  /**
   * What is drawn, in one line: the name of the `role="img"` every family's
   * drawing is (`EChart`'s `chart-plot`). The metric card is the exception:
   * its value is already text, so only its sparkline is a picture.
   */
  name: string;
  /**
   * Whether a column's numbers add up across groups — a count, a sum — so a
   * whole written over them (a donut's centre) is a number at all: the
   * total of some averages is not an average of anything.
   */
  adds?: (alias: string | undefined) => boolean;
  /**
   * The rows are the first groups of more (`AnalysisView.truncated` or
   * `atLimit`): a family that draws shares of a whole says they are shares
   * of the groups shown.
   */
  cutShort?: boolean;
  /**
   * The group a press set the board's filter to (D22 I): a family that can
   * say which of its marks stands for a group draws the others faint
   * (`faded`); left out, every mark as it is.
   */
  highlight?: (row: RecordData) => boolean;
  /** A filled-in value as the tooltip says it (`useFilledNote`). */
  filled?: FilledNote;
  /**
   * The series the reader switched off in the legend, by key — a family
   * with a legend of series draws them nowhere else (`CartesianContext`).
   */
  hidden?: ReadonlySet<string>;
  /** Switches a series off or back on; left out, the legend is text. */
  onToggleSeries?: (key: string) => void;
  /**
   * Whether a long axis zooms by gesture as well as by its slider: a
   * workbench's chart, not a dashboard panel's (`zoomOption`).
   */
  zoomGestures?: boolean;
}

export function useValueLabel(
  columns: readonly AnalysisColumnView[] | undefined,
): ValueLabel {
  const display = useSurfaceDisplay();
  const messages = useViewMessages();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    // As the analysis table shows the same value: a number band as the band,
    // what the field's kind names next, then a number in its format and a
    // boolean in words, all in the surface's language. A band is short
    // already, so the axis and the tooltip read it alike.
    return (alias, value, compact) => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return (
        (column &&
          (bandText(value, column, messages, display) ??
            displayValue(value, column, display))) ??
        (compact && typeof value === 'number'
          ? formatNumber(
              value,
              compactFormat(column?.numberFormat),
              display.locale,
            )
          : valueText(value, messages, column?.numberFormat, display.locale))
      );
    };
  }, [columns, display, messages]);
}

/**
 * A value the chart filled in rather than measured, as the tooltip and the
 * reading table say it: 「0（这一天没有记录）」 on a day axis, the unit's own
 * words on another — 这一周, 这个月 — and 「0（这一组没有记录）」 for a split
 * combination along categories (decisions.md D23, Q14). `alias` is the axis
 * the filled point stands on; `value` the number already written.
 */
export type FilledNote = (alias: string | undefined, value: string) => string;

export function useFilledNote(
  columns: readonly AnalysisColumnView[] | undefined,
): FilledNote {
  const messages = useViewMessages();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    return (alias, value) => {
      const unit =
        alias === undefined ? undefined : byAlias.get(alias)?.dateUnit;
      return messages.label(
        unit === undefined
          ? 'label.chart.filled.group'
          : `label.chart.filled.${unit}`,
        { value },
      );
    };
  }, [columns, messages]);
}

/** Whether a column's numbers add up across groups: a count or a sum. */
export function useAdds(
  columns: readonly AnalysisColumnView[] | undefined,
): (alias: string | undefined) => boolean {
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    return alias => {
      const fn = alias === undefined ? undefined : byAlias.get(alias)?.fn;
      return fn === 'COUNT' || fn === 'SUM';
    };
  }, [columns]);
}

/**
 * An alias as its column is titled — 「金额的总和」 for a metric, the field
 * for a group — and `undefined` when this result has no such column: a name
 * that says "订单数 按 地区" is worth having, one that says "m0 按 g0" is not,
 * so the caller decides what to do without a title rather than being handed
 * the alias.
 */
export type ColumnTitle = (alias: string | undefined) => string | undefined;

export function useColumnTitle(
  columns: readonly AnalysisColumnView[] | undefined,
): ColumnTitle {
  const messages = useViewMessages();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    return alias => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return column && columnTitle(column, messages);
    };
  }, [columns, messages]);
}

/**
 * What one funnel stage is called, wherever the funnel is read — the drawing
 * and the table under it say the same words.
 *
 * A stage taken from a dimension is one of that dimension's values and reads
 * as its column shows it. A stage that is a metric wears the name the
 * analyst gave it (`items[i].label`, typed on the options' data page); with
 * none given the projection falls back to the metric's alias — which names
 * the query and nothing a reader recognises — so the column's title stands
 * in, exactly as every slot on the panel is named by its column.
 */
export function stageName(
  stages: FunnelStages | undefined,
  index: number,
  /** The stage as the projection labelled it. */
  projected: string,
  label: ValueLabel,
  column: ColumnTitle,
): string {
  if (stages?.from === 'group') return label(stages.category, projected);
  const item = stages?.items[index];
  return item?.label ?? column(item?.metric) ?? projected;
}

/**
 * The heading over a funnel's percentages, drawing and reading table alike.
 *
 * The kernel divides each stage by the one before it, or by the first
 * (`conversion`, 'previous' unless the spec says otherwise), and a bare
 * 「25%」 beside a bar reads as a share of the whole — which it is only
 * against the first stage. So the heading says it is a conversion rate and
 * what it is relative to, in the options panel's own words for the choice.
 */
export function conversionHeading(
  mode: FunnelSpec['conversion'],
): Extract<
  MessageKey,
  | 'label.chart.column.conversion.previous'
  | 'label.chart.column.conversion.first'
> {
  return mode === 'first'
    ? 'label.chart.column.conversion.first'
    : 'label.chart.column.conversion.previous';
}
