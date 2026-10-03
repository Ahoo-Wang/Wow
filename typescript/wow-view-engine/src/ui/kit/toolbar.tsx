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

import * as React from 'react';
import { Dialog as DialogPrimitive } from '@base-ui/react/dialog';
import { Popover as PopoverPrimitive } from '@base-ui/react/popover';
import { Toolbar as ToolbarPrimitive } from '@base-ui/react/toolbar';
import { useRender } from '@base-ui/react/use-render';

/**
 * A control bar that is one tab stop, with the arrow keys inside it.
 *
 * The shadcn registry carries no `toolbar`, so this is Base UI's primitive
 * with nothing but a `data-slot` added — the same thin-wrapper shape
 * `popups.tsx` uses, and for the same reason: the registry's part is the
 * first choice, and where there is none the primitive is taken directly
 * rather than hand-rolled.
 *
 * What the primitive answers for, and what this package used to re-decide at
 * every bar: `role="toolbar"`, `aria-orientation`, and the roving tabindex —
 * one stop for the whole bar, Arrow keys between the controls, wrapping at
 * both ends. A dense row of icon buttons is exactly the case ARIA's toolbar
 * pattern exists for: without it, eight controls stand between the rows and
 * everything above them.
 */
export function Toolbar({ className, ...props }: ToolbarPrimitive.Root.Props) {
  return (
    <InToolbar.Provider value={true}>
      <ToolbarPrimitive.Root
        data-slot="toolbar"
        className={className}
        {...props}
      />
    </InToolbar.Provider>
  );
}

/**
 * Whether a `ToolbarItem` has a toolbar around it.
 *
 * Base UI's own `Toggle` and `ToggleGroup` do this with their group context —
 * inside one they are composite items, outside one they are plain controls —
 * and the controls a toolbar is built from here are components a test also
 * renders on their own (`ColumnSettings`, `SortSettings`, `ExportButton`).
 * Reading the primitive's context is not on its public surface, so this is
 * the same question asked in this package's own terms.
 */
const InToolbar = React.createContext(false);

interface ToolbarItemProps extends React.ComponentProps<'button'> {
  /**
   * The control this item is. Every one of them is already a button of some
   * kind — a popup's trigger, a tooltip's trigger over one — so the element
   * comes in through `render` and no markup is added around it.
   */
  render: React.ReactElement<Record<string, unknown>>;
}

/**
 * One control of a toolbar, which is an ordinary control anywhere else.
 *
 * Inside a {@link Toolbar} it joins the roving focus order; outside one it
 * renders exactly what it was given, so a component whose trigger is a
 * toolbar item stays usable — and testable — on its own.
 */
export function ToolbarItem(props: ToolbarItemProps) {
  // Two components rather than a branch inside one: each owns its own hooks,
  // and the answer cannot change under a mounted item — a toolbar does not
  // appear around a control that was already drawn.
  return React.useContext(InToolbar) ? (
    <CompositeItem {...props} />
  ) : (
    <LooseItem {...props} />
  );
}

function CompositeItem({ render, ...props }: ToolbarItemProps) {
  return <ToolbarPrimitive.Button render={render} {...props} />;
}

function LooseItem({ render, ref, ...props }: ToolbarItemProps) {
  // Base UI's own merge, so handlers chain and `className` joins rather than
  // the outer props overwriting what the element came with.
  return useRender({ render, ...(ref ? { ref } : {}), props });
}

/**
 * A popover opened from a toolbar, whose content is drawn outside it.
 *
 * Base UI's `Toolbar` hands its roving-focus context to its whole React
 * subtree — and a portal is still that subtree — so every control in a
 * popover opened from a toolbar button believed it was an item of the bar:
 * a checkbox, a pin toggle, a select trigger each left its `tabindex` to a
 * bar that never gave it one. Chromium still reached the native buttons;
 * Safari's default Tab, which stops only on fields and on what carries a
 * `tabindex`, skipped them, and a checkbox (a `<span>`) was out of every
 * browser's Tab order until each was given `tabIndex` by hand (the
 * 2026-09-25 keyboard walkthrough).
 *
 * So the popover is split along the line the bar draws: the **trigger**
 * stands in the toolbar and is one of its stops, the **popup** — the root
 * and everything in it — is rendered beside the toolbar rather than inside
 * it, and the two are joined by a handle (`Popover.createHandle`, Base UI's
 * detached trigger). Nothing in the popup is under the bar any more, so no
 * control in it needs to be told what it already is.
 */
export interface DetachedPopover {
  handle: PopoverHandle;
  /** Which half this rendering is: the bar's button, or the popup. */
  part: 'trigger' | 'popup';
}

/** The handle `DetachedPopover` joins its two halves by. */
export type PopoverHandle = PopoverPrimitive.Handle<unknown>;

/** A new handle, as `useState` takes an initialiser. */
function createPopoverHandle(): PopoverHandle {
  return PopoverPrimitive.createHandle<unknown>();
}

/** One handle for the life of the component, for one detached popover. */
export function usePopoverHandle(): PopoverHandle {
  const [handle] = React.useState(createPopoverHandle);
  return handle;
}

/**
 * A window opened from a toolbar, whose content is drawn outside it: the
 * export's (`ExportButton`, `ExportMenu`). The same split as
 * `DetachedPopover`, for the same reason — the Close, Cancel and Export of
 * a window drawn under the bar each took itself for one of its items and
 * wrote no `tabindex` — joined by `Dialog.createHandle`.
 */
export interface DetachedDialog {
  handle: DialogHandle;
  /**
   * What the keyboard goes back to as the window closes, where the window
   * is opened by something that is not its own trigger — a menu's item,
   * gone with the menu, so the menu's button in the bar.
   */
  opener: React.RefObject<HTMLButtonElement | null>;
  /** Which half this rendering is: the bar's button, or the window. */
  part: 'trigger' | 'popup';
}

/** The handle `DetachedDialog` joins its two halves by. */
export type DialogHandle = DialogPrimitive.Handle<unknown>;

/** A new handle, as `useState` takes an initialiser. */
function createDialogHandle(): DialogHandle {
  return DialogPrimitive.createHandle<unknown>();
}

/**
 * One handle and one opener for the life of the component, for one
 * detached window; the caller adds which half it draws.
 */
export function useDialogHandle(): Omit<DetachedDialog, 'part'> {
  const [handle] = React.useState(createDialogHandle);
  const opener = React.useRef<HTMLButtonElement>(null);
  return { handle, opener };
}
