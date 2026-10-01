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

import type * as React from 'react';
import {
  failureReasons,
  type ActionRunner,
  type BulkOutcome,
  type BulkRunning,
} from '../../react/index.js';
import { LineAlert, type AlertTone } from '../kit/alerts.js';
import { AlertAction, AlertTitle } from '../components/alert.js';
import { Button } from '../components/button.js';
import {
  type MessageFormatters,
  type SayWith,
  useSayWith,
  useViewMessages,
} from '../kit/MessagesProvider.js';

export interface BulkStatusProps {
  /** The surface's runner; nothing is drawn while it is idle. */
  command: Pick<ActionRunner, 'running' | 'outcome' | 'stop' | 'dismiss'>;
  /** The line, for the surface to land the keyboard on its button. */
  ref?: React.Ref<HTMLDivElement>;
}

/** How many of the reasons a line has room for; the rest are counted. */
const REASONS = 2;

/**
 * A surface's command, as one line: how far it has come while it runs,
 * with the way to stop it; what it came to once it has settled, with the
 * way to put the line away.
 *
 * It is the write outcome's recipe (`OutcomeActions`) applied to a host's
 * command: the same `LineAlert`, the same tones, the same single way out.
 * The record surface draws it under its rows, held to the bottom of the
 * view while they scroll, because the outcome outlives the selection it
 * acted on and the selection's bar goes when the selection does — and a
 * line that appeared above the rows pushed every row down under the
 * pointer that pressed it.
 *
 * Three tones for three readings, taken from the callout recipe: everything
 * done is a note (`info` — the expected ending should not shout), some of
 * it done is a `warning`, nothing done and something failed is an `error`;
 * a command in flight is a note. The reasons are the source's own words,
 * the commonest first, a couple of them with how many records gave each —
 * records refuse for different reasons, and one reason said for all of
 * them was a guess. What failed or was not sent stays selected
 * (`useActionRunner`), and the line says so; what has an unknown outcome
 * is let go, and the line says to check it.
 *
 * Stop starts nothing more and stays a button: pressed again it stops
 * waiting, so a command that never answers cannot hold the line.
 */
export function BulkStatus({ command, ref }: BulkStatusProps) {
  const messages = useViewMessages();
  const sayWith = useSayWith();
  const { running, outcome } = command;
  if (running) {
    return (
      <LineAlert
        ref={ref}
        tone="info"
        data-slot="bulk-status"
        data-state="running"
      >
        <AlertTitle className="fve:tabular-nums">
          {runningSentence(running, messages, sayWith)}
        </AlertTitle>
        <AlertAction>
          <Button variant="outline" size="sm" onClick={command.stop}>
            {messages.label(
              running.stopping ? 'label.bulk.stop-waiting' : 'label.bulk.stop',
            )}
          </Button>
        </AlertAction>
      </LineAlert>
    );
  }
  if (!outcome) return null;
  const [tone, sentence] = reading(outcome, messages);
  return (
    <LineAlert
      ref={ref}
      tone={tone}
      data-slot="bulk-status"
      data-state="settled"
    >
      <AlertTitle>{`${sayWith(outcome.title, outcome.values)} · ${sentence}`}</AlertTitle>
      <AlertAction>
        <Button variant="outline" size="sm" onClick={command.dismiss}>
          {messages.label('label.bulk.dismiss')}
        </Button>
      </AlertAction>
    </LineAlert>
  );
}

/** The line while a command runs: its name and how far it has come. */
export function runningSentence(
  running: BulkRunning,
  messages: MessageFormatters,
  sayWith: SayWith,
): string {
  const { done, total, failed } = running.progress;
  const parts = [
    sayWith(running.title, running.values),
    messages.label(
      failed > 0 ? 'label.bulk.running-failed' : 'label.bulk.running',
      { done, total, failed },
    ),
  ];
  if (running.stopping) parts.push(messages.label('label.bulk.stopping'));
  return parts.join(' · ');
}

/** The line once a command has settled: what it came to, and why not. */
export function outcomeSentence(
  outcome: BulkOutcome,
  messages: MessageFormatters,
  sayWith: SayWith,
): string {
  return `${sayWith(outcome.title, outcome.values)} · ${
    reading(outcome, messages)[1]
  }`;
}

/** The tone and the sentence one outcome reads as. */
function reading(
  outcome: BulkOutcome,
  messages: MessageFormatters,
): [AlertTone, string] {
  const { succeeded, failed, refused, unknown, skipped } = outcome;
  const notRun = refused.length + skipped.length;
  const total = succeeded.length + failed.length + unknown.length + notRun;
  if (total === 1) return one(outcome, messages);
  // Only what happened is counted: a stop with nothing failed says nothing
  // of failures.
  const parts: string[] = [];
  if (succeeded.length > 0)
    parts.push(messages.label('label.bulk.done', { done: succeeded.length }));
  if (failed.length > 0)
    parts.push(messages.label('label.bulk.failed', { failed: failed.length }));
  if (unknown.length > 0)
    parts.push(
      messages.label('label.bulk.unknown', { unknown: unknown.length }),
    );
  if (notRun > 0)
    parts.push(messages.label('label.bulk.skipped', { skipped: notRun }));
  // A reason is the source's words, or an action's refusal as the host
  // wrote it — a key, said here.
  const reasons = failureReasons([...failed, ...refused]);
  for (const { reason, count } of reasons.slice(0, REASONS))
    parts.push(
      messages.label('label.bulk.reason', {
        reason: messages.say(reason),
        count,
      }),
    );
  const more = reasons.length - REASONS;
  if (more > 0)
    parts.push(messages.label('label.bulk.more-reasons', { count: more }));
  if (outcome.kept.length > 0) parts.push(messages.label('label.bulk.left'));
  return [toneOf(succeeded.length, failed.length, total), parts.join(' · ')];
}

/** One record's command: the record named, and how it came out. */
function one(
  outcome: BulkOutcome,
  messages: MessageFormatters,
): [AlertTone, string] {
  const { succeeded, failed, refused, unknown, skipped } = outcome;
  if (succeeded.length > 0)
    return [
      'info',
      messages.label('label.bulk.one-done', { record: String(succeeded[0]) }),
    ];
  if (failed.length > 0)
    return [
      'error',
      messages.label('label.bulk.one-failed', {
        record: String(failed[0].key),
        reason: messages.say(failed[0].reason),
      }),
    ];
  if (refused.length > 0)
    return [
      'warning',
      messages.label('label.bulk.one-refused', {
        record: String(refused[0].key),
        reason: messages.say(refused[0].reason),
      }),
    ];
  if (unknown.length > 0)
    return [
      'warning',
      messages.label('label.bulk.one-unknown', {
        record: String(unknown[0].key),
      }),
    ];
  return [
    'warning',
    messages.label('label.bulk.one-skipped', { record: String(skipped[0]) }),
  ];
}

/**
 * All done is a note; nothing done and something failed is an error; the
 * rest — some done, or none done and none failed (not sent, or unknown) — a
 * warning.
 */
function toneOf(done: number, failed: number, total: number): AlertTone {
  if (done === total) return 'info';
  if (done === 0 && failed > 0) return 'error';
  return 'warning';
}
