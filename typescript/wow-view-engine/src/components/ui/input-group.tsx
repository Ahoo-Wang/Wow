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

'use client';

import type * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils.js';

import { Button } from './button.js';
import { Input } from './input.js';

function InputGroup({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="input-group"
      role="group"
      className={cn(
        'fve:group/input-group fve:relative fve:flex fve:h-8 fve:w-full fve:min-w-0 fve:items-center fve:rounded-lg fve:border fve:border-input fve:transition-colors fve:outline-none fve:in-data-[slot=combobox-content]:focus-within:border-inherit fve:in-data-[slot=combobox-content]:focus-within:ring-0 fve:has-disabled:bg-input/50 fve:has-disabled:opacity-50 fve:has-[[data-slot=input-group-control]:focus-visible]:border-ring fve:has-[[data-slot=input-group-control]:focus-visible]:ring-3 fve:has-[[data-slot=input-group-control]:focus-visible]:ring-ring/50 fve:has-[[data-slot][aria-invalid=true]]:border-destructive fve:has-[[data-slot][aria-invalid=true]]:ring-3 fve:has-[[data-slot][aria-invalid=true]]:ring-destructive/20 fve:has-[>[data-align=block-end]]:h-auto fve:has-[>[data-align=block-end]]:flex-col fve:has-[>[data-align=block-start]]:h-auto fve:has-[>[data-align=block-start]]:flex-col fve:has-[>textarea]:h-auto fve:dark:bg-input/30 fve:dark:has-disabled:bg-input/80 fve:dark:has-[[data-slot][aria-invalid=true]]:ring-destructive/40 fve:has-[>[data-align=block-end]]:[&>input]:pt-3 fve:has-[>[data-align=block-start]]:[&>input]:pb-3 fve:has-[>[data-align=inline-end]]:[&>input]:pr-1.5 fve:has-[>[data-align=inline-start]]:[&>input]:pl-1.5',
        className,
      )}
      {...props}
    />
  );
}

const inputGroupAddonVariants = cva(
  'fve:flex fve:h-auto fve:cursor-text fve:items-center fve:justify-center fve:gap-2 fve:py-1.5 fve:text-sm fve:font-medium fve:text-muted-foreground fve:select-none fve:group-data-[disabled=true]/input-group:opacity-50 fve:[&>kbd]:rounded-[calc(var(--fve-radius)-5px)] fve:[&>svg:not([class*=size-])]:size-4',
  {
    variants: {
      align: {
        'inline-start':
          'fve:order-first fve:pl-2 fve:has-[>button]:ml-[-0.3rem] fve:has-[>kbd]:ml-[-0.15rem]',
        'inline-end':
          'fve:order-last fve:pr-2 fve:has-[>button]:mr-[-0.3rem] fve:has-[>kbd]:mr-[-0.15rem]',
        'block-start':
          'fve:order-first fve:w-full fve:justify-start fve:px-2.5 fve:pt-2 fve:group-has-[>input]/input-group:pt-2 fve:[.border-b]:pb-2',
        'block-end':
          'fve:order-last fve:w-full fve:justify-start fve:px-2.5 fve:pb-2 fve:group-has-[>input]/input-group:pb-2 fve:[.border-t]:pt-2',
      },
    },
    defaultVariants: {
      align: 'inline-start',
    },
  },
);

function InputGroupAddon({
  className,
  align = 'inline-start',
  ...props
}: React.ComponentProps<'div'> & VariantProps<typeof inputGroupAddonVariants>) {
  return (
    <div
      role="group"
      data-slot="input-group-addon"
      data-align={align}
      className={cn(inputGroupAddonVariants({ align }), className)}
      onClick={e => {
        if ((e.target as HTMLElement).closest('button')) {
          return;
        }
        e.currentTarget.parentElement?.querySelector('input')?.focus();
      }}
      {...props}
    />
  );
}

const inputGroupButtonVariants = cva(
  'fve:flex fve:items-center fve:gap-2 fve:text-sm fve:shadow-none',
  {
    variants: {
      size: {
        xs: 'fve:h-6 fve:gap-1 fve:rounded-[calc(var(--fve-radius)-3px)] fve:px-1.5 fve:[&>svg:not([class*=size-])]:size-3.5',
        sm: '',
        'icon-xs':
          'fve:size-6 fve:rounded-[calc(var(--fve-radius)-3px)] fve:p-0 fve:has-[>svg]:p-0',
        'icon-sm': 'fve:size-8 fve:p-0 fve:has-[>svg]:p-0',
      },
    },
    defaultVariants: {
      size: 'xs',
    },
  },
);

function InputGroupButton({
  className,
  type = 'button',
  variant = 'ghost',
  size = 'xs',
  ...props
}: Omit<React.ComponentProps<typeof Button>, 'size' | 'type'> &
  VariantProps<typeof inputGroupButtonVariants> & {
    type?: 'button' | 'submit' | 'reset';
  }) {
  return (
    <Button
      type={type}
      data-size={size}
      variant={variant}
      className={cn(inputGroupButtonVariants({ size }), className)}
      {...props}
    />
  );
}

function InputGroupText({ className, ...props }: React.ComponentProps<'span'>) {
  return (
    <span
      className={cn(
        'fve:flex fve:items-center fve:gap-2 fve:text-sm fve:text-muted-foreground fve:[&_svg]:pointer-events-none fve:[&_svg:not([class*=size-])]:size-4',
        className,
      )}
      {...props}
    />
  );
}

function InputGroupInput({
  className,
  ...props
}: React.ComponentProps<'input'>) {
  return (
    <Input
      data-slot="input-group-control"
      className={cn(
        'fve:flex-1 fve:rounded-none fve:border-0 fve:bg-transparent fve:shadow-none fve:ring-0 fve:focus-visible:ring-0 fve:disabled:bg-transparent fve:aria-invalid:ring-0 fve:dark:bg-transparent fve:dark:disabled:bg-transparent',
        className,
      )}
      {...props}
    />
  );
}

export {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupText,
  InputGroupInput,
};
