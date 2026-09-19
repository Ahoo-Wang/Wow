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
import { Dialog as DialogPrimitive } from '@base-ui/react/dialog';
import { cn } from 'cn';
import { XIcon } from 'lucide-react';
import { Button } from './components/button.js';
import { ComboboxContent as VendoredComboboxContent } from './components/combobox.js';
import {
  DialogClose,
  DialogOverlay,
  DialogPortal,
  type DialogContent as VendoredDialogContent,
} from './components/dialog.js';
import { DropdownMenuContent as VendoredDropdownMenuContent } from './components/dropdown-menu.js';
import { PopoverContent as VendoredPopoverContent } from './components/popover.js';
import { SelectContent as VendoredSelectContent } from './components/select.js';
import { TooltipContent as VendoredTooltipContent } from './components/tooltip.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceTheme } from './ViewSurface.js';

/**
 * The popups this package renders, themed.
 *
 * Every token and base rule of the theme hangs off `.fve-root`, so the host
 * page stays untouched. A popup — a select's list, a menu, a popover, a
 * tooltip, a dialog, a combobox — is portalled to the document body, outside
 * that root, and would render without a background or a border. These
 * wrappers put the class and the surface's mode on the popup itself — the
 * pinned `theme` when there is one, otherwise the mode the surface resolved
 * from the cascade, so a `.dark` on any ancestor reaches the popup and the
 * class does not have to sit on `<html>`. The stylesheet's `color-scheme`
 * stays the source of truth; nothing here decides the mode. The vendored
 * components stay as the registry ships them; this package's own components
 * import the popup contents from here, and a test holds them to it.
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

export function ComboboxContent(
  props: React.ComponentProps<typeof VendoredComboboxContent>,
) {
  return (
    <VendoredComboboxContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
  );
}

/** The vendored popup's classes, copied verbatim from `components/dialog.tsx`. */
const DIALOG_POPUP_CLASS =
  'fixed top-1/2 left-1/2 z-50 grid w-full max-w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 gap-4 rounded-xl bg-popover p-4 text-sm text-popover-foreground ring-1 ring-foreground/10 duration-100 outline-none sm:max-w-sm data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95';

/**
 * The one popup this file composes instead of wrapping.
 *
 * A dialog portals two elements, not one: the backdrop is the popup's
 * *sibling* inside the portal, so putting `fve-root` on what the vendored
 * `DialogContent` accepts — the popup — leaves the backdrop outside every
 * root. The build scopes the whole stylesheet to
 * `:where(.fve-root, .fve-root *)`, so an unrooted backdrop loses every one
 * of its utilities and paints nothing: a dialog with no dimming behind it.
 * Wrapping cannot reach the sibling, so the markup lives here — a faithful
 * copy of the vendored `DialogContent` — with the root class and the
 * surface's mode on *both* portalled elements. `components/dialog.tsx` stays
 * as the registry ships it; keep this in step when it is updated.
 */
export function DialogContent({
  className,
  children,
  showCloseButton = true,
  ...props
}: React.ComponentProps<typeof VendoredDialogContent>) {
  const theme = useSurfaceTheme();
  const messages = useViewMessages();
  return (
    <DialogPortal>
      <DialogOverlay className="fve-root" data-theme={theme} />
      <DialogPrimitive.Popup
        data-slot="dialog-content"
        {...props}
        className={withClass(DIALOG_POPUP_CLASS, themedClass(className))}
        data-theme={theme}
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

export function DropdownMenuContent(
  props: React.ComponentProps<typeof VendoredDropdownMenuContent>,
) {
  return (
    <VendoredDropdownMenuContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
  );
}

export function PopoverContent(
  props: React.ComponentProps<typeof VendoredPopoverContent>,
) {
  return (
    <VendoredPopoverContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
  );
}

export function SelectContent(
  props: React.ComponentProps<typeof VendoredSelectContent>,
) {
  return (
    <VendoredSelectContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
  );
}

export function TooltipContent(
  props: React.ComponentProps<typeof VendoredTooltipContent>,
) {
  return (
    <VendoredTooltipContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
  );
}
