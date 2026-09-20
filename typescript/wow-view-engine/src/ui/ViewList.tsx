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
import { LayersIcon, PanelLeftCloseIcon, Settings2Icon } from 'lucide-react';
import {
  audienceOf,
  isSystemScope,
  type ViewAudience,
  type ViewInstanceSummary,
} from '../model/index.js';
import type { ViewListState } from '../react/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import { AUDIENCE_ICON, KIND_ICON } from './kinds.js';
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
 * The order the sidebar shows the two groups in. It is this component's
 * choice, not the model's, which is why it is written here.
 */
const GROUPS: readonly ViewAudience[] = ['personal', 'shared'];

/**
 * The views of one definition, grouped by who they are for, in the user's
 * own order within each group.
 *
 * Three facts share one row and none of them repeats another: the icon says
 * which kind of view it is, because one data definition holds record and
 * analysis views together; the group says who it is for; and the tag says it
 * came with the definition. A system view is a shared view — that is
 * `audienceOf`'s answer, not a third group.
 *
 * A system view is always here even when the store is unreachable, because it
 * travels with the definition rather than with the data.
 */
export function ViewList({
  list,
  title,
  currentId,
  onOpen,
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
      className="flex min-w-0 flex-col gap-3"
    >
      {/* The heading is its own row: the title, and whatever acts on the
          list as a whole sits beside it rather than among the views. */}
      <div
        data-slot="view-list-header"
        className="flex min-w-0 items-center gap-1"
      >
        <h2
          id={headingId}
          data-slot="view-list-title"
          className="min-w-0 flex-1 truncate px-1.5 text-sm font-medium"
        >
          {title || messages.label('label.view.list')}
        </h2>
        {onManage && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={messages.label('label.manage.open')}
            onClick={onManage}
          >
            <Settings2Icon />
          </Button>
        )}
        {onCollapse && (
          <Button
            ref={collapseRef}
            variant="ghost"
            size="icon-sm"
            aria-label={messages.label('label.workbench.collapse-sidebar')}
            aria-expanded
            onClick={onCollapse}
          >
            <PanelLeftCloseIcon />
          </Button>
        )}
      </div>
      <ViewListBody list={list} currentId={currentId} onOpen={onOpen} />
    </nav>
  );
}

function ViewListBody({
  list,
  currentId,
  onOpen,
}: {
  list: ViewListState;
  currentId: string | null;
  onOpen(instanceId: string): void;
}) {
  const messages = useViewMessages();
  if (list.loading)
    return (
      <div className="flex flex-col gap-2">
        {Array.from({ length: 3 }, (_unused, index) => (
          <Skeleton key={`view-${index}`} className="h-8 w-full" />
        ))}
      </div>
    );

  if (list.items.length === 0)
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <LayersIcon />
          </EmptyMedia>
          <EmptyTitle>{messages.label('label.view.none')}</EmptyTitle>
          <EmptyDescription>
            {messages.label(
              list.error ? 'label.view.list-failed' : 'label.view.none-hint',
            )}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );

  return (
    <>
      {GROUPS.map(audience => {
        const items = list.items.filter(
          item => audienceOf(item.scope) === audience,
        );
        return items.length === 0 ? null : (
          <ViewGroup
            key={audience}
            audience={audience}
            items={items}
            currentId={currentId}
            onOpen={onOpen}
          />
        );
      })}
    </>
  );
}

function ViewGroup({
  audience,
  items,
  currentId,
  onOpen,
}: {
  audience: ViewAudience;
  items: ViewInstanceSummary[];
  currentId: string | null;
  onOpen(instanceId: string): void;
}) {
  const messages = useViewMessages();
  const labelId = useId();
  const Icon = AUDIENCE_ICON[audience];
  return (
    <div
      data-slot="view-group"
      role="group"
      aria-labelledby={labelId}
      className="flex flex-col gap-1"
    >
      <span
        id={labelId}
        className="text-muted-foreground flex items-center gap-1 px-1.5 text-xs"
      >
        <Icon className="size-3" aria-hidden />
        {messages.label(`label.scope.group.${audience}`)}
      </span>
      {items.map(item => (
        <ViewListItem
          key={item.id}
          item={item}
          current={item.id === currentId}
          onOpen={onOpen}
        />
      ))}
    </div>
  );
}

function ViewListItem({
  item,
  current,
  onOpen,
}: {
  item: ViewInstanceSummary;
  current: boolean;
  onOpen(instanceId: string): void;
}) {
  const Icon = KIND_ICON[item.kind];
  const messages = useViewMessages();
  return (
    <Button
      variant={current ? 'secondary' : 'ghost'}
      size="sm"
      aria-current={current}
      className="justify-start"
      onClick={() => onOpen(item.id)}
    >
      <Tooltip>
        <TooltipTrigger render={<Icon data-icon="inline-start" />} />
        <TooltipContent>
          {messages.label(`label.kind.${item.kind}`)}
        </TooltipContent>
      </Tooltip>
      <span className="truncate">{item.title}</span>
      {isSystemScope(item.scope) && (
        <Tooltip>
          <TooltipTrigger
            render={
              <Badge variant="secondary" className="ml-auto">
                {messages.label('label.scope.tag.system')}
              </Badge>
            }
          />
          <TooltipContent>
            {messages.label('label.scope.system')}
          </TooltipContent>
        </Tooltip>
      )}
    </Button>
  );
}
