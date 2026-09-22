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

import { cn } from 'cn';
import { Skeleton } from '../components/skeleton.js';
import { SPACE } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ResultBlock } from './ResultBlock.js';

/** How many rows the block is drawn as; the same three a query is. */
const ROWS = 3;

/** How many bordered icon buttons the toolbar's right-hand block holds. */
const CONTROLS = 3;

export interface OpeningSkeletonProps {
  /**
   * Whether the block that is coming is the framed one, so the skeleton
   * wears the frame the result will (D12). A dashboard's is not.
   */
  framed?: boolean;
  /**
   * Whether to draw the title bar. It is left out on the one screen that
   * already has a row in that place: with the sidebar folded away, the
   * shell draws the way back and the view switcher there, and a skeleton
   * title bar under it would be two title bars.
   */
  header?: boolean;
}

/**
 * What stands there while a view is opening.
 *
 * It was one `h-8` bar across the page, which said "something is loading"
 * and nothing else: the page it stood in has a title bar, a framed result
 * with a toolbar on top and rows under it, and a single bar promised none
 * of that — so opening a view was a jump from one shape to another
 * (P-13). This draws the shape that is coming, at the sizes it will come
 * at, which is the whole job of a skeleton: the reader's eye is already
 * where the title will be, and the page does not move under it when the
 * rows land.
 *
 * The frame is `ResultBlock`'s own rather than the same four utilities
 * typed again — the block decides what its first row looks like, and a
 * skeleton with a second opinion about that is a skeleton of a different
 * page. Everything drawn is `aria-hidden`: it is the shape of an answer
 * and not the answer, and the one thing worth saying out loud is said once
 * by the live region.
 */
export function OpeningSkeleton({
  framed = true,
  header = true,
}: OpeningSkeletonProps) {
  const messages = useViewMessages();
  return (
    <div
      data-slot="opening-skeleton"
      // On the region rather than on the live region inside it: `aria-busy`
      // on a live region holds its announcements back until it turns false,
      // and this one never does — it unmounts when the view arrives, and
      // the sentence would never have been read.
      aria-busy="true"
      className={cn('flex min-w-0 flex-col', SPACE.BLOCKS)}
    >
      <span role="status" className="sr-only">
        {messages.label('label.workbench.opening')}
      </span>

      {header && (
        // The same banner the title bar is drawn in: ruled off, and the
        // rule running out under `main`'s padding to meet the sidebar's.
        <div
          data-slot="view-header-skeleton"
          aria-hidden
          className={cn(
            'border-border -mx-4 flex min-h-10 items-center border-b px-4 pb-3',
            SPACE.GROUPS,
          )}
        >
          {/* The kind's icon, the view's name — the left group says which
              view this is — and two controls at the right end, which is
              where the ones about how it is being looked at stand. */}
          <Skeleton className="size-5 shrink-0" />
          <Skeleton className="h-5 w-40" />
          <div className={cn('ml-auto flex items-center', SPACE.GROUPS)}>
            <Skeleton className="h-8 w-20" />
            <Skeleton className="size-8" />
          </div>
        </div>
      )}

      <ResultBlock framed={framed}>
        {/* The toolbar is the block's first row (D12 Ⅳ), so it wears the
            slot the frame rules off and pads — the shape is the frame's to
            decide, and this only says how tall the row is. */}
        <div
          data-slot="result-toolbar"
          aria-hidden
          className={cn('flex min-h-8 items-center', SPACE.GROUPS)}
        >
          <Skeleton className="h-4 w-32" />
          <div className={cn('ml-auto flex items-center', SPACE.GROUPS)}>
            {Array.from({ length: CONTROLS }, (_unused, index) => (
              <Skeleton key={index} className="size-8" />
            ))}
          </div>
        </div>

        <div
          data-slot="result-rows-skeleton"
          aria-hidden
          className={cn('flex flex-col p-3', SPACE.ROWS)}
        >
          {Array.from({ length: ROWS }, (_unused, index) => (
            <Skeleton key={index} className="h-4 w-full" />
          ))}
        </div>
      </ResultBlock>
    </div>
  );
}
