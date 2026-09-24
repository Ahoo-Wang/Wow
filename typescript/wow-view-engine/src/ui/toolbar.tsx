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

export interface ToolbarItemProps extends React.ComponentProps<'button'> {
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
