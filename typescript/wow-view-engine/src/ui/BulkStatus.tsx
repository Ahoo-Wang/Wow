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
  failureReasons,
  type BulkCommand,
  type BulkOutcome,
} from '../react/index.js';
import { LineAlert, type AlertTone } from './alerts.js';
import { AlertAction, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { type MessageFormatters, useViewMessages } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';

export interface BulkStatusProps {
  /** `useBulkCommand()`; nothing is drawn while it is idle. */
  command: BulkCommand;
}

/** How many of the reasons a line has room for; the rest are counted. */
const REASONS = 2;

/**
 * A host's bulk command, as one line: how far it has come while it runs,
 * with the way to stop it; what it came to once it has settled, with the
 * way to put the line away.
 *
 * It is the write outcome's recipe (`OutcomeActions`) applied to a host's
 * own command: the same `LineAlert`, the same tones, the same single way
 * out. The workbench draws it above the result (`record.bulk`), because the
 * outcome outlives the selection it acted on and the toolbar's bulk slot
 * goes when the selection does.
 *
 * Three tones for three readings, taken from the callout recipe: everything
 * done is a note (`info` — the expected ending should not shout), some of
 * it done is a `warning`, nothing done is an `error`; a command in flight is
 * a note. The reasons are the source's own words, the commonest first, a
 * couple of them with how many records gave each — records refuse for
 * different reasons, and one reason said for all of them was a guess. What
 * was not done stays selected (`useBulkCommand`), and the line says so.
 */
export function BulkStatus({ command }: BulkStatusProps) {
  const messages = useViewMessages();
  const { running, outcome } = command;
  if (running) {
    const { done, total, failed } = running.progress;
    return (
      <LineAlert tone="info" data-slot="bulk-status" data-state="running">
        <AlertTitle className="tabular-nums">
          {`${running.title} · `}
          {messages.label(
            failed > 0 ? 'label.bulk.running-failed' : 'label.bulk.running',
            { done, total, failed },
          )}
        </AlertTitle>
        <AlertAction>
          <Button
            variant="outline"
            size="sm"
            onClick={command.stop}
            disabled={running.stopping}
          >
            {messages.label(
              running.stopping ? 'label.bulk.stopping' : 'label.bulk.stop',
            )}
          </Button>
        </AlertAction>
      </LineAlert>
    );
  }
  if (!outcome) return null;
  const [tone, sentence] = reading(outcome, messages);
  return (
    <LineAlert tone={tone} data-slot="bulk-status" data-state="settled">
      <AlertTitle>{`${outcome.title} · ${sentence}`}</AlertTitle>
      <AlertAction>
        <Button variant="outline" size="sm" onClick={command.dismiss}>
          {messages.label('label.bulk.dismiss')}
        </Button>
      </AlertAction>
    </LineAlert>
  );
}

/** The tone and the sentence one outcome reads as. */
function reading(
  outcome: BulkOutcome,
  messages: MessageFormatters,
): [AlertTone, string] {
  const done = outcome.succeeded.length;
  const failed = outcome.failed.length;
  const skipped = outcome.skipped.length;
  const [key, tone] = countsOf(done, failed + skipped);
  const parts = [messages.label(key, { done, failed })];
  if (skipped > 0)
    parts.push(messages.label('label.bulk.skipped', { skipped }));
  const reasons = failureReasons(outcome.failed);
  for (const { reason, count } of reasons.slice(0, REASONS))
    parts.push(messages.label('label.bulk.reason', { reason, count }));
  const more = reasons.length - REASONS;
  if (more > 0)
    parts.push(messages.label('label.bulk.more-reasons', { count: more }));
  if (failed + skipped > 0) parts.push(messages.label('label.bulk.left'));
  return [tone, parts.join(' · ')];
}

/** The counts' sentence and tone: all done, some, or none. */
function countsOf(done: number, undone: number): [MessageKey, AlertTone] {
  if (undone === 0) return ['label.bulk.done', 'info'];
  if (done === 0) return ['label.bulk.failed', 'error'];
  return ['label.bulk.partial', 'warning'];
}
