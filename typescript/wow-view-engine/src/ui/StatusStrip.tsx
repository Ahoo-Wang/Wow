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

import { useState, type ReactNode } from 'react';
import { cn } from 'cn';
import type { Issue } from '../model/index.js';
import { isForbiddenQuery } from '../runtime/queryFailure.js';
import { LineAlert, type AlertTone } from './alerts.js';
import { AlertAction, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from './components/collapsible.js';
import { useViewMessages } from './MessagesProvider.js';
import { TEXT_UI } from './layout.js';
import { useKindWord } from './kinds.js';

export interface StatusStripProps {
  /** How loud it is: the colour, the icon and the role come with it. */
  tone: AlertTone;
  /** The whole of it in one sentence; the strip is one line tall. */
  title: string;
  /**
   * The findings behind the sentence. They fold away behind a count, because
   * the result below is what the user came for and a stack of sentences
   * pushes it off the screen.
   */
  details?: readonly string[];
  /** What to do about it — a retry, usually — at the end of the line. */
  action?: ReactNode;
  className?: string;
}

/**
 * Anything the view has to say that is not a row: a warning, a query that
 * failed, a config that needs fixing before it runs.
 *
 * It is one line by design. A block-sized alert pushes the result down the
 * page — and the result is still there, since a failed query never clears the
 * table — so a finding that blocks nothing must not hide what does not need
 * hiding. That is a decision about height, not about the component: this is
 * the registry's `Alert` in the line-high variant (`ui/alerts.tsx`), which is
 * where the tone's colour, its icon and its role are decided for every
 * callout at once — an error is announced as an alert, everything else as a
 * status, so a screen reader interrupts only for what stopped the view.
 */
export function StatusStrip({
  tone,
  title,
  details,
  action,
  className,
}: StatusStripProps) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  // Read once, so the count the toggle names and the list it opens cannot
  // disagree.
  const lines = details ?? [];

  return (
    <LineAlert tone={tone} data-slot="status-strip" className={className}>
      <AlertTitle>{title}</AlertTitle>
      {lines.length > 0 && (
        // `display: contents`: the fold is two things in two places — a
        // toggle that belongs at the end of the sentence, and a list that
        // belongs under it — so the box it would otherwise draw around them
        // has nothing to enclose. Without this the findings open *inside*
        // the line, in whatever width the toggle happens to occupy.
        <Collapsible
          open={open}
          onOpenChange={next => setOpen(next)}
          className="contents"
        >
          <CollapsibleTrigger render={<Button variant="ghost" size="xs" />}>
            {/* Once they are open, the findings are on screen: "{count} more"
                would point at them and claim they are still withheld. The
                open state folds them away again, so it says so. */}
            {open
              ? messages.label('label.status.less')
              : messages.label('label.status.more', { count: lines.length })}
          </CollapsibleTrigger>
          {/* A whole row of its own under the sentence, which is what the
              line's wrap is for. */}
          <CollapsibleContent className="w-full">
            <ul className={cn('list-disc pl-6', TEXT_UI)}>
              {lines.map((line, index) => (
                // Two findings can read the same after `dedupeIssues` has had
                // its say — a caller may not have used it — so the index is
                // the only key that is stable across a re-render.
                <li key={index}>{line}</li>
              ))}
            </ul>
          </CollapsibleContent>
        </Collapsible>
      )}
      {action && <AlertAction>{action}</AlertAction>}
    </LineAlert>
  );
}

/**
 * One of each sentence.
 *
 * A dashboard validates a global condition once as its own and once more per
 * panel it maps onto, and two leaves can trip the same rule; the code and the
 * params are the sentence, and the same sentence twice tells nobody anything
 * more. A strip counts what it shows, so the count has to be of sentences.
 */
export function dedupeIssues(issues: readonly Issue[]): Issue[] {
  const kept: Issue[] = [];
  for (const found of issues)
    if (!kept.some(said => sameWording(said, found))) kept.push(found);
  return kept;
}

function sameWording(a: Issue, b: Issue): boolean {
  if (a.code !== b.code) return false;
  const left = a.params ?? {};
  const right = b.params ?? {};
  const keys = Object.keys(left);
  return (
    keys.length === Object.keys(right).length &&
    keys.every(key => left[key] === right[key])
  );
}

export interface IssueStripProps {
  /** Every finding of the view; this picks the ones it is about. */
  issues: readonly Issue[];
  className?: string;
}

/**
 * The findings that do not block, said once and in one line.
 *
 * A warning stops nothing — the query ran, save is allowed — but the view is
 * not quite what its author saved, and a finding nobody can see was never
 * reported. One warning is its own sentence; several collapse behind a count,
 * because the result underneath is what the user came for.
 */
export function WarningStrip({ issues, className }: IssueStripProps) {
  const messages = useViewMessages();
  const warnings = dedupeIssues(
    issues.filter(found => found.severity === 'warning'),
  );
  if (warnings.length === 0) return null;
  const sentences = warnings.map(found => messages.issue(found));
  return (
    <StatusStrip
      tone="warning"
      // One finding needs no count and no fold: the line is the finding.
      title={
        sentences.length === 1
          ? sentences[0]
          : messages.label('label.view.warnings-count', {
              count: sentences.length,
            })
      }
      details={sentences.length === 1 ? undefined : sentences}
      className={className}
    />
  );
}

/**
 * What is true about the answer and not wrong with it, as a quiet line: the
 * groups a view's own limit left out, when every row shown is whole
 * (`note`, `IssueSeverity`). The info tone rather than a warning's — a
 * screen that warns about what it was asked to do teaches its reader to
 * stop reading warnings.
 */
export function NoteStrip({ issues, className }: IssueStripProps) {
  const messages = useViewMessages();
  const notes = dedupeIssues(issues.filter(found => found.severity === 'note'));
  if (notes.length === 0) return null;
  const sentences = notes.map(found => messages.issue(found));
  return (
    <StatusStrip
      tone="info"
      title={
        sentences.length === 1
          ? sentences[0]
          : messages.label('label.view.warnings-count', {
              count: sentences.length,
            })
      }
      details={sentences.length === 1 ? undefined : sentences}
      className={className}
    />
  );
}

export interface QueryStripProps {
  /** The query's own failure; nothing is said while there is none. */
  error: Issue | null | undefined;
  /**
   * Whether a result from before the failure is still on screen. It changes
   * what the line means — an empty frame that failed says one thing, rows
   * that are one refresh out of date another — and only the caller knows.
   */
  stale: boolean;
  /** Given, the line ends in a retry; an embed that offers none leaves it out. */
  onRetry?(): void;
  className?: string;
}

/**
 * A query that did not come back.
 *
 * The rows below it are the last ones that did: a failure never clears the
 * result, because a view that empties itself on a dropped connection has lost
 * what the user was reading for no reason the user caused.
 */
export function QueryStrip({
  error,
  stale,
  onRetry,
  className,
}: QueryStripProps) {
  const messages = useViewMessages();
  if (!error) return null;
  const failed = messages.issue(error);
  // The source refused the reader, not the query: a permission, which
  // asking again does not change — so it is said as one, in the warning
  // tone, and offers no retry to press into the same refusal.
  const forbidden = isForbiddenQuery(error);
  return (
    <StatusStrip
      tone={forbidden ? 'warning' : 'error'}
      // The rows on screen are not the answer to what was asked, and that is
      // the one sentence the reader needs beside the failure — so it is part
      // of the line itself, never folded behind a 「还有 1 项」 toggle where
      // the rows would be read as current until somebody opened it.
      title={
        stale ? messages.label('label.query.stale', { error: failed }) : failed
      }
      className={className}
      action={
        onRetry &&
        !forbidden && (
          // No colour of its own: `outline` names no text colour, so the
          // button reads in the tone of the alert around it — which is what
          // the `text-current` this used to carry was spelling out by hand.
          <Button variant="outline" size="xs" onClick={onRetry}>
            {messages.label('label.query.retry')}
          </Button>
        )
      }
    />
  );
}

export interface ErrorStripProps extends IssueStripProps {
  /** What the line says; a dashboard names itself rather than "this view". */
  title?: string;
  /**
   * The way out of it, at the end of the line. A finding that stops the
   * view is worth nothing to a reader who cannot see where to go and fix
   * it, and the surface is the only one that knows where that is — a record
   * view's is the column settings, which the toolbar would have opened if
   * there were a result for a toolbar to sit over.
   */
  action?: ReactNode;
}

/**
 * What stops the view from running.
 *
 * It is a line rather than a banner because the last successful result is
 * still on screen underneath — a config that stopped running did not stop
 * being worth reading — and a banner would push it away. It shows the issues
 * it is handed: a surface with a condition editor hands over `filter.unmarked`,
 * the ones no pill carries; an embed, which has no editor and so marks nothing
 * anywhere, hands over all of them.
 */
export function ErrorStrip({
  issues,
  title,
  action,
  className,
}: ErrorStripProps) {
  const messages = useViewMessages();
  const word = useKindWord();
  const errors = dedupeIssues(
    issues.filter(found => found.severity === 'error'),
  );
  if (errors.length === 0) return null;
  const sentences = errors.map(found => messages.issue(found));
  // One finding is its own sentence, the same way a single warning is.
  // "This view needs fixing" over a fold reading "1 more" was a heading
  // with one thing under it, and the one thing it hid was the only
  // sentence that said *what* to fix — so the reader had to press a button
  // to be told, and nothing at all was gained by the press (F-14).
  const alone = sentences.length === 1;
  return (
    <StatusStrip
      tone="error"
      title={
        alone
          ? sentences[0]
          : (title ?? messages.label(word('label.view.needs-fixing')))
      }
      details={alone ? undefined : sentences}
      className={className}
      action={action}
    />
  );
}
