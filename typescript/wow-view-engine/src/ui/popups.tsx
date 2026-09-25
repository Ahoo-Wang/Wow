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
import { Combobox as ComboboxPrimitive } from '@base-ui/react';
import { AlertDialog as AlertDialogPrimitive } from '@base-ui/react/alert-dialog';
import { Dialog as DialogPrimitive } from '@base-ui/react/dialog';
import { Menu as MenuPrimitive } from '@base-ui/react/menu';
import { Popover as PopoverPrimitive } from '@base-ui/react/popover';
import { Select as SelectPrimitive } from '@base-ui/react/select';
import { Tooltip as TooltipPrimitive } from '@base-ui/react/tooltip';
import { cn } from 'cn';
import { XIcon } from 'lucide-react';
import {
  AlertDialogOverlay,
  AlertDialogPortal,
  type AlertDialogContent as VendoredAlertDialogContent,
} from './components/alert-dialog.js';
import { Button } from './components/button.js';
import { type ComboboxContent as VendoredComboboxContent } from './components/combobox.js';
import {
  DialogClose,
  DialogOverlay,
  DialogPortal,
  type DialogContent as VendoredDialogContent,
} from './components/dialog.js';
import { type DropdownMenuContent as VendoredDropdownMenuContent } from './components/dropdown-menu.js';
import { type PopoverContent as VendoredPopoverContent } from './components/popover.js';
import {
  SelectScrollDownButton,
  SelectScrollUpButton,
  type SelectContent as VendoredSelectContent,
} from './components/select.js';
import { type TooltipContent as VendoredTooltipContent } from './components/tooltip.js';
import { useViewMessages } from './MessagesProvider.js';
import {
  useSurfaceAttributes,
  useSurfaceFont,
  useSurfaceHostTokens,
} from './ViewSurface.js';

/**
 * The popups this package renders, themed and on a layer of their own.
 *
 * Every token and base rule of the theme hangs off `.fve-root`, so the host
 * page stays untouched. A popup — a select's list, a menu, a popover, a
 * tooltip, a dialog, a combobox — is portalled to the document body, outside
 * that root, and would render without a background or a border. So each one
 * is composed here rather than taken from the registry as it ships: the
 * markup is a faithful copy of the vendored component's, with the root class
 * and the surface's mode on the portalled elements — the pinned `theme` when
 * there is one, otherwise the mode the surface resolved from the cascade, so
 * a `.dark` on any ancestor reaches the popup and the class does not have to
 * sit on `<html>`. The stylesheet's `color-scheme` stays the source of
 * truth; nothing here decides the mode. `components/*.tsx` stay as the
 * registry ships them and are updated with `shadcn add --diff`; keep the
 * copies below in step when they are, which `test/popups.test.tsx` holds
 * them to by rendering both and comparing what comes out.
 */
type ClassName<S> = string | ((state: S) => string | undefined) | undefined;

/** A popup's own class with `base` in front, whichever form it takes. */
function withClass<S>(base: string, className: ClassName<S>): ClassName<S> {
  return typeof className === 'function'
    ? (state: S) => cn(base, className(state))
    : cn(base, className);
}

/** The popup's own class with the root's in front, whichever form it takes. */
function themedClass<S>(className: ClassName<S>): ClassName<S> {
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
function layered<S>(style: Style<S>): Style<S> {
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
function useSurfaceType<S>(style: Style<S>): Style<S> {
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
const POPUP_LAYER: React.CSSProperties = {
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
const ALERT_DIALOG_BACKDROP_DIM = 'bg-black/50';

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
const ALERT_DIALOG_RAISED = 'ring-foreground/40';

/** The vendored positioner's classes, copied verbatim from `components/`. */
const POSITIONER_CLASS = 'isolate z-50';
const MENU_POSITIONER_CLASS = 'isolate z-50 outline-none';

/** The vendored popups' classes, copied verbatim from `components/`. */
const ALERT_DIALOG_POPUP_CLASS =
  'group/alert-dialog-content fixed top-1/2 left-1/2 z-50 grid w-full -translate-x-1/2 -translate-y-1/2 gap-4 rounded-xl bg-popover p-4 text-popover-foreground ring-1 ring-foreground/10 duration-100 outline-none data-[size=default]:max-w-xs data-[size=sm]:max-w-xs data-[size=default]:sm:max-w-sm data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const COMBOBOX_POPUP_CLASS =
  'group/combobox-content relative max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) min-w-[calc(var(--anchor-width)+--spacing(7))] origin-(--transform-origin) overflow-hidden rounded-lg bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 data-[chips=true]:min-w-(--anchor-width) data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 *:data-[slot=input-group]:m-1 *:data-[slot=input-group]:mb-0 *:data-[slot=input-group]:h-8 *:data-[slot=input-group]:border-input/30 *:data-[slot=input-group]:bg-input/30 *:data-[slot=input-group]:shadow-none data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const DIALOG_POPUP_CLASS =
  'fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 rounded-xl bg-popover p-4 text-sm text-popover-foreground ring-1 ring-foreground/10 duration-100 outline-none sm:max-w-sm data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const MENU_POPUP_CLASS =
  'z-50 max-h-(--available-height) w-(--anchor-width) min-w-32 origin-(--transform-origin) overflow-x-hidden overflow-y-auto rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 outline-none data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:overflow-hidden data-closed:fade-out-0 data-closed:zoom-out-95';
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
const POPOVER_POPUP_CLASS =
  'z-50 flex max-h-(--available-height) w-72 origin-(--transform-origin) flex-col gap-2.5 overflow-y-auto rounded-lg bg-popover p-2.5 text-sm text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-hidden duration-100 data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const SELECT_POPUP_CLASS =
  'relative isolate z-50 max-h-(--available-height) w-(--anchor-width) min-w-36 origin-(--transform-origin) overflow-x-hidden overflow-y-auto rounded-lg bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 data-[align-trigger=true]:animate-none data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const TOOLTIP_POPUP_CLASS =
  'z-50 inline-flex w-fit max-w-xs origin-(--transform-origin) items-center gap-1.5 rounded-md bg-foreground px-3 py-1.5 text-xs text-background has-data-[slot=kbd]:pr-1.5 data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 **:data-[slot=kbd]:relative **:data-[slot=kbd]:isolate **:data-[slot=kbd]:z-50 **:data-[slot=kbd]:rounded-sm data-[state=delayed-open]:animate-in data-[state=delayed-open]:fade-in-0 data-[state=delayed-open]:zoom-in-95 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';
const TOOLTIP_ARROW_CLASS =
  'z-50 size-2.5 translate-y-[calc(-50%-2px)] rotate-45 rounded-[2px] bg-foreground fill-foreground data-[side=bottom]:top-1 data-[side=inline-end]:top-1/2! data-[side=inline-end]:-left-1 data-[side=inline-end]:-translate-y-1/2 data-[side=inline-start]:top-1/2! data-[side=inline-start]:-right-1 data-[side=inline-start]:-translate-y-1/2 data-[side=left]:top-1/2! data-[side=left]:-right-1 data-[side=left]:-translate-y-1/2 data-[side=right]:top-1/2! data-[side=right]:-left-1 data-[side=right]:-translate-y-1/2 data-[side=top]:-bottom-2.5';

/**
 * The destructive dialog's two portalled elements, themed like the plain one.
 *
 * Same shape as `DialogContent` below and for the same reason — backdrop and
 * popup are siblings inside the portal, so both carry the root class, the
 * surface's mode and the popup layer. What it deliberately does not carry is
 * a close button: an alert dialog asks a question with a cost, and the answer
 * is one of the two in the footer rather than a corner that dismisses it.
 *
 * Three things here are not the registry's as it ships, and all three are
 * about the same fact: two of the questions this draws open on **top of
 * another dialog** — a delete and its second confirmation are raised from a
 * row of the view manager, which is itself a `Dialog` over a dimmed page.
 *
 * The backdrop is `forceRender`, because Base UI does not render a nested
 * backdrop unless asked; without it the confirmation opened with no dimming
 * of its own and the list behind it never moved. It dims at
 * `ALERT_DIALOG_BACKDROP_DIM` rather than the registry's 10%, because 10%
 * over a page that is already dimmed 10% is 19% — the confirmation read as a
 * second white card dropped into the list rather than as the one thing being
 * asked. And the card itself carries `ALERT_DIALOG_RAISED`, which is what
 * separates the two layers where a scrim cannot.
 */
export function AlertDialogContent({
  className,
  size = 'default',
  style,
  ...props
}: React.ComponentProps<typeof VendoredAlertDialogContent>) {
  const surface = useSurfaceAttributes();
  return (
    <AlertDialogPortal>
      <AlertDialogOverlay
        // Base UI drops a backdrop that is nested inside another dialog's
        // unless it is asked for by name, and two of the three questions
        // this component draws *are* nested: a delete is raised from a row
        // of the view manager, which is itself a `Dialog`. Left to the
        // default, the confirmation opened with no dimming of its own at
        // all — the manager's own 10% tint stayed exactly as it was, and a
        // white card appeared in the middle of a list that had not moved.
        forceRender
        className={cn('fve-root', ALERT_DIALOG_BACKDROP_DIM)}
        style={useSurfaceType(POPUP_LAYER)}
        {...surface}
      />
      <AlertDialogPrimitive.Popup
        data-slot="alert-dialog-content"
        data-size={size}
        {...props}
        className={withClass(
          `${ALERT_DIALOG_POPUP_CLASS} ${ALERT_DIALOG_RAISED}`,
          themedClass(className),
        )}
        style={useSurfaceType(layered(style))}
        {...surface}
      />
    </AlertDialogPortal>
  );
}

export function ComboboxContent({
  className,
  side = 'bottom',
  sideOffset = 6,
  align = 'start',
  alignOffset = 0,
  anchor,
  style,
  ...props
}: React.ComponentProps<typeof VendoredComboboxContent>) {
  return (
    <ComboboxPrimitive.Portal>
      <ComboboxPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        anchor={anchor}
        className={POSITIONER_CLASS}
        style={POPUP_LAYER}
      >
        <ComboboxPrimitive.Popup
          data-slot="combobox-content"
          data-chips={!!anchor}
          {...props}
          className={withClass(COMBOBOX_POPUP_CLASS, themedClass(className))}
          style={useSurfaceType(style)}
          {...useSurfaceAttributes()}
        />
      </ComboboxPrimitive.Positioner>
    </ComboboxPrimitive.Portal>
  );
}

/**
 * The one popup with no positioner, and with two portalled elements.
 *
 * A dialog's backdrop is the popup's *sibling* inside the portal, so putting
 * `fve-root` on the popup alone would leave the backdrop outside every root.
 * The build scopes the whole stylesheet to `:where(.fve-root, .fve-root *)`,
 * so an unrooted backdrop loses every one of its utilities and paints
 * nothing: a dialog with no dimming behind it. Both elements carry the root
 * class and the surface's mode here, and both take the popup layer, so the
 * host variable moves a dialog with the rest.
 */
export function DialogContent({
  className,
  children,
  style,
  showCloseButton = true,
  ...props
}: React.ComponentProps<typeof VendoredDialogContent>) {
  const surface = useSurfaceAttributes();
  const messages = useViewMessages();
  return (
    <DialogPortal>
      <DialogOverlay
        className="fve-root"
        style={useSurfaceType(POPUP_LAYER)}
        {...surface}
      />
      <DialogPrimitive.Popup
        data-slot="dialog-content"
        {...props}
        className={withClass(DIALOG_POPUP_CLASS, themedClass(className))}
        style={useSurfaceType(layered(style))}
        {...surface}
      >
        {children}
        {showCloseButton && (
          <DialogClose
            data-slot="dialog-close"
            render={
              <Button
                variant="ghost"
                className="absolute top-2 right-2"
                size="icon-sm"
              />
            }
          >
            <XIcon />
            {/* The one place the copy differs from the registry's: this file
                composes the markup now, so the label goes through the
                catalogue like every other word this package writes. */}
            <span className="sr-only">
              {messages.label('label.dialog.close')}
            </span>
          </DialogClose>
        )}
      </DialogPrimitive.Popup>
    </DialogPortal>
  );
}

/**
 * A side panel over the workbench — a record's detail, the visualization
 * panel on a phone — themed like a dialog: it is one (Base UI's `Dialog`,
 * which the registry's `Sheet` is), with the same two portalled elements, so
 * both carry the root class, the surface's mode and the popup layer, and the
 * close button's name comes from the catalogue.
 *
 * From the right edge (`side="right"`, the default) it takes the full
 * height; on a narrow screen the width, on a wide one a reading column. From
 * the bottom (`side="bottom"`, the registry's drawer position) it takes the
 * width and at most four fifths of the height, scrolling inside itself, so
 * the top of the page stays in view above it.
 */
export function SheetContent({
  className,
  children,
  style,
  side = 'right',
  showCloseButton = true,
  overlayClassName,
  ...props
}: DialogPrimitive.Popup.Props & {
  side?: 'right' | 'bottom';
  /** The registry's close button; left out where the content has its own. */
  showCloseButton?: boolean;
  /** Classes for the backdrop, beside the root class it always wears. */
  overlayClassName?: string;
}) {
  const surface = useSurfaceAttributes();
  const messages = useViewMessages();
  return (
    <DialogPortal>
      <DialogOverlay
        className={cn('fve-root', overlayClassName)}
        style={POPUP_LAYER}
        {...surface}
      />
      <DialogPrimitive.Popup
        data-slot="sheet-content"
        data-side={side}
        {...props}
        className={withClass(SHEET_POPUP_CLASS[side], themedClass(className))}
        style={useSurfaceType(layered(style))}
        {...surface}
      >
        {children}
        {showCloseButton && (
          <DialogClose
            data-slot="sheet-close"
            render={
              <Button
                variant="ghost"
                className="absolute top-3 right-3"
                size="icon-sm"
              />
            }
          >
            <XIcon />
            <span className="sr-only">
              {messages.label('label.dialog.close')}
            </span>
          </DialogClose>
        )}
      </DialogPrimitive.Popup>
    </DialogPortal>
  );
}

/** The registry's sheet recipe for each edge it comes from. */
const SHEET_POPUP_CLASS = {
  right:
    'fixed inset-y-0 right-0 flex h-full w-full flex-col gap-4 border-l bg-popover bg-clip-padding text-sm text-popover-foreground shadow-lg transition duration-200 ease-in-out data-ending-style:translate-x-[2.5rem] data-ending-style:opacity-0 data-starting-style:translate-x-[2.5rem] data-starting-style:opacity-0 sm:max-w-xl',
  bottom:
    'fixed inset-x-0 bottom-0 flex h-auto max-h-[80dvh] flex-col gap-4 overflow-y-auto border-t bg-popover bg-clip-padding text-sm text-popover-foreground shadow-lg transition duration-200 ease-in-out data-ending-style:translate-y-[2.5rem] data-ending-style:opacity-0 data-starting-style:translate-y-[2.5rem] data-starting-style:opacity-0',
} as const;

export function DropdownMenuContent({
  align = 'start',
  alignOffset = 0,
  side = 'bottom',
  sideOffset = 4,
  anchor,
  className,
  style,
  ...props
}: React.ComponentProps<typeof VendoredDropdownMenuContent> & {
  /**
   * What the menu is placed against when it has no trigger of its own: a
   * chart mark or a table row that was pressed, as an element or a point.
   */
  anchor?: MenuPrimitive.Positioner.Props['anchor'];
}) {
  return (
    <MenuPrimitive.Portal>
      <MenuPrimitive.Positioner
        className={MENU_POSITIONER_CLASS}
        style={POPUP_LAYER}
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
        anchor={anchor}
      >
        <MenuPrimitive.Popup
          data-slot="dropdown-menu-content"
          {...props}
          className={withClass(MENU_POPUP_CLASS, themedClass(className))}
          style={useSurfaceType(style)}
          {...useSurfaceAttributes()}
        />
      </MenuPrimitive.Positioner>
    </MenuPrimitive.Portal>
  );
}

/**
 * A submenu's popup: the same portal, layer and theme as the menu it opens
 * from, positioned beside its trigger as the registry's copy positions it.
 */
export function DropdownMenuSubContent({
  align = 'start',
  alignOffset = -3,
  side = 'right',
  sideOffset = 0,
  className,
  ...props
}: React.ComponentProps<typeof DropdownMenuContent>) {
  return (
    <DropdownMenuContent
      data-slot="dropdown-menu-sub-content"
      className={withClass('w-auto min-w-24', className)}
      align={align}
      alignOffset={alignOffset}
      side={side}
      sideOffset={sideOffset}
      {...props}
    />
  );
}

export function PopoverContent({
  className,
  align = 'center',
  alignOffset = 0,
  side = 'bottom',
  sideOffset = 4,
  style,
  ...props
}: React.ComponentProps<typeof VendoredPopoverContent>) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Positioner
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
        className={POSITIONER_CLASS}
        style={POPUP_LAYER}
      >
        <PopoverPrimitive.Popup
          data-slot="popover-content"
          {...props}
          className={withClass(POPOVER_POPUP_CLASS, themedClass(className))}
          style={useSurfaceType(style)}
          {...useSurfaceAttributes()}
        />
      </PopoverPrimitive.Positioner>
    </PopoverPrimitive.Portal>
  );
}

export function SelectContent({
  className,
  children,
  side = 'bottom',
  sideOffset = 4,
  align = 'center',
  alignOffset = 0,
  alignItemWithTrigger = true,
  style,
  ...props
}: React.ComponentProps<typeof VendoredSelectContent>) {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        alignItemWithTrigger={alignItemWithTrigger}
        className={POSITIONER_CLASS}
        style={POPUP_LAYER}
      >
        <SelectPrimitive.Popup
          data-slot="select-content"
          data-align-trigger={alignItemWithTrigger}
          {...props}
          className={withClass(SELECT_POPUP_CLASS, themedClass(className))}
          style={useSurfaceType(style)}
          {...useSurfaceAttributes()}
        >
          <SelectScrollUpButton />
          <SelectPrimitive.List>{children}</SelectPrimitive.List>
          <SelectScrollDownButton />
        </SelectPrimitive.Popup>
      </SelectPrimitive.Positioner>
    </SelectPrimitive.Portal>
  );
}

export function TooltipContent({
  className,
  side = 'top',
  sideOffset = 4,
  align = 'center',
  alignOffset = 0,
  children,
  style,
  ...props
}: React.ComponentProps<typeof VendoredTooltipContent>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Positioner
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
        className={POSITIONER_CLASS}
        style={POPUP_LAYER}
      >
        <TooltipPrimitive.Popup
          data-slot="tooltip-content"
          {...props}
          className={withClass(TOOLTIP_POPUP_CLASS, themedClass(className))}
          style={useSurfaceType(style)}
          {...useSurfaceAttributes()}
        >
          {children}
          <TooltipPrimitive.Arrow className={TOOLTIP_ARROW_CLASS} />
        </TooltipPrimitive.Popup>
      </TooltipPrimitive.Positioner>
    </TooltipPrimitive.Portal>
  );
}
