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
import { displayValue, valueText } from '../display.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** A category as its column shows it, by the alias the chart reads it from. */
export type CategoryLabel = (
  alias: string | undefined,
  value: unknown,
) => string;

/** What `AnalysisChart` hands whichever family the data asked for. */
export interface FamilyProps<D> {
  data: D;
  spec?: ChartSpec;
  className?: string;
  label: CategoryLabel;
}

export function useCategoryLabel(
  columns: readonly AnalysisColumnView[] | undefined,
): CategoryLabel {
  const display = useSurfaceDisplay();
  const messages = useViewMessages();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    // As the analysis table shows the same value: what the field's kind
    // names first, then a number in its format and a boolean in words.
    return (alias, value) => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return (
        (column && displayValue(value, column, display)) ??
        valueText(value, messages, column?.numberFormat)
      );
    };
  }, [columns, display, messages]);
}

/** A raw group value as a React key or a spec's colour key, never as text. */
export function labelOf(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return value.toString();
  return JSON.stringify(value) ?? '';
}
