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

import { mergeProps } from '@base-ui/react/merge-props';
import { useRender } from '@base-ui/react/use-render';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '../../lib/utils.js';

const badgeVariants = cva(
  'fve:group/badge fve:inline-flex fve:h-5 fve:w-fit fve:shrink-0 fve:items-center fve:justify-center fve:gap-1 fve:overflow-hidden fve:rounded-4xl fve:border fve:border-transparent fve:px-2 fve:py-0.5 fve:text-xs fve:font-medium fve:whitespace-nowrap fve:transition-all fve:focus-visible:border-ring fve:focus-visible:ring-[3px] fve:focus-visible:ring-ring/50 fve:has-data-[icon=inline-end]:pr-1.5 fve:has-data-[icon=inline-start]:pl-1.5 fve:aria-invalid:border-destructive fve:aria-invalid:ring-destructive/20 fve:dark:aria-invalid:ring-destructive/40 fve:[&>svg]:pointer-events-none fve:[&>svg]:size-3!',
  {
    variants: {
      variant: {
        default:
          'fve:bg-primary fve:text-primary-foreground fve:[a]:hover:bg-primary/80',
        secondary:
          'fve:bg-secondary fve:text-secondary-foreground fve:[a]:hover:bg-secondary/80',
        success: 'fve:bg-success/10 fve:text-success',
        warning: 'fve:bg-warning/10 fve:text-warning',
        info: 'fve:bg-info/10 fve:text-info',
        destructive:
          'fve:bg-destructive/10 fve:text-destructive fve:focus-visible:ring-destructive/20 fve:dark:bg-destructive/20 fve:dark:focus-visible:ring-destructive/40 fve:[a]:hover:bg-destructive/20',
        outline:
          'fve:border-border fve:text-foreground fve:[a]:hover:bg-muted fve:[a]:hover:text-muted-foreground',
        ghost:
          'fve:hover:bg-muted fve:hover:text-muted-foreground fve:dark:hover:bg-muted/50',
        link: 'fve:text-primary fve:underline-offset-4 fve:hover:underline',
      },
    },
    defaultVariants: { variant: 'default' },
  },
);

function Badge({
  className,
  variant = 'default',
  render,
  ...props
}: useRender.ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return useRender({
    defaultTagName: 'span',
    props: mergeProps<'span'>(
      { className: cn(badgeVariants({ variant }), className) },
      props,
    ),
    render,
    state: { slot: 'badge', variant },
  });
}

export { Badge, badgeVariants };
