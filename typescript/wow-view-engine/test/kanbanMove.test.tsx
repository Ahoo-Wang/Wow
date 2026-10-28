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

import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { actions, type RecordLaneView, type RecordRow } from '../src/index.js';
import type { RecordActionsController } from '../src/react/index.js';
import {
  UNAVAILABLE,
  moveActionFor,
  moveInput,
} from '../src/runtime/actions.js';
import { kanbanMove } from '../src/ui/actions/kanbanMove.js';
import {
  cardDragId,
  dropOf,
  laneStanding,
  useDroppedCard,
  withDroppedCard,
  type KanbanMove,
} from '../src/ui/record/kanbanDrag.js';

const STAGES = [
  { value: 'LEAD', label: 'Lead' },
  { value: 'WON', label: 'Won' },
];

const move = {
  id: 'move',
  label: 'Move',
  moves: 'stage',
  form: { stage: { label: 'Stage', options: STAGES } },
  available: (
    row: RecordRow,
    { input }: { input?: Record<string, unknown> },
  ) =>
    row.data.stage === 'WON'
      ? 'Won deals stay won'
      : input?.stage === row.data.stage
        ? 'Already there'
        : true,
  run: vi.fn(() => Promise.resolve()),
};

describe('a declared move', () => {
  it('is found by the field it sets, and runs with the lane as its input', () => {
    const list = actions([{ id: 'other', label: 'Other', run: vi.fn() }, move]);
    expect(moveActionFor(list, 'stage')).toBe(list[1]);
    expect(moveActionFor(list, 'priority')).toBeUndefined();
    expect(moveActionFor(list, undefined)).toBeUndefined();
    expect(moveInput(list[1]!, 'WON')).toEqual({ stage: 'WON' });
  });

  it('refuses a form that asks for anything but the field it sets', () => {
    expect(() =>
      actions([
        {
          ...move,
          form: { note: { label: 'Note' } },
        },
      ]),
    ).toThrow(/moves stage, so its form is that one field/);
    // No form at all is a move the menu does not offer, only the drag.
    expect(() => actions([{ ...move, form: undefined }])).not.toThrow();
  });
});

function controller(
  overrides: Partial<RecordActionsController> = {},
): RecordActionsController {
  return {
    now: new Date(0),
    busy: false,
    pending: null,
    start: vi.fn(),
    ...overrides,
  } as unknown as RecordActionsController;
}

const lead: RecordRow = { key: 'd-1', data: { id: 'd-1', stage: 'LEAD' } };
const won: RecordRow = { key: 'd-2', data: { id: 'd-2', stage: 'WON' } };

describe('kanbanMove', () => {
  const list = actions([move]);

  it("asks the action's rule for a lane, with that lane's value", () => {
    const bound = kanbanMove(controller(), list, 'stage')!;
    expect(bound.can(lead, 'WON')).toBe(true);
    expect(bound.can(lead, 'LEAD')).toBe('Already there');
    expect(bound.can(won, 'LEAD')).toBe('Won deals stay won');
  });

  it("runs it through the surface's controller, as a press on the card does", () => {
    const surface = controller();
    kanbanMove(surface, list, 'stage')!.run(lead, 'WON');
    expect(surface.start).toHaveBeenCalledWith('move', 'row', lead, {
      stage: 'WON',
    });
  });

  it('is busy while the move is asked or running', () => {
    expect(kanbanMove(controller(), list, 'stage')!.busy).toBe(false);
    expect(kanbanMove(controller({ busy: true }), list, 'stage')!.busy).toBe(
      true,
    );
    expect(
      kanbanMove(
        controller({ pending: {} as RecordActionsController['pending'] }),
        list,
        'stage',
      )!.busy,
    ).toBe(true);
  });

  it('is nothing where no action sets the lane field', () => {
    expect(kanbanMove(controller(), list, 'priority')).toBeUndefined();
    expect(kanbanMove(controller(), undefined, 'stage')).toBeUndefined();
  });

  it('refuses a record the action is hidden on', () => {
    const hidden = actions([{ ...move, hidden: () => true }]);
    expect(kanbanMove(controller(), hidden, 'stage')!.can(lead, 'WON')).toBe(
      UNAVAILABLE,
    );
  });
});

function laneOf(
  id: string,
  rows: RecordRow[],
  total: number | null,
): RecordLaneView {
  return {
    id,
    value: id,
    label: id,
    rows,
    total,
    next: null,
    summaries: null,
  };
}

describe('withDroppedCard', () => {
  const lanes = [laneOf('a', [lead], 3), laneOf('b', [won], null)];

  it('draws the card at the top of the lane it went to, the counts moved', () => {
    const moved = withDroppedCard(lanes, {
      key: 'd-1',
      from: 'a',
      to: 'b',
    });
    expect(moved[0]).toMatchObject({ rows: [], total: 2 });
    expect(moved[1]).toMatchObject({ rows: [lead, won], total: null });
  });

  it('leaves every other lane as it was', () => {
    const third = laneOf('c', [], 0);
    const moved = withDroppedCard([...lanes, third], {
      key: 'd-1',
      from: 'a',
      to: 'b',
    });
    expect(moved[2]).toBe(third);
  });

  it('leaves the lanes alone with nothing dropped, or a card no longer there', () => {
    expect(withDroppedCard(lanes, null)).toBe(lanes);
    expect(withDroppedCard(lanes, { key: 'gone', from: 'a', to: 'b' })).toBe(
      lanes,
    );
    expect(withDroppedCard(lanes, { key: 'd-1', from: 'a', to: 'x' })).toBe(
      lanes,
    );
  });

  it("names a card apart from a lane, and a key's type apart", () => {
    expect(cardDragId('1')).not.toBe(cardDragId(1));
    expect(cardDragId('d-1')).toBe('card:"d-1"');
  });
});

describe('useDroppedCard', () => {
  const card = { key: 'd-1', from: 'LEAD', to: 'WON' };
  const hold = (busy: boolean, reading: boolean) => ({ busy, reading });

  function dropped() {
    const hook = renderHook(
      ({ busy, reading }) => useDroppedCard(busy, reading),
      { initialProps: hold(false, false) },
    );
    // A drop starts the move in the same handler: the next render is busy.
    act(() => {
      hook.result.current[1](card);
      hook.rerender(hold(true, false));
    });
    return hook;
  }

  it('holds the card while the move is asked or runs, through a refresh landing meanwhile', () => {
    const hook = dropped();
    expect(hook.result.current[0]).toBe(card);
    // The timer's refresh asks and lands with the stage as it was.
    hook.rerender(hold(true, true));
    hook.rerender(hold(true, false));
    expect(hook.result.current[0]).toBe(card);
  });

  it("holds it until the command's own read lands, and not after", () => {
    const hook = dropped();
    hook.rerender(hold(false, true));
    expect(hook.result.current[0]).toBe(card);
    hook.rerender(hold(false, false));
    expect(hook.result.current[0]).toBeNull();
    // A later read does not bring it back.
    hook.rerender(hold(false, true));
    expect(hook.result.current[0]).toBeNull();
  });

  it('lets it go at once for a move called off with nothing being read', () => {
    const hook = dropped();
    hook.rerender(hold(false, false));
    expect(hook.result.current[0]).toBeNull();
  });
});

describe('what a finished drag moves', () => {
  const other: RecordLaneView = { ...laneOf('other', [], null), value: null };
  const lanes = [laneOf('LEAD', [lead], 1), laneOf('WON', [won], 1), other];
  const bound: KanbanMove = {
    can: (row, value) =>
      row.data.stage === 'WON'
        ? 'Won deals stay won'
        : value !== 'LOST' || 'no',
    run: vi.fn(),
    busy: false,
  };

  it('is the card, the lane it left and the lane it was dropped on', () => {
    expect(dropOf(lanes, cardDragId('d-1'), 'WON', bound)).toEqual({
      row: lead,
      from: 'LEAD',
      to: lanes[1],
    });
  });

  it('is nothing for a drop the move does not make', () => {
    // No move declared, or nothing under the pointer.
    expect(dropOf(lanes, cardDragId('d-1'), 'WON', undefined)).toBeNull();
    expect(dropOf(lanes, null, 'WON', bound)).toBeNull();
    expect(dropOf(lanes, cardDragId('d-1'), null, bound)).toBeNull();
    // No such card, or no such lane.
    expect(dropOf(lanes, cardDragId('gone'), 'WON', bound)).toBeNull();
    expect(dropOf(lanes, cardDragId('d-1'), 'nowhere', bound)).toBeNull();
    // Back on its own lane, or onto「其他」.
    expect(dropOf(lanes, cardDragId('d-1'), 'LEAD', bound)).toBeNull();
    expect(dropOf(lanes, cardDragId('d-1'), 'other', bound)).toBeNull();
    // A lane the rule refuses.
    expect(dropOf(lanes, cardDragId('d-2'), 'LEAD', bound)).toBeNull();
  });

  it('says how each lane stands to the card in hand', () => {
    const held = cardDragId('d-1');
    expect(laneStanding(lanes[1]!, lanes, held, bound)).toBe(true);
    // The lane it came from, nothing dragged, or no move: no standing.
    expect(laneStanding(lanes[0]!, lanes, held, bound)).toBeNull();
    expect(laneStanding(lanes[1]!, lanes, null, bound)).toBeNull();
    expect(laneStanding(lanes[1]!, lanes, held, undefined)).toBeNull();
    // 「其他」takes no card, and says nothing about why.
    expect(laneStanding(other, lanes, held, bound)).toBe('');
    // A refusing rule's words.
    expect(laneStanding(lanes[0]!, lanes, cardDragId('d-2'), bound)).toBe(
      'Won deals stay won',
    );
  });
});
