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
  ArrowRightLeftIcon,
  SquarePenIcon,
  Trash2Icon,
  UnplugIcon,
} from 'lucide-react';
import type { FieldDefinition, Issue } from '../model/index.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';
import { analysisIssueNamer } from './analysis/issueNames.js';
import { recordIssueNamer } from './record/issueNames.js';
import { Button } from './components/button.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';

/**
 * Why a panel is out, and who can bring it back, by the finding that put it
 * out: `[reason, way out]`.
 *
 * The kernels' own sentences were written for whoever edits a config, and
 * some carry what only a config knows — the instance id a panel points at,
 * a scope code, a store's error text. A reader of the board gets the reason
 * in their own words instead, and the way out: a reader is told who can
 * bring it back, named by role; whoever is building the board is handed the
 * way itself (`PanelUnavailableProps.remove`). No button offers what nothing
 * on screen can do.
 */
const OUTAGES: Readonly<Record<string, readonly [MessageKey, MessageKey]>> = {
  // Deleted, or out of this reader's reach: the store will not say which,
  // and neither can the panel.
  'dashboard.panel.unavailable': [
    'label.panel.out.missing',
    'label.panel.way-out.share',
  ],
  'dashboard.panel.failed': [
    'label.panel.out.failed',
    'label.panel.way-out.maintainer',
  ],
  'dashboard.panel.kind-unsupported': [
    'label.panel.out.kind',
    'label.panel.way-out.maintainer',
  ],
  'dashboard.panel.scope-too-narrow': [
    'label.panel.out.private',
    'label.panel.way-out.widen',
  ],
  'dashboard.panel.unknown-kind': [
    'label.panel.out.unknown-kind',
    'label.panel.way-out.maintainer',
  ],
  'dashboard.panel.id-duplicate': [
    'label.panel.out.settings',
    'label.panel.way-out.maintainer',
  ],
  'dashboard.panel.id-empty': [
    'label.panel.out.settings',
    'label.panel.way-out.maintainer',
  ],
};

/** Why a panel is out, said once, and what can be done about it. */
interface Outage {
  reason: string;
  /** Which of its settings and why, when the reason alone does not say. */
  detail?: string;
  wayOut: string;
}

function outageOf(
  issue: Issue | undefined,
  messages: MessageFormatters,
  name: (found: Issue) => Issue,
  owned: boolean,
): Outage {
  const said = (reason: MessageKey, wayOut: MessageKey): Outage => ({
    reason: messages.label(reason),
    wayOut: messages.label(wayOut),
  });
  // A panel held back with nothing of its own to say is waiting on the
  // dashboard, whose finding the workbench says above the grid.
  if (!issue)
    return said('label.panel.out.blocked', 'label.panel.way-out.dashboard');
  const mapped = OUTAGES[issue.code];
  if (mapped) return said(mapped[0], mapped[1]);
  // A binding names the fields on both ends; the reader needs to know that
  // the dashboard's filters do not reach this panel — and, when the field a
  // filter is wired to is not in the view at all, which filter, by the
  // label they see on the board (todo C).
  if (issue.code.startsWith('dashboard.binding.'))
    return {
      ...said('label.panel.out.filter', 'label.panel.way-out.maintainer'),
      ...(issue.code === 'dashboard.binding.panel-unknown'
        ? { detail: messages.issue(issue) }
        : {}),
    };
  // The dashboard's other rules about one panel — where it stands, what a
  // note or a link holds — already read in a reader's words and name no id.
  if (issue.code.startsWith('dashboard.'))
    return {
      reason: messages.issue(issue),
      wayOut: messages.label('label.panel.way-out.maintainer'),
    };
  // Anything else is the view the panel shows refusing its own settings:
  // a condition its field no longer takes, a chart it can no longer draw.
  // Which setting and why is said, in the words the screen uses (`name`) —
  // no one reading the board can open the settings to find out, and
  // 「the settings no longer work」 alone sent them to someone who could not
  // tell either. A view the board owns is its maintainer's to fix; a saved
  // one its owner's.
  return {
    reason: messages.label('label.panel.out.config'),
    detail: messages.issue(name(issue)),
    wayOut: messages.label(
      owned ? 'label.panel.way-out.maintainer' : 'label.panel.way-out.author',
    ),
  };
}

/**
 * A panel's finding in the words its screen uses: a field by its label, an
 * operator, a summary or a unit as its control says it. A panel that runs
 * names through its analysis (`analysisIssueNamer`); one that cannot run
 * has no runtime to ask, and names by the fields of the view it shows
 * (`DashboardPanelView.fields`) — a condition's field and operator, which
 * is what a view refuses most.
 */
export function outPanelNamer(
  fields: readonly FieldDefinition[],
  messages: MessageFormatters,
): (found: Issue) => Issue {
  const byField = recordIssueNamer(fields, messages);
  const byAnalysis = analysisIssueNamer(
    { groups: [], metrics: [], fields: [], conditionFields: fields },
    messages,
  );
  return found => byAnalysis(byField(found));
}

export interface PanelUnavailableProps {
  /** The finding that put the panel out; none when the dashboard holds it back. */
  issue: Issue | undefined;
  /**
   * While the board is built, the way out is the author's to take (D22 D):
   * given, the sentence naming who to ask gives way to these buttons.
   */
  remove?(): void;
  /** 替换视图…, for a data panel. */
  replace?(): void;
  /** 改内容…, for a note, a picture or links the kernel refused. */
  editContent?(): void;
  /**
   * How a finding is named in the screen's words (`outPanelNamer`); left
   * out, it is said as the kernel wrote it.
   */
  name?(found: Issue): Issue;
  /** Whether the panel shows a view the board owns, whose way out is its maintainer. */
  owned?: boolean;
}

function same(found: Issue): Issue {
  return found;
}

/**
 * A panel that cannot show anything: why, once, and who can bring it back.
 * It used to say 「不可用」 as its title and 「……不可用」 again under it,
 * which is the same word twice and no reason at all (U5).
 */
export function PanelUnavailable({
  issue,
  remove,
  replace,
  editContent,
  name = same,
  owned = false,
}: PanelUnavailableProps) {
  const messages = useViewMessages();
  const outage = outageOf(issue, messages, name, owned);
  // Whoever builds the board is the one the sentence would send a reader
  // to; they are given the way itself, and the sentence says what it does.
  const wayOut = !remove
    ? outage.wayOut
    : messages.label(
        replace
          ? 'label.panel.way-out.edit'
          : 'label.panel.way-out.edit-content',
      );
  return (
    <Empty data-slot="panel-unavailable" className="fve:p-4">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <UnplugIcon />
        </EmptyMedia>
        <EmptyTitle>{outage.reason}</EmptyTitle>
        {outage.detail !== undefined && (
          <EmptyDescription data-slot="panel-unavailable-detail">
            {outage.detail}
          </EmptyDescription>
        )}
        <EmptyDescription>{wayOut}</EmptyDescription>
      </EmptyHeader>
      {remove && (
        <EmptyContent>
          <div className="fve:flex fve:flex-wrap fve:justify-center fve:gap-2">
            {replace && (
              <Button variant="outline" size="sm" onClick={replace}>
                <ArrowRightLeftIcon data-icon="inline-start" />
                {messages.label('label.panel.replace')}
              </Button>
            )}
            {editContent && (
              <Button variant="outline" size="sm" onClick={editContent}>
                <SquarePenIcon data-icon="inline-start" />
                {messages.label('label.panel.edit-content')}
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={remove}>
              <Trash2Icon data-icon="inline-start" />
              {messages.label('label.panel.remove')}
            </Button>
          </div>
        </EmptyContent>
      )}
    </Empty>
  );
}
