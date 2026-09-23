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

import { UnplugIcon } from 'lucide-react';
import type { Issue } from '../model/index.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';
import {
  Empty,
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
 * in their own words instead, and the way out that exists today: there is
 * no removing or replacing a panel from the board yet (D22's batch B), so
 * the way out is the person who can, named by role. No button offers what
 * nothing on screen can do.
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

function outageOf(
  issue: Issue | undefined,
  messages: MessageFormatters,
): readonly [string, string] {
  // A panel held back with nothing of its own to say is waiting on the
  // dashboard, whose finding the workbench says above the grid.
  if (!issue)
    return [
      messages.label('label.panel.out.blocked'),
      messages.label('label.panel.way-out.dashboard'),
    ];
  const mapped = OUTAGES[issue.code];
  if (mapped) return [messages.label(mapped[0]), messages.label(mapped[1])];
  // A binding names the fields on both ends; the reader needs to know only
  // that the dashboard's filters do not reach this panel.
  if (issue.code.startsWith('dashboard.binding.'))
    return [
      messages.label('label.panel.out.filter'),
      messages.label('label.panel.way-out.maintainer'),
    ];
  // The dashboard's other rules about one panel — where it stands, what a
  // note or a link holds — already read in a reader's words and name no id.
  if (issue.code.startsWith('dashboard.'))
    return [
      messages.issue(issue),
      messages.label('label.panel.way-out.maintainer'),
    ];
  // Anything else is the view the panel shows refusing its own saved
  // settings — its definition changed under it. Its detail names fields and
  // is the view's owner's to read, in the view itself.
  return [
    messages.label('label.panel.out.refused'),
    messages.label('label.panel.way-out.author'),
  ];
}

export interface PanelUnavailableProps {
  /** The finding that put the panel out; none when the dashboard holds it back. */
  issue: Issue | undefined;
}

/**
 * A panel that cannot show anything: why, once, and who can bring it back.
 * It used to say 「不可用」 as its title and 「……不可用」 again under it,
 * which is the same word twice and no reason at all (U5).
 */
export function PanelUnavailable({ issue }: PanelUnavailableProps) {
  const messages = useViewMessages();
  const [reason, wayOut] = outageOf(issue, messages);
  return (
    <Empty data-slot="panel-unavailable" className="p-4">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <UnplugIcon />
        </EmptyMedia>
        <EmptyTitle>{reason}</EmptyTitle>
        <EmptyDescription>{wayOut}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}
