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

import type { ReactNode } from 'react';
import { cn } from 'cn';

export interface LegendEntry {
  key: string;
  label: string;
  /** A CSS colour: a theme slot or the one the spec pinned. */
  color: string;
}

/**
 * The legend, as text beside the drawing rather than inside it.
 *
 * It is the page's own type — it wraps, it can be selected and read, and it
 * never overlaps the plot, which a legend the library draws into its canvas
 * does once the names run long. A dot per series, as Metabase draws its
 * legend; top and left-aligned by default, so it reads before the marks do.
 */
export function ChartLegend({
  entries,
  at,
  lead,
}: {
  entries: readonly LegendEntry[];
  at: 'top' | 'bottom' | 'right';
  /** What comes before the entries, when the family has something to say. */
  lead?: ReactNode;
}) {
  return (
    <ul
      data-slot="chart-legend"
      className={cn(
        'text-muted-foreground flex min-w-0 gap-x-4 gap-y-1',
        at === 'right'
          ? 'max-w-[40%] shrink-0 flex-col justify-center'
          : 'flex-wrap items-center',
      )}
    >
      {lead}
      {entries.map(entry => (
        <li
          key={entry.key}
          data-slot="chart-legend-item"
          className="flex min-w-0 items-center gap-1.5"
        >
          <span
            aria-hidden
            className="size-2 shrink-0 rounded-full"
            style={{ background: entry.color }}
          />
          <span className="truncate">{entry.label}</span>
        </li>
      ))}
    </ul>
  );
}
