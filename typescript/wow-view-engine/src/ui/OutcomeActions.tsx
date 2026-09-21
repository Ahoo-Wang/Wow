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
import type { Issue } from '../model/index.js';
import type { WriteState } from '../runtime/index.js';
import { Button } from './components/button.js';
import { useViewMessages } from './MessagesProvider.js';
import { TEXT_UI } from './layout.js';
import { cn } from 'cn';

/**
 * Where an outcome is being reported, which is the only thing the two callers
 * disagree about.
 *
 * `view` is the band under the title bar of the view that owns the write, and
 * `row` is the line under a row of the manager, for a write no open view owns.
 * A row is one of many inside a dialog, so it is drawn as a plain line in the
 * smaller type and with the smaller buttons; the view's is a framed band.
 */
export type OutcomeSurface = 'row' | 'view';

/**
 * The ways out this outcome is allowed to offer, as callbacks.
 *
 * An absent one is a button that is not drawn, which is how each caller says
 * what it can actually do: the manager has nowhere to copy a view to, and the
 * view offers a copy or an overwrite only where the user may make one.
 */
export interface OutcomeActionSet {
  /** Conflict: adopt the server's copy and drop the local one. */
  reload?(): void;
  /** Conflict: put the local one over the server's. */
  overwrite?(): void;
  /**
   * Conflict: put the same intent once more. Offered instead of the pair
   * above by a caller that has already settled the conflict and kept what the
   * user meant, so there is no write left to recover — only an intent to
   * repeat. It replaces the pair rather than joining it.
   */
  resubmit?(): void;
  /** Conflict: keep the local one as a copy, leaving the server's alone. */
  copy?(): void;
  /** Unknown: replay the same request and let the server deduplicate. */
  retry?(): void;
  /** Unknown: stop holding it. */
  leave?(): void;
  /** Rejected: acknowledge the refusal so the line can come down. */
  dismiss?(): void;
}

export interface OutcomeActionsProps {
  /** What the write came to; a write that landed has none of these. */
  state: WriteState;
  actions: OutcomeActionSet;
  /**
   * Whether a write is in flight. Commands run one at a time, so a second
   * recovery addresses the same handle twice: the engine refuses it with
   * `view.write.in-flight`, and the line would report that refusal as though
   * the user's click had been the thing at fault.
   */
  pending?: boolean;
  surface?: OutcomeSurface;
}

/**
 * What became of a write, and the way out of it: one line and a row of
 * buttons, shared by the open view's band and the manager's rows.
 *
 * It is one line rather than a block, because what is under it — a result, a
 * list of views — is still the real one, and a banner that pushed it off
 * screen would report a problem by causing a second. Nothing here times out:
 * every outcome that needs a decision keeps its buttons until one is made.
 */
export function OutcomeActions({
  state,
  actions,
  pending = false,
  surface = 'view',
}: OutcomeActionsProps) {
  const messages = useViewMessages();
  const size = surface === 'row' ? 'xs' : 'sm';

  if (state.kind === 'conflict')
    return (
      <OutcomeLine surface={surface} tone="alert">
        <OutcomeText>{messages.label('label.write.conflict')}</OutcomeText>
        {actions.resubmit ? (
          <Button size={size} disabled={pending} onClick={actions.resubmit}>
            {messages.label('label.manage.resubmit')}
          </Button>
        ) : (
          <>
            {actions.reload && (
              <Button
                variant="outline"
                size={size}
                disabled={pending}
                onClick={actions.reload}
              >
                {messages.label(
                  // Taking the server's copy is the same move either way, but
                  // not the same sentence: a row is about the list it sits in,
                  // which comes back at the stored revision.
                  surface === 'row'
                    ? 'label.manage.reload'
                    : 'label.conflict.theirs',
                )}
              </Button>
            )}
            {actions.copy && (
              <Button
                variant="outline"
                size={size}
                disabled={pending}
                onClick={actions.copy}
              >
                {messages.label('label.conflict.copy')}
              </Button>
            )}
            {actions.overwrite && (
              <Button
                size={size}
                disabled={pending}
                onClick={actions.overwrite}
              >
                {messages.label('label.conflict.mine')}
              </Button>
            )}
          </>
        )}
      </OutcomeLine>
    );

  if (state.kind === 'unknown')
    return (
      <OutcomeLine surface={surface} tone="status">
        <OutcomeText>{messages.label('label.write.unknown')}</OutcomeText>
        {actions.retry && (
          <Button size={size} disabled={pending} onClick={actions.retry}>
            {messages.label('label.unknown.retry')}
          </Button>
        )}
        {actions.leave && (
          <Button
            variant="outline"
            size={size}
            disabled={pending}
            onClick={actions.leave}
          >
            {messages.label('label.unknown.leave')}
          </Button>
        )}
      </OutcomeLine>
    );

  // A refusal has nothing to retry, but it is not nothing to settle: the
  // engine may still be holding the write it refused, and the line goes on
  // reporting it until somebody says they have read it. Without a way out,
  // the line sits there for the session and the next command on this key
  // takes its slot — handle and all.
  return (
    <RefusalLine
      issue={state.issue}
      surface={surface}
      disabled={pending}
      onDismiss={actions.dismiss}
    />
  );
}

/**
 * A refusal says why, and offers only the way to have done with it.
 *
 * Used for a `rejected` outcome, and directly by the open view for a command
 * the engine turned down before dispatching it — that one leaves nothing
 * pending, so it comes with no way out and needs none.
 */
export function RefusalLine({
  issue,
  surface = 'view',
  disabled,
  onDismiss,
}: {
  issue: Issue;
  surface?: OutcomeSurface;
  disabled?: boolean;
  /** Absent for a failure the engine holds nothing for. */
  onDismiss?(): void;
}) {
  const messages = useViewMessages();
  return (
    <OutcomeLine surface={surface} tone="alert">
      <OutcomeText>
        {messages.issue(issue)}
        {/* The store's own words, where there is room for them. A manager row
            is one line among many in a dialog and carries the sentence only. */}
        {surface === 'view' &&
          issue.params?.reason !== undefined &&
          ` ${String(issue.params.reason)}`}
      </OutcomeText>
      {onDismiss && (
        <Button
          variant="outline"
          size={surface === 'row' ? 'xs' : 'sm'}
          disabled={disabled}
          onClick={onDismiss}
        >
          {messages.label('label.rejected.dismiss')}
        </Button>
      )}
    </OutcomeLine>
  );
}

/** The one line every outcome is drawn as, in the tone its kind calls for. */
function OutcomeLine({
  surface,
  tone,
  children,
}: {
  surface: OutcomeSurface;
  tone: 'alert' | 'status';
  children: ReactNode;
}) {
  if (surface === 'row')
    return (
      <p
        role={tone}
        className={cn(
          'flex flex-wrap items-center gap-2',
          TEXT_UI,
          tone === 'alert' ? 'text-destructive' : 'text-warning',
        )}
      >
        {children}
      </p>
    );

  return (
    <section
      data-slot="write-outcome"
      role={tone}
      className={
        tone === 'alert'
          ? 'border-destructive text-destructive flex flex-wrap items-center gap-2 rounded-md border p-2 text-sm'
          : 'text-warning border-warning flex flex-wrap items-center gap-2 rounded-md border p-2 text-sm'
      }
    >
      {children}
    </section>
  );
}

/** The sentence, which takes what room the buttons leave. */
function OutcomeText({ children }: { children: ReactNode }) {
  return <span className="min-w-0 flex-1">{children}</span>;
}
