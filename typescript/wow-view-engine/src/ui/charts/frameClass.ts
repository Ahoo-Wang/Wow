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

import { cn } from 'cn';
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
    placed === 'right' ? 'fve:flex-row' : 'fve:flex-col',
    hugged && 'fve:justify-center',
    setHeight &&
      'fve:aspect-auto fve:min-h-0 fve:*:data-[slot=chart-plot]:flex-none',
    className,
  );
}
