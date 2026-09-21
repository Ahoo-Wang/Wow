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

export interface EmptyResultProps {
  /** Overrides the catalogue's own wording. */
  title?: string;
  description?: string;
  /** Whether the query that matched nothing carried conditions of its own. */
  hasConditions: boolean;
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
 * Which step it is follows from what was asked. Under conditions the rows
 * are missing *because of them*, so the way out is to clear them and see
 * what there is; with no conditions the view is already showing everything
 * there is, and the only thing left to try is asking a different question.
 * One action, never two: a way out that has to be chosen between is not a
 * way out.
 */
export function EmptyResult({
  title,
  description,
  hasConditions,
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
          {description ?? messages.label('label.record.empty-hint')}
        </EmptyDescription>
      </EmptyHeader>
      {onAction && (
        <EmptyContent>
          <Button variant="outline" size="sm" onClick={onAction}>
            {messages.label(
              hasConditions
                ? 'label.record.empty-clear'
                : 'label.record.empty-add',
            )}
          </Button>
        </EmptyContent>
      )}
    </Empty>
  );
}
