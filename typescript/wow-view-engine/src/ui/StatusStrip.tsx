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
import { CircleAlertIcon, InfoIcon, TriangleAlertIcon } from 'lucide-react';
import type { Issue } from '../model/index.js';
import { Button, buttonVariants } from './components/button.js';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from './components/collapsible.js';
import { useViewMessages } from './MessagesProvider.js';

/** How loud the strip is, which decides its colour and how it is announced. */
export type StatusTone = 'warning' | 'error' | 'info';

export interface StatusStripProps {
  tone: StatusTone;
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

const TONE: Record<StatusTone, string> = {
  // The theme's own `warning` token; `destructive` has one of its own.
  warning: 'border-warning text-warning',
  error: 'border-destructive text-destructive',
  info: 'border-border text-muted-foreground',
};

const TONE_ICON: Record<StatusTone, typeof InfoIcon> = {
  warning: TriangleAlertIcon,
  error: CircleAlertIcon,
  info: InfoIcon,
};

/**
 * Anything the view has to say that is not a row: a warning, a query that
 * failed, a config that needs fixing before it runs.
 *
 * It is one line by design. A block-sized alert pushes the result down the
 * page — and the result is still there, since a failed query never clears the
 * table — so a finding that blocks nothing must not hide what does not need
 * hiding. An error is announced as an alert, everything else as a status, so
 * a screen reader interrupts only for what stopped the view.
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
  const Icon = TONE_ICON[tone];
  // Read once, so the count the toggle names and the list it opens cannot
  // disagree.
  const lines = details ?? [];

  return (
    <Collapsible
      data-slot="status-strip"
      data-tone={tone}
      role={tone === 'error' ? 'alert' : 'status'}
      open={open}
      onOpenChange={next => setOpen(next)}
      // The border sits on the fold rather than on the row inside it, so the
      // expanded findings stay within the same frame as the sentence.
      className={cn(
        'rounded-md border px-2 py-1 text-sm',
        TONE[tone],
        className,
      )}
    >
      <div className="flex items-center gap-2">
        <Icon aria-hidden="true" className="size-4 shrink-0" />
        <span className="min-w-0 flex-1">{title}</span>
        {lines.length > 0 && (
          <CollapsibleTrigger
            className={cn(
              buttonVariants({ variant: 'ghost', size: 'xs' }),
              'shrink-0 text-current',
            )}
          >
            {/* Once they are open, the findings are on screen: "{count} more"
                would point at them and claim they are still withheld. The
                open state folds them away again, so it says so. */}
            {open
              ? messages.label('label.status.less')
              : messages.label('label.status.more', { count: lines.length })}
          </CollapsibleTrigger>
        )}
        {action && <div className="shrink-0">{action}</div>}
      </div>
      {lines.length > 0 && (
        <CollapsibleContent>
          <ul className="mt-1 list-disc pl-6 text-xs">
            {lines.map((line, index) => (
              // Two findings can read the same after `dedupeIssues` has had
              // its say — a caller may not have used it — so the index is the
              // only key that is stable across a re-render.
              <li key={index}>{line}</li>
            ))}
          </ul>
        </CollapsibleContent>
      )}
    </Collapsible>
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
  return (
    <StatusStrip
      tone="error"
      title={messages.issue(error)}
      details={stale ? [messages.label('label.query.stale')] : undefined}
      className={className}
      action={
        onRetry && (
          <Button
            variant="outline"
            size="xs"
            className="text-current"
            onClick={onRetry}
          >
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
export function ErrorStrip({ issues, title, className }: ErrorStripProps) {
  const messages = useViewMessages();
  const errors = dedupeIssues(
    issues.filter(found => found.severity === 'error'),
  );
  if (errors.length === 0) return null;
  return (
    <StatusStrip
      tone="error"
      title={title ?? messages.label('label.view.needs-fixing')}
      details={errors.map(found => messages.issue(found))}
      className={className}
    />
  );
}
