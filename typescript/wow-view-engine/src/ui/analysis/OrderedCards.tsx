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

import type { ReactNode } from 'react';
import { DragDropProvider } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import { useAnnouncer } from '../Announcer.js';
import { DragHandle, moveTarget, type HandleMove } from '../DragHandle.js';
import { sortableList, withoutOptimisticSorting } from '../dragPlugins.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard } from '../variants.js';
import { chartDragAccessibility, listDrop } from './drag.js';

/** One row of an ordered list: what it is kept by, and what it reads as. */
export interface OrderedCard {
  key: string;
  /** The row as it reads on screen, which the handle and the voice name. */
  name: string;
}

export interface OrderedCardsProps<T extends OrderedCard> {
  /** Each card's `data-slot`: `stage-card`, `level-card`. */
  slot: string;
  /** The attribute each card carries its key under: `stage`, `level`. */
  mark: string;
  /** The live region's `data-slot`, one per list. */
  voice: string;
  /** What the list is, read as its name. */
  label: string;
  items: readonly T[];
  /** Commits one move; the list says where the row landed. */
  onReorder(from: number, to: number): void;
  /** The card after its handle: its name, a box, a way out. */
  children(item: T, index: number): ReactNode;
}

/**
 * A list of the visualization panel whose order is the chart's meaning — a
 * funnel's stages, a hierarchy's levels — put in another order the one way
 * every list here is (「可排序的列表一律拖拽排序」): each card led by the
 * shared handle (`DragHandle`), dragged by a pointer, moved a place by the
 * arrow keys on it, picked up by Space, or clicked for the menu of the four
 * places it can go. Where the row lands is said once, in the list's own
 * voice, for all of them alike (`label.chart.moved`), as the series list
 * says it; the library announces its own pick-up and cancel in the same
 * words (`CHART_DRAG_WORDING`).
 *
 * The optimistic plugin is left out as it is in every other list: it
 * reorders the DOM while the pointer moves, which makes the indexes the
 * cards are drawn from stale exactly when the drop is read.
 */
export function OrderedCards<T extends OrderedCard>({
  slot,
  mark,
  voice,
  label,
  items,
  onReorder,
  children,
}: OrderedCardsProps<T>) {
  const messages = useViewMessages();
  const { say, region } = useAnnouncer(voice);
  const keys = items.map(item => item.key);
  const nameOf = (key: string) =>
    items.find(item => item.key === key)?.name ?? key;
  /**
   * Commits one move and says where the row landed, for every input.
   * Nothing is written for a move that changes nothing — an end of the list
   * is an end, and a redraw of the same picture is a lie told to a reader
   * listening for the landing.
   */
  const moveTo = (from: number, to: number) => {
    const carried = items[from];
    if (!carried || from === to || to < 0 || to >= items.length) return;
    onReorder(from, to);
    say(
      messages.label('label.chart.moved', {
        name: carried.name,
        index: to + 1,
        total: items.length,
      }),
    );
  };
  return (
    <>
      <DragDropProvider
        {...sortableList(chartDragAccessibility(messages, nameOf))}
        onDragEnd={({ operation, canceled }) => {
          const drop = listDrop(keys, operation, canceled);
          if (drop) moveTo(drop.from, drop.to);
        }}
      >
        <ol aria-label={label} className="flex flex-col gap-2">
          {items.map((item, index) => (
            <OrderedCardRow
              // Kept by what it is, so a move carries the card — and the
              // handle that has the keyboard — along with it.
              key={item.key}
              slot={slot}
              mark={mark}
              item={item}
              index={index}
              total={items.length}
              onMove={move =>
                moveTo(index, moveTarget(move, index, items.length))
              }
            >
              {children(item, index)}
            </OrderedCardRow>
          ))}
        </ol>
      </DragDropProvider>
      {region}
    </>
  );
}

/** One card the library can carry, led by the handle it is carried by. */
function OrderedCardRow({
  slot,
  mark,
  item,
  index,
  total,
  onMove,
  children,
}: {
  slot: string;
  mark: string;
  item: OrderedCard;
  index: number;
  total: number;
  onMove(move: HandleMove): void;
  children: ReactNode;
}) {
  const messages = useViewMessages();
  const { ref, handleRef, isDragging } = useSortable({
    id: item.key,
    index,
    plugins: withoutOptimisticSorting,
  });
  return (
    <li ref={ref} data-dragging={isDragging ? '' : undefined}>
      <EditorCard data-slot={slot} {...{ [`data-${mark}`]: item.key }}>
        <DragHandle
          ref={handleRef}
          label={messages.label('label.chart.reorder', { name: item.name })}
          index={index}
          total={total}
          dragging={isDragging}
          // One row is first and last at once: a handle that can only put it
          // back where it is says it can do something it cannot.
          disabled={total < 2}
          onMove={onMove}
        />
        {children}
      </EditorCard>
    </li>
  );
}
