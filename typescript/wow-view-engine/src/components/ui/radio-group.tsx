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

import { Radio as RadioPrimitive } from '@base-ui/react/radio';
import { RadioGroup as RadioGroupPrimitive } from '@base-ui/react/radio-group';
import { cn } from '../../lib/utils.js';

function RadioGroup<Value>({
  className,
  ...props
}: RadioGroupPrimitive.Props<Value>) {
  return (
    <RadioGroupPrimitive
      data-slot="radio-group"
      className={cn('fve:grid fve:w-full fve:gap-2', className)}
      {...props}
    />
  );
}

function RadioGroupItem({ className, ...props }: RadioPrimitive.Root.Props) {
  return (
    <RadioPrimitive.Root
      data-slot="radio-group-item"
      className={cn(
        'fve:group/radio-group-item fve:peer fve:relative fve:flex fve:aspect-square fve:size-4 fve:shrink-0 fve:rounded-full fve:border fve:border-input fve:outline-none fve:group-has-[:focus-visible]/field-label:ring-0 fve:group-has-[:focus-visible]/field-label:not-data-checked:border-input fve:after:absolute fve:after:-inset-x-3 fve:after:-inset-y-2 fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring fve:disabled:cursor-not-allowed fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:aria-invalid:aria-checked:border-primary fve:dark:bg-input/30 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:data-checked:border-primary fve:data-checked:bg-primary fve:data-checked:text-primary-foreground fve:group-has-[:focus-visible]/field-label:data-checked:border-primary fve:dark:data-checked:bg-primary',
        className,
      )}
      {...props}
    >
      <RadioPrimitive.Indicator
        data-slot="radio-group-indicator"
        className="fve:flex fve:size-4 fve:items-center fve:justify-center"
      >
        <span className="fve:absolute fve:top-1/2 fve:left-1/2 fve:size-2 fve:-translate-x-1/2 fve:-translate-y-1/2 fve:rounded-full fve:bg-primary-foreground" />
      </RadioPrimitive.Indicator>
    </RadioPrimitive.Root>
  );
}

export { RadioGroup, RadioGroupItem };
