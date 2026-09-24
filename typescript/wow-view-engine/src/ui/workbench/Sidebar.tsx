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

import { useRef, type ReactNode, type RefObject } from 'react';
import { PanelLeftOpenIcon } from 'lucide-react';
import { Sheet, SheetTitle } from '../components/sheet.js';
import { IconButton } from '../IconButton.js';
import { SheetContent } from '../popups.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ViewList, type ViewListProps } from '../ViewList.js';
import { ViewSwitcher, type ViewSwitcherProps } from '../ViewSwitcher.js';
import { useKindWord } from '../kinds.js';

export interface SidebarColumnProps extends Omit<ViewListProps, 'onRetry'> {
  /**
   * What stands in the column in place of the list while it is given — the
   * shell's `panel` slot, read as React reads a child. It shows whether or
   * not the list is folded.
   */
  panel?: ReactNode;
  /**
   * The surface is too narrow for a column beside the view
   * (`useNarrowSurface`): the panel is drawn in a drawer over the page
   * rather than as a block on top of the result.
   */
  narrow?: boolean;
  /** The drawer was dismissed — Escape, a press outside, its close button. */
  onPanelClose?(): void;
  /** Whether the list is beside the view rather than folded away. */
  open: boolean;
}

/**
 * The column beside the view: the visualization panel while one is given
 * (D20 屏 I), otherwise the view list while the sidebar is open, otherwise
 * nothing — the folded list lives in the title bar as `FoldedSidebar`.
 *
 * On a narrow surface there is no column beside anything: the shell stacks,
 * and the panel stood on top of the result it configures — ten tiles and a
 * button, a phone's whole first screen, with the chart pushed below it
 * (2026-09-23 audit). There it is the registry's sheet from the bottom edge
 * (the drawer position), over the page, the result's top still in view
 * above it; dismissing it is the panel's own way back.
 */
export function SidebarColumn({
  panel,
  narrow = false,
  onPanelClose,
  open,
  list,
  ...rest
}: SidebarColumnProps) {
  const messages = useViewMessages();
  const landing = useRef<HTMLDivElement>(null);
  if (panel && narrow)
    return (
      <Sheet
        open
        onOpenChange={next => {
          if (!next) onPanelClose?.();
        }}
      >
        <SheetContent
          side="bottom"
          data-slot="view-panel"
          data-drawer=""
          className="bg-sidebar text-sidebar-foreground gap-0"
          // The panel's own back arrow is its way out, as it is beside the
          // view; a close button beside it would be a second saying the same.
          showCloseButton={false}
          // Dimmed, not blurred: the result above is what a pick here
          // redraws, and a reader looks up to see it change.
          overlayClassName="supports-backdrop-filter:backdrop-blur-none"
          // The keyboard lands where the panel's own levels land it: on the
          // heading it is sent to (`tabIndex={-1}`), which exists only once
          // the drawer has drawn its content — the panel's own effect runs
          // before that. Later levels and the way back to the button that
          // opened it are the panel's (`AnalysisParts`).
          initialFocus={() =>
            landing.current?.querySelector<HTMLElement>('[tabindex="-1"]') ??
            true
          }
          finalFocus={false}
        >
          <SheetTitle className="sr-only">
            {messages.label('label.chart.picker')}
          </SheetTitle>
          <div ref={landing} className="contents">
            {panel}
          </div>
        </SheetContent>
      </Sheet>
    );
  return (
    <>
      {panel && (
        <aside
          data-slot="view-panel"
          className="bg-sidebar text-sidebar-foreground border-sidebar-border flex w-full shrink-0 flex-col border-b md:w-64 md:border-r md:border-b-0"
        >
          {panel}
        </aside>
      )}
      {!panel && open && (
        // Bare: the ground, the padding and the rule that divides the two
        // columns are the list's own (D12), so an `aside` that also painted
        // them would be a second opinion about where the column ends. The
        // `Separator` that used to stand here went with them — one edge,
        // drawn once, by whichever part the edge belongs to.
        //
        // The same 16rem as the panel that takes its place (and shadcn's own
        // sidebar): a list a size narrower moved the whole work area 32px
        // each time the panel opened or closed, and cut a six-character
        // definition name beside the heading's three buttons.
        <aside
          data-slot="view-sidebar"
          className="flex w-full shrink-0 flex-col md:w-64"
        >
          <ViewList
            list={list}
            onRetry={list.error ? () => list.reload() : undefined}
            {...rest}
          />
        </aside>
      )}
    </>
  );
}

export interface FoldedSidebarProps extends ViewSwitcherProps {
  /** The definition's title, which the folded list leaves in the bar. */
  title?: string;
  /** The unfold button, focused when collapsing took the list away. */
  expandRef: RefObject<HTMLButtonElement | null>;
  /** A press of the unfold button. */
  onExpand(): void;
}

/**
 * What the sidebar leaves in the title bar while it is folded away: the way
 * back to the list, the definition's name and the list itself as one control.
 * Both exist only while the sidebar is away — with it on screen, the list
 * *is* the switcher and the sidebar's heading is the definition's title.
 */
export function FoldedSidebar({
  title,
  expandRef,
  onExpand,
  ...switcher
}: FoldedSidebarProps) {
  const messages = useViewMessages();
  const word = useKindWord();
  return (
    <div
      data-slot="view-collapsed"
      // No box of its own: `contents` hands the way back, the definition's
      // name and the switcher to the identity group as its own items.
      //
      // It used to be a flex box that carried `grow` — the group's spring —
      // and the switcher grew inside it up to its own label (`w-0 grow
      // max-w-fit`). With a long name that was fine: the switcher took the
      // room and the audience word and Save stood right after it. With a
      // short one the switcher stopped at its label and the box went on
      // growing, so the audience and Save stood at the far end of an empty
      // stretch instead of against the name (D12 Ⅰ). Capping the box at
      // `max-w-max` was no answer: the switcher is `w-0` precisely so a
      // long name does not ask the row for the whole string, which also
      // makes its max-content contribution its 6em floor — the cap pinned
      // it there. As direct items the switcher is the group's spring
      // itself, grows to its label and no further, the audience and Save
      // follow it, and whatever is left over lies after Save where nothing
      // stands. The group's floor is unchanged: it is the sum of its items'
      // floors either way, and the switcher keeps its own.
      className="contents"
    >
      <IconButton
        ref={expandRef}
        label={messages.label(word('label.workbench.expand-sidebar'))}
        variant="ghost"
        size="icon-sm"
        aria-expanded={false}
        onClick={onExpand}
      >
        <PanelLeftOpenIcon />
      </IconButton>
      {title && (
        // With the list folded away this is where the page's name lives, so
        // it is the `h1` the sidebar's heading was, at the weight of a name
        // rather than a caption: a muted small word before the switcher
        // read as a hint, and nothing said it was the parent of the view
        // beside it. The slash does — the two are a path, "Orders / Pending".
        //
        // Still the first thing to go when the row runs out of room: the
        // view's own name outranks the name of everything it is one of.
        // "When the row runs out of room" is a fact about the bar, and the
        // viewport `sm:` this used to ask answered a different question: in
        // a 360px panel on a wide page it showed "Orders" in full while the
        // view's own name was down to "全…". `@md/header` asks the bar
        // itself (`@container/header` in `ViewHeader`); `@2xl` was so
        // eager that most embeddings never saw the name at all.
        <>
          {/* `text-base`, the same 16/600 the sidebar's heading wears when
              the list is open: folding the list away moves this `h1`, it
              does not demote it. At `text-sm` it was 14 over a 13px
              switcher — two heading levels squashed into one and a half,
              and the page's own name set smaller than the view it holds. */}
          <h1
            data-slot="definition-title"
            className="hidden min-w-0 truncate text-base font-semibold @md/header:block"
          >
            {title}
          </h1>
          <span
            aria-hidden
            data-slot="definition-separator"
            className="text-muted-foreground hidden select-none @md/header:inline"
          >
            /
          </span>
        </>
      )}
      <ViewSwitcher {...switcher} />
    </div>
  );
}
