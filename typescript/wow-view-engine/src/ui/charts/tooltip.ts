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

import type { ChartTheme } from './theme.js';

/** One row of a tooltip: the mark's colour, whose it is, and the number. */
export interface TooltipRow {
  color: string;
  name: string;
  /** Already text, read as its column reads it — never shortened. */
  value: string;
}

/**
 * Text going into the tooltip's HTML. Every name and value here comes from
 * the data — a category, a split value, a column title an analyst typed —
 * so none of it is markup.
 */
export function escapeHtml(text: string): string {
  return text.replace(
    /[&<>"']/g,
    char =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        char
      ]!,
  );
}

/**
 * A tooltip as the registry's chart tooltip draws one — the same box, the
 * heading, a swatch, the name muted and the number in tabular figures — so
 * the drawing's tooltip and every other popup on the page read as one
 * family. The library renders it inside the chart's own element, which is
 * inside the surface, so the classes resolve against the theme's tokens and
 * follow a switch to dark without being told.
 */
export function tooltipHtml(heading: string, rows: readonly TooltipRow[]) {
  const body = rows
    .map(
      row =>
        `<div class="flex w-full items-center gap-2">` +
        `<div data-slot="chart-tooltip-swatch" class="size-2.5 shrink-0 rounded-[2px]" style="background:${escapeHtml(row.color)}"></div>` +
        `<div class="flex flex-1 items-center justify-between gap-2 leading-none">` +
        `<span class="text-muted-foreground">${escapeHtml(row.name)}</span>` +
        `<span class="text-foreground font-mono font-medium tabular-nums">${escapeHtml(row.value)}</span>` +
        `</div></div>`,
    )
    .join('');
  return (
    `<div data-slot="chart-tooltip" class="border-border/50 bg-background text-foreground grid min-w-32 items-start gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs shadow-xl">` +
    (heading ? `<div class="font-medium">${escapeHtml(heading)}</div>` : '') +
    `<div class="grid gap-1.5">${body}</div></div>`
  );
}

/**
 * The library's own tooltip box, emptied: ours is drawn inside it. It stays
 * within the chart (`confine`), so a panel's edge does not cut it.
 */
export function tooltipFrame(theme: ChartTheme) {
  return {
    confine: true,
    backgroundColor: 'transparent',
    borderWidth: 0,
    padding: 0,
    extraCssText: 'box-shadow:none;border-radius:0;',
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
  };
}
