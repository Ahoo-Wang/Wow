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
import { cn } from 'cn';
import { Item } from './components/item.js';

/**
 * The two marks a row of this package wears, and the colour each is drawn
 * in.
 *
 * The marks stay at the call sites — `data-dragging` and `data-broken` are
 * what the unit tests and the browser stories read a row's state by — while
 * the colours live here, so that no call site paints a vendored component
 * (decisions.md D16 ruling 8).
 */
const rowItemVariants = cva(
  'data-dragging:bg-muted data-broken:text-muted-foreground',
  {
    variants: {
      /**
       * How much room the row takes.
       *
       * `dense` is a row inside a popover or a dialog — the column
       * settings, the sort entries, the managed views — where the height is
       * the height of the controls on it and a list of a dozen has to stay
       * one list: the registry's own `xs` padding would add half a row
       * again to each of them. `roomy` keeps what the registry gives, which
       * is what a card body and a link list want.
       */
      density: {
        dense: 'px-1 py-0.5',
        roomy: '',
      },
      /**
       * How large the row's `ItemDescription` reads.
       *
       * `prose` is the registry's own `text-sm`, which is right when the
       * description is a sentence meant to be read — the blurb under a
       * link. `label` is this package's one chrome size (`TEXT_UI`, 13px),
       * which is what a *label* is: the field name beside a value on a card
       * is secondary text, and 13 / 14 says so by size before colour and
       * weight get a word in.
       *
       * It is a variant rather than a class at the call site because
       * `ItemDescription` is vendored and sizes itself (D16 ruling 8). The
       * two utilities are written out rather than composed from `TEXT_UI`:
       * Tailwind scans the source for whole class names, and a built one
       * reaches no stylesheet — so this pair and `TEXT_UI` in `layout.ts`
       * move together.
       */
      /**
       * A row that is itself the control — one choice in a picker, rendered
       * as a `button` — lit under the pointer the way the registry lights a
       * row that is a link (`[a]:hover:bg-muted`), and read from its start.
       */
      pressable: {
        true: 'hover:bg-muted cursor-pointer text-left',
        false: '',
      },
      description: {
        prose: '',
        label: [
          '[&_[data-slot=item-description]]:text-[length:var(--_fve-text-ui)]',
          '[&_[data-slot=item-description]]:leading-[1.125rem]',
        ],
      },
    },
    defaultVariants: {
      density: 'roomy',
      description: 'prose',
      pressable: false,
    },
  },
);

export type RowItemProps = React.ComponentProps<typeof Item> &
  VariantProps<typeof rowItemVariants>;

/**
 * One row of a list, over the registry's `Item` (decisions.md D16 ruling 3).
 *
 * Five lists in this package used to be five hand-laid-out flex rows, each
 * with its own gaps, its own truncation and — in the manager's case — a
 * hand-measured width to keep the action icons in a column. They are one
 * recipe now: `ItemMedia` leads, `ItemContent` takes the room, `ItemActions`
 * closes, and `ItemFooter` is the line that wraps underneath. What is left
 * at a call site is the content and the layout it asks for, never a colour.
 */
export function RowItem({
  className,
  density = 'roomy',
  description,
  pressable,
  ...props
}: RowItemProps) {
  return (
    <Item
      size={density === 'dense' ? 'xs' : 'default'}
      className={cn(
        rowItemVariants({ density, description, pressable }),
        className,
      )}
      {...props}
    />
  );
}
