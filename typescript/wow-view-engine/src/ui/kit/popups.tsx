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
import { CSPProvider } from '@base-ui/react/csp-provider';
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
} from '../components/alert-dialog.js';
import { Button } from '../components/button.js';
import { type ComboboxContent as VendoredComboboxContent } from '../components/combobox.js';
import {
  DialogClose,
  DialogOverlay,
  DialogPortal,
  type DialogContent as VendoredDialogContent,
} from '../components/dialog.js';
import { type DropdownMenuContent as VendoredDropdownMenuContent } from '../components/dropdown-menu.js';
import { type PopoverContent as VendoredPopoverContent } from '../components/popover.js';
import {
  SelectScrollDownButton,
  SelectScrollUpButton,
  type SelectContent as VendoredSelectContent,
} from '../components/select.js';
import { type TooltipContent as VendoredTooltipContent } from '../components/tooltip.js';
import { cspNonce } from './cspNonce.js';
import { useKeptFocus } from './keptFocus.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceAttributes } from './surfaceContext.js';
import {
  ALERT_DIALOG_BACKDROP_DIM,
  ALERT_DIALOG_POPUP_CLASS,
  ALERT_DIALOG_RAISED,
  COMBOBOX_POPUP_CLASS,
  DIALOG_POPUP_CLASS,
  MENU_POPUP_CLASS,
  MENU_POSITIONER_CLASS,
  POPOVER_POPUP_CLASS,
  POPUP_LAYER,
  POSITIONER_CLASS,
  SELECT_POPUP_CLASS,
  TOOLTIP_ARROW_CLASS,
  TOOLTIP_POPUP_CLASS,
  layered,
  themedClass,
  useSurfaceType,
  withClass,
} from './popupLayer.js';

/**
 * The popups this package renders, themed and on a layer of their own.
 *
 * Every token and base rule of the theme hangs off `.fve-root`, so the host
 * page stays untouched. A popup — a select's list, a menu, a popover, a
 * tooltip, a dialog, a combobox — is portalled to the document body, outside
 * that root, and would render without a background or a border. So each one
 * is composed here rather than taken from the registry as it ships: the
 * markup is a faithful copy of the vendored component's, with the root class
 * and the surface's mode on the portalled elements — the pinned `colorMode`
 * when there is one, otherwise the mode the surface resolved from the cascade, so
 * a `.dark` on any ancestor reaches the popup and the class does not have to
 * sit on `<html>`. The stylesheet's `color-scheme` stays the source of
 * truth; nothing here decides the mode. `components/*.tsx` stay as the
 * registry ships them and are updated with `shadcn add --diff`; keep the
 * copies below, and their classes in `popupLayer.ts`, in step when they are,
 * which `test/popups.test.tsx` holds them to by rendering both and comparing
 * what comes out.
 */

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
  finalFocus,
  ...props
}: React.ComponentProps<typeof VendoredAlertDialogContent>) {
  const surface = useSurfaceAttributes();
  const kept = useKeptFocus(finalFocus, props.ref);
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
        {...kept}
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
  finalFocus,
  ...props
}: React.ComponentProps<typeof VendoredDialogContent>) {
  const surface = useSurfaceAttributes();
  const kept = useKeptFocus(finalFocus, props.ref);
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
        {...kept}
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
                className="fve:absolute fve:top-2 fve:right-2"
                size="icon-sm"
              />
            }
          >
            <XIcon />
            {/* The one place the copy differs from the registry's: this file
                composes the markup now, so the label goes through the
                catalogue like every other word this package writes. */}
            <span className="fve:sr-only">
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
  finalFocus,
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
  const kept = useKeptFocus(finalFocus, props.ref);
  return (
    <DialogPortal>
      <DialogOverlay
        className={cn('fve-root', SHEET_OVERLAY_CLASS, overlayClassName)}
        style={POPUP_LAYER}
        {...surface}
      />
      <DialogPrimitive.Popup
        data-slot="sheet-content"
        data-side={side}
        {...props}
        {...kept}
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
                className="fve:absolute fve:top-3 fve:right-3"
                size="icon-sm"
              />
            }
          >
            <XIcon />
            <span className="fve:sr-only">
              {messages.label('label.dialog.close')}
            </span>
          </DialogClose>
        )}
      </DialogPrimitive.Popup>
    </DialogPortal>
  );
}

/**
 * A side panel's backdrop leaves with the panel, not before it (#3690). The
 * registry's backdrop fades out in 100ms, a dialog's own length; a panel
 * slides out in 200ms, and the backdrop's keyframes ended first and let it
 * snap back to full for the last 80ms — the dim flashed twice on every
 * close. It takes the panel's length here, and its fade holds its last frame
 * (`fill-mode-forwards`) until the panel is gone. It does not blur: a panel
 * is read beside the rows it opened from, as a desktop's inspector is, and a
 * frosted list hid which row that was.
 */
const SHEET_OVERLAY_CLASS =
  'fve:duration-200 fve:fill-mode-forwards fve:supports-backdrop-filter:backdrop-blur-none';

/** The registry's sheet recipe for each edge it comes from. */
const SHEET_POPUP_CLASS = {
  right:
    'fve:fixed fve:inset-y-0 fve:right-0 fve:flex fve:h-full fve:w-full fve:flex-col fve:gap-4 fve:border-l fve:bg-popover fve:bg-clip-padding fve:text-sm fve:text-popover-foreground fve:shadow-lg fve:transition fve:duration-200 fve:ease-in-out fve:data-ending-style:translate-x-[2.5rem] fve:data-ending-style:opacity-0 fve:data-starting-style:translate-x-[2.5rem] fve:data-starting-style:opacity-0 fve:sm:max-w-xl',
  bottom:
    'fve:fixed fve:inset-x-0 fve:bottom-0 fve:flex fve:h-auto fve:max-h-[80dvh] fve:flex-col fve:gap-4 fve:overflow-y-auto fve:border-t fve:bg-popover fve:bg-clip-padding fve:text-sm fve:text-popover-foreground fve:shadow-lg fve:transition fve:duration-200 fve:ease-in-out fve:data-ending-style:translate-y-[2.5rem] fve:data-ending-style:opacity-0 fve:data-starting-style:translate-y-[2.5rem] fve:data-starting-style:opacity-0',
} as const;

type MenuContentProps = React.ComponentProps<
  typeof VendoredDropdownMenuContent
> & {
  /**
   * What the menu is placed against when it has no trigger of its own: a
   * chart mark or a table row that was pressed, as an element or a point.
   */
  anchor?: MenuPrimitive.Positioner.Props['anchor'];
};

/**
 * A menu's popup. `handsBack` says whether it returns the keyboard when its
 * caller says nothing — a menu does, a submenu leaves it to its menu.
 */
export function DropdownMenuContent({
  align = 'start',
  alignOffset = 0,
  side = 'bottom',
  sideOffset = 4,
  anchor,
  className,
  style,
  finalFocus,
  handsBack = true,
  ...props
}: MenuContentProps & { handsBack?: boolean }) {
  const kept = useKeptFocus(finalFocus, props.ref, handsBack);
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
          {...kept}
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
}: MenuContentProps) {
  return (
    <DropdownMenuContent
      handsBack={false}
      data-slot="dropdown-menu-sub-content"
      className={withClass('fve:w-auto fve:min-w-24', className)}
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
  finalFocus,
  ...props
}: React.ComponentProps<typeof VendoredPopoverContent>) {
  const kept = useKeptFocus(finalFocus, props.ref);
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
          {...kept}
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
  finalFocus,
  ...props
}: React.ComponentProps<typeof VendoredSelectContent>) {
  const kept = useKeptFocus(finalFocus, props.ref);
  // The popup adds a `<style>` of its own (the list's scrollbar hidden
  // while its scroll arrows show); under a strict policy it carries the
  // page's nonce (`cspNonce`, D74).
  return (
    <CSPProvider nonce={cspNonce()}>
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
            {...kept}
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
    </CSPProvider>
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
