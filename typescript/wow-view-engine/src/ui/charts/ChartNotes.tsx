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
import type { LegendPlace } from './EChart.js';

/**
 * What a drawing says over itself about what it could not draw, or how to
 * read what it did: groups left out, numbers that are approximate, a value
 * off the scale. Quiet text above the plot, one sentence each, in the
 * place a legend would stand — the families that have no legend of their
 * own put it there (`EChart`'s `legend`).
 */
export function chartNotes(
  notes: readonly string[],
  slot: string,
  legend?: ReactNode | ((placed: LegendPlace) => ReactNode),
): { at: LegendPlace; node: (placed: LegendPlace) => ReactNode } | undefined {
  if (notes.length === 0 && legend === undefined) return undefined;
  return {
    at: 'top',
    node: placed => (
      <div className="flex min-w-0 flex-col gap-1">
        {notes.length > 0 && (
          <span
            data-slot={slot}
            className="flex flex-wrap gap-x-3 text-muted-foreground"
          >
            {notes.map(note => (
              <span key={note}>{note}</span>
            ))}
          </span>
        )}
        {typeof legend === 'function' ? legend(placed) : legend}
      </div>
    ),
  };
}
