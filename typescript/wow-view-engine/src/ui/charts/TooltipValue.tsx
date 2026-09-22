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

/**
 * One measured value inside a chart tooltip, read as its column reads it.
 *
 * The vendored `ChartTooltipContent` prints a number as `toLocaleString()` on
 * the machine's own language and offers no narrower hook than `formatter`,
 * which replaces the whole row — so the row is drawn here: the series' colour,
 * its name, and the number as `ValueLabel` writes it. That is the same seam
 * `ui/popups.tsx` closes for the popups' markup, and for the same reason:
 * `ui/components/**` is upstream's and is not edited by hand (AGENTS.md).
 * The classes are upstream's own for this row, so the two tooltips are one
 * tooltip; only the value is ours.
 */
export function TooltipValue({
  color,
  name,
  value,
}: {
  /** The mark's colour, so the swatch matches what the tooltip points at. */
  color?: string;
  name: ReactNode;
  /** Already text: the caller formatted it through the column. */
  value: string;
}) {
  return (
    <>
      <div
        data-slot="chart-tooltip-swatch"
        className="h-2.5 w-2.5 shrink-0 rounded-[2px]"
        style={{ background: color }}
      />
      <div className="flex flex-1 items-center justify-between gap-2 leading-none">
        <span className="text-muted-foreground">{name}</span>
        <span className="text-foreground font-mono font-medium tabular-nums">
          {value}
        </span>
      </div>
    </>
  );
}
