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
 * The clock the record actions' rules are asked at: the moment they are
 * asked at, read off the host's clock (`useAskedAt`), and a timer that moves
 * it on to the next moment any record on screen changes on its own
 * (`useRulesClock`).
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import type { RecordRow } from '../record/index.js';
import { nextChange, type RecordActions } from '../runtime/actions.js';
import type { RuntimeEnvironment } from '../runtime/environment.js';

/** The clock when the host gives none. */
export const SYSTEM_CLOCK: Pick<RuntimeEnvironment, 'now'> = {
  now: () => new Date(),
};

/**
 * The shortest wait before a rule is asked again on its own. A `changesAt`
 * that answers a moment ahead of every clock it is asked at — a target that
 * moves with `now` — would otherwise wake the surface without pause; a rule
 * that really flips sooner is seen at most this late.
 */
const RECHECK_FLOOR = 1_000;

/**
 * The moment the rules are asked at, read off the clock it came from, and
 * the way to move it on; a moment the clock still stands at renders
 * nothing. A new clock (the engine's, once the surface has its runtime) is
 * read at once, so no rule is asked at a moment of the old one.
 */
export function useAskedAt(
  clock: Pick<RuntimeEnvironment, 'now'>,
): [Date, (at: Date) => void] {
  const [held, setHeld] = useState(() => ({ clock, at: clock.now() }));
  let current = held;
  if (held.clock !== clock) {
    current = { clock, at: clock.now() };
    setHeld(current);
  }
  const setNow = useCallback(
    (at: Date) =>
      setHeld(previous =>
        previous.at.getTime() === at.getTime() ? previous : { ...previous, at },
      ),
    [],
  );
  return [current.at, setNow];
}

/**
 * The next moment any record on screen changes on its own, and a timer
 * that moves the clock there. A clock left behind while rows came in is
 * only ever early: a rule asked too early refuses, and says when it will
 * change, which is at once.
 */
export function useRulesClock(
  actions: RecordActions | undefined,
  rows: readonly RecordRow[],
  also: readonly RecordRow[],
  now: Date,
  clock: Pick<RuntimeEnvironment, 'now'>,
  setNow: (at: Date) => void,
): void {
  const next = useMemo(
    () => nextChange(actions, [...rows, ...also], now),
    [actions, rows, also, now],
  );
  const nextAt = next?.getTime() ?? null;
  useEffect(() => {
    if (nextAt === null) return;
    const timer = setTimeout(
      () => setNow(clock.now()),
      // The longest wait a timer holds; a later change is asked again then.
      Math.min(
        Math.max(RECHECK_FLOOR, nextAt - clock.now().getTime()),
        2_147_483_647,
      ),
    );
    return () => clearTimeout(timer);
  }, [nextAt, clock, setNow]);
}
