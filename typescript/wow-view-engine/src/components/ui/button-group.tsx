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
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils.js';

const buttonGroupVariants = cva(
  'fve:flex fve:w-fit fve:items-stretch fve:*:focus-visible:relative fve:*:focus-visible:z-10 fve:has-[>[data-slot=button-group]]:gap-2 fve:has-[select[aria-hidden=true]:last-child]:[&>[data-slot=select-trigger]:last-of-type]:rounded-r-lg fve:[&>[data-slot=select-trigger]:not([class*=w-])]:w-fit fve:[&>input]:flex-1',
  {
    variants: {
      orientation: {
        horizontal:
          'fve:*:data-slot:rounded-r-none fve:[&>[data-slot]:not(:has(~[data-slot]))]:rounded-r-lg! fve:[&>[data-slot]~[data-slot]]:rounded-l-none fve:[&>[data-slot]~[data-slot]]:border-l-0',
        vertical:
          'fve:flex-col fve:*:data-slot:rounded-b-none fve:[&>[data-slot]:not(:has(~[data-slot]))]:rounded-b-lg! fve:[&>[data-slot]~[data-slot]]:rounded-t-none fve:[&>[data-slot]~[data-slot]]:border-t-0',
      },
    },
    defaultVariants: {
      orientation: 'horizontal',
    },
  },
);

function ButtonGroup({
  className,
  orientation,
  ...props
}: React.ComponentProps<'div'> & VariantProps<typeof buttonGroupVariants>) {
  return (
    <div
      role="group"
      data-slot="button-group"
      data-orientation={orientation}
      className={cn(buttonGroupVariants({ orientation }), className)}
      {...props}
    />
  );
}

export { ButtonGroup };
