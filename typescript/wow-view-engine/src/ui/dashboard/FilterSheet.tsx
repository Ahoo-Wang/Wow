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

import { useState, type ReactNode, type RefObject } from 'react';
import { FilterIcon } from 'lucide-react';
import { Button } from '../components/button.js';
import {
  Sheet,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '../components/sheet.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SheetContent } from '../popups.js';

/**
 * The filter bar in the one-column reading (D26 Q38): one button,
 * 「筛选（已设 n 个）」, that opens every filter in the registry's sheet from
 * the bottom edge — the drawer the visualization panel is on a narrow
 * surface (`SidebarColumn`), the panels' tops still in view above it. A row
 * of chips scrolled sideways on a phone showed one filter and a half, and
 * the half was a control. What the reader cannot change — the board's fixed
 * scope, a filter the page locked — is read beside the button, as it is on
 * the bar: a reading has nothing to open a sheet for.
 */
export function FilterSheet({
  listRef,
  count,
  opens,
  held,
  children,
}: {
  /** The list in the sheet: where a press that takes its own control away lands (`useLanding`). */
  listRef: RefObject<HTMLDivElement | null>;
  /** How many of the reader's filters hold a value. */
  count: number;
  /** Whether anything in the sheet can be changed at all. */
  opens: boolean;
  /** What is read beside the button: the fixed scope and the locked filters. */
  held: ReactNode;
  /** The bar's own chips, the time grouping and 「清空」. */
  children: ReactNode;
}) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  const name = messages.label('label.filters.bar');
  return (
    <div
      data-slot="dashboard-filter-bar"
      data-narrow=""
      role="region"
      aria-label={name}
      className="flex flex-wrap items-center gap-2"
    >
      {held}
      {opens && (
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger
            data-slot="dashboard-filters-open"
            render={<Button variant="outline" size="sm" />}
          >
            <FilterIcon data-icon="inline-start" />
            {count > 0
              ? messages.label('label.filters.sheet-set', { count })
              : name}
          </SheetTrigger>
          <SheetContent
            side="bottom"
            data-slot="dashboard-filter-sheet"
            // Dimmed, not blurred: the panels above are what a value set
            // here narrows, and a reader looks up to see them change.
            overlayClassName="supports-backdrop-filter:backdrop-blur-none"
          >
            <SheetHeader>
              <SheetTitle>{name}</SheetTitle>
            </SheetHeader>
            <div
              ref={listRef}
              data-slot="dashboard-filter-list"
              className="flex min-h-0 flex-col items-start gap-2 overflow-y-auto px-4 pb-4"
            >
              {children}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </div>
  );
}
