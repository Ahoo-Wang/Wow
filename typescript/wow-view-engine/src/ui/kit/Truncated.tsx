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
import { useRef, useState, type HTMLAttributes } from 'react';
import { cn } from 'cn';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { TooltipContent } from './popups.js';

/** The elements a one-line text of user content is drawn as. */
type TruncatedTag = 'span' | 'p' | 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';

export type TruncatedProps = Omit<
  HTMLAttributes<HTMLElement>,
  'children' | 'title'
> & {
  /** The text, whole: drawn on one line, cut with an ellipsis. */
  text: string;
  /** The element it is drawn as: a span, or a heading. */
  as?: TruncatedTag;
  [data: `data-${string}`]: string | number | boolean | undefined;
};

/** Whether an element's text runs past its box. */
const clipped = (element: HTMLElement | null) =>
  element !== null && element.scrollWidth > element.clientWidth;

/**
 * One line of user content that may not fit — a view's name, a panel's
 * title, a legend entry — cut with an ellipsis, and read whole one hover
 * away where it is cut (second review R2-75: 9 of 13 view names cut in the
 * 1280 sidebar, 「品类 → 子类的实付构成（近 12…」 and 「…分解（近 12…」 told
 * apart only by opening each). A `Tooltip` and not the native `title`
 * (D16-6), as the record table's column names do (`SortableHeader`); it
 * opens only where the text is cut, so nothing repeats what is already on
 * screen. A screen reader is read the text itself, which the ellipsis never
 * cuts. The trigger is the element itself: nothing is wrapped around it, so
 * the layout it stood in is untouched.
 */
export function Truncated({
  text,
  as: Tag = 'span',
  className,
  ...props
}: TruncatedProps) {
  const ref = useRef<HTMLElement | null>(null);
  const [open, setOpen] = useState(false);
  return (
    <Tooltip
      open={open}
      onOpenChange={next => setOpen(next && clipped(ref.current))}
    >
      <TooltipTrigger
        render={
          <Tag
            {...props}
            ref={(element: HTMLElement | null) => {
              ref.current = element;
            }}
            className={cn('fve:truncate', className)}
          />
        }
      >
        {text}
      </TooltipTrigger>
      <TooltipContent>{text}</TooltipContent>
    </Tooltip>
  );
}
