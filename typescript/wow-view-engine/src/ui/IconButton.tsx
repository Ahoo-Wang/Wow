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
import { Button } from './components/button.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';

/**
 * A control that is nothing but an icon says its name on hover.
 *
 * D12 puts every function on a screen into an icon button, which leaves the
 * name of each one somewhere only a screen reader goes. So the name is said
 * twice over one string: `aria-label` for the reader, a tooltip for the
 * pointer. **One message, two channels** — a call site that writes a second
 * wording has written a second name for the same control, and the two will
 * drift.
 *
 * This is the one place that pairs them, so "which buttons have a tooltip"
 * stops being a question about call sites. `test/iconTooltips.test.tsx`
 * holds the rule against the rendered DOM: any button on a workbench whose
 * only content is an icon must carry what a tooltip's trigger stamps on
 * whatever it renders.
 *
 * Not every icon button is a plain `Button` — a popup's trigger already
 * renders one, and a segmented switch renders its own item. `IconTooltip`
 * takes that element as it is; `IconButton` is the shorthand for the common
 * case where there is nothing between the tooltip and the button.
 */
export interface IconTooltipProps {
  /** The control's name, said to a reader and shown to a pointer. */
  label: string;
  /** The element the tooltip hangs off — already a button of some kind. */
  render: React.ReactElement<Record<string, unknown>>;
  /** Which side the tooltip takes; `top` unless the room is elsewhere. */
  side?: React.ComponentProps<typeof TooltipContent>['side'];
  /**
   * While true, nothing opens. A drag handle is the case: the pointer is
   * holding the row, and a black label following it round the list says
   * nothing the user does not already know while making the drop harder to
   * see. Keyboard focus still shows it, which is where a handle's name is
   * needed most — the arrows it answers are not written anywhere else.
   */
  silent?: boolean;
  children?: React.ReactNode;
}

export function IconTooltip({
  label,
  render,
  side,
  silent,
  children,
}: IconTooltipProps) {
  return (
    // `disabled` rather than a controlled `open`: a tooltip that is held
    // shut by `open={false}` is a controlled tooltip for the rest of its
    // life, and would never open again once the drag ended.
    <Tooltip disabled={silent}>
      {/* The name goes on the trigger rather than inside `render`, because
          the trigger is what every shape here has in common — and Base UI
          merges it onto whatever element comes out, the vendored Button
          included. */}
      <TooltipTrigger aria-label={label} render={render}>
        {children}
      </TooltipTrigger>
      <TooltipContent side={side}>{label}</TooltipContent>
    </Tooltip>
  );
}

export interface IconButtonProps extends Omit<
  React.ComponentProps<typeof Button>,
  'render'
> {
  /** The button's name, said to a reader and shown to a pointer. */
  label: string;
  /** Which side the tooltip takes; `top` unless the room is elsewhere. */
  side?: IconTooltipProps['side'];
  /** While true, no tooltip opens — see `IconTooltipProps.silent`. */
  silent?: boolean;
}

/**
 * An icon button and its tooltip, over one name.
 *
 * Everything a `Button` takes passes through — `variant`, `size`, `ref`,
 * `disabled`, the data attributes a test looks for — because this adds a
 * name and a tooltip and decides nothing else about the control.
 */
export function IconButton({
  label,
  side,
  silent,
  children,
  ...button
}: IconButtonProps) {
  return (
    <IconTooltip
      label={label}
      side={side}
      {...(silent === undefined ? {} : { silent })}
      render={<Button {...button} />}
    >
      {children}
    </IconTooltip>
  );
}
