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
 * Section 3 of docs/design/architecture.md, cell by cell, on the pure
 * transitions: every event from every status.
 */

import { describe, expect, it } from 'vitest';
import {
  initialQueryState,
  queryTransition,
  type QueryEvent,
  type QueryState,
} from '../src/internal/queryTransitions';

type State = QueryState<number>;
type Event = QueryEvent<number>;

const failure = new Error('bad');
const states: Record<string, State> = {
  idle: { status: 'idle', result: undefined, error: undefined },
  'idle with a result': { status: 'idle', result: 1, error: undefined },
  loading: { status: 'loading', result: 1, error: undefined },
  success: { status: 'success', result: 1, error: undefined },
  error: { status: 'error', result: 1, error: failure },
};

describe('initialQueryState', () => {
  it('is loading when the hook runs its query on mount, idle otherwise', () => {
    expect(initialQueryState(true)).toEqual({
      status: 'loading',
      result: undefined,
      error: undefined,
    });
    expect(initialQueryState(false)).toEqual(states.idle);
  });
});

describe.each(Object.entries(states))('from %s', (_, state) => {
  const after = (event: Event) => queryTransition(state, event);

  it('start: loading, the result kept, the error cleared', () => {
    expect(after({ type: 'start' })).toEqual({
      status: 'loading',
      result: state.result,
      error: undefined,
    });
  });

  it('succeed: success with the new result', () => {
    expect(after({ type: 'succeed', result: 2 })).toEqual({
      status: 'success',
      result: 2,
      error: undefined,
    });
  });

  it('fail: error, the result kept', () => {
    const next = new Error('worse');
    expect(after({ type: 'fail', error: next })).toEqual({
      status: 'error',
      result: state.result,
      error: next,
    });
  });

  it('abort: idle, the result kept, the error cleared', () => {
    expect(after({ type: 'abort' })).toEqual({
      status: 'idle',
      result: state.result,
      error: undefined,
    });
  });

  it('reset: idle, nothing kept', () => {
    expect(after({ type: 'reset' })).toEqual(states.idle);
  });
});
