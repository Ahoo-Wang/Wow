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
import { Checkbox } from '../components/checkbox.js';

/**
 * A checkbox that can say "some": checked, unchecked, or a dash when only
 * part of what it stands for is picked.
 *
 * The registry's checkbox draws its one glyph, a check, whenever the box is
 * checked or mixed, and fills only a checked one: a select-all over 2 of 20
 * rows read as a dark ✓ on a white box — all of them, to a user about to act
 * on the selection — while it said `aria-checked="mixed"` (second review
 * R2-81). The registry file is vendored and not edited by hand (D16-8), so
 * the mixed state is drawn here, one layer over it: filled as a checked box
 * is, its check hidden, and a dash in the primary's ink in its place.
 */
export function MixedCheckbox({
  className,
  ...props
}: React.ComponentProps<typeof Checkbox>) {
  return (
    <Checkbox
      className={cn(
        'fve:data-indeterminate:border-primary fve:data-indeterminate:bg-primary fve:data-indeterminate:text-primary-foreground fve:dark:data-indeterminate:bg-primary',
        'fve:data-indeterminate:[&_svg]:hidden',
        "fve:data-indeterminate:before:h-0.5 fve:data-indeterminate:before:w-2 fve:data-indeterminate:before:rounded-full fve:data-indeterminate:before:bg-current fve:data-indeterminate:before:content-['']",
        className,
      )}
      {...props}
    />
  );
}
