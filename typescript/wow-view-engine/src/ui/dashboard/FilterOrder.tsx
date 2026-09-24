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

import { useEffect, useRef, type ReactNode } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import type { DashboardField } from '../../model/index.js';
import { DragHandle } from '../DragHandle.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dropped } from '../dragDrop.js';
import { announcedPlugins, withoutOptimisticSorting } from '../dragPlugins.js';
import { dragWording } from '../dragWording.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * Putting the bar's filters in another order while the board is built: the
 * board's own edit (`DashboardFilterEditing.moveFilter`, into the draft and
 * saved with the board) and the board's one voice for what it did.
 */
export interface FilterOrder {
  /** Moves a filter to `index` among all the board's filters. */
  move(name: string, index: number): void;
  /** What the board's live region says. */
  say(words: string): void;
}

/** What one filter on the bar is carried by, while it can be. */
export interface FilterCarry {
  /** The chip the library moves. */
  ref(element: HTMLElement | null): void;
  /** The handle at the chip's start: a pointer drags it, the arrows move it. */
  handle: ReactNode;
  /** True while the library is carrying this chip. */
  dragging: boolean;
}

/** Where the bar's drag sentences live in the catalogue. */
const FILTER_DRAG_WORDING = {
  instructions: 'label.filters.instructions',
  picked: 'label.filters.picked',
  cancelled: 'label.filters.cancelled',
  placeholder: 'filter',
} as const;

/**
 * The filters on the bar as a list to arrange (D22 G): each chip carried by
 * a handle (`DragHandle`, as the tab bar's), dragged by a pointer or moved a
 * place by ←/→ on the handle, the place it lands said once — 「「仓库」现在是
 * 第 2 个筛选，共 3 个」.
 *
 * A board is built in the workbench alone (D36), whose bar holds every
 * filter, so a place on the bar is the filter's place among the board's.
 * The time grouping is not a filter in this order: it keeps its place
 * after them.
 */
export function useFilterOrder(
  shown: readonly DashboardField[],
  order: FilterOrder | undefined,
): {
  /** Around the chips: the drag, while there is an order to change. */
  wrap(children: ReactNode): ReactNode;
  /** One chip's carrying, by its place on the bar; nothing when read. */
  carry(
    field: DashboardField,
    index: number,
    chip: (carry: FilterCarry | undefined) => ReactNode,
  ): ReactNode;
} {
  const messages = useViewMessages();
  const handles = useRef(new Map<string, HTMLElement>());
  // A chip moved by its arrows may be put back in the DOM at its new place,
  // and a browser that blurs a node taken out takes the keyboard off its
  // handle with it; it goes back after the render that moved it.
  const refocusing = useRef<string | null>(null);
  useEffect(() => {
    const name = refocusing.current;
    if (name === null) return;
    refocusing.current = null;
    const handle = handles.current.get(name);
    if (handle && document.activeElement !== handle) handle.focus();
  });

  if (!order || shown.length < 2)
    return {
      wrap: children => children,
      carry: (_, __, chip) => chip(undefined),
    };

  /** Whether it moved: a press past either end is no move. */
  const move = (name: string, to: number): boolean => {
    const from = shown.findIndex(field => field.name === name);
    if (from < 0 || to < 0 || to >= shown.length || to === from) return false;
    order.move(name, to);
    order.say(
      messages.label('label.filters.moved', {
        filter: shown.find(field => field.name === name)?.label ?? name,
        index: to + 1,
        total: shown.length,
      }),
    );
    return true;
  };

  return {
    wrap: children => (
      <DragDropProvider
        plugins={announcedPlugins(
          dragAccessibility(
            dragWording(messages, FILTER_DRAG_WORDING),
            name => shown.find(field => field.name === name)?.label ?? name,
          ),
        )}
        onDragEnd={({ operation, canceled }) => {
          const drop = dropped(operation, canceled);
          if (!drop) return;
          move(
            drop.source,
            shown.findIndex(field => field.name === drop.target),
          );
        }}
      >
        {children}
      </DragDropProvider>
    ),
    carry: (field, index, chip) => (
      <SortableFilter
        key={field.name}
        field={field}
        index={index}
        onHandle={element => {
          if (element) handles.current.set(field.name, element);
          else handles.current.delete(field.name);
        }}
        onMove={step => {
          // Only a move that happened has a render to come back after: a
          // mark left standing would take the keyboard at some later one.
          if (move(field.name, index + step)) refocusing.current = field.name;
        }}
        chip={chip}
      />
    ),
  };
}

/** One chip the library can carry, and the handle it is carried by. */
function SortableFilter({
  field,
  index,
  onHandle,
  onMove,
  chip,
}: {
  field: DashboardField;
  index: number;
  onHandle(element: HTMLElement | null): void;
  onMove(step: -1 | 1): void;
  chip(carry: FilterCarry): ReactNode;
}) {
  const messages = useViewMessages();
  const { ref, handleRef, isDragging } = useSortable({
    id: field.name,
    index,
    plugins: withoutOptimisticSorting,
  });
  return chip({
    ref,
    dragging: isDragging,
    handle: (
      <DragHandle
        ref={element => {
          handleRef(element);
          onHandle(element);
        }}
        axis="horizontal"
        label={messages.label('label.filters.reorder', {
          filter: field.label,
        })}
        dragging={isDragging}
        onMove={onMove}
      />
    ),
  });
}
