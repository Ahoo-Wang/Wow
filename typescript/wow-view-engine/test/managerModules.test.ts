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
 * The React-free modules `useViewManager` is composed of, and the rules of
 * `react/writes.ts` as a manager row asks them. The hook itself is covered end
 * to end by `viewManager.test.tsx`, and the write vocabulary on its own by
 * `writes.test.ts`; these ask each rule the questions that are awkward to
 * stage through a rendered list.
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
  kept,
  NO_OUTCOMES,
  PREFERENCES_KEY,
  projectStates,
  withOutcome,
  type Outcome,
} from '../src/react/manager/outcomes.js';
import {
  holdsHandle,
  mayRefuse,
  refused,
  strandedHandle,
  UNSENT,
} from '../src/react/writes.js';
import { createCommandQueue, enqueue } from '../src/react/manager/queue.js';
import {
  groupIndexOf,
  planMoveTo,
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
    expect(holdsHandle(null)).toBe(false);
    expect(holdsHandle(outcomeOf('unknown'))).toBe(true);
    expect(holdsHandle(outcomeOf('conflict'))).toBe(true);
    // §7.4: correct it and save again.
    expect(holdsHandle(outcomeOf('rejected'))).toBe(false);
    // Settled outcomes address nothing, whatever they report.
    expect(holdsHandle(outcomeOf('unknown', { handle: null }))).toBe(false);
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
    expect(queue.backs).toEqual([]);
    let started = false;
    const landed = enqueue(queue, 'one', () => {
      started = true;
      return Promise.resolve(1);
    });
    // Not a microtask later: the row the user clicked shows progress now.
    expect(started).toBe(true);
    expect(queue.backs).toHaveLength(1);
    await expect(landed).resolves.toBe(1);
    await Promise.resolve();
    expect(queue.backs).toEqual([]);
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

  it('starts a fresh chain for another tag rather than waiting on it', async () => {
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
    // The old chain settles alone, with nobody reading its result.
    hanging.resolve('old');
    await expect(stale).resolves.toBe('old');
  });

  /**
   * The inputs the caller left are not the inputs it abandoned. Coming back to
   * a tag whose write is still in flight must chain behind that write: a
   * second one against the same target is what the engine refuses with
   * `view.write.in-flight`, and the caller would report that as the failure of
   * the click that was only second.
   */
  it('waits for a returning tag own in-flight task', async () => {
    const queue = createCommandQueue<string>();
    const hanging = deferred<string>();
    const order: string[] = [];
    const first = enqueue(queue, 'a', () =>
      hanging.promise.then(value => {
        order.push(value);
        return value;
      }),
    );
    // Another tag in between, which neither waits nor displaces A's chain.
    await enqueue(queue, 'b', () => {
      order.push('b');
      return Promise.resolve('b');
    });

    let started = false;
    const second = enqueue(queue, 'a', () => {
      started = true;
      order.push('a2');
      return Promise.resolve('a2');
    });
    expect(started).toBe(false);

    hanging.resolve('a1');
    await Promise.all([first, second]);
    expect(order).toEqual(['b', 'a1', 'a2']);
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

  it('counts a row’s place inside its own audience group', () => {
    expect(groupIndexOf(visible, audiences, 'p1')).toBe(0);
    expect(groupIndexOf(visible, audiences, 'p2')).toBe(1);
    // The shared group starts counting again: the two groups are drawn one
    // under the other, each under its own heading.
    expect(groupIndexOf(visible, audiences, 's1')).toBe(0);
    expect(groupIndexOf(visible, audiences, 's2')).toBe(1);
    // A row the list no longer holds, and one no audience is known for.
    expect(groupIndexOf(visible, audiences, 'gone')).toBe(-1);
    expect(groupIndexOf(['x', 'p1'], audiences, 'x')).toBe(-1);
  });

  it('counts past a row of the other audience', () => {
    const mixed = ['p1', 's1', 'p2'];
    expect(groupIndexOf(mixed, audiences, 'p2')).toBe(1);
  });

  it('moves in the full order while placing rows in the visible one', () => {
    // `hidden` is another kind's view: the store keeps one order per
    // definition, so it must survive a record workbench reordering.
    const full = ['p1', 'hidden', 'p2', 's1', 's2'];
    const planned = planMoveTo(visible, full, audiences, 'p2', 0);
    expect(planned).toEqual({
      order: ['p2', 'hidden', 'p1', 's1', 's2'],
      visible: ['p2', 'p1', 's1', 's2'],
    });
    // Neither list it was given is rearranged in place.
    expect(full[0]).toBe('p1');
    expect(visible[0]).toBe('p1');
  });

  /**
   * A drop is not always onto the next row down. Carrying the first of three
   * to the last closes the two it passed up behind it, and every id that took
   * no part keeps the place it had.
   */
  it('carries a row past more than one of its own', () => {
    const three = ['p1', 'p2', 'p3', 's1'];
    const withThird = new Map(audiences).set('p3', 'personal');
    const full = ['p1', 'hidden', 'p2', 'p3', 's1'];

    expect(planMoveTo(three, full, withThird, 'p1', 2)).toEqual({
      order: ['p2', 'hidden', 'p3', 'p1', 's1'],
      visible: ['p2', 'p3', 'p1', 's1'],
    });
  });

  /** A group is as far as a row goes; past its end is its end. */
  it('clamps an index to the row’s own group', () => {
    expect(planMoveTo(visible, visible, audiences, 'p1', 9)?.visible).toEqual([
      'p2',
      'p1',
      's1',
      's2',
    ]);
    expect(planMoveTo(visible, visible, audiences, 's2', -3)?.visible).toEqual([
      'p1',
      'p2',
      's2',
      's1',
    ]);
  });

  it('plans nothing when there is nowhere to go', () => {
    // Already where it is being sent, at either end and in the middle.
    expect(planMoveTo(visible, visible, audiences, 'p1', 0)).toBeNull();
    expect(planMoveTo(visible, visible, audiences, 'p1', -1)).toBeNull();
    expect(planMoveTo(visible, visible, audiences, 's2', 1)).toBeNull();
    // A row the list no longer holds, and one no audience is known for.
    expect(planMoveTo(visible, visible, audiences, 'gone', 1)).toBeNull();
    expect(planMoveTo(['x', 'p1'], visible, audiences, 'x', 0)).toBeNull();
    // Rows the visible list holds but the submitted order does not: the
    // order would be stored without them, so nothing is submitted.
    expect(planMoveTo(visible, ['p1', 's1'], audiences, 'p1', 1)).toBeNull();
    expect(planMoveTo(visible, ['p2', 's1'], audiences, 'p1', 1)).toBeNull();
  });
});
