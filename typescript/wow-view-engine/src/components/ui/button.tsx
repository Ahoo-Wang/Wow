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

import { Button as ButtonPrimitive } from '@base-ui/react/button';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils.js';

const buttonVariants = cva(
  'fve:group/button fve:inline-flex fve:shrink-0 fve:items-center fve:justify-center fve:rounded-lg fve:border fve:border-transparent fve:bg-clip-padding fve:text-sm fve:font-medium fve:whitespace-nowrap fve:transition-all fve:outline-none fve:select-none fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring fve:active:not-aria-[haspopup]:translate-y-px fve:disabled:pointer-events-none fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*=size-])]:size-4',
  {
    variants: {
      variant: {
        default:
          'fve:bg-primary fve:text-primary-foreground fve:hover:bg-primary/80',
        outline:
          'fve:border-border fve:bg-background fve:hover:bg-muted fve:hover:text-foreground fve:aria-expanded:bg-muted fve:aria-expanded:text-foreground fve:dark:border-input fve:dark:bg-input/30 fve:dark:hover:bg-input/50',
        secondary:
          'fve:bg-secondary fve:text-secondary-foreground fve:hover:bg-[color-mix(in_oklch,var(--fve-secondary),var(--fve-foreground)_5%)] fve:aria-expanded:bg-secondary fve:aria-expanded:text-secondary-foreground',
        ghost:
          'fve:hover:bg-muted fve:hover:text-foreground fve:aria-expanded:bg-muted fve:aria-expanded:text-foreground fve:dark:hover:bg-muted/50',
        destructive:
          'fve:bg-destructive/10 fve:text-destructive fve:hover:bg-destructive/20 fve:focus-visible:border-destructive/40 fve:focus-visible:ring-destructive/20 fve:dark:bg-destructive/20 fve:dark:hover:bg-destructive/30 fve:dark:focus-visible:ring-destructive/40',
        link: 'fve:text-primary fve:underline-offset-4 fve:hover:underline',
      },
      size: {
        default:
          'fve:h-(--fve-control-height) fve:gap-1.5 fve:px-2.5 fve:has-data-[icon=inline-end]:pr-2 fve:has-data-[icon=inline-start]:pl-2',
        xs: 'fve:h-6 fve:gap-1 fve:rounded-[min(var(--fve-radius-md),10px)] fve:px-2 fve:text-xs fve:in-data-[slot=button-group]:rounded-lg fve:has-data-[icon=inline-end]:pr-1.5 fve:has-data-[icon=inline-start]:pl-1.5 fve:[&_svg:not([class*=size-])]:size-3',
        sm: 'fve:h-7 fve:gap-1 fve:rounded-[min(var(--fve-radius-md),12px)] fve:px-2.5 fve:text-[length:calc(var(--fve-font-size)*32/35)] fve:in-data-[slot=button-group]:rounded-lg fve:has-data-[icon=inline-end]:pr-1.5 fve:has-data-[icon=inline-start]:pl-1.5 fve:[&_svg:not([class*=size-])]:size-3.5',
        lg: 'fve:h-9 fve:gap-1.5 fve:px-2.5 fve:has-data-[icon=inline-end]:pr-2 fve:has-data-[icon=inline-start]:pl-2',
        icon: 'fve:size-(--fve-control-height)',
        'icon-xs':
          'fve:size-6 fve:rounded-[min(var(--fve-radius-md),10px)] fve:in-data-[slot=button-group]:rounded-lg fve:[&_svg:not([class*=size-])]:size-3',
        'icon-sm':
          'fve:size-7 fve:rounded-[min(var(--fve-radius-md),12px)] fve:in-data-[slot=button-group]:rounded-lg',
        'icon-lg': 'fve:size-9',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
);

function Button({
  className,
  variant = 'default',
  size = 'default',
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}

export { Button, buttonVariants };
