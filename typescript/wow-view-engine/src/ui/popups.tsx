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
import { cn } from 'cn';
import { ComboboxContent as VendoredComboboxContent } from './components/combobox.js';
import { DialogContent as VendoredDialogContent } from './components/dialog.js';
import { DropdownMenuContent as VendoredDropdownMenuContent } from './components/dropdown-menu.js';
import { PopoverContent as VendoredPopoverContent } from './components/popover.js';
import { SelectContent as VendoredSelectContent } from './components/select.js';
import { TooltipContent as VendoredTooltipContent } from './components/tooltip.js';
import { useSurfaceTheme } from './ViewSurface.js';

/**
 * The popups this package renders, themed.
 *
 * Every token and base rule of the theme hangs off `.fve-root`, so the host
 * page stays untouched. A popup — a select's list, a menu, a popover, a
 * tooltip, a dialog, a combobox — is portalled to the document body, outside
 * that root, and would render without a background or a border. These
 * wrappers put the class and the surface's theme on the popup itself. The
 * vendored components stay as the registry ships them; this package's own
 * components import the popup contents from here, and a test holds them to
 * it.
 */
type ClassName<S> = string | ((state: S) => string | undefined) | undefined;

/** The popup's own class with the root's in front, whichever form it takes. */
function themedClass<S>(className: ClassName<S>): ClassName<S> {
  return typeof className === 'function'
    ? (state: S) => cn('fve-root', className(state))
    : cn('fve-root', className);
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

export function DialogContent(
  props: React.ComponentProps<typeof VendoredDialogContent>,
) {
  return (
    <VendoredDialogContent
      {...props}
      className={themedClass(props.className)}
      data-theme={useSurfaceTheme()}
    />
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
