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

import { groupKeyText, type HeatmapData } from '../../analysis/index.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import type { FamilyProps } from './family.js';

/**
 * A grid rather than a chart library: a heatmap is cells with a background,
 * and every library's version of that costs more than it saves.
 */
export function Heatmap({
  data,
  spec,
  className,
  label,
  name,
  onPick,
}: FamilyProps<HeatmapData>) {
  const messages = useViewMessages();
  const values = data.cells
    .flat()
    .filter((cell): cell is number => cell !== null);
  const max = values.length > 0 ? Math.max(...values) : 0;
  const min = values.length > 0 ? Math.min(...values) : 0;
  const span = max - min || 1;
  // How far up the scale a cell sits, 0 to 1. A log scale spreads the low
  // end out, for a matrix where one cell dwarfs the rest.
  const along = (cell: number) =>
    spec?.heatmap?.scale === 'log'
      ? Math.log1p(cell - min) / Math.log1p(span)
      : (cell - min) / span;
  const labelled = spec?.labels === true;

  return (
    <div
      data-slot="heatmap"
      role="img"
      aria-label={name}
      className={cn('flex flex-col gap-1 overflow-x-auto', className)}
    >
      {data.ys.map((y, row) => (
        <div key={groupKeyText(y) || row} className="flex items-center gap-1">
          <span className="text-muted-foreground w-24 shrink-0 truncate text-xs">
            {label(spec?.heatmap?.y, y)}
          </span>
          {data.xs.map((x, column) => {
            const cell = data.cells[row]?.[column] ?? null;
            return (
              <div
                key={groupKeyText(x) || column}
                title={messages.label('label.chart.cell', {
                  y: label(spec?.heatmap?.y, y),
                  x: label(spec?.heatmap?.x, x),
                  // The cell measures one metric, so it reads as that
                  // column reads — the table and the grid agree.
                  value:
                    cell === null
                      ? messages.label('label.summary.unavailable')
                      : label(spec?.heatmap?.value, cell),
                })}
                className={cn(
                  'relative size-8 shrink-0',
                  onPick && cell !== null && 'cursor-pointer',
                )}
                // An empty cell is no group of the result: nothing fell in it.
                onClick={
                  onPick && cell !== null && spec?.heatmap
                    ? event =>
                        onPick(
                          {
                            [spec.heatmap!.x]: x,
                            [spec.heatmap!.y]: y,
                          },
                          event.currentTarget,
                        )
                    : undefined
                }
              >
                {/* The wash is a layer of its own, so a value written over
                    the cell does not fade with it. */}
                <div
                  className="bg-primary absolute inset-0 rounded-sm"
                  style={{
                    opacity: cell === null ? 0.06 : 0.15 + along(cell) * 0.85,
                  }}
                />
                {labelled && cell !== null && (
                  <span
                    data-slot="heatmap-label"
                    className="text-foreground absolute inset-0 flex items-center justify-center truncate px-0.5 text-[10px] leading-none"
                  >
                    {label(spec?.heatmap?.value, cell)}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      ))}
      <div className="flex items-center gap-1">
        <span className="w-24 shrink-0" />
        {data.xs.map((x, column) => (
          <span
            key={groupKeyText(x) || column}
            className="text-muted-foreground w-8 shrink-0 truncate text-center text-xs"
          >
            {label(spec?.heatmap?.x, x)}
          </span>
        ))}
      </div>
    </div>
  );
}
