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

import type { RefObject } from 'react';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type { ExecuteViewManagerAction } from './useViewManagerAction.js';
import { ViewManagerRow } from './ViewManagerRow.js';
import { groupViewInstances, type InstanceGroup } from './ViewNavigation.js';
import { useViewCapabilities } from './useViewCapabilities.js';
import { ListOrder } from '../lib/ListOrder.js';

export function ViewManagerGroup({
  engine,
  group,
  busy,
  busyRef,
  execute,
  fallbackFocus,
  onDelete,
}: {
  engine: ViewEngine;
  group: InstanceGroup;
  busy: boolean;
  busyRef: RefObject<boolean>;
  execute: ExecuteViewManagerAction;
  fallbackFocus: RefObject<HTMLElement | null>;
  onDelete(id: string, trigger: HTMLButtonElement): void;
}) {
  const capabilities = useViewCapabilities(engine);
  const items = group.sessions.map(session => ({
    id: session.instance.id,
    session,
  }));
  return (
    <section aria-label={group.label}>
      <h3 className="fve:mb-2 fve:text-xs fve:font-medium fve:text-muted-foreground">
        {group.label}
      </h3>
      <ListOrder
        items={items}
        owner={engine}
        disabled={busy || !capabilities.reorder}
        titleOf={item => item.session.baseline.title}
        onChange={async (_next, move) => {
          if (busyRef.current || !engine.canReorderInstances()) return false;
          const state = engine.getSnapshot();
          const current =
            groupViewInstances(state).find(item => item.id === group.id)
              ?.sessions ?? [];
          if (
            current.length !== items.length ||
            current.some((session, i) => session.instance.id !== items[i].id)
          )
            return false;
          const ids = [...state.instanceIds];
          const from = ids.indexOf(move.id),
            to = ids.indexOf(current[move.to].instance.id);
          if (from < 0 || to < 0) return false;
          ids.splice(from, 1);
          ids.splice(to, 0, move.id);
          let succeeded = false;
          await execute(
            () => engine.reorderInstances(ids),
            () => {
              succeeded = true;
            },
          );
          return succeeded;
        }}
      >
        <ol
          aria-label={`${group.label}顺序`}
          className="fve:m-0 fve:flex fve:list-none fve:flex-col fve:gap-2 fve:p-1"
        >
          {group.sessions.map(session => (
            <ViewManagerRow
              key={session.instance.id}
              engine={engine}
              session={session}
              busy={busy}
              execute={execute}
              fallbackFocus={fallbackFocus}
              onDelete={trigger => onDelete(session.instance.id, trigger)}
            />
          ))}
        </ol>
      </ListOrder>
    </section>
  );
}
