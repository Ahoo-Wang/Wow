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
 * The controls inside a condition pill, which draw no chrome of their own
 * (D12: one border per condition, and the pill *is* the field): its text box,
 * its select, its chip box and its combobox's text box, each the vendored
 * component under `controlChromeVariants`. `chrome="box"` gives a control its
 * registry edge back for the few places one is borrowed outside a pill.
 *
 * A thin wrapper beside each component, as `variants.tsx` says of all of
 * them (D16 ruling 8); `test/variants.test.tsx` holds their class recipes.
 */

import type * as React from 'react';
import { cva } from 'class-variance-authority';
import { cn } from 'cn';
import { ComboboxChips, ComboboxInput } from '../components/combobox.js';
import { Input } from '../components/input.js';
import { SelectTrigger } from '../components/select.js';

/**
 * Whether a control draws its own box, or leaves it to what holds it.
 *
 * One border per condition (D12): a condition pill *is* the field, and the
 * operator select and the value control inside it draw no edge, no fill and
 * no shadow of their own, showing focus by the ring alone. Before D16 that
 * was written as `[&_[data-slot=input]]:border-transparent …` on the pill,
 * reaching two levels down into two vendored components by the slot names
 * they happen to ship with — the most upstream-fragile line in the package,
 * and silently undone by any registry rename. It is the control's own class
 * now.
 */
export const controlChromeVariants = cva('', {
  variants: {
    chrome: {
      box: '',
      none: 'fve:border-transparent fve:bg-transparent fve:shadow-none',
    },
  },
  defaultVariants: { chrome: 'none' },
});

/**
 * What a control inside a pill takes on top of the vendored component's,
 * written out rather than read off the class recipe so the public type does
 * not follow it; `test/variants.test.tsx` holds the two in step.
 */
export interface ControlChromeProps {
  /** `none`, the default: no edge, fill or shadow. `box`: the registry's. */
  chrome?: 'box' | 'none' | null;
}

/**
 * The text box of a condition pill.
 *
 * `chrome="box"` asks for the registry's own edge back, for the one place
 * this control is borrowed outside a pill: the analysis editor's row limit,
 * which stands on a toolbar with nothing around it to be the field.
 */
export function PillInput({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof Input> & ControlChromeProps) {
  return (
    <Input
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}

/**
 * The select of a condition pill — its operator, and the values that are
 * picked rather than typed.
 *
 * `chrome="box"` is the group block's and/or, which sits on the group's own
 * header rather than inside a pill and is the only edge it has.
 */
/**
 * The chip box of a condition pill: the chosen candidates and the search
 * box among them, borderless like every other control inside a pill.
 */
export function PillChips({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof ComboboxChips> & ControlChromeProps) {
  return (
    <ComboboxChips
      className={cn(
        'fve:min-h-7 fve:px-1 fve:py-0.5',
        controlChromeVariants({ chrome }),
        className,
      )}
      {...props}
    />
  );
}

/**
 * The text box of a condition pill that also offers a list — a value typed
 * or picked from the field's own values (`inputs/suggested.tsx`). The
 * registry's `ComboboxInput` is an `InputGroup` with an edge of its own; in a
 * pill the edge is the pill's (D12), so it is taken off here as it is off
 * `PillInput`. Its chevron is left off too: a press on the box opens the
 * list already, and the chevron would be one more unnamed Tab stop in every
 * pill — the placeholder says there is something to pick.
 */
export function PillComboboxInput({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof ComboboxInput> & ControlChromeProps) {
  return (
    <ComboboxInput
      showTrigger={false}
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}

export function PillSelectTrigger({
  chrome,
  className,
  ...props
}: React.ComponentProps<typeof SelectTrigger> & ControlChromeProps) {
  return (
    <SelectTrigger
      className={cn(controlChromeVariants({ chrome }), className)}
      {...props}
    />
  );
}
