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

import { LayersIcon, LockIcon, UsersIcon } from 'lucide-react';
import type { ViewInstanceSummary, ViewScope } from '../model/index.js';
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
import { Skeleton } from './components/skeleton.js';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from './components/tooltip.js';

export interface ViewListProps {
  list: ViewListState;
  currentId: string | null;
  onOpen(instanceId: string): void;
}

const SCOPE_ICON: Record<ViewScope, typeof LockIcon> = {
  system: LayersIcon,
  shared: UsersIcon,
  personal: LockIcon,
};

const SCOPE_LABEL: Record<ViewScope, string> = {
  system: 'Shipped with the definition',
  shared: 'Shared with everyone',
  personal: 'Only you',
};

/**
 * The views of one definition, in the user's own order.
 *
 * A system view is always here even when the store is unreachable, because it
 * travels with the definition rather than with the data.
 */
export function ViewList({ list, currentId, onOpen }: ViewListProps) {
  if (list.loading) {
    return (
      <div data-slot="view-list" className="flex flex-col gap-2">
        {Array.from({ length: 3 }, (_unused, index) => (
          <Skeleton key={`view-${index}`} className="h-8 w-full" />
        ))}
      </div>
    );
  }

  if (list.items.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <LayersIcon />
          </EmptyMedia>
          <EmptyTitle>No view yet</EmptyTitle>
          <EmptyDescription>
            {list.error
              ? 'The list could not be loaded.'
              : 'Save the current conditions to make one.'}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  }

  return (
    <nav
      data-slot="view-list"
      aria-label="Views"
      className="flex flex-col gap-1"
    >
      {list.items.map(item => (
        <ViewListItem
          key={item.id}
          item={item}
          current={item.id === currentId}
          onOpen={onOpen}
        />
      ))}
    </nav>
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
  const Icon = SCOPE_ICON[item.scope];
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
        <TooltipContent>{SCOPE_LABEL[item.scope]}</TooltipContent>
      </Tooltip>
      <span className="truncate">{item.title}</span>
      {item.scope !== 'personal' && (
        <Badge variant="secondary" className="ml-auto">
          {item.scope}
        </Badge>
      )}
    </Button>
  );
}
