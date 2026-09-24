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

import {
  InfoIcon,
  MousePointerClickIcon,
  TriangleAlertIcon,
} from 'lucide-react';
import type { Issue } from '../../model/index.js';
import type { DashboardPanelView } from '../../react/index.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { BadgeTooltip, IconTooltip } from '../IconButton.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { ToneBadge } from '../variants.js';
import { presentationMark } from './PanelBodies.js';

/**
 * What a panel's title row says about the panel besides its name, every
 * finding already named as the panel's screen names things: its warnings
 * and its notes (glyphs before the title), and on a line under the title
 * the board filters holding a value that do not reach it, the filter a
 * press on it sets, and the look of its own it wears.
 */
export interface PanelMarkSet {
  warnings: readonly Issue[];
  notes: readonly Issue[];
  unreached: readonly string[];
  pressesFilter?: string;
  look: string | null;
}

/**
 * The marks of one panel. `shown` is the finding its body already says —
 * a broken panel's reason — left out here, so nothing is said twice.
 */
export function panelMarks({
  panel,
  shown,
  nameIssue,
  unreached = [],
  pressesFilter,
  messages,
}: {
  panel: DashboardPanelView;
  shown: Issue | undefined;
  nameIssue(issue: Issue): Issue;
  unreached?: readonly string[];
  pressesFilter?: string;
  messages: MessageFormatters;
}): PanelMarkSet {
  return {
    // A panel that runs and still has something to say shows its view and
    // wears the finding in its header. A broken one says why in its body;
    // whatever else it has to say — a config can carry a warning beside its
    // error — still goes in the header.
    warnings: panel.issues
      .filter(found => found.severity === 'warning' && found !== shown)
      .map(nameIssue),
    // What is true of the panel's answer and nothing is wrong with — the
    // groups its own limit left out — rides beside the title quietly: no
    // warning colour on the glyph, none on the panel's edge.
    notes: panel.issues
      .filter(found => found.severity === 'note')
      .map(nameIssue),
    unreached,
    pressesFilter,
    // A look of its own, on purpose (D22 D): a reader comparing the panel
    // with the view in the workbench is told so.
    look: presentationMark(panel, messages),
  };
}

/** Whether the line of badges under the title has anything on it. */
export function hasBadges(marks: PanelMarkSet): boolean {
  return (
    marks.unreached.length > 0 ||
    marks.pressesFilter !== undefined ||
    Boolean(marks.look)
  );
}

/** Whether the title row carries any mark at all. */
export function hasMarks(marks: PanelMarkSet): boolean {
  return (
    marks.warnings.length > 0 || marks.notes.length > 0 || hasBadges(marks)
  );
}

/**
 * The marks of one line of a panel's header: `glyphs`, the warning and the
 * note before the title; `badges`, the line under it — nothing when it
 * would be empty.
 */
export function PanelMarks({
  marks,
  line,
}: {
  marks: PanelMarkSet;
  line: 'glyphs' | 'badges';
}) {
  return line === 'glyphs' ? (
    <FindingGlyphs marks={marks} />
  ) : (
    <BadgeLine marks={marks} />
  );
}

function FindingGlyphs({ marks }: { marks: PanelMarkSet }) {
  const messages = useViewMessages();
  return (
    <>
      {/*
        The marker is the package's own `IconTooltip`: the same string
        names the control and fills the tooltip, focus opens it, and a
        tap opens it too. The colour sits on the glyph rather than on
        the button — `text-warning` is what the marker means, and the
        vendored ghost variant keeps its own hover and focus colours.
      */}
      {marks.warnings.length > 0 && (
        <IconTooltip
          label={messages.issues(marks.warnings)}
          render={
            <Button data-slot="panel-warning" variant="ghost" size="icon-sm" />
          }
        >
          <TriangleAlertIcon className="text-warning" />
        </IconTooltip>
      )}
      {marks.notes.length > 0 && (
        <IconTooltip
          label={messages.issues(marks.notes)}
          render={
            <Button data-slot="panel-note" variant="ghost" size="icon-sm" />
          }
        >
          <InfoIcon className="text-muted-foreground" />
        </IconTooltip>
      )}
    </>
  );
}

/*
 * The title's line is the name's: a reader tells panels apart by it, so
 * the badges beside it take a line of their own under it rather than
 * squeezing it to nothing on a narrow panel (U-10).
 */
function BadgeLine({ marks }: { marks: PanelMarkSet }) {
  const messages = useViewMessages();
  if (!hasBadges(marks)) return null;
  const { unreached, pressesFilter, look } = marks;
  return (
    <div data-slot="panel-badges" className="flex min-w-0 flex-wrap gap-1">
      {unreached.length > 0 && (
        <ToneBadge
          data-slot="panel-not-reached"
          tone="warning"
          dot={false}
          className="max-w-full"
        >
          {messages.label('label.filters.not-reached', {
            filters: unreached
              .map(filter =>
                messages.label('label.filters.name-quoted', { name: filter }),
              )
              .join(messages.label('label.filter.join')),
          })}
        </ToneBadge>
      )}
      {pressesFilter !== undefined && (
        <BadgeTooltip
          note={messages.label('label.click.badge-note', {
            filter: pressesFilter,
          })}
          render={
            <Badge
              data-slot="panel-click-filter"
              variant="outline"
              render={<button type="button" />}
            />
          }
        >
          <MousePointerClickIcon data-icon="inline-start" />
          {messages.label('label.click.badge', { filter: pressesFilter })}
        </BadgeTooltip>
      )}
      {look && (
        <BadgeTooltip
          note={messages.label('label.panel.presentation.note')}
          render={
            <Badge
              data-slot="panel-presentation"
              variant="secondary"
              render={<button type="button" />}
            />
          }
        >
          {look}
        </BadgeTooltip>
      )}
    </div>
  );
}
