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

import { Checkbox as CheckboxPrimitive } from '@base-ui/react/checkbox';
import { CheckIcon, MinusIcon } from 'lucide-react';
import { cn } from '../../lib/utils.js';

function Checkbox({
  className,
  indeterminate,
  ...props
}: CheckboxPrimitive.Root.Props) {
  const Icon = indeterminate ? MinusIcon : CheckIcon;
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      indeterminate={indeterminate}
      className={cn(
        'fve:peer fve:relative fve:flex fve:size-4 fve:shrink-0 fve:items-center fve:justify-center fve:rounded-[4px] fve:border fve:border-muted-foreground fve:transition-colors fve:outline-none fve:group-has-disabled/field:opacity-50 fve:group-has-[:focus-visible]/field-label:ring-0 fve:group-has-[:focus-visible]/field-label:not-data-checked:border-muted-foreground fve:after:absolute fve:after:-inset-x-3 fve:after:-inset-y-2 fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50 fve:disabled:cursor-not-allowed fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:aria-invalid:aria-checked:border-primary fve:dark:bg-input/30 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:data-checked:border-primary fve:data-checked:bg-primary fve:data-checked:text-primary-foreground fve:group-has-[:focus-visible]/field-label:data-checked:border-primary fve:dark:data-checked:bg-primary fve:data-indeterminate:border-primary fve:data-indeterminate:bg-primary fve:data-indeterminate:text-primary-foreground',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator
        data-slot="checkbox-indicator"
        className="fve:grid fve:place-content-center fve:text-current fve:transition-none fve:[&>svg]:size-3.5"
      >
        <Icon />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  );
}

export { Checkbox };
