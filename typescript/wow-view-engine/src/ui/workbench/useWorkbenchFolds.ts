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

import { useLayoutEffect, useRef, useState, type RefObject } from 'react';
import type { ViewExpansion } from '../ViewExpansion.js';
import { useViewExpansion } from '../ViewExpansion.js';
import { useSidebarFold } from './useSidebarFold.js';

export interface WorkbenchFoldsOptions {
  /**
   * Whether the workbench offers to fill the screen at all. A page that is
   * already a full-screen view of one thing has nothing to gain from it.
   */
  expandable: boolean;
  /** Whether a view opened and is the page's to draw. */
  open: boolean;
  /** Whether the next view is on its way. */
  opening: boolean;
  /** Controlled page fold; the measurement answers for it when left out. */
  defaultSidebarOpen?: boolean;
  /** Told whenever either fold opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
}

export interface WorkbenchFolds {
  /** The shell's own root, which the fill expands where it already is. */
  surfaceRef: RefObject<HTMLDivElement | null>;
  /** The title bar's fill toggle, which the expansion returns focus to. */
  expandViewRef: RefObject<HTMLButtonElement | null>;
  /** The sidebar's own fold button, focused when expanding took it away. */
  collapseRef: RefObject<HTMLButtonElement | null>;
  /** The collapsed row's unfold button, focused when collapsing took it away. */
  expandRef: RefObject<HTMLButtonElement | null>;
  /** The fold as it is on screen right now. */
  sidebarOpen: boolean;
  /** A press of either fold button; focus follows it. */
  toggleSidebar(next: boolean): void;
  /** Filling the screen, as the title bar's toggle governs it. */
  fill: ViewExpansion;
}

/**
 * The two folds a workbench shell holds: whether the view list is beside the
 * view, and whether the view fills the screen.
 *
 * They are one concern rather than two because the second decides the first.
 * Both belong to this screen at this moment — nothing about them is worth
 * saving, and the leave guard has nothing to ask about them — which is why
 * the shell holds them rather than handing every workbench the same state.
 */
export function useWorkbenchFolds({
  expandable,
  open,
  opening,
  defaultSidebarOpen,
  onSidebarOpenChange,
}: WorkbenchFoldsOptions): WorkbenchFolds {
  const collapseRef = useRef<HTMLButtonElement>(null);
  const expandRef = useRef<HTMLButtonElement>(null);

  // Filling the screen. The surface expands where it already is — the whole
  // point of the decision, since re-parenting it would remount the editor
  // and take the draft with it — so the shell needs a handle on its own root
  // and on the button that governs it, and nothing else moves.
  const surfaceRef = useRef<HTMLDivElement>(null);
  const expandViewRef = useRef<HTMLButtonElement>(null);
  // Off while there is no view: the toggle lives in the title bar, which is
  // not drawn then, and an expansion nobody can see a way out of is a trap.
  // Passing the condition in rather than hiding the button releases one that
  // is already in force — switching to a view that will not open puts the
  // page back instead of stranding it.
  //
  // But a switch that is merely *loading* keeps it (Q7, decided 2026-09-21):
  // filling the screen is a posture of the workspace, not a state of the
  // view that happened to be open — the user asked for room for the rows,
  // and the next view's rows want the same room. The old runtime's release
  // is one render with nothing open and the next one already on its way;
  // ending the fill there made every switch a way out nobody had taken.
  // The title bar is back the moment the view is, and Escape works in
  // between.
  const expansion = useViewExpansion(
    surfaceRef,
    expandViewRef,
    expandable && (open || opening),
  );
  // Filling the screen is a screen of its own, and the list is folded for
  // it: filling is a gesture about the *result* — "give the rows the room" —
  // and a 224px column of navigation is the first thing that is not the
  // result. On a phone it is worse than that, because below `md` the list is
  // not even beside the rows: it stacks above them and the table starts
  // 204px down, which is the fewest rows a filled screen has ever bought.
  //
  // So the fold is two answers rather than one, and which is in force is
  // **derived** from whether the screen is filled. Nothing is remembered and
  // nothing is put back: leaving reads the page's answer again because it is
  // still there. The alternative — one boolean, folded and restored by an
  // effect watching the expansion — is a cascading render for a value the
  // render can simply work out, and it was also two rules pretending to be
  // one.
  //
  // `inFill` starts folded on every fill, which is what `fill.toggle` below
  // is for: pressing that button is the only way *into* a fill (Escape and
  // switching views only end one), so it is the one place the previous
  // fill's answer has to be let go.
  const expanded = expansion.expanded;
  const [inFill, setInFill] = useState(false);
  // The fold as the *page* has it — the one a host sets and is told about,
  // and the one the surface's own width answers for until a press settles
  // it. What is actually on screen is derived from it, because a screen
  // filled by the view has a fold of its own.
  const page = useSidebarFold(
    surfaceRef,
    defaultSidebarOpen,
    onSidebarOpenChange,
  );
  const sidebarOpen = expanded ? inFill : page.open;
  const fill: ViewExpansion = {
    expanded,
    toggle: () => {
      setInFill(false);
      expansion.toggle();
    },
  };

  // The state the last run saw, not "has this run before". StrictMode does
  // setup, cleanup, setup on mount, so a "first run" flag is already spent
  // by the second setup and the effect would take focus off the host's page
  // on arrival — which is the one thing it must never do. Comparing the
  // value answers the question actually being asked: did this change?
  const shown = useRef(sidebarOpen);
  // And *why* it changed. The fold now moves for three reasons — a button,
  // the room the surface has, the screen being filled — and only the first
  // is a press. Focus follows a press because the press took its own button
  // off the screen; it must not follow the other two, which would take the
  // keyboard out of whatever the user was doing — on arrival, on the way
  // into a filled screen, or in the middle of dragging a window.
  const pressed = useRef(false);
  useLayoutEffect(() => {
    if (shown.current === sidebarOpen) return;
    shown.current = sidebarOpen;
    if (!pressed.current) return;
    pressed.current = false;
    // Collapsing and expanding each take away the button that was just
    // pressed, so focus moves to the one that undoes it. Both are held as
    // refs rather than found again by selector: the buttons live in two
    // different components, and a shell that went looking for one in the
    // document would be reaching past both of them.
    (sidebarOpen ? collapseRef : expandRef).current?.focus();
  }, [sidebarOpen]);

  return {
    surfaceRef,
    expandViewRef,
    collapseRef,
    expandRef,
    sidebarOpen,
    toggleSidebar(next) {
      pressed.current = true;
      // While the screen is filled the answer is the fill's, and it lasts as
      // long as the fill does: a user who wants the list back inside one gets
      // it, and the next fill still starts without it. That press says nothing
      // about the page's own fold, so it does not end the measurement either —
      // which is why the two folds report to the host separately, each about
      // the answer it holds.
      if (expanded) {
        setInFill(next);
        onSidebarOpenChange?.(next);
      } else page.set(next);
    },
    fill,
  };
}
