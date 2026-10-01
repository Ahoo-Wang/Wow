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

import type { CSSProperties } from 'react';
import { cn } from 'cn';
import type { MessageFormatters } from '../kit/MessagesProvider.js';
import type { LegendPlace } from './EChart.js';

/**
 * The chart frame's classes: the plot and its legend side by side or
 * stacked, a hugged plot centred, a plot of a set height no longer stretched
 * to the frame's ratio, and the states of the tooltip the library draws.
 */
export function frameClass(
  placed: LegendPlace | undefined,
  hugged: boolean,
  setHeight: boolean,
  className: string | undefined,
) {
  return cn(
    'fve:flex fve:aspect-video fve:min-h-52 fve:w-full fve:gap-2 fve:text-xs',
    // The tooltip the library draws is ours (`tooltipHtml`), inside its
    // own transparent box: hidden, nothing of it is on screen.
    'fve:data-menu-open:[&_[data-slot=chart-tooltip]]:invisible',
    'fve:data-tap-armed:[&_[data-slot=chart-tooltip]]:after:text-muted-foreground fve:data-tap-armed:[&_[data-slot=chart-tooltip]]:after:content-(--_fve-tap-hint)',
    // How to follow up, under a pointer's tooltip; a first tap's own line
    // above takes its place.
    'fve:not-data-tap-armed:data-press-hint:[&_[data-slot=chart-tooltip]]:after:text-muted-foreground fve:not-data-tap-armed:data-press-hint:[&_[data-slot=chart-tooltip]]:after:content-(--_fve-press-hint)',
    placed === 'right' ? 'fve:flex-row' : 'fve:flex-col',
    hugged && 'fve:justify-center',
    setHeight &&
      'fve:aspect-auto fve:min-h-0 fve:*:data-[slot=chart-plot]:flex-none',
    className,
  );
}

/**
 * What a pressable chart's tooltip adds under its rows, as strings the
 * stylesheet writes after the tooltip's own content, so no family's tooltip
 * has to know of them: a first tap's 「再点一下追问」 (`data-tap-armed`), and
 * a pointer's 「点击追问」 where a press follows up (`data-press-hint`,
 * R2-40) — with 「拖动框选一段」 where a drag brushes the axis.
 */
export function hintStyle(
  messages: Pick<MessageFormatters, 'label'>,
  pressable: boolean,
  pressHint: boolean,
  brushes: boolean,
): CSSProperties | undefined {
  if (!pressable) return undefined;
  return {
    '--_fve-tap-hint': cssString(messages.label('label.drill.tap-again')),
    ...(pressHint && {
      '--_fve-press-hint': cssString(
        messages.label(
          brushes ? 'label.drill.press-brush-hint' : 'label.drill.press-hint',
        ),
      ),
    }),
  } as CSSProperties;
}

/** Text as a CSS string, for `content` to write. */
function cssString(text: string): string {
  return `"${text.replace(/["\\]/g, '\\$&').replace(/\n/g, ' ')}"`;
}
