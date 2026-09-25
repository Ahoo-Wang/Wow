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

import { InboxIcon } from 'lucide-react';
import { Button } from '../components/button.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { useViewMessages } from '../MessagesProvider.js';
import type { MessageKey } from '../messages.js';
import type { FilterEditorController } from '../../react/index.js';
import type { EmptyWayOut } from './emptyWayOut.js';

export interface EmptyResultProps {
  /** Overrides the catalogue's own wording. */
  title?: string;
  description?: string;
  /** Which way out the query that matched nothing has (`emptyWayOut`). */
  wayOut: EmptyWayOut;
  /** The one way out; without it the state is a sentence and nothing more. */
  onAction?(): void;
}

/**
 * A query that ran and matched nothing, with one way out of it.
 *
 * The four other "nothing here" screens are said above the table — the query
 * failed, the config needs fixing — and this is the one the table owns,
 * because nothing above it says it. What it adds to that sentence is a
 * *next step*: a screen that reports a dead end and offers no way off it
 * leaves the reader to find the control that caused it, which on a folded
 * editor is not even on screen.
 *
 * Which step it is follows from what was asked (`emptyWayOut`): back to a
 * saved view's own conditions when the reader added to them, on to another
 * question when the saved view itself is empty right now, clear when a view
 * never saved is under conditions, add one when there are none. The
 * sentence says which of those it is — 「这个视图现在没有记录」 is not
 * 「没有记录符合当前条件」. One action, never two: a way out that has to be
 * chosen between is not a way out.
 */
export function EmptyResult({
  title,
  description,
  wayOut,
  onAction,
}: EmptyResultProps) {
  const messages = useViewMessages();
  return (
    <Empty data-slot="record-empty">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <InboxIcon />
        </EmptyMedia>
        <EmptyTitle>{title ?? messages.label('label.record.empty')}</EmptyTitle>
        <EmptyDescription>
          {description ?? messages.label(HINT[wayOut])}
        </EmptyDescription>
      </EmptyHeader>
      {onAction && (
        <EmptyContent>
          <Button variant="outline" size="sm" onClick={onAction}>
            {messages.label(ACTION[wayOut])}
          </Button>
        </EmptyContent>
      )}
    </Empty>
  );
}

const HINT: Record<EmptyWayOut, MessageKey> = {
  restore: 'label.record.empty-hint',
  clear: 'label.record.empty-hint',
  edit: 'label.record.empty-view',
  add: 'label.record.empty-none',
};

const ACTION: Record<EmptyWayOut, MessageKey> = {
  restore: 'label.record.empty-restore',
  clear: 'label.record.empty-clear',
  edit: 'label.record.empty-edit',
  add: 'label.record.empty-add',
};

/**
 * What an empty result says where it has no way out to offer — a board's
 * record panel, an embedded view: the conditions are someone else's to
 * change. Under any condition in force, the view's own or the scope a board
 * or a page put on it, the rows are missing because of what was asked
 * (「没有记录符合当前条件。」), as the workbench says it; with none, there are
 * no records at all.
 */
export function emptyHintOf(
  filter: Pick<FilterEditorController, 'applied' | 'scoped'>,
): MessageKey {
  return filter.applied.length > 0 || filter.scoped.length > 0
    ? 'label.record.empty-hint'
    : 'label.record.empty-none';
}
