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
 * Moving a kanban's card to another lane (D83): the host's declared action
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
 * there. It holds over the result it was dropped on (`rows`, the table's
 * rows then) and while the move is busy: the read after the command brings a
 * new result, which draws the card where it now is, and a refused or
 * cancelled move leaves the card where it was.
 */
export interface DroppedCard {
  key: RecordKey;
  from: string;
  to: string;
  rows: readonly RecordRow[];
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
        if (!move || canceled || !operation.source || !operation.target) return;
        const found = cardOf(lanes, String(operation.source.id));
        const to = lanes.find(lane => lane.id === String(operation.target?.id));
        if (!found || !to || to.value === null || to.id === found.lane.id)
          return;
        if (move.can(found.row, to.value) !== true) return;
        onDrop(found.row, found.lane.id, to);
      }}
    >
      {children}
    </DragDropProvider>
  );
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
 * is the one the card layout draws too, and knows nothing of dragging. The
 * card being dragged says so (`data-dragging`).
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
      className="fve:data-[dragging]:opacity-60"
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
  const dragged = source ? cardOf(lanes, String(source.id)) : undefined;
  const asked =
    move && dragged && dragged.lane.id !== lane.id
      ? lane.value === null
        ? ''
        : move.can(dragged.row, lane.value)
      : null;
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
