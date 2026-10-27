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

import type * as React from 'react';
import { useState } from 'react';
import {
  DragDropProvider,
  useDragOperation,
  useDraggable,
  useDroppable,
} from '@dnd-kit/react';
import type { RecordKey } from '../../model/index.js';
import type { RecordLaneView, RecordRow } from '../../record/index.js';
import { pointerDrag } from '../kit/dragPlugins.js';

/**
 * Moving a kanban's card to another lane: the host's declared action
 * that sets the lane field (`RecordAction.moves`), as a record surface binds
 * it — its rule asked for a lane, its run through the surface's runner, which
 * asks first where it says so, runs, says how it went and reads the view
 * again. Without it the cards stay where they are.
 */
export interface KanbanMove {
  /** Whether this card may go to the lane of `value`: `true`, or why not. */
  can(row: RecordRow, value: string | number): true | string;
  /** Moves it: the action, with the lane's value as its input. */
  run(row: RecordRow, value: string | number): void;
  /**
   * A move is on its way — asked and waiting for an answer, or running —
   * so the card dropped stays drawn in the lane it was dropped on.
   */
  busy: boolean;
}

/**
 * A card drawn in the lane it was dropped on before the source says it is
 * there; `useDroppedCard` says for how long.
 */
export interface DroppedCard {
  key: RecordKey;
  from: string;
  to: string;
}

/**
 * The card a drop moved, held in its new lane until the board shows the
 * move itself: while the move is busy — a refresh landing meanwhile
 * still reads the old stage, and would draw the card back where it was —
 * and then while a read is on its way (`reading`), which is the one the
 * command asks for after it writes. A move refused or called off with
 * nothing being read lets the card go back at once; once let go, a later
 * read does not bring it back.
 */
export function useDroppedCard(
  busy: boolean,
  reading: boolean,
): [DroppedCard | null, (card: DroppedCard) => void] {
  const [dropped, setDropped] = useState<DroppedCard | null>(null);
  const holding = busy || reading;
  if (dropped && !holding) setDropped(null);
  return [holding ? dropped : null, setDropped];
}

/** A card's id among the drag's sources: lanes are the targets. */
export function cardDragId(key: RecordKey): string {
  return `card:${JSON.stringify(key)}`;
}

/**
 * The lanes with a dropped card drawn at the top of the lane it went to, and
 * each lane's count moved with it. The lanes as they were when the card is
 * no longer in its lane (a refresh landed meanwhile) or either lane is gone.
 */
export function withDroppedCard(
  lanes: readonly RecordLaneView[],
  dropped: DroppedCard | null,
): readonly RecordLaneView[] {
  if (!dropped) return lanes;
  const from = lanes.find(lane => lane.id === dropped.from);
  const to = lanes.find(lane => lane.id === dropped.to);
  const row = from?.rows.find(each => each.key === dropped.key);
  if (!from || !to || !row || from === to) return lanes;
  return lanes.map(lane => {
    if (lane === from)
      return {
        ...lane,
        rows: lane.rows.filter(each => each !== row),
        total: lane.total === null ? null : Math.max(0, lane.total - 1),
      };
    if (lane === to)
      return {
        ...lane,
        rows: [row, ...lane.rows],
        total: lane.total === null ? null : lane.total + 1,
      };
    return lane;
  });
}

/**
 * The board's drag, always there so a lane can ask what is dragged (without
 * a move nothing is: every card is disabled): a pointer picks a card up and drops it on a lane
 * (`pointerDrag` — the keyboard's way is the card's menu). A drop is a move
 * only onto another lane of an option, and only where `move.can` says yes;
 * anything else puts the card back.
 */
export function KanbanDrag({
  move,
  lanes,
  onDrop,
  children,
}: {
  move: KanbanMove | undefined;
  lanes: readonly RecordLaneView[];
  onDrop(row: RecordRow, from: string, to: RecordLaneView): void;
  children: React.ReactNode;
}) {
  return (
    <DragDropProvider
      {...pointerDrag()}
      onDragEnd={({ operation, canceled }) => {
        const drop = canceled
          ? null
          : dropOf(
              lanes,
              operation.source?.id ?? null,
              operation.target?.id ?? null,
              move,
            );
        if (drop) onDrop(drop.row, drop.from, drop.to);
      }}
    >
      {children}
    </DragDropProvider>
  );
}

/**
 * What a finished drag moves, read off the ids it ended between: the card,
 * the lane it came from and the lane it was dropped on — or `null` when it
 * is no move: nothing to move with, no card or no lane under it, the lane it
 * came from, the「其他」lane, or one the move's rule refuses.
 */
export function dropOf(
  lanes: readonly RecordLaneView[],
  source: string | number | null,
  target: string | number | null,
  move: KanbanMove | undefined,
): { row: RecordRow; from: string; to: RecordLaneView } | null {
  if (!move || source === null || target === null) return null;
  const found = cardOf(lanes, String(source));
  const to = lanes.find(lane => lane.id === String(target));
  if (!found || !to || to.value === null || to.id === found.lane.id)
    return null;
  if (move.can(found.row, to.value) !== true) return null;
  return { row: found.row, from: found.lane.id, to };
}

/**
 * How a lane stands to the card being dragged (`source`, its drag id):
 * `true` when the card may land here, the rule's reason when it may not
 * (empty for the「其他」lane, which takes no card), and `null` when nothing
 * is dragged, there is no move, or this is the lane the card came from.
 */
export function laneStanding(
  lane: RecordLaneView,
  lanes: readonly RecordLaneView[],
  source: string | number | null,
  move: KanbanMove | undefined,
): true | string | null {
  const dragged =
    move && source !== null ? cardOf(lanes, String(source)) : undefined;
  if (!move || !dragged || dragged.lane.id === lane.id) return null;
  return lane.value === null ? '' : move.can(dragged.row, lane.value);
}

function cardOf(
  lanes: readonly RecordLaneView[],
  id: string,
): { row: RecordRow; lane: RecordLaneView } | undefined {
  for (const lane of lanes) {
    const row = lane.rows.find(each => cardDragId(each.key) === id);
    if (row) return { row, lane };
  }
  return undefined;
}

/**
 * One card a pointer can pick up. Wrapped rather than handed a ref: the card
 * is the one the card layout draws too, and knows nothing of dragging.
 *
 * The library moves this very element under the pointer (`data-dnd-dragging`)
 * and leaves a hidden copy in its place (`data-dnd-placeholder`). The card in
 * hand stays opaque — a faded one let the cards under it read through — and
 * is lifted: a tilt and a deep shadow, which the library's own `!important`
 * translate and transform leave free, and an edge in the primary colour — a
 * shadow alone all but vanishes on a dark surface, among cards its colour. Its place stays as a
 * dashed slot of its size, drawn by the copy's `::before`, which can be
 * visible inside a hidden parent: where it came from, and that it can go back.
 */
export function DraggableCard({
  id,
  disabled,
  children,
}: {
  id: string;
  disabled: boolean;
  children: React.ReactNode;
}) {
  const { ref, isDragSource } = useDraggable({ id, disabled });
  return (
    <div
      ref={ref}
      data-slot="kanban-card"
      data-dragging={isDragSource || undefined}
      className="fve:relative fve:rounded-xl fve:data-[dnd-dragging]:rotate-2 fve:data-[dnd-dragging]:shadow-xl fve:data-[dnd-dragging]:ring-2 fve:data-[dnd-dragging]:ring-primary/30 fve:data-[dnd-placeholder]:before:visible fve:data-[dnd-placeholder]:before:absolute fve:data-[dnd-placeholder]:before:inset-0 fve:data-[dnd-placeholder]:before:rounded-xl fve:data-[dnd-placeholder]:before:border-2 fve:data-[dnd-placeholder]:before:border-dashed fve:data-[dnd-placeholder]:before:border-border fve:data-[dnd-placeholder]:before:bg-muted/50 fve:data-[dnd-placeholder]:before:content-['']"
    >
      {children}
    </div>
  );
}

/** Where a lane stands while a card is dragged. */
export interface LaneDrop {
  /**
   * `open`: the card may be dropped here; `shut`: it may not, `reason` says
   * why; `null`: nothing is dragged, or this is the lane it came from.
   */
  state: 'open' | 'shut' | null;
  reason: string | null;
  /** The pointer is over it, and it is open. */
  over: boolean;
}

/**
 * A lane as a drop target: open to the card being dragged where the move's
 * rule takes this lane, shut with the rule's reason where not. The「其他」
 * lane is never one — it is no stage a card can be put in.
 */
export function useLaneDrop(
  lane: RecordLaneView,
  lanes: readonly RecordLaneView[],
  move: KanbanMove | undefined,
): [(element: Element | null) => void, LaneDrop] {
  const { source } = useDragOperation();
  const asked = laneStanding(lane, lanes, source?.id ?? null, move);
  const { ref, isDropTarget } = useDroppable({
    id: lane.id,
    disabled: !move || asked !== true,
  });
  // The ref apart from what render reads: a value beside a ref is read as
  // one by the hooks' rules.
  return [
    ref,
    {
      state: asked === null ? null : asked === true ? 'open' : 'shut',
      reason: typeof asked === 'string' && asked !== '' ? asked : null,
      over: isDropTarget && asked === true,
    },
  ];
}
