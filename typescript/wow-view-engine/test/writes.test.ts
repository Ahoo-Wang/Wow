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
 * The one write vocabulary both `useSaveCommands` and `useViewManager` speak.
 * The hooks are covered end to end by `reactHooks.test.tsx` and
 * `viewManager.test.tsx`; these ask each rule directly, and where the two
 * hooks answer the same question differently — one outcome for an open
 * runtime, one per row for the manager — they are asked side by side.
 */

import { describe, expect, it } from 'vitest';
import {
  ViewStoreError,
  type Issue,
  type ViewInstance,
  type ViewPreferences,
} from '../src/model/index.js';
import {
  ViewCommandError,
  ViewWriteError,
  type WriteAction,
  type WritePayload,
  type WriteState,
} from '../src/runtime/index.js';
import {
  blocksNewIntent,
  holdsHandle,
  mayRefuse,
  mayReplace,
  recovered,
  refused,
  savesView,
  settle,
  strandedHandle,
  UNRECOVERED,
  UNSENT,
  type SettledWrite,
} from '../src/react/writes.js';

const RENAME: WritePayload = {
  action: 'rename',
  id: 'orders-1',
  revision: '1',
  title: 'Mine',
};

const REASON: Issue = {
  severity: 'error',
  code: 'view.rename.failed',
  path: [],
};

const REMOTE: ViewPreferences = {
  order: [],
  defaultInstanceId: null,
  revision: '2',
};

function stateOf(kind: WriteState['kind']): WriteState {
  const base = { requestId: 'r1', payload: RENAME };
  if (kind === 'conflict') return { ...base, kind, remote: REMOTE };
  if (kind === 'rejected') return { ...base, kind, issue: REASON };
  return { ...base, kind };
}

function outcomeOf(
  kind: WriteState['kind'],
  handle: SettledWrite['handle'] = { id: 'r1' },
): SettledWrite {
  return { state: stateOf(kind), handle };
}

const KINDS: WriteState['kind'][] = ['conflict', 'rejected', 'unknown'];

describe('settle', () => {
  it("takes a write error's own outcome and handle", () => {
    const state = stateOf('unknown');

    expect(
      settle(new ViewWriteError(state), 'view.rename.failed', RENAME),
    ).toEqual({ state, handle: { id: 'r1' } });
  });

  it('records anything else as a refusal that quotes nothing sent', () => {
    const settled = settle(
      new ViewCommandError(REASON),
      'view.save.failed',
      RENAME,
    );

    // Nothing left, so there is no handle and no revision to quote; the
    // payload is the intent, and the Issue is the refusal's own.
    expect(settled).toEqual({
      state: {
        requestId: UNSENT,
        payload: RENAME,
        kind: 'rejected',
        issue: REASON,
      },
      handle: null,
    });
  });

  it('keeps a store failure under the code the caller named', () => {
    const settled = settle(
      new ViewStoreError('NOT_FOUND', 'gone'),
      'view.delete.failed',
      RENAME,
    );

    expect(settled.handle).toBeNull();
    expect(settled.state).toMatchObject({
      kind: 'rejected',
      issue: { code: 'view.delete.failed.not_found' },
    });
  });

  it('builds the refusal shape on its own', () => {
    expect(refused(RENAME, REASON)).toEqual({
      requestId: UNSENT,
      payload: RENAME,
      kind: 'rejected',
      issue: REASON,
    });
  });
});

describe('recovered', () => {
  const instance = {
    id: 'orders-1',
    revision: '2',
    config: { pageSize: 20 },
  } as unknown as ViewInstance;

  it.each([
    ['an instance', instance, true, instance],
    ['preferences, which carry no instance', REMOTE, true, null],
    ['a delete, which resolves with nothing', undefined, false, null],
  ])(
    'reports a landing that produced %s',
    (_what, result, written, carried) => {
      expect(recovered(result as ViewInstance | undefined, written)).toEqual({
        landed: true,
        written,
        instance: carried,
      });
    },
  );

  it('answers nothing replayed when there was no target', () => {
    expect(UNRECOVERED).toEqual({
      landed: false,
      written: false,
      instance: null,
    });
  });
});

describe('savesView', () => {
  it.each([
    ['create', true],
    ['save', true],
    ['rename', false],
    ['delete', false],
    ['preferences', false],
  ] as [WriteAction, boolean][])(
    'a recovered %s writes the config on screen: %s',
    (action, saves) => {
      expect(savesView(action)).toBe(saves);
    },
  );

  it('answers no for a write the runtime no longer holds', () => {
    expect(savesView(undefined)).toBe(false);
  });
});

describe('what a new intent runs into', () => {
  /**
   * The one place the two hooks differ. `useSaveCommands` speaks for an open
   * runtime and holds one outcome, so only an `unknown` — which might already
   * have landed — refuses a new write. A manager row holds one outcome per
   * key and would drop the handle in it, so a `conflict` stops it too.
   */
  it.each([
    ['conflict', false, true],
    ['rejected', false, false],
    ['unknown', true, true],
  ] as [WriteState['kind'], boolean, boolean][])(
    'a %s blocks the runtime: %s, blocks a row holding its handle: %s',
    (kind, runtime, row) => {
      expect(blocksNewIntent(stateOf(kind))).toBe(runtime);
      expect(holdsHandle(outcomeOf(kind))).toBe(row);
    },
  );

  it.each([null, undefined])('nothing on screen blocks nothing (%s)', empty => {
    expect(blocksNewIntent(empty)).toBe(false);
    expect(holdsHandle(empty)).toBe(false);
  });

  it.each(KINDS)('an outcome with no handle to drop lets %s past', kind => {
    expect(holdsHandle(outcomeOf(kind, null))).toBe(false);
  });
});

describe('strandedHandle', () => {
  it('names the handle a rejection still holds', () => {
    expect(strandedHandle(outcomeOf('rejected'))).toEqual({ id: 'r1' });
  });

  it.each(['conflict', 'unknown'] as WriteState['kind'][])(
    'strands nothing for a %s, which no new command gets past',
    kind => {
      expect(strandedHandle(outcomeOf(kind))).toBeNull();
    },
  );

  it.each([null, undefined])(
    'strands nothing when there is none (%s)',
    empty => {
      expect(strandedHandle(empty)).toBeNull();
      expect(strandedHandle(outcomeOf('rejected', null))).toBeNull();
    },
  );
});

describe('mayReplace', () => {
  it.each(KINDS)(
    'an outcome that carries its own handle takes the slot from a %s',
    kind => {
      expect(mayReplace(outcomeOf(kind), outcomeOf('unknown'))).toBe(true);
    },
  );

  it.each(KINDS)('a refusal may not displace the handle a %s holds', kind => {
    const refusal = outcomeOf('rejected', null);
    expect(mayReplace(outcomeOf(kind), refusal)).toBe(false);
    expect(mayRefuse(outcomeOf(kind))).toBe(false);
  });

  it.each([
    ['an empty slot', null],
    ['an outcome with nothing left to replay', outcomeOf('rejected', null)],
  ])('a refusal takes %s', (_what, existing) => {
    const refusal = outcomeOf('rejected', null);
    expect(mayReplace(existing, refusal)).toBe(true);
    expect(mayRefuse(existing)).toBe(true);
  });
});
