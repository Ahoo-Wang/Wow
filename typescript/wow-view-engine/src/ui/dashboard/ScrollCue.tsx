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

import { ArrowDownIcon, ArrowRightIcon } from 'lucide-react';
import { Badge } from '../components/badge.js';
import { useViewMessages } from '../MessagesProvider.js';
import type { ScrollState } from './scrollMore.js';

/**
 * What a panel's body holds past its edges, said on its bottom edge (P1-3):
 * 「下面还有 5 行」, 「右边还有 2 列」, both, or 「下面还有内容」 where the body
 * holds no table.
 *
 * A panel on a board has the height its author gave it and grows only so
 * far (`fittedLayout`), so a long table still runs past the bottom — and a
 * fade there would wash out the table's sticky header or totals band. The
 * count says how much is past it, which a fade never did.
 *
 * It is a picture of the scroll position, not a control: no Tab stop, no
 * press (it lets a click through to the row under it) and nothing a screen
 * reader reads — the body is a named Tab stop and the table says its own
 * rows. It stands over the body's end corner, above the horizontal
 * scrollbar and a sticky totals band (`inset`), so it covers neither.
 */
export function ScrollCue({ scroll }: { scroll: ScrollState }) {
  const messages = useViewMessages();
  const rows =
    scroll.rows &&
    messages.label('label.panel.more-rows', { count: scroll.rows });
  const columns =
    scroll.columns &&
    messages.label('label.panel.more-columns', { count: scroll.columns });
  const said =
    rows && columns
      ? messages.label('label.panel.more-both', { rows, columns })
      : rows ||
        columns ||
        (scroll.below ? messages.label('label.panel.more-below') : '');
  if (!said) return null;
  const Icon = rows || !columns ? ArrowDownIcon : ArrowRightIcon;
  return (
    // No height of its own, and the card's `gap-2` taken back above it: the
    // cue hangs over the body without moving anything under it.
    <div
      data-slot="panel-scroll-cue-anchor"
      aria-hidden="true"
      className="relative -mt-2 h-0"
    >
      <Badge
        variant="secondary"
        data-slot="panel-scroll-cue"
        data-rows={scroll.rows}
        data-columns={scroll.columns}
        className="pointer-events-none absolute right-(--_fve-panel-padding) shadow-sm"
        style={{ bottom: scroll.inset + 4 }}
      >
        <Icon data-icon="inline-start" />
        {said}
      </Badge>
    </div>
  );
}
