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

import type { BulkOutcome } from '../react/index.js';
import { LineAlert, type AlertTone } from './alerts.js';
import { AlertAction, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { useViewMessages } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';

export interface BulkOutcomeStripProps {
  /** Nothing is drawn until a command has settled. */
  outcome: BulkOutcome | null;
  /** Takes the line down; `useBulkCommand().dismiss` is what goes here. */
  onDismiss(): void;
  /**
   * A name for what was run, said before the counts: a host with more than
   * one bulk command has to say which one this is about.
   */
  title?: string;
}

/**
 * What a business bulk command came to, as one line.
 *
 * It is the write outcome's recipe (`OutcomeActions`) applied to a host's
 * own command: the same `LineAlert`, the same tones, the same single way
 * out. A host that drew its own `Alert` here would teach the reader a
 * second vocabulary for "this failed" on the same screen.
 *
 * **Where it goes is beside the workbench, not inside the toolbar.** The
 * outcome outlives the selection it acted on — the run clears it — and the
 * bulk slot is unmounted the moment that happens.
 *
 * Three tones for three readings, taken from the callout recipe rather than
 * invented here: everything done is a note (`info` — it is the expected
 * ending, and an expected ending should not shout), some of it done is a
 * `warning`, and nothing done is an `error`. There is no success tone in
 * this package, and one added for this line alone would be a fourth colour
 * that only ever appears here.
 */
export function BulkOutcomeStrip({
  outcome,
  onDismiss,
  title,
}: BulkOutcomeStripProps) {
  const messages = useViewMessages();
  if (!outcome) return null;

  const done = outcome.succeeded.length;
  const failed = outcome.failed.length;
  const [key, tone] = readingOf(done, failed);

  return (
    <LineAlert tone={tone} data-slot="bulk-outcome">
      <AlertTitle>
        {title && `${title} · `}
        {messages.label(key, { count: done + failed, done, failed })}
        {/* The service's own words, after the counts and on the same line:
            the counts say how much, and only the service can say why. */}
        {outcome.reason !== undefined && ` ${outcome.reason}`}
      </AlertTitle>
      <AlertAction>
        <Button variant="outline" size="sm" onClick={onDismiss}>
          {messages.label('label.bulk.dismiss')}
        </Button>
      </AlertAction>
    </LineAlert>
  );
}

/** The sentence and the tone one pair of counts reads as. */
function readingOf(done: number, failed: number): [MessageKey, AlertTone] {
  if (failed === 0) return ['label.bulk.done', 'info'];
  if (done === 0) return ['label.bulk.failed', 'error'];
  return ['label.bulk.partial', 'warning'];
}
