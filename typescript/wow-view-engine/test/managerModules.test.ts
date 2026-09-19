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

/**
 * The React-free modules `useViewManager` is composed of. The hook itself is
 * covered end to end by `viewManager.test.tsx`; these ask each rule the
 * questions that are awkward to stage through a rendered list.
 */

import { describe, expect, it } from 'vitest';
import type {
  Issue,
  ViewAudience,
  ViewInstanceSummary,
} from '../src/model/index.js';
import type { WritePayload, WriteState } from '../src/runtime/index.js';
import type { ViewPermissions } from '../src/index.js';
import { abilitiesOf } from '../src/react/manager/abilities.js';
import {
  blocks,
  kept,
  mayRefuse,
  NO_OUTCOMES,
  PREFERENCES_KEY,
  projectStates,
  refused,
  strandedHandle,
  UNSENT,
  withOutcome,
  type Outcome,
} from '../src/react/manager/outcomes.js';
import { createCommandQueue, enqueue } from '../src/react/manager/queue.js';
import {
  neighbourOf,
  planMove,
  sameOrder,
} from '../src/react/manager/order.js';
import { deferred } from './fixtures.js';

const RENAME: WritePayload = {
  action: 'rename',
  id: 'orders-1',
  revision: '1',
  title: 'Mine',
};

function stateOf(kind: WriteState['kind']): WriteState {
  const base = { requestId: 'r1', payload: RENAME };
  if (kind === 'conflict')
    return {
      ...base,
      kind,
      remote: { order: [], defaultInstanceId: null, revision: '2' },
    };
  if (kind === 'rejected')
    return {
      ...base,
      kind,
      issue: { severity: 'error', code: 'view.rename.failed', path: [] },
    };
  return { ...base, kind };
}

function outcomeOf(
  kind: WriteState['kind'],
  overrides: Partial<Outcome> = {},
): Outcome {
  return { state: stateOf(kind), handle: { id: 'r1' }, ...overrides };
}

describe('manager/outcomes', () => {
  it('files preferences under a key no store may issue', () => {
    expect(PREFERENCES_KEY).toBe('system:preferences');
    expect(NO_OUTCOMES.size).toBe(0);
  });

  it('records a refusal as a rejection that quotes nothing sent', () => {
    const issue: Issue = {
      severity: 'error',
      code: 'view.rename.failed',
      path: [],
    };
    expect(refused(RENAME, issue)).toEqual({
      requestId: UNSENT,
      payload: RENAME,
      kind: 'rejected',
      issue,
    });
  });

  it('keeps only a settled conflict that still holds the intent', () => {
    const again = () => Promise.resolve();
    expect(kept(outcomeOf('conflict', { handle: null, again }))).toBe(true);
    // Still the engine's to answer for: `resolveConflict` is the way out.
    expect(kept(outcomeOf('conflict', { again }))).toBe(false);
    // Nothing was kept, so there is no intent to put again.
    expect(kept(outcomeOf('conflict', { handle: null }))).toBe(false);
    // A refusal never left; a second identical write would do nothing.
    expect(kept(outcomeOf('rejected', { handle: null, again }))).toBe(false);
    expect(kept(undefined)).toBe(false);
  });

  it('blocks a new intent only while a handle is outstanding', () => {
    expect(blocks(null)).toBe(false);
    expect(blocks(outcomeOf('unknown'))).toBe(true);
    expect(blocks(outcomeOf('conflict'))).toBe(true);
    // §7.4: correct it and save again.
    expect(blocks(outcomeOf('rejected'))).toBe(false);
    // Settled outcomes address nothing, whatever they report.
    expect(blocks(outcomeOf('unknown', { handle: null }))).toBe(false);
  });

  it('strands only the rejection a new command would take the slot of', () => {
    expect(strandedHandle(outcomeOf('rejected'))).toEqual({ id: 'r1' });
    expect(strandedHandle(outcomeOf('unknown'))).toBeNull();
    expect(strandedHandle(outcomeOf('rejected', { handle: null }))).toBeNull();
    expect(strandedHandle(null)).toBeNull();
  });

  it('lets a refusal in only where no handle would be dropped', () => {
    expect(mayRefuse(null)).toBe(true);
    expect(mayRefuse(outcomeOf('rejected', { handle: null }))).toBe(true);
    expect(mayRefuse(outcomeOf('unknown'))).toBe(false);
  });

  it('answers a no-op drop with no map at all', () => {
    const outcome = outcomeOf('unknown');
    const one = withOutcome(NO_OUTCOMES, 'orders-1', outcome);
    expect(one?.get('orders-1')).toBe(outcome);
    // The map it was given is left alone: it is React state.
    expect(NO_OUTCOMES.size).toBe(0);
    const dropped = withOutcome(one!, 'orders-1', null);
    expect(dropped?.size).toBe(0);
    expect(withOutcome(dropped!, 'orders-1', null)).toBeNull();
  });

  it('projects the states alone, keeping the handles private', () => {
    const outcome = outcomeOf('conflict');
    const projected = projectStates(new Map([['orders-1', outcome]]));
    expect(projected.get('orders-1')).toBe(outcome.state);
    expect(projectStates(NO_OUTCOMES).size).toBe(0);
  });
});

describe('manager/abilities', () => {
  function summary(
    id: string,
    scope: ViewInstanceSummary['scope'],
  ): ViewInstanceSummary {
    return {
      id,
      definitionId: 'orders',
      title: id,
      scope,
      kind: 'record',
      revision: '1',
    };
  }

  function permitting(
    overrides: Partial<ViewPermissions> = {},
  ): ViewPermissions {
    return {
      createPersonal: true,
      createShared: true,
      reorder: false,
      setDefault: false,
      instance: () => ({ save: true, rename: true, delete: true }),
      ...overrides,
    };
  }

  it('never writes a system view, however freely the store permits it', () => {
    const can = abilitiesOf([summary('sys', 'system')], permitting());
    expect(can.instance('sys')).toEqual({ rename: false, delete: false });
    // A row the list does not hold is the store's to answer for.
    expect(can.instance('gone')).toEqual({ rename: true, delete: true });
    expect(can.anything).toBe(false);
  });

  it('offers the way in only when something can be managed', () => {
    const rows = [summary('p1', 'personal')];
    expect(abilitiesOf(rows, permitting()).anything).toBe(true);
    expect(
      abilitiesOf(
        rows,
        permitting({
          instance: () => ({ save: true, rename: false, delete: false }),
        }),
      ).anything,
    ).toBe(false);
    expect(
      abilitiesOf([summary('sys', 'system')], permitting({ reorder: true }))
        .anything,
    ).toBe(true);
    expect(
      abilitiesOf([summary('sys', 'system')], permitting({ setDefault: true }))
        .anything,
    ).toBe(true);
  });
});

describe('manager/queue', () => {
  it('starts an idle queue in the same turn, and goes idle again', async () => {
    const queue = createCommandQueue<string>();
    expect(queue.back).toBeNull();
    let started = false;
    const landed = enqueue(queue, 'one', () => {
      started = true;
      return Promise.resolve(1);
    });
    // Not a microtask later: the row the user clicked shows progress now.
    expect(started).toBe(true);
    expect(queue.back).not.toBeNull();
    await expect(landed).resolves.toBe(1);
    await Promise.resolve();
    expect(queue.back).toBeNull();
  });

  it('runs same-tagged tasks one after the other', async () => {
    const queue = createCommandQueue<string>();
    const first = deferred<string>();
    const order: string[] = [];
    const one = enqueue(queue, 'tag', () =>
      first.promise.then(value => {
        order.push(value);
        return value;
      }),
    );
    const two = enqueue(queue, 'tag', () => {
      order.push('two');
      return Promise.resolve('two');
    });
    expect(order).toEqual([]);
    first.resolve('one');
    await Promise.all([one, two]);
    expect(order).toEqual(['one', 'two']);
  });

  it('keeps one broken link from stalling the queue for good', async () => {
    const queue = createCommandQueue<string>();
    const broken = enqueue(queue, 'tag', () => Promise.reject(new Error('no')));
    await expect(broken).rejects.toThrow('no');
    await expect(
      enqueue(queue, 'tag', () => Promise.resolve('after')),
    ).resolves.toBe('after');
  });

  it('starts a fresh queue for another tag rather than waiting on it', async () => {
    const queue = createCommandQueue<string>();
    const hanging = deferred<string>();
    const stale = enqueue(queue, 'old', () => hanging.promise);
    let started = false;
    const landed = enqueue(queue, 'new', () => {
      started = true;
      return Promise.resolve('new');
    });
    expect(started).toBe(true);
    await expect(landed).resolves.toBe('new');
    // The old queue settles alone, with nobody reading its result.
    hanging.resolve('old');
    await expect(stale).resolves.toBe('old');
  });

  it('chains by the comparison the caller supplied', async () => {
    const queue = createCommandQueue<{ id: string }>(
      (one, other) => one.id === other.id,
    );
    const first = deferred<string>();
    const order: string[] = [];
    const one = enqueue(queue, { id: 'a' }, () =>
      first.promise.then(value => {
        order.push(value);
        return value;
      }),
    );
    const two = enqueue(queue, { id: 'a' }, () => {
      order.push('two');
      return Promise.resolve('two');
    });
    first.resolve('one');
    await Promise.all([one, two]);
    expect(order).toEqual(['one', 'two']);
  });
});

describe('manager/order', () => {
  const audiences = new Map<string, ViewAudience>([
    ['p1', 'personal'],
    ['p2', 'personal'],
    ['s1', 'shared'],
    ['s2', 'shared'],
  ]);
  const visible = ['p1', 'p2', 's1', 's2'];

  it('compares two orders by position', () => {
    expect(sameOrder(['a', 'b'], ['a', 'b'])).toBe(true);
    expect(sameOrder(['a', 'b'], ['b', 'a'])).toBe(false);
    expect(sameOrder(['a'], ['a', 'b'])).toBe(false);
  });

  it('finds the neighbour in the audience group of the row itself', () => {
    expect(neighbourOf(visible, audiences, 'p1', 'down')).toBe(1);
    expect(neighbourOf(visible, audiences, 's2', 'up')).toBe(2);
    // The ends of a group, where the arrow is disabled.
    expect(neighbourOf(visible, audiences, 'p1', 'up')).toBe(-1);
    expect(neighbourOf(visible, audiences, 's2', 'down')).toBe(-1);
    // A group boundary is not a neighbour: the swap would move nothing.
    expect(neighbourOf(visible, audiences, 'p2', 'down')).toBe(-1);
    expect(neighbourOf(visible, audiences, 's1', 'up')).toBe(-1);
    // A row the list no longer holds, and one no audience is known for.
    expect(neighbourOf(visible, audiences, 'gone', 'up')).toBe(-1);
    expect(neighbourOf(['x', 'p1'], audiences, 'x', 'down')).toBe(-1);
  });

  it('skips a row of the other audience to reach its own', () => {
    const mixed = ['p1', 's1', 'p2'];
    expect(neighbourOf(mixed, audiences, 'p1', 'down')).toBe(2);
  });

  it('swaps in the full order while pairing rows in the visible one', () => {
    // `hidden` is another kind's view: the store keeps one order per
    // definition, so it must survive a record workbench reordering.
    const full = ['p1', 'hidden', 'p2', 's1', 's2'];
    const planned = planMove(visible, full, audiences, 'p2', 'up');
    expect(planned).toEqual({
      order: ['p2', 'hidden', 'p1', 's1', 's2'],
      visible: ['p2', 'p1', 's1', 's2'],
    });
    // Neither list it was given is rearranged in place.
    expect(full[0]).toBe('p1');
    expect(visible[0]).toBe('p1');
  });

  it('plans nothing when there is nowhere to go', () => {
    expect(planMove(visible, visible, audiences, 'p1', 'up')).toBeNull();
    expect(planMove(visible, visible, audiences, 'gone', 'down')).toBeNull();
    // A pair the visible list holds but the submitted order does not: the
    // order would be stored without them, so nothing is submitted.
    expect(planMove(visible, ['p1', 's1'], audiences, 'p1', 'down')).toBeNull();
    expect(planMove(visible, ['p2', 's1'], audiences, 'p1', 'down')).toBeNull();
  });
});
