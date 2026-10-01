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

import { FileQuestionMarkIcon, LockIcon, RotateCcwIcon } from 'lucide-react';
import type { Issue } from '../../model/index.js';
import { Button } from '../components/button.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { useKindIssue, useKindWord } from '../kit/kinds.js';

/** The store's answer that the reader may not open the view. */
const FORBIDDEN = 'view.open.failed.forbidden';

/**
 * What a surface shows when the chosen view cannot be opened: the reason,
 * and the ways on — trying again, where the failure may pass
 * (`retryableOpen`), and the default. The workbenches' main column and both
 * embeds draw it, so a view a reader cannot open looks the same wherever it
 * is (second review R2-80).
 *
 * Neutral, not destructive: nothing broke, the view is simply not
 * available here. A view the reader has no right to wears a lock, and its
 * reason says who can change that; the reason is said by its code
 * (`data-issue`).
 */
export function Unopenable({
  issue,
  onRetry,
  onDefault,
}: {
  issue: Issue;
  onRetry?(): void;
  onDefault?(): void;
}) {
  const messages = useViewMessages();
  const word = useKindWord();
  const ownWord = useKindIssue();
  return (
    <Empty role="alert" data-slot="view-unopenable" data-issue={issue.code}>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          {issue.code === FORBIDDEN ? <LockIcon /> : <FileQuestionMarkIcon />}
        </EmptyMedia>
        <EmptyTitle>{messages.label(word('label.view.unopenable'))}</EmptyTitle>
        <EmptyDescription>{messages.issue(ownWord(issue))}</EmptyDescription>
      </EmptyHeader>
      {(onRetry || onDefault) && (
        <EmptyContent>
          <div className="fve:flex fve:flex-wrap fve:justify-center fve:gap-2">
            {onRetry && (
              <Button
                variant="outline"
                size="sm"
                data-slot="view-open-retry"
                onClick={onRetry}
              >
                <RotateCcwIcon data-icon="inline-start" />
                {messages.label('label.view.open-retry')}
              </Button>
            )}
            {onDefault && (
              <Button variant="outline" size="sm" onClick={onDefault}>
                {messages.label(word('label.view.open-default'))}
              </Button>
            )}
          </div>
        </EmptyContent>
      )}
    </Empty>
  );
}
