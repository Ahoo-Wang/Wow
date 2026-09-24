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
import { cn } from 'cn';
import { ChevronDownIcon, SlidersHorizontalIcon } from 'lucide-react';
import { Button } from './components/button.js';
import { IconTooltip } from './IconButton.js';
import { PendingDot } from './PendingDot.js';
import { ButtonGroup } from './components/button-group.js';
import {
  DropdownMenu,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { useViewMessages } from './MessagesProvider.js';
import { DropdownMenuContent } from './popups.js';
import { TEXT_UI } from './layout.js';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from './components/collapsible.js';

export interface EditorFoldProps {
  open: boolean;
  /**
   * Base UI hands a reason along with the new state; this package has one
   * question — is the editor open — and the workbench answers it the same
   * way whoever asked.
   */
  onOpenChange(open: boolean): void;
  children: ReactNode;
}

/**
 * The one fold the handle and the band are two halves of.
 *
 * The handle sits in the title bar and the band below the status line, with
 * other blocks between them, so they cannot be nested one in the other —
 * which is why the pair used to be wired by hand: the open state held above
 * both, `aria-expanded` and `aria-controls` written out at the trigger, the
 * panel unmounted with an early `return null`. That is a disclosure, and
 * `Collapsible` is the disclosure this project already has: one root around
 * both ends gives the trigger its ARIA pair, drops `aria-controls` while the
 * panel is away, and unmounts the panel itself.
 *
 * The root draws nothing. `contents` keeps it out of the layout entirely, so
 * the blocks it spans stay direct flex children of the column they were in
 * and the spacing ruler is untouched.
 */
export function EditorFold({ open, onOpenChange, children }: EditorFoldProps) {
  return (
    <Collapsible
      className="contents"
      open={open}
      onOpenChange={next => onOpenChange(next)}
    >
      {children}
    </Collapsible>
  );
}

export interface EditorBandProps {
  /** The id the header's toggle points `aria-controls` at. */
  id: string;
  children: ReactNode;
  className?: string;
}

/**
 * The fold the editor of a view lives in.
 *
 * The result is the point of a view, so the editor gives it the room: a saved
 * view opens folded, a new one open, and the state is the opening's alone —
 * nothing about how a view is looked at is worth persisting.
 *
 * The band draws the editor and nothing else. What opens and closes it sits
 * in the title bar ({@link EditorBandToggle}), with the other controls for
 * *how this view is being looked at*, rather than on a row of its own above
 * the editor: a fold whose handle takes a line is a fold that saved nothing.
 * Both ends live under one {@link EditorFold}.
 *
 * It is a shell and nothing else. It holds no idea of conditions, panels or
 * aggregations, which is what lets the three workbenches share it while their
 * editors stay their own.
 */
export function EditorBand({ id, children, className }: EditorBandProps) {
  return (
    // Unmounted rather than hidden, which is `Collapsible.Panel`'s own
    // default: the editor's inputs are the view's draft, and a folded band
    // must not keep a focusable control on the page. Nothing animates — the
    // fold is instant here as it is in `StatusStrip`, so there is no motion
    // for `prefers-reduced-motion` to have an opinion about.
    <CollapsibleContent
      id={id}
      data-slot="editor-band"
      // No landmark of its own. The editor inside already names itself — a
      // filter panel is a `section` called "Filter" — and a band wrapped
      // round it under the same name is a second landmark with the same
      // name, which is a thing a screen reader cannot tell apart from the
      // first. The toggle reaches it by `aria-controls` and needs no role.
      className={cn('flex flex-col', className)}
    >
      {children}
    </CollapsibleContent>
  );
}

export interface EditorBandToggleProps {
  label: string;
  /** The ways of editing this editor offers, under a chevron beside it. */
  modes?: ReactNode;
  /** How many nodes say something other than what ran. */
  pending: number;
}

/**
 * The handle of the editor's fold, in the title bar.
 *
 * It carries the one credential the folded editor cannot carry for itself:
 * the dot and the count for "edited, not applied". Everything else about it
 * is a button — which is why the mode menu hangs off it as a second button
 * in one group rather than as an item inside a menu of its own.
 *
 * It is the {@link EditorFold}'s trigger, so `aria-expanded`, the
 * `aria-controls` that points at the band only while the band is there, and
 * the click that flips the state all come from the primitive.
 */
export function EditorBandToggle({
  label,
  modes,
  pending,
}: EditorBandToggleProps) {
  const messages = useViewMessages();
  const pendingLabel =
    pending > 0
      ? messages.label('label.editor.pending', { count: pending })
      : null;
  // The toggle says what it opens and nothing about how: "Filter", not
  // "Filter · Simple" (2026-09-23 visual review). The mode is a setting of
  // the editor, changed in the menu beside it and shown there as the
  // checked item; on the button it was a second word competing with the
  // one credential a folded editor cannot carry any other way — the count
  // of what is edited and not applied (D17-6, D2).
  //
  // The count is joined into the name rather than left to the content: the
  // label and the count are two inline spans, and a name computed from them
  // runs them together — "Filter2 not applied". Joined, what is heard is
  // what is seen, in the same order (WCAG 2.5.3).
  const name = pendingLabel === null ? undefined : `${label} · ${pendingLabel}`;
  return (
    <ButtonGroup data-slot="editor-toggle">
      <CollapsibleTrigger
        render={<Button variant="outline" size="sm" />}
        aria-label={name}
      >
        <SlidersHorizontalIcon data-icon="inline-start" />
        <span className="truncate">{label}</span>
        {pendingLabel !== null && (
          <span className={cn('flex items-center gap-1 font-normal', TEXT_UI)}>
            {/* The same dot the pills wear. It names nothing here: the
                count right beside it is the wording. */}
            <PendingDot />
            {pendingLabel}
          </span>
        )}
      </CollapsibleTrigger>

      {modes && (
        <DropdownMenu>
          <IconTooltip
            label={messages.label('label.workbench.editor-modes')}
            render={
              <DropdownMenuTrigger
                render={<Button variant="outline" size="sm" />}
              />
            }
          >
            <ChevronDownIcon />
          </IconTooltip>
          <DropdownMenuContent align="end">{modes}</DropdownMenuContent>
        </DropdownMenu>
      )}
    </ButtonGroup>
  );
}
