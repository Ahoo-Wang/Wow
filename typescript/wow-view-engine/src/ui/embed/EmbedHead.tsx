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
import { ArrowUpRightIcon } from 'lucide-react';
import type { ViewNavigation } from '../../runtime/index.js';
import { Button } from '../components/button.js';
import type { PanelHeadingLevel } from '../DashboardPanel.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * An embed's first row, when it has one: its title as a heading at the
 * level the host's outline calls for, and on the right what the host
 * switched on — 在工作台中打开, the export, 「编辑」. Nothing at all when
 * there is neither: an embed is its result, and a row of chrome nobody
 * asked for is what it exists not to add (D10).
 */
export function EmbedHead({
  title,
  headingLevel,
  children,
}: {
  /** The title to draw; left out, none. */
  title?: string | undefined;
  headingLevel: PanelHeadingLevel;
  /** The controls on the right. */
  children?: ReactNode;
}) {
  if (title === undefined && !children) return null;
  const Title: `h${PanelHeadingLevel}` = `h${headingLevel}`;
  return (
    <div data-slot="embed-head" className="flex flex-wrap items-center gap-2">
      {title !== undefined && (
        <Title
          data-slot="embed-title"
          className="min-w-0 truncate text-base font-semibold"
        >
          {title}
        </Title>
      )}
      {children && (
        <div
          data-slot="embed-actions"
          className="ml-auto flex shrink-0 items-center gap-2"
        >
          {children}
        </div>
      )}
    </div>
  );
}

/**
 * 在工作台中打开, for an embedded view: the saved view itself under the
 * page's narrowing, in its own field names, handed to the host's route —
 * the same `{ kind: 'view' }` a dashboard panel's menu hands over. What the
 * reader did here (a sort, a page, a search) is not carried: the workbench
 * opens the view as its author saved it.
 */
export function OpenInWorkbench({
  to,
  onNavigate,
}: {
  to: ViewNavigation;
  onNavigate(to: ViewNavigation): void;
}) {
  const messages = useViewMessages();
  return (
    <Button
      data-slot="embed-open"
      variant="outline"
      size="sm"
      onClick={() => onNavigate(to)}
    >
      <ArrowUpRightIcon data-icon="inline-start" />
      {messages.label('label.panel.open')}
    </Button>
  );
}
