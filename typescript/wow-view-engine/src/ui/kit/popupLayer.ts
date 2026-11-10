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

/**
 * What every popup here stands on: the surface's theme carried onto a portal
 * that is outside it, and a layer of its own.
 *
 * A popup is portalled to `<body>`, outside `.fve-root`, so its class takes
 * the root's in front (`themedClass`), its style the surface's type and host
 * variables (`useSurfaceType`) and the popup layer (`layered`, `POPUP_LAYER`).
 * Beside them are the classes each popup and positioner is drawn with: the
 * registry's own, copied verbatim from `components/` (which
 * `test/popups.test.tsx` holds in step by rendering both), and the few this
 * package adds on purpose, each named and said why. The popups themselves are
 * `popups.tsx`.
 */

import type * as React from 'react';
import { cn } from 'cn';
import { useSurfaceFont, useSurfaceHostTokens } from './surfaceContext.js';

type ClassName<S> = string | ((state: S) => string | undefined) | undefined;

/** A popup's own class with `base` in front, whichever form it takes. */
export function withClass<S>(
  base: string,
  className: ClassName<S>,
): ClassName<S> {
  return typeof className === 'function'
    ? (state: S) => cn(base, className(state))
    : cn(base, className);
}

/** The popup's own class with the root's in front, whichever form it takes. */
export function themedClass<S>(className: ClassName<S>): ClassName<S> {
  return withClass('fve-root', className);
}

type Style<S> =
  | React.CSSProperties
  | ((state: S) => React.CSSProperties | undefined)
  | undefined;

/** A popup's own style over `base`, whichever form it takes. */
function over<S>(base: React.CSSProperties, style: Style<S>): Style<S> {
  return typeof style === 'function'
    ? (state: S) => ({ ...base, ...style(state) })
    : { ...base, ...style };
}

/**
 * A popup's own style over the layer below, whichever form it takes.
 *
 * Merged rather than replaced, and in this order: a caller that sets an
 * unrelated property keeps the layer, and a caller that really means to move
 * this one popup still wins. Replacing would be the worse half of both — a
 * dialog's backdrop stays on the layer whatever its popup does, so a popup
 * that lost it would end up behind the dimming it brought with it.
 */
export function layered<S>(style: Style<S>): Style<S> {
  return over(POPUP_LAYER, style);
}

/**
 * A popup's own style over the type of the surface it opened from, and over
 * the host variables that surface was handed (`tokens`), which are on no
 * ancestor of the portal either.
 *
 * The popup is portalled to `<body>`, so the family it would inherit is the
 * body's, and a host that sets its type on an application frame rather than
 * on the body leaves that at the browser's serif default. The stylesheet sets
 * a root in `var(--_fve-surface-font, var(--fve-font-sans))`, and this writes the
 * surface's computed family into the first on the popup itself — the one
 * element of the portal the stylesheet reaches — so the popup reads exactly
 * as the surface does, whichever preset, host variable or inherited family
 * put it there. It is a custom property rather than `font-family`, so the
 * theme stays the stylesheet's to say; before the surface has read its own
 * type there is nothing to write, and the style is the caller's alone.
 */
export function useSurfaceType<S>(style: Style<S>): Style<S> {
  const font = useSurfaceFont();
  const tokens = useSurfaceHostTokens();
  if (font === undefined && tokens === undefined) return style;
  return over(
    {
      ...tokens,
      ...(font === undefined ? {} : { '--_fve-surface-font': font }),
    } as React.CSSProperties,
    style,
  );
}

/**
 * The layer every popup here paints on, written as a style and not a class.
 *
 * The element that has to carry it is the *positioner*, the box Base UI puts
 * around a popup to place it against its trigger — and the positioner is not
 * a `.fve-root`. The build pins every rule of `src/styles.css` to
 * `:where(.fve-root, .fve-root *)`, so the registry's own `isolate z-50` on
 * the positioner matches nothing and it computes to `z-index: auto`; and the
 * `z-50` on the content inside it cannot escape, because the layout engine
 * writes `transform: translate(x, y)` on the positioner, which makes it a
 * stacking context. A popup left that way paints at level 0 and stays on top
 * only because its portal appends it to the end of `<body>` — so anything a
 * host raises above level 0 covers every popup this package opens. A style
 * needs neither the stylesheet nor a root to apply, which is the one thing
 * that is true wherever a portal puts the popup.
 *
 * `--fve-popup-z-index` is the host's way in: a page whose own chrome stacks
 * above 50 sets it on `:root` like any `--fve-*` token, and every popup here
 * moves together. Below it the dialog's two elements read the same variable,
 * so one number governs the lot.
 */
export const POPUP_LAYER: React.CSSProperties = {
  zIndex: 'var(--fve-popup-z-index, 50)',
};

/**
 * How hard a destructive question dims what is behind it.
 *
 * The registry's own backdrop — `bg-black/10` for both the dialog and the
 * alert dialog — is a tint, and it is the same tint twice: an alert dialog
 * raised from inside a dialog puts 10% black over 10% black, which comes to
 * 19% and reads as one flat surface with a second white card on it. The
 * number below is the dim a modal question is usually given, and it is a
 * deliberate departure from the vendored class rather than drift, which is
 * why it is named here and not folded into the copies above. It is not a
 * theme colour: black at an opacity is what a backdrop is in this registry,
 * light theme and dark alike, and `--fve-*` moves what is *under* it.
 *
 * Only the destructive dialog takes it. A plain `Dialog` — save-as, the
 * manager, the export window — is a place to do something rather than a
 * question with a cost, and the registry's tint is right for those: they
 * are the ones a user reads the page *around*.
 */
export const ALERT_DIALOG_BACKDROP_DIM = 'fve:bg-black/50';

/**
 * The edge the destructive dialog's own card is raised by, over the ring the
 * registry gives it.
 *
 * A scrim separates two surfaces by darkening the lower one, and near black
 * there is nothing left to darken: the dark theme paints both this card and
 * the manager under it in `--popover` (`oklch(0.205)`), and 50% black takes
 * the lower one from `rgb(23,23,23)` to `rgb(12,12,12)` — **1.09:1**, which
 * is below even the 1.5 this package holds a dark hairline to. So the upper
 * layer carries its own edge: the registry's `ring-foreground/10` is a hint
 * on a page that has nothing else on it, and this is the same ring at the
 * weight a card standing on another card needs. It is a semantic token at a
 * different opacity, so it flips with the theme and moves with the host's
 * `--fve-foreground` like the ring it replaces.
 *
 * It is the one class this copy adds to the vendored popup's, which is why
 * it is named here rather than edited into `ALERT_DIALOG_POPUP_CLASS` — the
 * copy stays a copy, and `test/popups.test.tsx` names this addition
 * alongside the root class when it compares the two renders.
 */
export const ALERT_DIALOG_RAISED = 'fve:ring-foreground/40';

/** The vendored positioner's classes, copied verbatim from `components/`. */
export const POSITIONER_CLASS = 'fve:isolate fve:z-50';
export const MENU_POSITIONER_CLASS = 'fve:isolate fve:z-50 fve:outline-none';

/** The vendored popups' classes, copied verbatim from `components/`. */
export const ALERT_DIALOG_POPUP_CLASS =
  'fve:group/alert-dialog-content fve:fixed fve:top-1/2 fve:left-1/2 fve:z-50 fve:grid fve:w-full fve:max-h-[calc(100dvh-2rem)] fve:overflow-y-auto fve:[overflow-wrap:anywhere] fve:-translate-x-1/2 fve:-translate-y-1/2 fve:gap-4 fve:rounded-xl fve:bg-popover fve:p-4 fve:text-popover-foreground fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:data-[size=default]:max-w-xs fve:data-[size=sm]:max-w-xs fve:data-[size=default]:sm:max-w-sm fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
export const COMBOBOX_POPUP_CLASS =
  'fve:group/combobox-content fve:relative fve:max-h-(--available-height) fve:w-(--anchor-width) fve:max-w-(--available-width) fve:min-w-[calc(var(--anchor-width)+--spacing(7))] fve:origin-(--transform-origin) fve:overflow-hidden fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:data-[chips=true]:min-w-(--anchor-width) fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:*:data-[slot=input-group]:m-1 fve:*:data-[slot=input-group]:mb-0 fve:*:data-[slot=input-group]:h-8 fve:*:data-[slot=input-group]:border-input/30 fve:*:data-[slot=input-group]:bg-input/30 fve:*:data-[slot=input-group]:shadow-none fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
/**
 * A dialog never runs past the viewport: taller content scrolls inside it
 * (`max-h` one rem from each edge). A dialog whose body is a long list makes
 * that list the scroller instead, so its heading stays (`ViewManager`).
 *
 * Nor does it run past its own width: a word with no break in it — a
 * record's key such as `payment-service:payment:…:(0):pay-0012` — breaks
 * where it must (`overflow-wrap: anywhere`, which also lets the grid track
 * shrink to the dialog). Left to the vendored grid, the key held the track
 * at its own width and pushed the dialog's buttons out of sight.
 */
export const DIALOG_POPUP_CLASS =
  'fve:fixed fve:top-1/2 fve:left-1/2 fve:z-50 fve:grid fve:w-full fve:max-w-[calc(100%-2rem)] fve:max-h-[calc(100dvh-2rem)] fve:overflow-y-auto fve:[overflow-wrap:anywhere] fve:-translate-x-1/2 fve:-translate-y-1/2 fve:gap-4 fve:rounded-xl fve:bg-popover fve:p-4 fve:text-sm fve:text-popover-foreground fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:sm:max-w-sm fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
/**
 * The menu and the popover depart from the registry by one more class each:
 * `relative`. Each is a scroll port, and Base UI positions the positioner
 * around it and leaves the popup static, so an absolute descendant — an
 * `sr-only` word — is placed against the positioner, outside the popup's
 * clip: a long one's last words hang below it and make the page scroll (a
 * menu 2000px long made a 640px page 2515px tall). The select's and the
 * combobox's popups are `relative` in the registry already.
 */
export const MENU_POPUP_CLASS =
  'fve:relative fve:z-50 fve:max-h-(--available-height) fve:w-(--anchor-width) fve:min-w-32 fve:origin-(--transform-origin) fve:overflow-x-hidden fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:p-1 fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:overflow-hidden fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
/**
 * The one place the copy is deliberately not the registry's: a popover that
 * is taller than the room under its trigger scrolls instead of running off
 * the screen.
 *
 * Every other popup here already does — the select's list, the menu and the
 * combobox all carry `max-h-(--available-height)` with an `overflow-y-auto`
 * under it, and Base UI writes that variable on every positioner, popover's
 * included. The registry's popover is written for the short ones (a form of
 * three fields, a confirmation), and this package opens long ones in it: the
 * column settings list one row per column, and on a twenty-column table the
 * popup grew past the viewport with no way to reach the bottom of it — the
 * page behind does not scroll a portalled popup, and the popup had no
 * scroll port of its own. `test/popups.test.tsx` knows about this pair of
 * classes by name, so the copy is still held to the registry everywhere
 * else.
 */
export const POPOVER_POPUP_CLASS =
  'fve:relative fve:z-50 fve:flex fve:max-h-(--available-height) fve:w-72 fve:origin-(--transform-origin) fve:flex-col fve:gap-2.5 fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:p-2.5 fve:text-sm fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:outline-hidden fve:duration-100 fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
export const SELECT_POPUP_CLASS =
  'fve:relative fve:isolate fve:z-50 fve:max-h-(--available-height) fve:w-(--anchor-width) fve:min-w-36 fve:origin-(--transform-origin) fve:overflow-x-hidden fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:data-[align-trigger=true]:animate-none fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
export const TOOLTIP_POPUP_CLASS =
  'fve:z-50 fve:inline-flex fve:w-fit fve:max-w-xs fve:origin-(--transform-origin) fve:items-center fve:gap-1.5 fve:rounded-md fve:bg-foreground fve:px-3 fve:py-1.5 fve:text-xs fve:text-background fve:has-data-[slot=kbd]:pr-1.5 fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:**:data-[slot=kbd]:relative fve:**:data-[slot=kbd]:isolate fve:**:data-[slot=kbd]:z-50 fve:**:data-[slot=kbd]:rounded-sm fve:data-[state=delayed-open]:animate-in fve:data-[state=delayed-open]:fade-in-0 fve:data-[state=delayed-open]:zoom-in-95 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95';
export const TOOLTIP_ARROW_CLASS =
  'fve:z-50 fve:size-2.5 fve:translate-y-[calc(-50%-2px)] fve:rotate-45 fve:rounded-[2px] fve:bg-foreground fve:fill-foreground fve:data-[side=bottom]:top-1 fve:data-[side=inline-end]:top-1/2! fve:data-[side=inline-end]:-left-1 fve:data-[side=inline-end]:-translate-y-1/2 fve:data-[side=inline-start]:top-1/2! fve:data-[side=inline-start]:-right-1 fve:data-[side=inline-start]:-translate-y-1/2 fve:data-[side=left]:top-1/2! fve:data-[side=left]:-right-1 fve:data-[side=left]:-translate-y-1/2 fve:data-[side=right]:top-1/2! fve:data-[side=right]:-left-1 fve:data-[side=right]:-translate-y-1/2 fve:data-[side=top]:-bottom-2.5';
