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
import { Maximize2Icon, Minimize2Icon } from 'lucide-react';
import { Button } from './components/button.js';
import { IconButton } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';

/** Whether a surface fills the screen, and the one way to change it. */
export interface ViewExpansion {
  expanded: boolean;
  toggle(): void;
}

/** One surface that is expanded right now, and the control that opened it. */
interface Expanded {
  element: HTMLElement;
  toggleRef: RefObject<HTMLElement | null>;
}

/**
 * What one document owes while surfaces are expanded on it.
 *
 * Two can be open at once — a dashboard panel's workbench and an embedded
 * view beside it — and both want the background still. `open` holds them,
 * which answers two questions with one structure: whether the page is still
 * owed its scrolling (any at all), and which expansion the Escape key
 * belongs to (`inFront`).
 *
 * `scroller` is the element that actually scrolls this document, and the two
 * `saved` entries are its `overflow-x` and `overflow-y` as they were, each
 * with its own priority: a host that wrote `overflow-y: auto !important`
 * meant it, and handing back a plain `auto` — or handing back a shorthand
 * that overwrites an axis the host never set — would be a different page
 * from the one we borrowed.
 */
interface DocumentLock {
  open: Expanded[];
  scroller: HTMLElement;
  saved: { name: string; value: string; priority: string }[];
}

const scrollLocks = new WeakMap<Document, DocumentLock>();

/**
 * The two axes, locked and restored one at a time.
 *
 * Reading the `overflow` shorthand back cannot describe a host that set only
 * `overflow-y`, or gave the axes different values or different priorities,
 * and writing the shorthand would replace both longhands — so restoring from
 * it would lose whatever the host had on the other axis for good.
 */
const LOCKED = ['overflow-x', 'overflow-y'] as const;

/**
 * What actually scrolls a document: `<html>` in standards mode, `<body>` in
 * quirks mode, which is what `scrollingElement` answers.
 *
 * Body's `overflow` reaches the viewport only while `html`'s is `visible`, so
 * a host that set `html { overflow: auto }` — an ordinary way to own page
 * scrolling — goes on scrolling behind a surface that locked the body alone.
 */
function scrollerOf(doc: Document): HTMLElement {
  return (doc.scrollingElement as HTMLElement | null) ?? doc.documentElement;
}

/**
 * The expanded surface a user is actually looking at.
 *
 * Every expanded surface paints in the same layer (see the `z-index` note in
 * `styles.css`), so what covers what is decided by document order and not by
 * who was expanded first. Answering Escape from the most recently expanded
 * one would collapse the surface *underneath* whenever a host expanded a
 * later-in-document surface first — so the one in front is read off the
 * document rather than remembered.
 */
function inFront(open: Expanded[]): Expanded | undefined {
  let front: Expanded | undefined;
  for (const each of open) {
    const after =
      front === undefined ||
      Boolean(
        front.element.compareDocumentPosition(each.element) &
        Node.DOCUMENT_POSITION_FOLLOWING,
      );
    if (after) front = each;
  }
  return front;
}

/**
 * The control of an expanded surface that a user can actually see.
 *
 * A toggle inside the surface is on screen with the rest of it. A toggle
 * *outside* it — a host's own button beside an `EmbeddedView`, which is the
 * only shape an embed has — is underneath a surface filling the screen, and
 * what the user can see instead is the exit that surface grew for exactly
 * that case. Handing focus to the covered button would drop a keyboard user
 * into content nobody can see, which is the thing this is meant to prevent.
 */
function reachable({ element, toggleRef }: Expanded): HTMLElement | null {
  const toggle = toggleRef.current;
  if (toggle && element.contains(toggle)) return toggle;
  return (
    element.querySelector<HTMLElement>(
      ':scope > [data-slot="view-exit"]:not([hidden])',
    ) ?? toggle
  );
}

/**
 * Anything that owns the Escape key while it is open. A dialog, a menu and a
 * listbox all close on Escape, and the one in front has the first claim: a
 * user pressing it over an open field picker means "close the picker", never
 * "put the whole view back in the page".
 *
 * `dialog[open]` is in the list because a native `<dialog>` carries its role
 * implicitly — the ARIA role is not an attribute, so `[role="dialog"]` never
 * matches one. A document listener runs before the browser's own default
 * close, so without this the first Escape over a host's `<dialog>` would shut
 * the dialog *and* put the whole view back in the page.
 */
const ABOVE =
  '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"], dialog[open]';

/** The geometry the stylesheet reads; absent means "the viewport itself". */
const FITTED = [
  '--_fve-expanded-x',
  '--_fve-expanded-y',
  '--_fve-expanded-w',
  '--_fve-expanded-h',
] as const;

/**
 * The surface an element belongs to.
 *
 * The stylesheet expands `.fve-root` and nothing else — every rule in this
 * package is pinned to that boundary — so pointing this at something inside
 * a view has to mean "expand the view it is in", not "write an attribute
 * that paints nothing while the page sits locked behind it".
 */
function surfaceOf(node: HTMLElement | null): HTMLElement | null {
  return node?.closest<HTMLElement>('.fve-root') ?? node;
}

/**
 * Put the surface on the viewport, whatever its ancestors did.
 *
 * `position: fixed` resolves against the viewport only while no ancestor has
 * made itself the containing block, and `transform`, `filter`, `perspective`,
 * `backdrop-filter`, `will-change`, `contain` and `container-type` all do —
 * which is to say every animated wrapper and most grid shells. Enumerating
 * them is a list that goes stale with the next CSS module, so this measures
 * the box the browser actually gave us instead: if it is not the viewport,
 * the difference *is* the correction, and one pass is exact.
 *
 * A *scaling* ancestor needs one more pass. `getBoundingClientRect()` already
 * reports screen pixels, but the four properties below are written in the
 * element's own coordinates, where one local pixel is `scale` screen pixels —
 * so a first pass that hands back the measured difference verbatim lands
 * short by exactly that ratio, in both the offset and the size. Rather than
 * enumerate the properties that scale (`transform`, and `scale` and `zoom`,
 * which are not part of it), the correction is measured the same way the
 * offset is: write the naive values, measure what the browser made of them,
 * and the ratio between what was asked for and what appeared *is* the scale.
 * The second pass costs one more layout read, and only in the case that
 * already needed a correction.
 *
 * It corrects the geometry, which is what a host notices. Three things stay
 * out of reach, and no rendering that stays in place can escape any of them:
 * an ancestor that also *clips* (`overflow: hidden`, `contain: paint`) still
 * clips, one that raises its own stacking context above the portalled popups
 * still covers them, and one that *rotates or skews* has no axis-aligned box
 * that covers the viewport at all — an inset and a size cannot express a
 * turn. Leaving in place is the decision this whole feature is built on.
 */
function fitToViewport(element: HTMLElement, view: Window): void {
  const style = element.style;
  for (const name of FITTED) style.removeProperty(name);
  const box = element.getBoundingClientRect();
  // No layout engine (jsdom) has nothing to correct, and neither has a box
  // that already covers the viewport.
  if (box.width === 0 && box.height === 0) return;
  const off = (a: number, b: number) => Math.abs(a - b) >= 0.5;
  if (
    !off(box.left, 0) &&
    !off(box.top, 0) &&
    !off(box.width, view.innerWidth) &&
    !off(box.height, view.innerHeight)
  )
    return;
  const write = (x: number, y: number, w: number, h: number) => {
    style.setProperty('--_fve-expanded-x', `${x}px`);
    style.setProperty('--_fve-expanded-y', `${y}px`);
    style.setProperty('--_fve-expanded-w', `${w}px`);
    style.setProperty('--_fve-expanded-h', `${h}px`);
  };
  write(-box.left, -box.top, view.innerWidth, view.innerHeight);
  // What a viewport's worth of local pixels turned into on screen.
  const asked = element.getBoundingClientRect();
  const sx = asked.width / view.innerWidth;
  const sy = asked.height / view.innerHeight;
  if (!Number.isFinite(sx) || !Number.isFinite(sy) || sx <= 0 || sy <= 0)
    return;
  // A thousandth is below anything a screen can show, and leaving the first
  // pass alone keeps the un-scaled case — every case there was before — at
  // exactly the numbers it had.
  if (Math.abs(sx - 1) < 1e-3 && Math.abs(sy - 1) < 1e-3) return;
  write(
    -box.left / sx,
    -box.top / sy,
    view.innerWidth / sx,
    view.innerHeight / sy,
  );
}

/**
 * Let a surface fill the screen **where it stands**.
 *
 * Expanding in place rather than portalling is the whole decision. A portal
 * re-parents the subtree, React recreates every node under it, and focus, the
 * scroll position and a half-finished IME composition go with them — and the
 * surface loses the host ancestor whose `.dark` class it was following.
 * Nothing moves instead: the `.fve-root` at or above the element the ref
 * points at is marked `data-view-expanded`, and one unlayered rule in
 * `styles.css` pins it to the viewport.
 *
 * The top layer — `requestFullscreen()`, or `popover="manual"` — would solve
 * the containing-block problem outright and was rejected for one reason:
 * every popup this package opens is portalled to `document.body`, so the
 * field picker, the filter menus, the save split button and the view switcher
 * would all render *behind* the expanded view, or under fullscreen not render
 * at all. A view you cannot open a menu in is not an expanded view. In the
 * normal layer the promise is kept by the popups themselves: `popups.tsx`
 * puts a layer of their own on every one, above the level this surface takes
 * — see the `z-index` note in `styles.css`.
 *
 * It is **not a modal**, and says so by omission: no `aria-modal`, no focus
 * trap, no `inert` anywhere. Nothing is being asked and there is nothing to
 * answer — this is the same content it was a moment ago, in the same place,
 * with the same focus. Claiming modality would tell a screen reader that the
 * host's page is unavailable, which is a promise about content we are in no
 * position to make: the expanded element is still a descendant of the host's
 * own DOM, and making "everything else" inert would mean walking up and
 * inerting a sibling at every level — which is a portal's job, and a portal
 * is what costs the draft.
 *
 * What it does borrow from a modal is the one thing a full-viewport surface
 * genuinely needs: the background does not scroll underneath it, because a
 * scroll whose effect nobody can see is a scroll position silently lost. And
 * Escape closes it, unless the key belongs to something in front.
 *
 * @param target the surface to expand, or anything inside one
 * @param toggleRef the control that opened it, which focus goes back to
 * @param enabled false while there is nothing to expand, which also ends an
 *   expansion already in force rather than holding it for later
 */
export function useViewExpansion(
  target: RefObject<HTMLElement | null>,
  toggleRef: RefObject<HTMLElement | null>,
  enabled = true,
): ViewExpansion {
  const [expanded, setExpanded] = useState(false);
  // Losing the control ends the expansion rather than parking it: reporting
  // `expanded: false` while still holding it would fill the screen again the
  // moment the control came back, a view taking over the page with nobody
  // having asked for it. Adjusted here, while rendering, rather than in an
  // effect — React drops this render and redoes it before anything is shown,
  // so the surface is never briefly expanded with no way out of it.
  const [was, setWas] = useState(enabled);
  if (was !== enabled) {
    setWas(enabled);
    if (!enabled) setExpanded(false);
  }
  const on = enabled && expanded;

  // The element the last run of the effect below settled on, so that the one
  // watching the document can tell a change worth re-running it for from the
  // thousand ordinary mutations a result makes while it renders.
  const held = useRef<HTMLElement | null>(null);

  // What a `RefObject` cannot say on its own.
  //
  // React writes and clears `target.current` silently, and the effect below
  // has no other reason to run again — so a host that toggles before the
  // content it points at has mounted would leave the API reporting
  // `expanded: true` with no element carrying the attributes and no lock on
  // the page, and a surface that unmounted under a live expansion would
  // leave the page locked with nothing on it. Neither is a render of this
  // component, so the document itself is what is watched, and only while
  // something is expanded. A host that silently re-points a live `RefObject`
  // at a surface already on screen changes nothing here to see; it should
  // collapse and expand again.
  const [changed, setChanged] = useState(0);
  useLayoutEffect(() => {
    if (!on) return;
    const doc = toggleRef.current?.ownerDocument ?? globalThis.document;
    const Observer = doc?.defaultView?.MutationObserver;
    if (!doc || !Observer) return;
    const observer = new Observer(() => {
      if (surfaceOf(target.current) !== held.current) setChanged(n => n + 1);
    });
    observer.observe(doc, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
    };
  }, [on, target, toggleRef]);

  useLayoutEffect(() => {
    const element = on ? surfaceOf(target.current) : null;
    held.current = element;
    if (!element) return;
    // The element's own document, not the global one: an expanded surface
    // inside an iframe locks the frame it is in, and the two stacks never
    // meet.
    const doc = element.ownerDocument;
    const view = doc.defaultView;
    const scroller = scrollerOf(doc);
    const lock = scrollLocks.get(doc) ?? {
      open: [],
      scroller,
      saved: LOCKED.map(name => ({
        name,
        value: scroller.style.getPropertyValue(name),
        priority: scroller.style.getPropertyPriority(name),
      })),
    };
    if (lock.open.length === 0) {
      scrollLocks.set(doc, lock);
      // Important, because the page we are borrowing may well be holding its
      // own overflow that way — a host stylesheet's `!important` outranks a
      // plain inline declaration, and the background would go on scrolling
      // under a surface that covers it.
      for (const { name } of lock.saved)
        lock.scroller.style.setProperty(name, 'hidden', 'important');
    }
    // This expansion's identity among the open ones.
    const handle: Expanded = { element, toggleRef };
    lock.open.push(handle);
    element.setAttribute('data-view-expanded', 'true');

    // The way out, for a surface whose control is not inside it. A host that
    // put the control in its own chrome — which is the only shape an
    // `EmbeddedView` has — just had that control covered by a surface filling
    // the screen, and a touch device has no Escape key to fall back on.
    const detached = !element.contains(toggleRef.current);
    const exit = element.querySelector<HTMLElement>(
      ':scope > [data-slot="view-exit"]',
    );
    if (exit) exit.hidden = !detached;

    const fit = view ? () => fitToViewport(element, view) : null;
    fit?.();
    view?.addEventListener('resize', fit!);

    /**
     * Put this surface back, and leave focus somewhere it can be seen.
     *
     * Not simply this surface's own toggle: with another surface still
     * expanded over it, that toggle is back in page layout and behind it,
     * and the keyboard user's next Tab would walk content nobody can see. So
     * focus goes to the control of whatever is still in front — the one of
     * its controls that is *on* it, since the surface in front covers a
     * host's own button just as surely as it covers this one — and falls
     * back to this surface's own toggle only when nothing is in front, which
     * is when that toggle is back on screen.
     */
    function collapse() {
      setExpanded(false);
      const at = lock.open.indexOf(handle);
      const rest = lock.open.filter((_, index) => index !== at);
      const below = inFront(rest);
      (below ? reachable(below) : toggleRef.current)?.focus();
    }

    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      // Escape belongs to the input method while a composition is running —
      // it cancels the candidate list — and the native event still reads
      // `Escape` on an ordinary input, un-prevented. Collapsing the view
      // there would take the half-typed word and the focus with it.
      // `keyCode === 229` is the same signal on browsers that leave
      // `isComposing` unset.
      if (event.isComposing || event.keyCode === 229) return;
      // Only the surface in front answers. Every expansion listens on the
      // same document, so without this one Escape would collapse all of them
      // at once and leave two of them fighting over where focus lands.
      if (inFront(lock.open) !== handle) return;
      // Two ways something in front can claim the key: focus is inside it,
      // or focus is still on the trigger that opened it — Base UI marks such
      // a trigger `data-popup-open`, which is the same tell `FilterPanel`
      // reads before it treats Enter as a submit. The constructor comes from
      // the event's own document: in an iframe, `instanceof Element` against
      // the top window's realm is false, and the check would be skipped for
      // exactly the surfaces most likely to be embedded.
      const target = event.target;
      if (
        view &&
        target instanceof view.Element &&
        (target.closest(ABOVE) !== null ||
          target.closest('[data-popup-open]') !== null)
      )
        return;
      collapse();
    }
    doc.addEventListener('keydown', onKeyDown);

    // The exit is rendered by `ViewSurface`, which knows nothing about any of
    // this; the surface that owns the expansion is what answers its click.
    function onClick(event: MouseEvent) {
      const target = event.target;
      if (!(view && target instanceof view.Element)) return;
      if (target.closest('[data-slot="view-exit"]') === null) return;
      collapse();
    }
    element.addEventListener('click', onClick);

    return () => {
      element.removeAttribute('data-view-expanded');
      for (const name of FITTED) element.style.removeProperty(name);
      if (exit) exit.hidden = true;
      if (fit) view?.removeEventListener('resize', fit);
      const at = lock.open.indexOf(handle);
      if (at >= 0) lock.open.splice(at, 1);
      if (lock.open.length === 0) {
        // Exactly what was there, each axis with its own value and its own
        // priority; then forget it, so the next expansion reads the page as
        // it is then rather than as it was.
        for (const { name, value, priority } of lock.saved) {
          if (value) lock.scroller.style.setProperty(name, value, priority);
          else lock.scroller.style.removeProperty(name);
        }
        scrollLocks.delete(doc);
      }
      doc.removeEventListener('keydown', onKeyDown);
      element.removeEventListener('click', onClick);
      held.current = null;
    };
    // `changed` carries no value of its own: it is the document saying the
    // element this was pointed at is no longer the element it settled on.
  }, [target, toggleRef, on, changed]);

  return {
    expanded: on,
    // While there is nothing to expand, this does nothing — rather than
    // flipping a state that `on` then masks. A host's own control can
    // outlive `enabled` going false (a shortcut still bound, a button not
    // yet unmounted), and a press stashed there would fill the screen the
    // moment the control came back, with nobody having asked for it. That is
    // the same trap the adjustment above exists to avoid, reached by the
    // other door.
    toggle: () => {
      if (enabled) setExpanded(value => !value);
    },
  };
}

export interface ViewExpandToggleProps {
  expansion: ViewExpansion;
  ref?: RefObject<HTMLButtonElement | null>;
}

/**
 * The control that fills the screen with this view, in the title bar's
 * right-hand group: it is one of the answers to *how am I looking at this*,
 * beside the editor's fold and before whatever the host adds.
 *
 * `aria-expanded` rather than `aria-pressed`, which is what the legacy
 * button wore: the surface it governs is a region that grows and shrinks,
 * the same relationship the sidebar's and the editor's toggles already
 * describe, and one page should not name that relationship two ways.
 * `aria-keyshortcuts` is how the Escape route is discoverable without
 * spending a tooltip on it — and it is announced only while there is
 * something for the key to do.
 *
 * The name is the one the state asks for, and the tooltip is the same
 * string: a pointer hovering this while the view fills the screen reads
 * "Exit full screen", which is what pressing it does now — not what it did
 * before it was pressed.
 */
export function ViewExpandToggle({ expansion, ref }: ViewExpandToggleProps) {
  const messages = useViewMessages();
  const { expanded } = expansion;
  const label = messages.label(
    expanded ? 'label.workbench.collapse-view' : 'label.workbench.expand-view',
  );
  return (
    <IconButton
      ref={ref}
      label={label}
      data-slot="view-expand"
      variant="outline"
      size="icon-sm"
      aria-expanded={expanded}
      aria-keyshortcuts={expanded ? 'Escape' : undefined}
      onClick={expansion.toggle}
    >
      {expanded ? <Minimize2Icon /> : <Maximize2Icon />}
    </IconButton>
  );
}

/**
 * The way back out of a surface whose control is not inside it.
 *
 * `ViewSurface` renders one on every surface and it is `hidden` until
 * `useViewExpansion` finds that the control governing this expansion is
 * *outside* the element — which is the only shape an `EmbeddedView` has,
 * since it grows no control of its own and a host puts one in its own chrome.
 * The moment the surface fills the screen, that host control is underneath
 * it: a desktop user might guess Escape, and a touch device has no Escape at
 * all, so without this there is no way out.
 *
 * It carries no handler. The surface that owns the expansion is the one that
 * knows how to collapse, and it answers the click — which keeps `ViewSurface`
 * free of any expansion state, and keeps this out of the page while the
 * control that governs the view is on screen with the rest of the chrome.
 */
export function ViewExpandExit() {
  const messages = useViewMessages();
  return (
    <Button hidden data-slot="view-exit" variant="outline" size="sm">
      <Minimize2Icon />
      {messages.label('label.workbench.collapse-view')}
    </Button>
  );
}
