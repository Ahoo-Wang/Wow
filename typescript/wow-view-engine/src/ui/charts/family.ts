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
import type { ChartSpec } from '../../model/index.js';
import { columnTitle, displayValue, valueText } from '../display.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/**
 * A value as its column shows it, by the alias the chart read it from.
 *
 * It labels both halves of a chart: a category on an axis or in a legend, and
 * a measured number on a tick, in a tooltip or on a bar. They go through one
 * function on purpose — a table showing ¥1,234.00 beside a tooltip showing
 * 1234 is two readings of one number, and only one of them is the column's.
 */
export type ValueLabel = (alias: string | undefined, value: unknown) => string;

/** What `AnalysisChart` hands whichever family the data asked for. */
export interface FamilyProps<D> {
  data: D;
  spec?: ChartSpec;
  className?: string;
  label: ValueLabel;
  /** An alias as its column is titled; `undefined` when no column holds it. */
  column: ColumnTitle;
  /**
   * What is drawn, in one line. Each family puts it on whatever element is
   * the picture — the `<svg>` for the three chart-library families, the grid
   * itself for the two hand-drawn ones — as the name of a `role="img"`. The
   * metric card is the exception: its value is already text, so only its
   * sparkline is a picture.
   */
  name: string;
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
    // As the analysis table shows the same value: what the field's kind
    // names first, then a number in its format and a boolean in words, both
    // in the surface's language.
    return (alias, value) => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return (
        (column && displayValue(value, column, display)) ??
        valueText(value, messages, column?.numberFormat, display.locale)
      );
    };
  }, [columns, display, messages]);
}

/**
 * An alias as its column is titled — 「金额 的 合计」 for a metric, the field
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

/** A raw group value as a React key or a spec's colour key, never as text. */
export function labelOf(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return value.toString();
  return JSON.stringify(value) ?? '';
}
