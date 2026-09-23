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

import { useId, type RefObject } from 'react';
import { cn } from 'cn';
import {
  PanelLeftCloseIcon,
  PlusIcon,
  Settings2Icon,
  StarIcon,
} from 'lucide-react';
import {
  audienceOf,
  isSystemScope,
  VIEW_AUDIENCES,
  type ViewAudience,
  type ViewInstanceSummary,
} from '../model/index.js';
import type { ViewListState } from '../react/index.js';
import { AlertAction, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { LineAlert } from './alerts.js';
import { IconButton } from './IconButton.js';
import { NewViewControl, type NewViewCommand } from './workbench/NewView.js';
import { SidebarItem } from './variants.js';
import { KIND_ICON } from './kinds.js';
import { SystemMark } from './SystemMark.js';
import { SPACE, TEXT_UI } from './layout.js';
import { useViewMessages } from './MessagesProvider.js';
import { Skeleton } from './components/skeleton.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';

export interface ViewListProps {
  list: ViewListState;
  /**
   * What this list is a list of — the definition's own title. It is the
   * definition's to say rather than the list's, so a workbench reads it off
   * `engine.definitions` and passes it; the list renders it because the
   * heading is also what names the `nav` to a screen reader.
   */
  title?: string;
  currentId: string | null;
  onOpen(instanceId: string): void;
  /**
   * Makes a view from nothing. Given one, the heading grows the `+` D12
   * puts there; left out — no permission, or nothing behind it — it does
   * not exist (D4). The empty line below it says nothing about creating:
   * the offer is made once, in the room the new view will fill.
   */
  /** Makes a view; absent when none may be made here (D4). */
  create?: NewViewCommand;
  /**
   * Reads the list again after it failed. The failure is said where the
   * missing rows would be — under the views that are there, or in place of
   * them — with this beside it; without it the sentence stands alone.
   */
  onRetry?(): void;
  /**
   * Opens the view manager — renaming, deleting, reordering and the default
   * view. Given one, the heading grows a button for it; left out, the list is
   * a list. The dialog itself is not the list's: the header offers the same
   * way in while the list is collapsed away, and two entries onto two dialogs
   * would be two dialogs, so whoever draws both holds the open state.
   */
  onManage?(): void;
  /**
   * Folds the list away. Given one, the heading grows the button that does
   * it; left out, the list cannot be collapsed and says so by having no
   * control for it.
   */
  onCollapse?(): void;
  /**
   * The collapse button itself, so whoever owns the state can put focus on
   * the control that undoes what it just did. Expanding lands here; the
   * button that expands lives in the title bar and is held there.
   */
  collapseRef?: RefObject<HTMLButtonElement | null>;
}

/**
 * The views of one definition, grouped by who they are for, in the user's
 * own order within each group.
 *
 * It is a **navigation column and not a panel**: it paints its own grey
 * ground and rules itself off from the work area, so a glance says which
 * side of the screen is the list and which side is the view. Everything
 * inside then has one ground to stand out from — the open view is the work
 * area's own white on it, hover is a step the other way, and neither can be
 * read as the other.
 *
 * Three facts share one row and none of them repeats another: the icon says
 * which kind of view it is, because one data definition holds record and
 * analysis views together; the group heading says who it is for; and the
 * lock at the row's end says it came with the definition. A system view is
 * a shared view — that is `audienceOf`'s answer, not a third group.
 *
 * A system view is always here even when the store is unreachable, because it
 * travels with the definition rather than with the data.
 */
export function ViewList({
  list,
  title,
  currentId,
  onOpen,
  create,
  onRetry,
  onManage,
  onCollapse,
  collapseRef,
}: ViewListProps) {
  const messages = useViewMessages();
  const headingId = useId();
  return (
    <nav
      data-slot="view-list"
      aria-labelledby={headingId}
      // The ground and the rule are the column's own rather than the
      // `aside`'s around it, so a host that composes `ViewList` into its own
      // frame gets the navigation column and not a bare list on white.
      //
      // The rule follows the layout: the surface is a column below `md` and
      // a row from there up, so the edge between list and work area is the
      // bottom one until the two stand side by side.
      className="bg-sidebar text-sidebar-foreground border-sidebar-border flex min-w-0 flex-1 flex-col border-b md:border-r md:border-b-0"
    >
      {/* The heading is its own row: the definition's name, and whatever acts
          on the list as a whole sits beside it rather than among the views.

          It is ruled off, and the rule lands on the same line as the title
          bar's: the two columns each have a head, and two heads that end at
          two different heights read as two pages side by side. The geometry
          is therefore copied rather than guessed — `WorkbenchShell`'s `main`
          opens with `p-4`, and its `view-header-block` is a `min-h-10` row
          over `pb-3` and a border, so this is the same four numbers in the
          same order. `RecordWorkbench.test.stories.tsx` measures the two
          bottoms against each other.

          The `+` is D12's third button, and it is here only with a command
          behind it (`useWorkbench.create`): a control with no command is a
          promise, so by D4 it is absent rather than disabled. It leads: it
          is the one action that adds to the list under it, the other two
          act on the list as it is. */}
      <div
        data-slot="view-list-header"
        className="border-sidebar-border border-b px-3 pt-4 pb-3"
      >
        <div className="flex min-h-10 min-w-0 items-center gap-1">
          {/* The page's own name, one level above every view in it (the
              view's name is the `h2` in the title bar): a definition is the
              thing this whole screen is about, and a heading the size of
              the group labels under it read as one more of them. */}
          <h1
            id={headingId}
            data-slot="view-list-title"
            className="min-w-0 flex-1 truncate px-1.5 text-base font-semibold"
          >
            {title || messages.label('label.view.list')}
          </h1>
          {create && (
            <NewViewControl
              command={create}
              trigger={props => (
                <IconButton
                  label={messages.label('label.view.new')}
                  variant="ghost"
                  size="icon-sm"
                  {...props}
                >
                  <PlusIcon />
                </IconButton>
              )}
            />
          )}
          {onManage && (
            <IconButton
              label={messages.label('label.manage.open')}
              variant="ghost"
              size="icon-sm"
              onClick={onManage}
            >
              <Settings2Icon />
            </IconButton>
          )}
          {onCollapse && (
            <IconButton
              ref={collapseRef}
              label={messages.label('label.workbench.collapse-sidebar')}
              variant="ghost"
              size="icon-sm"
              aria-expanded
              onClick={onCollapse}
            >
              <PanelLeftCloseIcon />
            </IconButton>
          )}
        </div>
      </div>
      <div
        data-slot="view-list-body"
        className={cn('flex min-w-0 flex-col p-3', SPACE.ROWS)}
      >
        <ViewListBody
          list={list}
          currentId={currentId}
          onOpen={onOpen}
          onRetry={onRetry}
        />
      </div>
    </nav>
  );
}

function ViewListBody({
  list,
  currentId,
  onOpen,
  onRetry,
}: {
  list: ViewListState;
  currentId: string | null;
  onOpen(instanceId: string): void;
  onRetry?(): void;
}) {
  const messages = useViewMessages();
  // The way to ask again, drawn wherever the failure is said.
  const retry = onRetry && (
    <Button variant="outline" size="xs" onClick={onRetry}>
      {messages.label('label.manage.reload')}
    </Button>
  );
  /**
   * Why the list is short, or why it is not here at all — one line, the
   * store's own reason in the issue's words, with the way to ask again at
   * its end. A warning rather than an error: nothing on screen is broken,
   * something is missing.
   */
  const failed = list.error && (
    <LineAlert tone="warning" frame="bare" data-slot="view-list-failed">
      <AlertTitle>{messages.issue(list.error)}</AlertTitle>
      {retry && <AlertAction>{retry}</AlertAction>}
    </LineAlert>
  );
  if (list.loading)
    return (
      <div className="flex flex-col gap-2">
        {Array.from({ length: 3 }, (_unused, index) => (
          <Skeleton key={`view-${index}`} className="h-8 w-full" />
        ))}
      </div>
    );

  /* Where the views would be, one line and nothing else (user, 2026-09-22).
     It used to be the work area's empty state a second time — the same icon,
     the same sentence, the same hint about making one — in a 224px column
     forty pixels under the `+` that makes one. One question is answered in
     one place: **this** column says how many views there are, and the room
     the view will fill (`workbench/NoViews.tsx`) is where the sentence and
     the button belong, because that is the room being offered. No button
     here, then, and no second copy of the hint that points at one.

     A failure is a different question and keeps its own answer: a list that
     could not be read does not say there are no views, it says it could not
     be read, and it is the only place the way to ask again can stand. */
  if (list.items.length === 0)
    return (
      failed || (
        // `sidebar-foreground/70` for the reason the group heading gives:
        // `muted-foreground` clears 4.5:1 on white and measures 4.34:1 on
        // this column's ground.
        <p
          data-slot="view-list-empty"
          className={cn('text-sidebar-foreground/70 px-1.5', TEXT_UI)}
        >
          {messages.label('label.view.none')}
        </p>
      )
    );

  return (
    <>
      {/* Beside the views that are still here — the declared ones — so a
          list that is short says it is short rather than looking complete. */}
      {failed}
      {VIEW_AUDIENCES.map(audience => {
        const items = list.items.filter(
          item => audienceOf(item.scope) === audience,
        );
        return items.length === 0 ? null : (
          <ViewGroup
            key={audience}
            audience={audience}
            items={items}
            currentId={currentId}
            defaultId={list.preferences?.defaultInstanceId ?? null}
            onOpen={onOpen}
          />
        );
      })}
    </>
  );
}

/**
 * One audience's views, under a heading that can be read.
 *
 * The heading is a heading and not a caption beside an icon: it is the one
 * thing that divides the column, and a grey word with a padlock next to it
 * read as another row rather than as the line above a set of them. The icon
 * is gone for the same reason — the words already say who the group is for,
 * and a second glyph column beside the kind icons said it twice.
 */
function ViewGroup({
  audience,
  items,
  currentId,
  defaultId,
  onOpen,
}: {
  audience: ViewAudience;
  items: ViewInstanceSummary[];
  currentId: string | null;
  /** The view that opens first, which wears the star. */
  defaultId: string | null;
  onOpen(instanceId: string): void;
}) {
  const messages = useViewMessages();
  const labelId = useId();
  return (
    <div
      data-slot="view-group"
      role="group"
      aria-labelledby={labelId}
      className="flex flex-col gap-1"
    >
      {/* Not `muted-foreground`: that grey was picked to clear 4.5:1 on
          *white*, and on the column's ground it measures 4.34:1 — a rule the
          column's own ground would quietly break for every secondary word on
          it. `sidebar-foreground/70` is the registry's own recipe for this,
          and it moves with whatever ground a host sets: 5.5:1 here, 7.1:1 in
          dark, 5.1:1 on a hovered row and 5.8:1 on the open one. */}
      {/* One level under the page's name, so the outline reads h1 → h2 →
          the views: a group is a heading of the list, not of the page. */}
      <h2
        id={labelId}
        data-slot="view-group-heading"
        className={cn('text-sidebar-foreground/70 px-1.5 font-medium', TEXT_UI)}
      >
        {messages.label(`label.scope.group.${audience}`)}
      </h2>
      {items.map(item => (
        <ViewListItem
          key={item.id}
          item={item}
          current={item.id === currentId}
          isDefault={item.id === defaultId}
          onOpen={onOpen}
        />
      ))}
    </div>
  );
}

/**
 * One view in the column.
 *
 * What the open one looks like, and why it is a bar rather than another
 * step of grey, is `SidebarItem`'s (`ui/variants.tsx`): the colours of a
 * vendored `Button` live in one wrapper beside the component rather than in
 * a `className` at each call site (D16). This row says *which* view it is
 * and whether that view is the open one, and nothing about the paint.
 *
 * The star is the manager's star, read off the same preference, so the two
 * screens cannot disagree about which view opens first. It is drawn rather
 * than pressed: setting the default is the manager's job, and a list whose
 * rows both open a view and change a preference has two meanings per click.
 */
function ViewListItem({
  item,
  current,
  isDefault,
  onOpen,
}: {
  item: ViewInstanceSummary;
  current: boolean;
  isDefault: boolean;
  onOpen(instanceId: string): void;
}) {
  const Icon = KIND_ICON[item.kind];
  const messages = useViewMessages();
  return (
    <SidebarItem current={current} onClick={() => onOpen(item.id)}>
      {/* The kind, hung off a wrapper rather than off the glyph itself.
          `Button` draws `[&_svg]:pointer-events-none` over everything inside
          it, so an `<svg>` that is a tooltip's trigger receives no pointer
          at all and the label could never open — the row said its kind to a
          pointer in the source and to nobody on screen (D-2). A span takes
          the hover the glyph refuses, since a hit on a `pointer-events:
          none` child lands on its parent.

          The trigger stays this small rather than becoming the whole row:
          the row may hold a second tooltip (the system lock at its end), and
          two triggers one inside the other would open both labels at once
          the moment the tag is pointed at. The kind stays out of the row's
          *name* for the same reason it is an icon and not a word: the row
          is called by the view it opens, and a category said in front of
          every name is a list where every item starts with the same two
          syllables. */}
      <Tooltip>
        <TooltipTrigger
          render={<span data-slot="view-kind" className="flex shrink-0" />}
        >
          <Icon data-icon="inline-start" />
        </TooltipTrigger>
        <TooltipContent>
          {messages.label(`label.kind.${item.kind}`)}
        </TooltipContent>
      </Tooltip>
      <span className="truncate">{item.title}</span>
      {/* The row's end holds the facts about the view rather than its name:
          the star when it opens first, and the lock when it came with the
          definition (`SystemMark`). One group pushed to the end, so a
          default system view has both side by side and neither floats. */}
      {(isDefault || isSystemScope(item.scope)) && (
        <span className="ml-auto flex shrink-0 items-center gap-1.5">
          {isDefault && (
            <>
              <StarIcon
                data-slot="view-default-star"
                className="text-primary size-3.5 fill-current"
                aria-hidden
              />
              {/* The star is a picture of a fact, so the fact is also a
                  word: a reader hears "Mine, Default" rather than nothing. */}
              <span className="sr-only">
                {messages.label('label.manage.default')}
              </span>
            </>
          )}
          {isSystemScope(item.scope) && <SystemMark />}
        </span>
      )}
    </SidebarItem>
  );
}
